# 学习文档：RAG 评测结果透明化（阶段 1 · 子阶段 6A）

## 1. 业务目标与范围

前一个阶段已经能跑出 Recall@K、Precision@K、MRR、NDCG@K 和逐题明细，但报告只告诉使用者「这题没召回」，没有回答「为什么没召回」：

- 命中片段只在数据库里留下了文档名，分块正文、向量分、词项重叠分、命中来源全部在评估过程中被丢弃；
- 同一个召回结果里既有向量路也有关键词路，界面却只有一个笼统的「Top1」，无法判断是语义匹配还是关键词匹配起了作用；
- 一次运行使用的是什么指标口径、是否真的启用了 rerank，没有任何快照。今天看「Recall 提升了 8pp」，可能只是换了算法口径，或者 rerank 配置刚好生效了；
- 历史基线只有「更早的一次可比运行」，没有提示两次运行的口径是否一致。

本子阶段只做一件事：**让评估结果可解释，并且让可解释信息成为运行快照的一部分**。

具体交付：

1. 逐题保存 Top-K 命中明细：排名、文档、分块序号、融合排序分、向量分、词项重叠分、命中来源、片段预览；
2. 运行级保存配置快照：`metricSpec`（指标口径版本）、`retrievalMode`（检索链路）、`rerankApplied`（是否真正应用精排）；
3. 前端运行详情支持展开每一题，查看完整 Top-K 命中表；分数标签统一为「融合排序分」，避免把 RRF 分误读成相似度；
4. 基线口径与当前不一致时，界面明确提示「差值仅供参考」；
5. 旧运行没有明细时降级展示说明，不影响指标查看。

不做的事：不实现显式基线选择（计划在 6B）、不实现跨运行逐题对比、不保存完整分块正文、不改变任何评估指标的计算口径。

---

## 2. 业务流程图

```text
用户点击「发起评估」
        │
        ▼
RagEvalHolder.startRun  ── 校验数据集/用例/文档/并发 ──► 创建 queued 运行
        │
        ▼
RagEvalRunner.execute（后台线程）
        │
        ├─ 逐题调用 KbRagHolder.search
        │       │
        │       ├─ 向量路 + 关键词路 → hybridRank → RRF 融合
        │       ├─ applyRerank → RerankOutcome(items, applied)
        │       └─ KbSearchResult{ items, rerankApplied, warning }
        │
        ├─ 每题收集：retrievedDocs（文档名）+ hits（分数/来源/预览）
        ├─ RagEvaluator 统一计算指标（口径不变）
        ├─ 逐题落库：指标 + retrievedHits(JSON)
        └─ 运行落库：指标 + metricSpec + retrievalMode + rerankApplied
        │
        ▼
前端轮询 listRuns → 打开 getRun
        │
        ├─ 头部展示：TopK / 向量模型 / 口径 / 检索链路 / 基线
        ├─ 指标卡展示：当前值 + 基线差值（口径不同则提示）
        └─ 逐题表展开 → Top-K 命中明细（分数、来源、片段预览）
```

关键点：命中明细是在**评估执行时**采集并持久化的，不是在详情页临时回查知识库。知识库后来被重新上传或删除，历史报告仍然自洽。

---

## 3. 前端原理

### 3.1 展开行而不是新弹窗

运行详情本身已经是弹窗，再嵌套弹窗会增加层级、遮挡和焦点管理成本。Element Plus 表格的 `type="expand"` 列适合「一行主信息 + 一段补充证据」：默认收起，点击展开图标才渲染嵌套表格，200 条用例也不会一次性把 DOM 撑大。

```vue
<el-table-column type="expand" width="40">
  <template #default="scope">
    <template v-if="scope.row.retrievedHits?.length">
      <!-- 嵌套 Top-K 表格 -->
    </template>
    <div v-else class="rag-eval-muted">
      本次运行未记录 Top-K 明细（旧版本运行或该题检索失败）。
    </div>
  </template>
</el-table-column>
```

`?.` 是必要的：接口升级前写入的历史运行没有 `retrievedHits` 字段，前端不能因为旧数据白屏。

### 3.2 分数语义必须在界面上说清楚

`KbSearchItem.score` 是 RRF 融合分，只表示排序位置，不是 0-1 的相似度。用户很容易把 `0.0328` 误读成「相关度只有 3%」，因此：

