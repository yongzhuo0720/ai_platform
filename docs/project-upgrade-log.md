# 项目升级日志

记录每一轮自主升级的范围、变更、验证证据、遗留问题与下一轮计划。只写实际完成并验证过的内容；未验证的事项明确标为待办。

历史轮次的详细材料见：

- `README.md`（项目总览与运行方式）
- `docs/第二轮深度升级-审查报告-2026-11-05.md`（第二轮审查结论）
- `docs/功能检查与改进路线图-2026-10-24.md`（功能缺口盘点）
- 各阶段学习文档（`docs/学习文档-*`）

---

## 2026-11-07：SSE 网关异步中继、断连回收与审计可靠性

### 背景

本轮先处理已知遗留问题。排查流式对话链路时确认了三个相互关联的缺陷：

1. 网关直接在 Tomcat 请求线程里做上游 IO。一条默认上限 10 分钟的流式回答会占住 Web 线程，几十条长对话即可拖慢登录、后台等无关键口。
2. 客户端中途关闭页面后，容器会回收响应对象，而中继线程仍在 catch/finally 里写响应，导致**下一次复用的连接被重置**（可稳定复现的 ECONNRESET）。
3. 客户端断开时中继线程被中断，Druid 获取连接时抛 `SQLException: interrupt`，审计日志静默丢失。

### 已完成

#### 1. 网关转发迁移到专用线程池（Servlet 异步）

- 新增 `AiGatewayAsyncDispatcher`：控制器把整段转发交给 `aiGatewayExecutor`，Tomcat 请求线程在开始转发时立即归还。
- 线程池 `ai-gateway-*`：核心 8、最大 48、有界队列 128；队列满时 `CallerRunsPolicy` 退化为同步转发，**不丢请求**。
- 同步回退路径完整保留：`async-dispatch-enabled=false`、容器不支持异步、线程池不存在时，行为与改造前一致。
- `LoginAuthInterceptor`、`ApiTokenAuthInterceptor` 对 `DispatcherType.ASYNC` 放行：异步派发只是转发结束后的收尾，不重复鉴权，避免长流结束后再写 401。
- 上游 HTTP 改为连接池客户端（Apache HttpClient 4.5.x，版本由 Spring Boot BOM 管理）：连接池上限与中继线程数对齐，闲置 30 秒回收，**关闭自动重试**（生成类请求重发会导致上游重复生成、重复计费）。

#### 2. 响应回收竞态修复（AsyncResponseGate）

- 新增 `AsyncResponseGate`：以同一把锁串行化「容器取消」与「响应写入」。
  - 取消后所有写入（SSE 分片、非流式响应体、错误 JSON、最终 flush）一律拒绝，不再触碰已回收的响应。
  - 取消时通过 `HttpRequestBase.abort()` 唤醒阻塞的上游读，及时释放连接。
  - 门闩注册为 `CallableProcessingInterceptor`；超时/错误回调返回 `RESULT_NONE`，保留容器默认的 503 / 异常处理语义，不用 `WebAsyncTask.onTimeout`（返回 null 会吞掉错误）。
- 中继线程的异常分类统一识别：客户端断开、线程中断、门闩已取消、SSE 写失败，均按「客户端已断开」收尾，不再向容器抛出 500。

#### 3. 审计落库可靠性

- `AiCallLogHolder` 改为专用守护线程 `ai-audit-writer` 串行写库；调用方最多等待 5 秒，超时只告警、不阻塞转发。
- 调用方线程被中断不再影响 JDBC：审计写入发生在独立线程上，避免了 `SQLException: interrupt` 导致记录静默丢失。
- 应用停机时等待队列内审计写完（最多 5 秒），再强制关闭。

#### 4. 断连探测与心跳

- SSE 心跳改由独立调度器（2 个守护线程）按固定间隔写入，不再依赖「中继线程读超时后顺手写」。
- 心跳间隔默认 15 秒调整为 3 秒：该值同时是断连探测周期，浏览器点「停止」后最迟一个周期即可判定断开、释放上游连接并回收预扣额度。
- 心跳写失败即判定客户端已断开，由门闩关闭上游请求，唤醒阻塞读。

#### 5. AssistantBean 无用字段清理

- 删除 `type`、`agentId`、`apiUrl`、`authToken`、`channel` 五个字段：智能体分发早已由服务端统一处理，这些字段无任何读写方。
- 删除只为上述字段服务的 `AssistantBeanHolder.findUsable`（无调用方）。
- 同步清理前端 `AssistantData` 类型中的对应可选项。
- 已全局检索确认无残留引用；后端测试同步更新。

### 变更文件

**新增**

- `backend/.../service/gateway/AiGatewayAsyncDispatcher.java`
- `backend/.../service/gateway/AsyncResponseGate.java`
- `backend/.../service/gateway/AiGatewayHeartbeatScheduler.java`
- `backend/.../app-start/config/AiGatewayExecutorConfig.java`
- `backend/.../app-start/config/AiGatewayHttpClientConfig.java`
- `backend/.../service/gateway/AiGatewayAsyncDispatcherTest.java`（8 个用例）
- `backend/.../service/holde/AiCallLogHolderTest.java`（2 个用例）

**修改**

- `AiGatewayService.java`、`AiGatewayController.java`、`OpenAiCompatController.java`
- `AiGatewayProperties.java`、`application-dev.example.properties`
- `AiCallLogHolder.java`、`AssistantBean.java`、`AssistantBeanHolder.java`
- `LoginAuthInterceptor.java`、`ApiTokenAuthInterceptor.java`
- `app-business-service/pom.xml`（显式声明 HttpClient）
- 相关测试：`AiGatewayServiceTest`、`AiGatewayOpenAiCompatTest`、`AiUpstreamStub`、`AssistantBeanHolderTest`
- `frontend/src/api/assistant.ts`

### 测试结果

- 后端（service + app-start 两模块）：**592 个用例，0 失败、0 错误、0 跳过**
  - `app-business-service`：565
  - `app-start`：27
  - 相对上轮 585 净增 7 个；完整 reactor 口径见下一条（含 permission/common）
