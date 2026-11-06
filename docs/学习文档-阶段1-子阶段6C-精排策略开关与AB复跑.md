# 阶段 1 · 子阶段 6C：精排策略开关与 A/B 复跑

## 1. 业务目标

RAG 评估工作台在 6A/6B 已经能回答三个问题：

- 这次检索「命中了什么」（Top-K 明细、三路分数）；
- 两次运行「差在哪」（整体指标差值、逐题退化/提升）；
- 两次运行「能不能比」（用例集、TopK、指标口径、检索链路告警）。

但还剩一个关键问题没有闭环：**精排到底值不值？**

在此之前，rerank 只有一套全局配置：

- 管理员开启，所有检索都走精排；
- 管理员关闭，所有检索都不走；
- 想对比「开 / 关」，只能改全局配置再跑一次，期间会影响其他用户和线上对话。

本子阶段给评估运行增加**精排策略快照**与**单次检索覆盖开关**，让同一数据集在同一 TopK 下：

1. 先跑一轮「跟随全局」；
2. 再一键跑一轮「关闭精排」；
3. 用 6B 的基线对比得到整体差值与逐题胜负。

其他变量保持不变，精排成为唯一实验变量。这个功能不是新增一个静态开关页，而是把开关的真实语义一路传到检索执行层，并落到运行记录中。

## 2. 业务流程图

```text
用户在评估页选择「精排策略」
        │
        ├── auto（默认）：跟随全局 rerank 配置
        └── off：本次运行主动关闭精排
        │
        ▼
POST /ai/api/ragEval/startRun { datasetKey, topK, rerankMode }
        │
        ▼
RagEvalHolder.startRun
        ├── 归一化 rerankMode（空 → auto，转小写）
        ├── 非法值直接拒绝，不创建运行记录
        ├── 写入运行快照：topK / rerankMode / caseSetKey
        └── 提交异步任务
        │
        ▼
RagEvalRunner.execute
        ├── 读取运行记录中的 rerankMode
        ├── auto → search(..., rerankEnabled = null)
        └── off  → search(..., rerankEnabled = false)
        │
        ▼
KbRagHolder.search
        ├── rerankEnabled = false → 跳过 applyRerank，不解析全局配置
        └── 否则 → 原逻辑：解析配置、调用 rerank、失败降级
        │
        ▼
运行结束
        ├── rerankMode：策略快照（用户选择）
        ├── rerankApplied：实际是否生效（真实调用结果）
        └── retrievalMode：hybrid-rrf 或 hybrid-rrf+rerank
        │
        ▼
前端运行详情
        ├── 策略标签：跟随全局 / 主动关闭
        ├── 效果说明：已生效 / 未应用（未配置或降级）
        └── 一键反向复跑 → 完成后选为基线 → 逐题对比
```

## 3. 前端原理

### 3.1 组件与状态

改动集中在 `frontend/src/views/RagEvalView` 三件套：

- `index.ts`：状态与请求编排；
- `index.vue`：启动栏选择器、详情页策略标签与反向复跑按钮；
- `index.less`：只新增一个右对齐样式。

状态新增：

```ts
rerankMode: 'auto' as RagEvalRerankMode
```

请求签名扩展为：

```ts
startRun: (datasetKey: string, topK: number, rerankMode: RagEvalRerankMode = 'auto') =>
  axios.post('/ai/api/ragEval/startRun', { data: { datasetKey, topK, rerankMode } })
```

### 3.2 三个 computed 的解释职责

```ts
const rerankModeLabel = computed(() =>
  state.runDetail?.run.rerankMode === 'off' ? '主动关闭' : '跟随全局',
)

const rerankEffectHint = computed(() => {
  if (run.rerankMode === 'off') return '本次运行主动关闭精排…'
  if (run.rerankApplied) return '精排已在本轮真实生效'
  return '未应用精排：全局未配置精排模型，或调用失败已降级'
})

const canStartCounterpart = computed(() =>
  run && !startingRun && !hasActiveRun && (status === 'success' || 'partial'))
```