- 列名固定为「融合排序分」，不叫「得分」或「相似度」；
- 展开区标题旁常驻一行说明：融合排序分只用于排序，不是相似度；
- 向量分、词项重叠分分列展示，让两条召回路径的贡献可见；
- `-1` 表示该片段没有参与这条路径，显示为「未参与」而不是 `0` 或空白。

```ts
function formatScore(value: number | null | undefined) {
  if (value === null || value === undefined) return '-'
  return value < 0 ? '未参与' : value.toFixed(4)
}
```

### 3.3 命中来源用标签而不是图标

`channel` 只有三种取值，用颜色 + 文案的标签最快传达信息，且不依赖颜色单独区分（满足可访问性要求）：

| channel | 展示 | 含义 |
| --- | --- | --- |
| `both` | 双路（绿色） | 向量路与关键词路都召回 |
| `dense` | 向量路（蓝色） | 仅语义召回 |
| `lexical` | 关键词路（橙色） | 仅词项召回 |

### 3.4 运行配置快照的展示

详情头部按「有值才显示」渲染：

- `metricSpec` → `口径 rag-eval-v1`
- `retrievalMode` → `检索链路 hybrid-rrf+rerank`（有 rerank 时标签为绿色，否则灰色）
- 基线与当前都带版本且不同 → 在基线文案后追加「口径不同，差值仅供参考」

历史基线没有 `metricSpec` 时**不提示**。未知不等于不一致，否则所有旧运行都会出现警告，反而稀释真正的问题。

---

## 4. 后端原理

### 4.1 数据在链路上本来就有，只是被丢掉了

`KbSearchItem` 早就带有 `score / denseScore / lexicalScore / channel / chunkIndex`，这些字段用于检索排序和可解释性。问题出在 `RagEvalRunner.evaluateCase`：

```java
// 改造前：只保留文档名和正文
data.retrievedDocs.add(docName);
data.contents.add(hit.getContent());
```

正文用于关键词匹配，文档名用于指标计算，但分数与来源在评估结束后就消失了。改造后在同一循环里多收集一份 `RagEvalHitDto`，不增加任何检索调用。

### 4.2 rerank 是否生效不能靠猜

`KbRagHolder.applyRerank` 原来只返回 `List<KbSearchItem>`。调用方无法区分三种情况：

1. 没有配置 rerank，返回原列表；
2. 配置了但服务失败，降级返回原列表；
3. 精排成功，但排序恰好与原顺序一致。

前两种和第三种在返回值上完全相同。评估报告如果只看顺序是否变化，会把「rerank 没生效」误判成「rerank 生效但没改变结果」。因此把返回值改为 `RerankOutcome(items, applied)`：

- 未配置、候选 ≤ 1、精排返回 null、抛异常 → `applied = false`；
- 精排调用成功并返回索引 → `applied = true`，即使顺序未变。

`KbSearchResult` 增加 `rerankApplied`，由检索层如实上报。

### 4.3 运行级 `rerankApplied` 的聚合口径

一次运行可能有多道题，每题的检索都独立判断。运行级采用「任一题真正应用过就为 true」：

```java
if (data.rerankApplied) {
    rerankApplied = true;
}
```

`retrievalMode` 随之写为 `hybrid-rrf+rerank` 或 `hybrid-rrf`。

这个口径的边界必须写清楚：`true` 表示「本次运行至少有一题走了精排」，不表示「所有题都走了精排」。混合情况出现在运行中途修改 rerank 配置或部分题检索失败时。要精确到每题，应该把 `rerankApplied` 也写进逐题明细；本轮先保证运行级可见，逐题级别留到需要做 A/B 时再加。

### 4.4 为什么只存片段预览

逐题明细表最多保存 `TopK ≤ 20` 条命中。如果每条都存完整分块：

- 单题可达几十 KB，200 题就是数 MB；
- 评估明细表会被知识库原文撑大，备份、查询、导出都变慢；
- 原文属于用户知识库内容，评估报告没有必要复制一份。

因此只存 200 字符预览，并做空白折叠：

```java
String text = content.trim().replaceAll("\\s+", " ");
return text.length() <= PREVIEW_CHARS ? text : text.substring(0, PREVIEW_CHARS);
```

关键不变量：**关键词匹配必须继续使用完整正文**。`contents` 与 `hits` 是两份数据，截断只作用于后者；测试 `execute_shouldTruncatePreviewWithoutAffectingKeywordMatching` 专门守住这条边界。

### 4.5 指标口径版本化

`metricSpec = "rag-eval-v1"` 记录的是当前指标算法的语义：