- 构建：`mvn -o package` 成功，应用可正常启动。
- 前端：`assistant.ts` 仅类型清理，构建与类型检查通过。

### 真实环境验收（本地全栈 + 慢速 mock 上游）

- **客户端中断**：连续 4 轮「发起流式请求 → 1.5~2 秒后客户端断开」，服务端均在约 2.9 秒内收尾，审计表写入
  `200 / 客户端已断开连接`，不再丢失记录。
- **连接复用**：中断后 100ms / 300ms / 800ms 间隔发起后续请求，**零 ECONNRESET**；改造前可稳定复现。
- **正常流式回归**：两轮正常请求均返回 `200`、1864 字节、约 9.2 秒完整输出；随后的列表请求约 5ms 正常返回。
- **审计线程**：调用方线程被中断时，审计仍由独立线程写入；单元测试用闩锁证明确实落库。

### 未解决 / 已知风险

1. 真实 Dify 服务端 E2E：已在下一条补验（chat 与 knowledgeChat 均返回 200 并落审计）。
2. 客户端中断的那条 POST 在 Tomcat access log 中仍记为 `500`：这是容器异步错误派发的固有表现（应用已按断开收尾），不影响后续请求；如需与业务错误区分，可后续在访问日志层做标记。
3. 线程池上限（48）是经验值，尚未做高并发压测；队列满时会退化为同步转发，不会报错但会重新占用请求线程。

---

## 2026-11-07（续）：真实环境核心链路复核与模型连接测试补全

### 真实环境复核（本地全栈）

- **Dify 云端对话**（`/aiGateway/chat/chat-messages`，streaming）：HTTP 200，82 个 SSE 事件 / 30,091 字节 / 3.3 秒；审计落库 197 tokens、3331ms。
- **Dify 知识库问答**（`/aiGateway/knowledgeChat/chat-messages`）：HTTP 200，21 个 SSE 事件 / 12,088 字节 / 2.9 秒；审计 199 tokens、2875ms。
- **本地知识库检索**（`/kbRag/search`）：真实 SiliconFlow `BAAI/bge-m3` 向量 + `BAAI/bge-reranker-v2-m3` 精排；关闭精排 409ms、开启精排 336ms，返回 `rerankApplied=true`。
- **模型直连流式**（`/aiGateway/models/7/chat/completions`）：慢速 mock 上游，15 个 SSE 事件 / 1,864 字节 / 9.3 秒，用于验证中继与断连链路，不代表真实模型效果。
- **Agent 编排**（`/agentBean/run`）：本地 mock 模型上游，状态 `succeeded`、1 轮迭代、96 tokens，步骤 `llm:success` / `finish:success`，审计 `models | agent/run | 200 | 96`。测试用模型/Agent 配置与运行记录已按 runKey 精确清理。

> Dify 与 SiliconFlow 为真实外部服务；直连与 Agent 两项目的是验证服务端编排与计费链路，上游模型用本地 mock，未冒充真实模型联调。

### 缺陷修复：连接测试覆盖向量与重排模型

原实现只支持 OpenAI 对话模型：向量模型（只有 `embedModel`、没有 `upstreamModel`）报「服务地址、服务密钥、上游模型名均配置后才能测试」；重排模型（`protocol=rerank`）直接报「该配置未启用 OpenAI 兼容直连，无需测试」。模型管理页因此无法对这两类模型做健康检查。

现改为：

- `protocol=rerank` → POST `/rerank`（`model/query/documents/top_n`），校验 `results[0].index`；
- `protocol=openai` 且只有 `embedModel` → POST `/embeddings`（`model/input`），校验 `data[0].embedding`；
- 非 200 响应统一走 `postFailure`，鉴权失败 / 限流 / 请求被拒 / 不可达分类与对话模型一致；返回体仍不含服务地址与密钥。
- 新增 5 个单元测试（向量成功、向量 401、重排成功、重排拒绝模型、未知协议），该类共 12 个用例。

真实环境实测（重建并重启后端后）：

- 向量配置 iid=10：`status=ok`、HTTP 200、332ms、提示「通过向量接口验证」；
- 重排配置 iid=11：`status=ok`、HTTP 200、108ms、提示「通过重排接口验证」。

### 测试结果

- 后端完整 reactor：**675 个用例，0 失败、0 错误、0 跳过**
  - `app-business-service`：570（本轮 +5）
  - `app-business-permission`：71
  - `app-start`：27
  - `app-common`：7
- 口径修正：上一条的「592」只统计了 service + app-start 两个模块，完整 reactor 基线应为 670。
- `mvn -o package` 成功；重启后连接测试与核心链路均正常。
- 前端本轮无改动。

### 已知问题 / 风险

1. **中文关键词召回偏弱**：`to_tsvector('simple', content)` 对中文按连续字符串切分，`plainto_tsquery('simple', '差旅报销的审批流程是什么')` 实际只能命中连续原文整串，词法路命中率低，检索基本由向量路承担。后续可考虑 PG 中文分词扩展，或应用层分词后再做 FTS。
2. 客户端中途断开的 POST 在 Tomcat access log 仍记 500（容器异步语义），应用侧已按断开收尾。
3. 线程池上限 48 未做压测；队列满退化为同步转发。
4. Windows 下打包偶发「BUILD SUCCESS 但 fat jar 未更新」（运行中的 JVM 占用 jar），删除 `app-start-1.0-SNAPSHOT.jar` 后重新打包可解决；`scripts/start.ps1` 会先清理残留进程，日常启动不受影响。

### 下一轮计划

- 第二阶段：RAG 中文召回改进（分词 / 混合检索权重）、模型管理页展示向量与重排的测试结果、网关配额与多实例细化。
- 继续以真实环境验证为主，mock 仅用于无法稳定获取的上游。
---

## 2026-11-07（三）：本地 RAG 中文关键词召回修复

### 问题

上一条把「中文关键词召回偏弱」记为已知问题，本轮实测确认它比描述更严重：