关键点：`rerankApplied = false` 有两种完全不同的原因——

- 用户主动关闭（`rerankMode = off`）；
- 用户想开但没开成（全局未配置 / 调用失败降级）。

只看 `rerankApplied` 会把两种情况混为一谈，实验结论就会失真。因此策略快照必须单独存储。

### 3.3 反向复跑

```ts
const mode = run.rerankMode === 'off' ? 'auto' : 'off'
await ragEvalApi.startRun(run.datasetKey, run.topK, mode)
```

复跑使用**运行记录里的 datasetKey 与 topK**，而不是页面上当前表单值：

- 保证 A/B 只改变精排策略；
- 即使用户后来改了启动栏的 TopK，也不会污染实验；
- 提交成功后把启动栏同步为刚跑的策略，避免下一次手动发起时选错。

按钮禁用条件：运行中、提交中、非成功/部分成功。数据集有活动运行时，后端本身也会拒绝，前端禁用只是减少无效请求。

## 4. 后端原理

### 4.1 三态语义

`RagEvalRerankMode` 只有两个合法值：

| 值 | 含义 | 传给检索层 |
| --- | --- | --- |
| `auto` | 跟随全局配置 | `null` |
| `off` | 本次主动关闭 | `false` |

为什么不用布尔值？

- `true` 表示「强制开启」在全局未配置时无法兑现，容易造成「请求了但没发生」的误解；
- `null` 需要表达「没有指定，沿用默认」，布尔原生只有两态；
- 字符串枚举给未来留出 `force` 等语义，但当前不实现未使用的分支。

`normalize` 负责空值兜底与大小写归一，`isValid` 负责白名单校验。校验发生在任何数据库写入之前。

### 4.2 检索层的最小改动

`KbRagHolder` 保留原四参方法，新增五参重载：

```java
public Result<KbSearchResult> search(String uid, String query, String kbName, Integer topK) {
    return search(uid, query, kbName, topK, null);
}
```

这样 Agent 工具、网关对话等其他调用方零改动，行为不变。只有评估运行会显式传 `false`。

`applyRerank` 的第一行：

```java
if (Boolean.FALSE.equals(rerankEnabled)) {
    return new RerankOutcome(fused, false);
}
```

放在 `rerankResolver.resolve()` 之前，意味着关闭精排时：

- 不读取（也不刷新）全局配置缓存；
- 不发起任何精排网络请求；
- 不产生精排延迟和费用；
- 返回 `applied = false`，前端据此显示「主动关闭」。

### 4.3 运行快照

`RagEvalRunBean` 新增：

```java
@SqlColumn()
@ApiModelProperty("精排策略快照：auto 跟随全局 / off 本次主动关闭")
String rerankMode;
```

启动时写入，运行器读取。为什么不只放在请求里传给异步任务？

- 异步任务可能在进程重启后恢复；
- 运行详情需要长期解释历史结果；
- 请求对象不落库，进程内传递不具持久性。

### 4.4 运行器

```java
String rerankMode = RagEvalRerankMode.normalize(run.getRerankMode());
Boolean rerankEnabled = RagEvalRerankMode.disabled(rerankMode) ? Boolean.FALSE : null;
kbRagHolder.search(uid, question, kbName, topK, rerankEnabled);
```

历史运行 `rerankMode` 为 `NULL` 时归一化为 `auto`，行为与升级前一致。

## 5. API 流程

### 5.1 启动评估

```http
POST /ai/api/ragEval/startRun
Content-Type: application/json

{
  "data": {
    "datasetKey": "dk1",
    "topK": 5,
    "rerankMode": "off"
  }
}
```

成功：`resp_code = 0`，`datas = runKey`。
非法策略：`resp_code != 0`，提示「精排策略只能是 auto（跟随全局）或 off（本次关闭）」，不产生运行记录。

### 5.2 知识库检索（可选覆盖）

```http
POST /ai/api/kbRag/search

{
  "data": {
    "query": "差旅报销标准",
    "kbName": "默认知识库",
    "topK": 5,
    "rerankEnabled": false
  }
}
```