- Recall / Precision 按**文档**去重，同一文档多个分块只算一次；
- NDCG 使用二值相关性（命中为 1，未命中为 0）；
- 关键词命中要求全部关键词出现在**期望文档的同一条命中分块**中；
- 失败题按 0 分计入整体指标。

任何一条口径变化都必须升版本，否则新旧运行的差值是在比较两套算法。前端只在两边都有版本且不同时提示「口径不同」，避免历史数据产生噪音。

### 4.6 编解码失败要降级而不是报错

`RagEvalKeywordCodec.parseHits` 在 JSON 为空、格式损坏或旧数据不兼容时返回空列表：

```java
try {
    List<RagEvalHitDto> list = MAPPER.readValue(
            json, new TypeReference<List<RagEvalHitDto>>() {});
    return list == null ? new ArrayList<RagEvalHitDto>() : list;
} catch (Exception e) {
    return new ArrayList<RagEvalHitDto>();
}
```

原因：逐题指标是主数据，Top-K 明细是辅助证据。明细解析失败不应该让整个运行详情接口 500，界面显示「本次运行未记录 Top-K 明细」即可。

---

## 5. API 调用链路

```text
POST /ai/api/ragEval/getRun
body: { data: { runKey: "..." } }

RagEvalController.getRun
  → RagEvalHolder.getRun(uid, runKey)
      1. findRun(uid, runKey)                      按归属查运行
      2. resultMapper.select(page, {runKey, uid})  逐题明细
      3. toResultDto(bean)
           - retrievedDocs → List<String>
           - retrievedHits → codec.parseHits(...)
      4. findBaseline(uid, run)                    同数据集更早的可比运行
  ← Result<RagEvalRunDetail>{
        run: { ..., metricSpec, retrievalMode, rerankApplied },
        results: [{ ..., retrievedDocs, retrievedHits[], ... }],
        baseline: { ... } | null
    }
```

权限边界不变：运行记录、逐题明细、基线都按 `lastuser = uid` 过滤。`runKey` 是业务键，但不是权限凭证；换一个用户传同一个 `runKey` 查不到数据。

---

## 6. 数据库设计与迁移

### 6.1 新增列

| 表 | 列 | 类型 | 说明 |
| --- | --- | --- | --- |
| `ragevalrunbean` | `metricSpec` | varchar(32) | 指标口径版本 |
| `ragevalrunbean` | `retrievalMode` | varchar(32) | `hybrid-rrf` / `hybrid-rrf+rerank` |
| `ragevalrunbean` | `rerankApplied` | boolean | 本次运行是否真正应用精排 |
| `ragevalcaseresultbean` | `retrievedHits` | text | Top-K 明细 JSON 数组 |

新增列全部可空。旧运行保持 NULL，接口读取时 `parseHits(null)` 返回空列表，前端按「未记录」展示。

### 6.2 迁移语句（应用启动时自动执行）

`TableSchemaInitializer.ensureRagEvalTables()` 在 `CREATE TABLE IF NOT EXISTS` 之后追加幂等补列：

```sql
ALTER TABLE ragevalrunbean
    ADD COLUMN IF NOT EXISTS "metricSpec" varchar(32);
ALTER TABLE ragevalrunbean
    ADD COLUMN IF NOT EXISTS "retrievalMode" varchar(32);
ALTER TABLE ragevalrunbean
    ADD COLUMN IF NOT EXISTS "rerankApplied" boolean;
ALTER TABLE ragevalcaseresultbean
    ADD COLUMN IF NOT EXISTS "retrievedHits" text;
```

`scripts/sql/rag-eval-schema.sql` 同步更新：建表语句包含新列，并在末尾追加同样的 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，供不重启应用、由 DBA 手工迁移的环境使用。两份 DDL 必须保持一致，这是本项目的既有约定。

### 6.3 上线前检查

```sql
-- 1. 确认列已补齐
SELECT column_name, data_type
  FROM information_schema.columns
 WHERE table_name IN ('ragevalrunbean', 'ragevalcaseresultbean')
   AND column_name IN ('metricSpec', 'retrievalMode', 'rerankApplied', 'retrievedHits');

-- 2. 旧运行数量（上线后这些运行没有明细，属于预期）
SELECT count(*) FROM ragevalrunbean WHERE "metricSpec" IS NULL;
```

### 6.4 回滚方案

新列只增不改，回滚应用版本不会影响旧代码读取——旧实体不会查询这些列。如需彻底回滚：