- 查询「差旅报销的审批流程是什么」时，Top-3 的 `channel` 全部是 `dense`，关键词路**零召回**；
- 原因：`to_tsvector('simple', content)` 没有中文分词，连续中文是一个词元；
  `plainto_tsquery('simple', 整句)` 只能命中原文中连续出现的同一整串；
- 排序侧本来就有二元滑窗（`tokenize`），但召回侧从未用上它，两处口径不一致。

### 修复

- 新增 `KbRagHolder.buildLexicalWhere(query)`：
  - 保留原有整句全文检索（英文词级匹配、精确短语仍然有效）；
  - 把查询按 `tokenize` 展开成二元词项，逐个拼 `"content" ILIKE '%词项%'`，任一词项命中即召回；
  - 最多展开 16 个词项（`MAX_LEXICAL_TERMS`），避免长查询把 SQL 撑爆；
  - 词项只含字母、数字与汉字，不含 `% _ \`，不会改变 LIKE 匹配范围；仍套一层 `escapeSqlLiteral` 做纵深防御；
  - 整段 WHERE 外包一层括号，与归属条件 `lastuser = uid` 做 AND 时优先级正确。
- 更新检索流程与索引相关注释，说明「FTS 负责英文、二元词项补中文」的分工。

### 测试

- `KbRagHolderTest` 新增 3 个用例（共 30 个）：
  - 中文查询必须展开为二元词项并保留整句 FTS；
  - 长查询词项数量封顶 16；
  - LIKE 模式串不含通配符、单引号被转义。
- 强化原有恶意输入用例：捕获实际下发的关键词路 SQL，断言包含 `ILIKE` 且单引号已加倍。
- 后端完整 reactor：**678 个用例，0 失败、0 错误、0 跳过**
  - `app-business-service`：573（本轮 +3）
  - `app-business-permission` 71、`app-start` 27、`app-common` 7

### 真实环境验证

同一知识库（「评估语料」）与同一问题，重建并重启后端后复测：

| 指标 | 修复前 | 修复后 |
|---|---|---|
| Top-3 `channel` | dense / dense / dense | both / both / both |
| Top-1 RRF 分 | 0.0164 | 0.0325 |
| 检索耗时（首次/预热） | 195ms | 2016ms（冷启动）/ 183ms |
| 精排开启耗时 | 363ms | 305ms |

- 追加查询「一线城市住宿标准是多少」：预热 162ms，Top-1 命中「一线城市（北京、上海、广州…」分块，来源 `both`。
- 结论：中文问题现在确实走两路召回；延迟在预热后与修复前持平。

### 已知风险

1. `ILIKE` 分支无法使用 `idx_kbchunk_content_fts`（GIN 表达式索引只能服务 FTS 分支）。当前单用户分块数在百级、扫描上限 5000，实测延迟可接受；数据量继续增长时可引入 `pg_trgm` GIN 索引或应用层分词后再建索引。
2. 二元词项会带来少量泛化命中（如「多少」命中无关文档），目前由词项重叠排序 + RRF 排名压制；后续可用停用词表或词频权重细化。

### 下一轮计划

- 继续第二阶段：网关在 Redis 故障下的降级行为核查、配额与多实例一致性、Agent 工具调用的真实上游验证。
- 每轮保持全量测试 + 真实环境复核 + 日志更新。

---

## 2026-11-07（四）：Agent 工具调用闭环真实验证（模型上游为本地 mock）

### 验证目标

补齐 Agent「模型决定调用工具 → 执行工具 → 结果回灌 → 模型收尾」的端到端验证。
此前只验证过 Agent 纯对话（无工具）链路，工具执行器与工具注册链路没有跑通端到端。

### 环境与口径（避免把 mock 说成真实模型）

- **模型上游：本地 mock（OpenAI 兼容）**。当前环境没有可用的真实对话模型，
  不新建、不改动任何真实凭据，因此模型侧的两次回复由本地 mock 返回，不能算真实模型联调。
- **工具执行：真实链路**。`search_kb` 走 `AgentToolExecutor → KbRagHolder.search`
  → PostgreSQL 真实分块 → 真实 SiliconFlow 向量模型（`BAAI/bge-m3`）。
- 测试脚本放在 `.tmp/`，用完即删配置与数据，不进入仓库。

### 执行序列（清理后无残留）

1. 创建临时模型配置，指向 `127.0.0.1:18091` 的 mock 上游；
2. 创建临时 Agent（`maxIterations=3`、`tokenBudget=10000`、`toolTimeoutMs=8000`）；
3. 注册 `kb_search` 工具（JSON Schema 必填 `query`）；
4. 调用 `/ai/api/agentBean/run`，mock 首轮返回 `tool_calls(search_kb)`，
   工具真实检索到「01-差旅报销制度」分块（1383 字）回灌，mock 次轮据此收尾；
5. 核对响应、步骤表、审计表后，删除工具 / Agent / 模型配置，
   并按 `runKey` 清理运行、步骤与审计行。

### 结果

| 指标 | 实测 |
|---|---|
| 运行状态 | `succeeded` |
| 迭代轮数 | 2 |
| token（prompt/completion/合计） | 200 / 60 / **260** |
| 运行耗时 | 600ms（HTTP 往返 612ms） |
| 步骤序列 | `llm → tool_result(search_kb) → llm → finish`，全部 success |
| 工具返回 | 1383 字，命中「差旅与费用报销制度」正文 |
| 审计落库 | 2 行（100 + 160 token），合计 260，与运行总计一致 |

- 说明：工具执行以 `tool_result` 单步落库（入参与结果同一条），
  这是 `AgentRunner` 的既有设计，不是缺失 `tool` 步骤。
- 清理后复核：临时 Agent / 模型 / 工具均为 0 行，运行、步骤、审计行已删除。

### 测试与改动

- 本轮只新增临时验证脚本与本文档，**没有修改生产代码**；
- 后端回归基线仍为上一节的 **678 个用例，0 失败、0 错误、0 跳过**。

### 已知限制

1. 模型侧仍是 mock，真实对话模型（含工具调用能力）的上游联调尚未完成；
   配置到位后应重跑同一脚本，只替换 `baseUrl/apiKey/upstreamModel`。
2. 工具失败重试、模型返回多个 `tool_calls`、工具超时三条分支目前只有单元测试覆盖，
   未做真实端到端演练，留待下一轮。

### 下一轮计划

- 继续第二阶段：网关配额与多实例一致性、Redis 故障降级复核；
- 有真实对话模型后补 Agent 工具调用真实上游验证与超时/多工具分支；
- 每轮保持全量测试 + 真实环境复核 + 日志更新。

---

## 2026-11-07（五）：Agent 预算边界不再丢弃已完成答案

### 问题（真实缺陷）

`AgentRunner` 在拿到本轮模型回复后，先判断 Token 预算、再判断是否还有工具调用：

```text
记录 llm 步骤 → 预算已耗尽？ → 是：报错并结束
                              → 否：没有 tool_calls？ → 是：取 content 作为最终答案