`rerankEnabled` 缺省或 `true` 都跟随全局；只有 `false` 关闭。该字段用于评估链路，也方便后续排查「精排是否真的影响这条查询」。

### 5.3 运行详情

`POST /ai/api/ragEval/getRun` 返回的 `run` 现在包含：

```json
{
  "rerankMode": "off",
  "rerankApplied": false,
  "retrievalMode": "hybrid-rrf"
}
```

前端据此区分「主动关闭」与「配置缺失/降级」。

## 6. 数据库设计

### 6.1 新增列

```sql
ALTER TABLE ragevalrunbean
    ADD COLUMN IF NOT EXISTS "rerankMode" varchar(16);
```

建表语句同步增加 `"rerankMode" varchar(16)`。

### 6.2 迁移策略

- `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 幂等，可重复执行；
- 旧数据保持 `NULL`，读取时按 `auto` 解释，不要求回填；
- 两份 DDL 必须同步：
  - `TableSchemaInitializer`（应用启动自动执行）；
  - `scripts/sql/rag-eval-schema.sql`（运维手工执行）。
- 未使用数据库默认值，避免把「未知策略」伪装成 `auto` 落库；语义在代码里统一归一化。

## 7. 核心代码

### 7.1 策略常量

`backend/.../rag/RagEvalRerankMode.java`

```java
public static final String AUTO = "auto";
public static final String OFF = "off";

public static String normalize(String mode) {
    if (mode == null || mode.trim().isEmpty()) return AUTO;
    return mode.trim().toLowerCase();
}