```sql
ALTER TABLE ragevalcaseresultbean DROP COLUMN IF EXISTS "retrievedHits";
ALTER TABLE ragevalrunbean DROP COLUMN IF EXISTS "rerankApplied";
ALTER TABLE ragevalrunbean DROP COLUMN IF EXISTS "retrievalMode";
ALTER TABLE ragevalrunbean DROP COLUMN IF EXISTS "metricSpec";
```

删除列会丢失本阶段之后产生的明细，执行前必须备份两张表。本机当前没有可用的 PostgreSQL 实例（无 psql、5432 未监听、无 Docker），以上 DDL **未在真实数据库执行**，仅由单元测试与静态检查验证代码路径。

---

## 7. 核心代码与口径

### 7.1 采集命中明细（RagEvalRunner）

```java
for (KbSearchItem hit : items) {
    if (hit == null) {
        continue;
    }
    String docName = hit.getDocName() == null ? "" : hit.getDocName();
    data.retrievedDocs.add(docName);
    data.contents.add(hit.getContent() == null ? "" : hit.getContent());
    data.hits.add(toHitDto(data.hits.size() + 1, hit, docName));
}
```

`rank` 用 `data.hits.size() + 1` 生成，与 `retrievedDocs` 的顺序严格一致；`contents` 保留完整正文供关键词匹配。

### 7.2 运行快照（RagEvalRunner）

```java
finished.setMetricSpec(METRIC_SPEC);
finished.setRetrievalMode(rerankApplied
        ? RETRIEVAL_HYBRID_RERANK : RETRIEVAL_HYBRID);
finished.setRerankApplied(rerankApplied);
```

### 7.3 精排结果与状态一起返回（KbRagHolder）

```java
private static final class RerankOutcome {
    private final List<KbSearchItem> items;
    private final boolean applied;
}
```

`search()` 把 `applied` 写入 `KbSearchResult.rerankApplied`，评估层无需再根据顺序猜测。

### 7.4 详情接口解析（RagEvalHolder）

```java
dto.setRetrievedDocs(RagEvalKeywordCodec.parseStringList(bean.getRetrievedDocs()));
dto.setRetrievedHits(RagEvalKeywordCodec.parseHits(bean.getRetrievedHits()));
```

---

## 8. 异常处理

| 场景 | 行为 | 用户可见结果 |
| --- | --- | --- |
| 未配置 rerank | `applied=false`，原顺序返回 | 检索链路显示 `hybrid-rrf` |
| rerank 超时/异常 | 捕获异常，降级原顺序，`applied=false` | 评估照常完成，不被精排故障阻塞 |
| 精排返回 null | 降级原顺序，`applied=false` | 同上 |
| 单题检索失败 | 该题 `hits=[]`，写入失败原因，指标按 0 计 | 展开行显示「未记录 Top-K 明细」 |
| 明细 JSON 损坏 | `parseHits` 返回空列表 | 指标正常展示，详情不报错 |
| 历史运行无新列 | 列为 NULL，解析为空列表 | 显示旧版说明 |
| 明细落库失败 | 计入 `persistFailures`，运行转 `partial` | 提示「N 条逐题结果保存失败」 |

评估主链路的原则不变：**精排与明细是增强信息，不能阻塞检索与评估本身**。

---

## 9. 验收方法

### 9.1 自动化结果（2026-11-06 实测）

后端全量 reactor：

```text
ai-common          7
app-business-permission 71
app-business-service    466
app-start                27
合计                    571   Failures 0 / Errors 0
```

定向测试：

```text
RagEvalRunnerTest  12 项通过
RagEvalHolderTest  18 项通过
```

本子阶段新增/扩展的后端断言：

- 命中明细按原始顺序生成 rank，分数、分块、来源、预览完整持久化；
- 未参与关键词路的片段保留 `-1`，不被伪造成 0 分；
- `rerankApplied=true` 时运行快照写入 `hybrid-rrf+rerank`；
- 预览截断到 200 字符，但关键词匹配仍使用完整正文；
- 旧运行没有 `retrievedHits` 时返回空列表，详情接口不失败。

前端：

```text
类型检查              vue-tsc 通过
逻辑测试              245 项通过（20 个文件）
组件测试              258 项通过（27 个文件）
生产构建              vite build 通过
```

组件测试新增断言：

- 头部展示 `口径 rag-eval-v1` 与 `检索链路 hybrid-rrf+rerank`；
- 展开行展示「Top-2 命中明细」「融合排序分」「双路」「向量路」「块 3」「未参与」；
- 第二题没有明细时显示「本次运行未记录 Top-K 明细」；
- 基线口径不同时显示「口径不同，差值仅供参考」。