```

后果：当模型在最后一轮返回完整答案、且累计用量**恰好达到**预算时，
运行会被标记为 `failed`（错误「Token 预算已耗尽」），`answer` 为空。
这一轮的 token 已经真实消耗，用户却拿不到已经付费的答案。

### 修复

- 调整判断顺序：先判断「没有工具调用」——最终答案已产出、不会再有后续动作，
  直接按成功收尾；预算判断移到其后，只约束「还要继续执行工具、再空转一轮」的情况。
- 行为不变的部分：预算耗尽且模型仍要求调用工具时，仍然立即失败并记录 `error` 步骤；
  最大迭代、重复工具调用、工具失败回灌等分支均不受影响。

### 测试

- 新增 `AgentRunnerTest#run_shouldKeepFinalAnswerWhenBudgetExhaustedOnLastRound`：
  预算 100，模型单轮返回 60+40 tokens 的最终答案，断言 `succeeded`、答案完整、无错误。
  修复前该用例失败（`failed` + 空答案），修复后通过。
- `AgentRunnerTest` 共 18 个用例全部通过。
- 后端完整 reactor：**679 个用例，0 失败、0 错误、0 跳过**
  - `app-business-service`：574（本轮 +1）
  - `app-business-permission` 71、`app-start` 27、`app-common` 7

### 真实环境验证

重建 jar 并重启本地后端（8507），用本地 mock 上游（明确标注：非真实模型）
返回 60+40 tokens 的完整答案，Agent 预算配置为 100：

| 指标 | 实测 |
|---|---|
| 运行状态 | `succeeded` |
| 迭代轮数 | 1 |
| 总 tokens | 100（等于预算） |
| 返回答案 | 「预算边界验证：这是完整答案」 |
| 步骤序列 | `llm → finish`，均 success |
| 审计落库 | 1 行，100 tokens |

- 联调后已删除临时 Agent / 模型配置，并按 `runKey` 清理运行、步骤与审计行。

### 已知限制

- 模型返回**空内容且无工具调用**时，当前仍会以 `succeeded` 收尾并得到空答案。
  这与「预算边界」是两个独立问题，已在下一节修复。

---

## 2026-11-07（六）：Agent 空回复改为明确失败

### 问题（真实缺陷）

模型返回空内容（`content` 为空或全空白）且没有工具调用时，`AgentRunner` 会：

- 将运行标记为 `succeeded`；
- `answer` 为空字符串；
- 只记录一个内容为空的 `finish` 步骤。

用户看到「运行成功」却没有任何答案，也无法区分是模型拒答、内容过滤、
配额截断还是上游异常。这与真实故障时应当给出的可操作提示相矛盾。

### 修复

- 在最终答案分支先判空：`content == null` 或 `trim()` 后为空时，
  运行保持 `failed`，错误信息为「模型未返回内容，请重试或更换模型」，
  并落一条 `stepType=error` 的失败步骤（保留本轮已产生的 token 用量）。
- 仅含空白的回复按空回复处理，避免用空格伪装成有效答案。
- 正常非空回复、工具调用循环、预算边界逻辑不变。

### 测试

- 新增 `AgentRunnerTest#run_shouldFailWhenModelReturnsBlankAnswer`：
  模型返回 `"   "`，断言 `failed`、空答案、错误信息包含「未返回内容」、
  模型只被调用一次。修复前该用例失败（`succeeded`），修复后通过。
- `AgentRunnerTest` 共 19 个用例全部通过。
- 后端完整 reactor：**680 个用例，0 失败、0 错误、0 跳过**
  - `app-business-service`：575（本轮 +1）
  - `app-business-permission` 71、`app-start` 27、`app-common` 7

### 真实环境验证

重建 jar 并重启本地后端（8507），用本地 mock 上游（明确标注：非真实模型）
返回空 `content`、10 tokens：

| 指标 | 实测 |
|---|---|
| 运行状态 | `failed` |
| 总 tokens | 10（真实消耗仍计入） |
| 返回答案 | 空 |
| 错误信息 | 「模型未返回内容，请重试或更换模型」 |
| 步骤序列 | `llm → error`（`error` 为 failed） |
| 审计落库 | 1 行，10 tokens |

- 联调后已删除临时 Agent / 模型配置，并按 `runKey` 清理运行、步骤与审计行。

### 已知限制

1. 只返回推理内容（`reasoning_content`）而没有 `content` 的模型会被判为空回复；
   如需支持，应在模型客户端做明确的内容提取策略，而不是把空答案当成功。
2. 前端 Agent 运行详情对 `failed + error` 的展示依赖既有错误态样式，
   本轮未改前端，下一轮 UI 走查时确认提示文案可见。

---

## 2026-11-07（七）：Agent 流式运行补充验收

### 背景

同步 `/api/agentBean/run` 的工具调用闭环已验收，但前端使用的是流式
`/api/agentBean/streamRun`，此前没有端到端核对事件序列与落库一致性。

### 方法

- 模型上游仍为本地 mock（明确标注：非真实模型）；工具 `search_kb`
  走真实 PostgreSQL 分块与真实 SiliconFlow 向量。
- 客户端按 SSE 空行切分事件，校验事件名、`runKey`、步骤内容与最终详情。

### 结果

