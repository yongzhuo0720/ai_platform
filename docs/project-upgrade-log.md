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