### 9.2 本阶段没有证明什么

- 数据库迁移 SQL 未在真实 PostgreSQL 执行（本机无实例），只验证了代码路径与 DDL 文本一致性；
- 未用真实向量模型 + 真实 rerank 服务跑一轮评估来观察明细落库，相关行为由 Mapper 边界单测覆盖；
- 未做浏览器点击级验证；本轮 UI 正确性由 258 项组件测试与类型检查保证，真实浏览器联调留到本阶段验收时补。

### 9.3 残余风险

- 命中明细使单题行体积增加约 `TopK × 300B`，当前 TopK 上限 20、题量上限 200，单次运行最多约 1.2MB JSON，可接受；若未来放宽上限需要重新评估；
- 运行级 `rerankApplied` 是「任一题为 true」，不是逐题精确快照；
- `metricSpec` 只覆盖指标算法版本，不覆盖 chunk size、分块策略等影响检索质量的配置；这些参数目前没有运行级快照，跨版本对比仍可能失真；
- 基线仍是隐式选择（同数据集、更早、状态可比的第一条），差值提示不能替代显式基线（6B）。

---

## 10. 面试问答

### Q1：为什么存 RRF 融合分，而不是只存余弦相似度？

因为混合检索下不存在单一「相似度」。一个片段可能向量排第 3、关键词排第 1，另一个反过来。RRF 只看排名不看原始分，正是为多路排序融合设计的。只存余弦分会丢掉关键词路的贡献；只存 RRF 分又无法解释向量路本身有多强，所以三个分都保留，界面上明确标注「融合排序分只用于排序，不是相似度」。

### Q2：`channel` 字段解决了什么问题？

它把「为什么召回」变成可枚举的事实：`both` 表示两路都中，`dense` 表示只有语义匹配，`lexical` 表示只有词项匹配。排查「专有名词为什么没召回」时，一眼能看出是向量模型区分度不足，还是全文检索没覆盖。没有这个字段，只能看到一条命中记录，无法归因。

### Q3：片段预览截断了，关键词命中会不会被截断影响？

不会。关键词匹配使用的是 `contents` 里的完整正文，预览只写入 `hits`。两者在 `evaluateCase` 中分别收集。测试 `execute_shouldTruncatePreviewWithoutAffectingKeywordMatching` 用 400 字符正文 + 末尾关键词验证了这条不变量。

### Q4：为什么需要 `metricSpec`，直接比较数字不行吗？

指标数字只有在同一算法口径下才可比。文档级去重、NDCG 二值相关、关键词同块命中，任何一条改变都会让数字变化。没有版本号时，一次「Recall 提升 8pp」可能来自算法变化而不是检索变好。版本号让不正当比较显性化。

### Q5：`rerankApplied` 为什么不能看顺序有没有变化？

因为精排成功但排序恰好不变时，顺序也没变；精排未配置或失败降级时，顺序还是没变。三种语义完全不同的情况在返回值上无法区分。必须由执行精排的代码显式上报「我到底调没调用成功」。

### Q6：为什么运行级用「任一题为 true」而不是要求全部题都为 true？

检索配置是运行级配置，正常情况下一整轮都应该启用精排。用「任一题」可以在配置中途变更或部分题失败时仍然如实反映「本轮确实走过精排」，避免误报为完全未启用。它牺牲的是逐题精度，这个限制已经写进文档；需要逐题精度时再把标记下放到逐题明细。

### Q7：为什么明细解析失败要返回空列表，而不是抛异常？

因为接口的主职责是返回运行指标，明细是辅助证据。一条历史脏数据不应该让整个运行详情 500。这与「写入失败转 partial」是同一个设计取向：主数据优先，辅助数据可降级。

### Q8：为什么不直接关联分块表实时查询明细？

评估报告必须是**运行时刻的快照**。知识库重新上传、分块重建、文档删除之后，实时关联会得到与当时不同的结果，甚至查不到。把命中信息在评估时写进明细，才能保证历史报告自洽，也符合审计场景的要求。

### Q9：两份 DDL 如何避免漂移？

`TableSchemaInitializer` 负责启动时自动迁移，`scripts/sql` 负责手工迁移和结构留档，两者是同一结构的两个副本。本项目约定修改时必须同步，并在学习文档中记录新增列与回滚语句。进一步的做法是让脚本从同一份声明生成，或在 CI 中做 schema diff；当前规模下先靠约定与代码审查。