| 指标 | 实测 |
|---|---|
| HTTP | 200，661ms |
| 事件序列 | `agent_start` ×1 → `agent_step` ×4 → `agent_done` ×1 |
| 流式步骤 | `llm → tool_result(search_kb) → llm → finish`，均 success |
| runKey | start 事件与 done 详情一致，且每个 step 的 runKey 相同 |
| 运行结果 | `succeeded`，2 轮，260 tokens，答案含 1383 字知识库正文 |
| 落库 | 运行 1 行、步骤 4 行；审计 2 行（100 + 160 = 260） |

- 流式事件内容与同步接口的运行详情一致，未发现重复或丢失事件。
- 本轮无生产代码改动；联调后已删除临时配置并清理运行、步骤、审计行。

### 已知限制

- 客户端**中途断开**时 `SseListener` 依赖写失败才感知，未做主动断开演练；
  断开后的运行收尾与审计完整性留待下一轮验证。
- 事件为细粒度步骤推送，尚未做断线重连/Last-Event-ID 补发。

---

## 2026-11-07（八）：Agent 单轮多工具调用验收

### 背景

此前工具闭环只覆盖「每轮一个 tool_call」。模型一次返回多个 tool_call
是 OpenAI 兼容协议的常规行为，需要确认编排器会逐个执行、逐个回灌，
而不是只处理第一个或把结果串成一条。

### 方法

- 模型上游为本地 mock（明确标注：非真实模型），一轮返回两个 `kb_search`
  调用（不同参数）；第二个 `kb_search` 仍走真实 PostgreSQL 分块、真实
  SiliconFlow 向量检索。
- 校验步骤数量与顺序、两条工具结果是否各自独立且非空、Token 与审计闭环。

### 结果

| 指标 | 实测 |
|---|---|
| 运行结果 | `succeeded`，2 轮 |
| 步骤序列 | `llm → tool_result ×2 → llm → finish` |
| 两条工具结果 | 1383 字 / 1380 字，内容不同、均来自真实知识库检索 |
| 总 tokens | 290（mock 记账 70+20 与 160+40） |
| 答案 | 「（mock 多工具联调）收到 2 个工具结果，字数：1383 / 1380」 |
| 审计 | 2 行：90 + 200 = 290，与运行记录一致 |

- 结论：同一轮的多个 tool_call 会按顺序逐个执行，每个结果单独落一条
  `tool_result` 步骤，并在下一轮请求中全部以 `role=tool` 回灌。
- 本轮无生产代码改动；联调后已删除临时模型/Agent/工具配置，并按
  `runKey` 清理运行、步骤与审计行，残留计数全部为 0。

### 已知限制

- mock 的多个工具调用都指向 `kb_search`，未覆盖「同轮混合多种工具类型」；
  执行器按工具类型分发，风险较低，但如需覆盖应补一个自带 `builtin` 时间的用例。
- 工具失败分支（单个工具抛错、超时）在同轮多调用下的部分成功语义尚未验收。

---

## 2026-11-07（九）：Agent 流式中途断开演练

### 背景

（七）只验证了正常流式事件序列，断开场景仅有单元测试
（`stream_shouldStopSubsequentModelAndToolCallsAfterFlushDisconnectAndKeepUsage`
覆盖第 1～4 次 flush 失败）。本轮用真实 TCP 断开补齐：

- 客户端收到 `agent_start` 后立即销毁 socket；
- mock 上游首轮延迟 3 秒，给断连留出窗口（明确标注：非真实模型）；
- 6 秒后按 `runKey` 回查运行记录、步骤与审计。

### 结果（连续两轮一致）

| 指标 | 实测 |
|---|---|
| 运行状态 | `failed`，`finishedAt` 已写，耗时约 3.6s |
| 错误信息 | 「客户端已断开连接，执行已停止」 |
| 模型调用 | 每轮 1 次（mock 日志确认无第二次调用） |
| 迭代 / tokens | 1 轮 / 100（80 + 20），真实计入 |
| 步骤 | `llm → tool_result`；无 `finish`，无第二段 `llm` |
| 审计 | 每轮 1 行，100 tokens，`runKey` 关联正确 |

- 断连感知发生在「下一次 SSE 写失败」时。第一轮演练中 `llm` 步骤的写操作
  被内核缓冲接受，断连在 `tool_result` 写入时才暴露，因此**可能多执行一个
  已启动的工具调用**；但不会再发起计费模型调用，运行也会正常收尾。
- 这是同步 Servlet 输出链路的固有边界：没有写入就无法可靠感知对端关闭，
  本轮不引入额外心跳，避免为极小概率场景增加常驻写流量。
- 演练后已删除临时配置，并按 `runKey` 清理运行、步骤与审计行，残留计数为 0。

### 已知限制

- 若客户端在两次写入之间断开且不再有写入，运行会继续到下一次步骤才停止；
  与本轮观察一致，属于已知边界而非数据错误。
- 尚未实现断线重连与事件补发（`Last-Event-ID`）。

---

## 2026-11-07（十）：Agent 失败态前端展示验收

### 背景

（六）修复空回复后，只通过接口确认了 `failed + error` 的落库结果，
未验证用户能否在页面上看懂失败原因。本轮补做真实浏览器走查。

### 方法

- 真实后端（8507）+ 真实前端开发服务器（5173）+ Chromium（Edge，
  无头模式）真实点击，不使用 `page.route` 拦截；
- 临时模型指向本地空回复 mock（明确标注：非真实模型），临时 Agent
  不配置工具；
- 登录态通过 Write Cookie 注入既有 QA 会话，未创建或修改任何账号。

### 结果

| 检查项 | 实测 |
|---|---|
| 运行状态徽标 | 「失败」（danger 配色） |
| 运行错误提示 | `role=alert` 显示「运行错误：模型未返回内容，请重试或更换模型」 |
| 步骤错误 | 第 2 步（`error`）显示同一条错误 |
| 错误定位 | 「定位到第 2 步错误」按钮存在且可聚焦 |
| 最终回答区 | 显示「本次运行未返回最终回答。」，不出现空白成功态 |
| 浏览器控制台 | 无 error，无未捕获异常 |
| 落库 | 运行 failed / 10 tokens；步骤 `llm → error`；审计 1 行 10 tokens |