public static boolean disabled(String mode) {
    return OFF.equals(normalize(mode));
}
```

### 7.2 Holder 校验与写入

`RagEvalHolder.startRun`

```java
String rerankMode = RagEvalRerankMode.normalize(data.getRerankMode());
if (!RagEvalRerankMode.isValid(rerankMode)) {
    return Result.failed(null, "精排策略只能是 auto（跟随全局）或 off（本次关闭）");
}
...
run.setRerankMode(rerankMode);
```

### 7.3 检索层关闭分支

`KbRagHolder.applyRerank`

```java
if (Boolean.FALSE.equals(rerankEnabled)) {
    return new RerankOutcome(fused, false);
}
```

### 7.4 前端反向复跑

`RagEvalView/index.ts`

```ts
async function startCounterpartRun() {
  const run = state.runDetail?.run
  if (!run || !canStartCounterpart.value) return
  const mode = counterpartRerankMode.value
  const { data: res } = await ragEvalApi.startRun(run.datasetKey, run.topK, mode)
  ...
}
```

## 8. 异常处理

| 场景 | 处理 |
| --- | --- |
| rerankMode 为空 | 归一化为 auto，兼容旧前端/旧调用方 |
| rerankMode 非法（如 `force`） | 启动前拒绝，提示合法值，不写运行记录 |
| rerankMode 大小写/空格 | trim + 小写归一化后落库 |
| 全局未配置精排 | auto 下 `rerankApplied=false`，前端提示「未应用」而非报错 |
| 精排调用超时/异常 | 原有逻辑降级为混合排序，运行仍可成功，详情标注未生效 |
| 运行中数据集被删除 | 运行器原有逻辑失败收口，不影响策略字段 |
| 用户删除基线/运行 | 原有逻辑清空选择，不产生悬空引用 |
| 已有活动运行 | 后端拒绝 + 前端禁用反向复跑按钮，双重保护 |
| 历史运行无 rerankMode | 读取为 auto，界面显示「跟随全局」，不误报 |

## 9. 验收结果（实测）

### 9.1 后端

- 定向测试：`RagEvalHolderTest` 26 + `RagEvalRunnerTest` 14 + `KbRagHolderTest` 27 = **67 项通过**，Failures 0 / Errors 0。
- 全量 reactor：app-common 7 + permission 71 + service 478 + start 27 = **583 项通过**，Failures 0 / Errors 0，BUILD SUCCESS。

新增用例覆盖：

- 启动默认策略为 auto；
- 显式 ` OFF ` 归一化后落库为 off；
- 非法策略在写库前拒绝；
- 运行策略 off 时检索层收到 `FALSE`；
- 历史运行无策略时检索层收到 `null`；
- 检索层显式关闭时不调用 `rerankResolver.resolve()`；
- 跟随全局且配置可用时真实应用精排。

### 9.2 前端

- `typecheck` 通过；
- 逻辑测试 **245 项（20 文件）** 通过；
- 组件测试 **263 项（27 文件）** 通过；
- 组件测试新增：关闭精排随请求提交、策略标签与生效提示、主动关闭说明与一键反向复跑。

### 9.3 静态门禁

- `check:markdown`、`check:secrets`、`check:bundle`、`check:styles` 全部通过。
- 生产构建 `vite build` 通过。

### 9.4 未证明什么

- 未在真实 PostgreSQL 上执行本次 `ALTER TABLE`，验证依赖 DDL 幂等语法与单测；
- 未调用真实 rerank 服务，A/B 数值未在真实语料上测量；
- 未做浏览器点击级 E2E，前端行为由组件测试覆盖；
- 单测中的检索结果为构造数据，不能当作线上指标。

## 10. 面试问答

### Q1：为什么不做一张「实验表」？

实验需要的最小信息是：同一数据集、同一 TopK、两次运行、一个变量不同。现有运行记录已经具备这些字段，6B 又提供了显式基线与逐题对比。增加实验表会带来分组状态、并发控制、级联删除等一整套新复杂度，而收益只是少点两次基线选择。先用最小模型闭环，等确实需要多轮实验统计时再扩展。

### Q2：如何保证 A/B 只改变精排一个变量？

复跑固定使用运行记录里的 `datasetKey` 与 `topK`，精排策略取相反值；用例集通过 `caseSetKey` 快照校验；指标口径 `metricSpec` 不变。6B 的 `baselineWarnings` 会继续提示用例集、TopK、向量模型、检索链路的变化。

### Q3：精排开关变了，为什么 metricSpec 不变？

`metricSpec` 描述的是**指标计算公式**，不是检索配置。Recall/Precision/MRR/NDCG 的算法没有变；精排属于被测变量，不是口径变化。检索配置由 `retrievalMode` 与 `rerankMode` 记录。把配置塞进口径会让「可比较」判定永远失败。

### Q4：为什么用字符串枚举而不是布尔？

布尔无法表达「未指定」。`true` 在全局未配置时也无法强制生效。`auto/off` 把「跟随默认」与「主动关闭」区分开，并且为未来扩展保留空间；当前只实现两个值，不做无意义分支。

### Q5：为什么 rerankMode 要落库，不随异步任务传参？

异步任务可能排队、重启恢复；运行详情需要在数月后仍能解释「为什么这轮没有精排」。落库是唯一可靠的事实来源，内存传参只覆盖正常路径。

### Q6：如何兼容升级前的历史运行？

新增列允许 NULL；读取时 `normalize(null) = auto`；前端把空值显示为「跟随全局」。不需要回填，也不会因为缺少快照导致详情打不开。

### Q7：关闭精排会不会影响普通对话？

不会。四参 `search` 继续传 `null`，只有评估运行显式传 `false`。普通对话、Agent 工具仍按全局配置执行。`rerankEnabled` 是请求级覆盖，不是全局状态修改。

### Q8：如何防止任意用户关闭线上精排？

该字段只影响**本次请求**，不改全局配置；检索仍要求登录并校验知识库归属。用户无法通过它影响其他用户。真正的全局配置仍由管理员维护。

### Q9：下一步怎么把 A/B 做得更严谨？

当前给的是每轮整体指标与逐题胜负。要进一步做统计结论，需要：

- 固定评测集与多次重复运行，减少检索服务抖动；
- 对逐题差值做配对检验（如 bootstrap 置信区间）；
- 区分「精排提升排序」与「精排改变召回集合」；
- 记录精排耗时与费用，计算收益/成本比。

这些是后续子阶段的候选，不在本轮虚构实现。