- 页面无需改动，既有失败态样式与文案满足可读性要求。
- 走查后已删除临时 Agent / 模型配置，并按 `runKey` 清理运行、步骤与审计行，
  残留计数为 0。

### 已知限制

- 本次仅为一次性真实链路走查，未加入仓库 E2E 套件：现有
  `frontend/e2e/agent.spec.ts` 用接口拦截保证 CI 稳定，真实后端用例需要
  额外准备数据库与 mock 上游，不适合默认 CI。
- 截图保存在本地 `.tmp`，不随仓库提交。

---

## 2026-11-07（十一）：真实 Redis 配额边界联调

### 背景

网关配额此前只有基于 mock Redis 的单元测试，没有在真实 Redis 与真实后端
进程中验证「预扣 → 结算 → 超额拦截」的完整边界。本轮补做一次端到端联调。

### 方法

- 真实后端（8507）+ 真实 Redis（db 11）+ 本地 mock 模型上游（非真实模型）；
- 读取当日管理员已用计数 69841，临时把配额覆盖设为 71441
  （= 当前已用 + 1600：两次 1500 预扣可通过，第三次预扣必然超额）；
- 每次 Agent 运行只发起一次模型调用，mock 固定返回 usage 80/20，
  结算后应记为 100 tokens；
- 断言三次运行的状态、Token、错误文案与配额总览用量；
- finally 删除临时 Agent / 模型与配额覆盖，按 `runKey` 清理运行、步骤、审计，
  并恢复 Redis 计数。

### 结果

| 检查项 | 实测 |
|---|---|
| 初始已用 / 临时配额 | 69841 / 71441 |
| 第 1 次运行 | `succeeded`，100 tokens，步骤 `llm → finish` |
| 第 2 次运行 | `succeeded`，100 tokens，步骤 `llm → finish` |
| 第 3 次运行 | `failed`，0 tokens，步骤仅 `error` |
| 第 3 次错误文案 | 「今日 AI 额度已用完（已用 70041 / 71441 tokens），请明天再试或联系管理员」 |
| 配额总览 usedToday | 70041（= 69841 + 200，第三次预扣已回滚） |
| 审计行 | 仅 2 行、各 100 tokens；第三次无上游调用、无审计 |
| 清理 | 临时配置 0 残留、配额覆盖 0 残留、Redis 计数恢复 69841 并重置 TTL |

结论：预扣在真实 Redis 上按预期拦截超额请求，结算值与上游 usage 一致，
被拦截的请求不会产生上游调用或审计记录。

### 已知限制

- mock 上游不是真实模型，本轮只验证配额与结算路径；真实模型的 usage 口径
  已由此前的流式链路验收覆盖；
- 未做多用户并发争抢配额的压测，原子性由 Redis `increment` 加超额回滚保证，
  已有单元测试覆盖；
- 联调脚本为一次性验证，保留在本地 `.tmp`，未加入默认测试套件。

---

## 2026-11-07（十二）：多实例全局并发闸门与模型配置跨实例同步

### 背景

此前并发闸门是进程内信号量：单实例能限流，但部署两个实例后实际并发上限
会翻倍。模型配置的 5 分钟本地快照也有同样的问题——A 实例新增/停用模型后，
B 实例只能等 TTL 到期，期间会出现「新模型在别的实例报未配置」或
「配置已删但旧实例仍在调用旧上游」。

### 改动

**全局并发租约（`GatewayConcurrencyGuard`）**

- Redis ZSET 保存当前占用的许可：key 为 `ai:gateway:concurrent:<module>`，
  member 为请求 UUID，score 为租约到期毫秒；
- 获取许可用 Lua 原子完成「清理过期租约 → 检查 ZCARD → ZADD → PEXPIRE」，
  多实例共享同一个上限；释放用 Lua 先 ZREM，计数归零时 DEL；
- 租约默认 60 秒，流式取 `maxStreamMs + 60s`，无上限任务取 30 分钟，
  实例崩溃后过期租约自动回收，不会永久占住名额；
- Redis 不可用时退回单实例信号量（fail-open 到本机保护），
  与「治理组件故障不阻断业务」的既有约定一致。

**模型配置跨实例同步（`ModelConfigVersionWatcher`，新增）**

- `ModelsConfigBeanHolder` 写库成功后先递增 Redis 版本号
  `ai:gateway:modelconfig:version`，再发布本地 `ModelConfigChangedEvent`；
- 各实例每 2 秒读取版本号，值变化时广播同一个本地事件，
  `ModelGatewayResolver` / `EmbeddingClient` / `ModelPriceResolver` /
  `RerankResolver` 按既有监听逻辑丢弃快照；
- 首次读到的版本只建立基线；版本键缺失按 0 处理，Redis 被清空后的
  首次写入也能被感知；
- Redis 读取失败只打「正常→故障 / 故障→正常」两条日志，保持原有 5 分钟
  TTL 兜底，不刷屏、不影响业务；
- 版本号递增或事件发布失败都不会把已经成功的写操作报成失败。

### 验证

**单元测试**：后端全量 694 项（7 + 71 + 589 + 27）全部通过，0 失败；
新增 10 项：并发租约 4 项（Redis 拒绝优先、共享 key 与租约参数、
Redis 异常回退本地、无上限不访问 Redis），版本同步 9 项、Holder 版本递增
与失败兜底 1 项（含既有用例扩展）。

**双实例并发联调**（A=8507、B=8508，共享真实 PostgreSQL/Redis，
两实例均设 `maxConcurrentPerModule=1`，上游为本地 8 秒延迟 mock）：

| 步骤 | 实测 |
|---|---|
| run1 在 A 上执行 | 8240ms，`succeeded`，100 tokens |
| run2 在 B 上并发执行 | 564ms 内 `failed`，错误「当前 AI 请求过多，请稍后重试」 |
| run1 释放后 run3 打到 B | 8108ms，`succeeded`，100 tokens |
| 上游调用 | 本轮 mock 仅新增 2 次调用（run2 未触达上游） |
| Redis 租约残留 | 运行结束后 `ai:gateway:concurrent:*` 为 0 个 key |

**跨实例配置同步联调**：

| 步骤 | 实测 |
|---|---|
| A 建模型 + Agent，访问 B 配置状态暖快照 | B 返回成功 |
| A 删除模型后立刻在 B 运行 | 8067ms，仍成功（证明 B 确实在用旧快照） |
| 等待 3 秒（轮询周期 2 秒）后再在 B 运行 | 24ms 内失败，提示「尚未配置可用的直连模型…」 |
| B 实例日志 | 连续记录版本 `0→1`、`1→2`、`2→3`、`3→4`、`4→6` 的失效动作 |

结论：并发上限在实例间真实共享，拒绝发生在调用上游之前；模型配置的
新增与删除都能在约 2 秒内传播到其他实例，不再依赖 5 分钟 TTL。

### 数据清理

- 按 12 个 `runKey` 删除运行 12 行、步骤 20 行、模型调用审计 8 行；
- 临时模型 / Agent 残留计数为 0；
- Redis 当日用量计数恢复为 69841，TTL 重置为 48 小时；
- 并发租约与预扣键均无残留；两个联调实例与 mock 进程随后停止，
  恢复单实例默认并发上限运行。

### 已知限制

- 联调上游是本地 mock，不是真实模型；本轮验证的是并发闸门与配置传播，
  真实模型的 usage 口径已由此前流式链路验收覆盖；
- 两个实例运行在同一台机器、共享同一 PostgreSQL/Redis，未覆盖跨机器网络
  分区；Redis 故障时的降级行为由单元测试覆盖；
- 版本轮询最坏有约 2 秒延迟；直接改库（绕过后台写接口）仍只能等 5 分钟 TTL；
- 联调脚本为一次性验证，保留在本地 `.tmp`，未加入默认 CI 套件。

## 2026-11-07（十三）：数据库备份恢复演练脚本化

### 背景

此前只有「库里数据是真的」的验收，没有验证过「备份文件能不能恢复」。
Phase 5 要求补齐备份与恢复，因此本轮针对真实 PostgreSQL 18.6 做了一次完整演练，
并把流程固化为可重复执行的脚本，而不是留在一次性命令里。

### 实现

新增 `scripts/backup-restore-drill.ps1`：

- 从 `application-dev.properties` 读取账号密码（支持 `${ENV:default}` 占位符），
  全程不打印密码，也不把口令写进命令历史或日志；
- `pg_dump -Fc` 生成自定义格式备份，`pg_restore --no-owner --exit-on-error`
  恢复到临时库；恢复前先 `pg_terminate_backend` 清掉旧连接；
- 校验三组证据：逐表行数（`query_to_xml` 单条 SQL 扫描全部业务表）、
  索引/约束/序列数量、9 张关键表的整表内容 MD5；
- 演练库名必须包含 `drill`/`restore`，否则要求 `-Force`；不允许与源库同名；
- 默认在 `finally` 里删除演练库与容器内备份，`-DumpOut` 可另存留档；
- 修复了 PowerShell 5.1 的一个真实坑：`$ErrorActionPreference=Stop` 且
  `2>&1` 时，原生命令写到 stderr 的 NOTICE 会被当成终止错误，
  导致 `drop database if exists` 这种成功语句把脚本打挂。改为局部降级
  错误偏好、按退出码判断，并从返回值中剔除 NOTICE/WARNING/INFO 行；
- 统一控制台输出为 UTF-8，避免中文 Windows 下日志乱码。

同时清理了本地 `docs/部署手册.md`（按 `.gitignore` 仅保留在本机、不入库）
里遗留的明文数据库口令示例，改为从环境变量注入，并补充备份频率与恢复演练建议。

### 验证（真实库，非 mock）

| 项目 | 实测 |
|---|---|
| 备份 | 552800 字节，耗时 0.21 秒 |
| 恢复 | `--exit-on-error` 成功，耗时 1.85 秒 |
| 逐表行数 | 37 张表 / 13369 行，源库与恢复库无差异 |
| 架构对象 | 索引 82、约束 74、序列 39，两边一致 |
| 关键表内容哈希 | agentbean、agentrunbean、agentstepbean、aicalllogbean、kbchunkbean、kbdocbean、modelsconfigbean、notificationbean、ragevalrunbean 共 9 张表 MD5 一致 |
| 重复执行 | 连续第二次运行仍通过（覆盖演练库已存在、需先删除的路径） |
| 安全护栏 | `-DrillDatabase ai` 被拒绝，退出码 1，未触碰源库 |
| 清理 | 演练库计数 0、容器内 dump 计数 0、临时输出文件已删除 |

### 已知限制

- 演练与生产在同一台机器、同一个 PostgreSQL 实例，未覆盖「整机损坏后从异地
  备份重建」；也没有接入 WAL 归档，因此不具备时间点恢复（PITR）能力；
- 恢复后的库只做了数据与架构比对，没有启动一个指向该库的后端实例做功能冒烟；
- `-DumpOut` 留档的文件包含真实业务数据，仓库已忽略 `.tmp/`，但仍需控制
  文件权限与保留周期。

## 2026-11-07（十四）：恢复库启动冒烟、健康检查修复与序列缺陷

### 背景

上一轮恢复演练只比对了行数、架构对象和内容哈希，没有证明「应用真能跑在
恢复库上」。本轮补上应用启动冒烟，并在过程中发现两个真实缺陷。

### 实现

1. **恢复库启动冒烟（`-BootSmoke`）**：数据比对通过后，用恢复库启动一个
   临时后端实例（独立端口 8599、独立 Redis db 12），轮询 `/ai/actuator/health`
   到 `UP`，再调用免登录的 `POST /ai/api/chatShare/get` 读取预置记录，
   同时校验 `userId`/`lastuser` 已被抹除。结束后停止进程、删除预置数据、
   销毁演练库；失败时保留日志目录便于排查。
2. **修复 `/actuator/health` 空指针**：HNSP 的
   `MultiRequestBodyArgumentResolver.supportsParameter` 会接管 actuator 的
   `@RequestBody(required=false)` 参数，`resolveArgument` 里直接调用
   `request.getContentType().startsWith(...)`；GET 健康检查没有 Content-Type，
   于是抛 NPE，接口返回 HTTP 200 + `resp_code=-1` 而不是 `{"status":"UP"}`。
   新增 `ActuatorContentTypeFilterConfig`，仅对 `/actuator/*` 在缺失
   Content-Type 时补 `application/json`，不改变其他请求的语义；配套 3 个单测。
3. **修复真实数据缺陷：`chatsharebean_iid_seq` 落后于 `max(iid)`**。
   冒烟写入预置分享时报 `duplicate key value violates unique constraint
   "chatsharebean_pkey"`。根因是历史数据迁移显式写了 iid，但没有同步序列：
   源库 `last_value=1`，表内 `max(iid)=7`，任何新建分享都会主键冲突。
   已执行 `setval('chatsharebean_iid_seq', greatest(last_value, max(iid)), true)`，
   校准后 `last_value=7`，下一次取值 8。全库仅此一张表使用 `iid` 主键。
4. **演练脚本加固**：
   - `Invoke-Psql` 增加 `-v ON_ERROR_STOP=1`。psql 默认遇到 SQL 错误仍退出 0，
     之前的写入失败会被静默吞掉，只剩一句「分享不存在」，容易误判成读接口问题；
   - 写入预置数据后立即 `select count(*)` 复核，失败时明确报「预置数据写入失败」；
   - 新增「序列与主键对齐」检查：扫描所有带 `iid` 列且有同名序列的表，
     发现 `last_value < max(iid)` 直接判失败，避免同类问题只在写接口才暴露。
5. **修复 Maven 增量构建的假成功**：`scripts/start.ps1` 原来执行
   `mvn package -DskipTests`，在源码时间戳异常时会出现「Nothing to compile」，
   外层 jar 仍是上一次构建的产物（实测 jar 17:00、源码 17:07，嵌套 class
   哈希与 target 不一致）。改为 `mvn clean package -DskipTests`。
6. **PowerShell 5.1 踩坑记录**（均已写进脚本注释）：
   - `docker exec -e PGPASSWORD $var` 不是「设置环境变量」，`-e` 只接受
     `VAR` 或 `VAR=value` 单个参数；`$var` 会被当成容器名。正确做法是先
     `$env:PGPASSWORD`，再 `-e PGPASSWORD` 让 docker 继承；
   - JDBC URL 里的 `$DrillDatabase?currentSchema` 会被 PowerShell 当成
     「变量名含 ?」，必须写成 `${DrillDatabase}?currentSchema`；
   - actuator 响应没有 charset 时，`Invoke-WebRequest` 的 `Content` 是
     `byte[]`，`ConvertFrom-Json` 解析不出字段；改用 `Invoke-RestMethod`。

### 验证（真实 PostgreSQL，非 mock）

| 项目 | 实测 |
|---|---|
| 备份 / 恢复 | 552800 字节；`--exit-on-error` 恢复成功 |
| 逐表行数 | 37 张表 / 13369 行一致 |
| 架构对象 | 索引 82、约束 74、序列 39，一致 |
| 关键表内容哈希 | 9 张表 MD5 一致 |
| 序列审计 | 通过（修复前 `chatsharebean` 序列=1、最大 iid=7；修复后 7/7） |
| 启动冒烟 | health=UP 30.5 秒（JDK 8、独立端口 8599、Redis db 12） |
| 读链路 | 免登录分享接口读到预置标题，作者字段为 null |
| 清理 | 冒烟进程已停、演练库已删、容器内 dump 已删、临时日志已删 |
| 后端回归 | `scripts/verify.ps1 -SkipE2E`：697 项 / 63 份报告，失败+错误+跳过 0 |
| 前端门禁 | 类型检查、图标、色值棘轮、单测 26/26、组件测试 30/30、流式、Markdown/XSS、生产构建、体积预算、密钥扫描、RAG 口径自检全部通过 |

### 已知限制与风险

- 冒烟只覆盖「启动 + 免登录读一条分享」，没有覆盖写入、鉴权、网关调用等
  全链路；启动耗时 30 秒左右，不适合塞进每次提交的默认门禁；
- 序列审计按 `<表名>_iid_seq` 命名约定匹配，若将来出现自定义序列名会漏检；
- 源库序列修复是一次性数据操作，不在代码里自动执行；从旧备份恢复的环境
  仍需手工校准，建议恢复后先跑演练脚本的序列检查；
- 冒烟实例与主实例共用同一个 PostgreSQL 实例，只隔离了端口与 Redis db；
- `mvn clean package` 每次启动都会全量编译，构建时间比增量构建长。

## 2026-11-07（十五）：数据库关系完整性抽查

在真实 PostgreSQL 上对 AI 业务表做了一次只读抽查，确认「代码承担外键职责」
的假设是否成立：

- 全库没有数据库级外键（HNSP 生成式表的既有设计），关系完整性完全由代码维护；
- 抽查 8 条跨表关系，全部 0 孤儿：Agent 运行→Agent、Agent 步骤→运行、
  Agent 步骤的 runKey 与运行记录一致、工具→Agent、知识库分块→文档、
  文档正文→文档、评估结果→评估运行、评估用例→评估集；
- 业务唯一键无重复：runKey、shareToken、notification eventKey、
  (docIid, chunkIndex) 均为 0 重复；
- 关键字段空值检查：runKey、模块名、分块向量均无空值；
- 发现 8 条历史 `kbdocbean` 记录的 `docKey`/`contentHash` 为空
  （2026-10-24 至 10-29 上传，早于文档流水线的稳定键与去重机制）。
  这些记录状态均为 `ready`，列表接口本来就屏蔽 `docKey`，当前不影响展示、
  检索与删除；新上传都会写入这两个字段。未做回填——回填需要重算正文哈希，
  属于数据迁移，留待后续按需处理。

**结论**：关系完整性与业务唯一性当前没有发现问题；唯一已知缺口是上述历史
文档的内部键为空，记录在案，不作为本轮变更。
