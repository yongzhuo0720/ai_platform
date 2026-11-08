# 智汇平台 NexusAI

> 第一次接触这个项目？先读 [`docs/项目说明-通俗版.md`](docs/项目说明-通俗版.md)——用业务语言讲清楚它是什么、一条请求怎么走、这次升级解决了什么。

面向企业内部的一站式 AI 能力门户：把**智能对话、知识库问答、应用构建、模型与数据集仓库**整合进同一个平台，并按**角色 / 部门**做细粒度的可见性控制。

AI 能力有两条路径：Dify 提供对话、工作流与托管知识库；OpenAI 兼容模型通过后端网关调用，搭配本平台的**文档分块、向量化、混合检索与可选 Rerank 精排**。平台同时管理用户、权限、会话同步与调用治理。

---

## 技术栈

| 层 | 选型 |
|---|---|
| 前端 | Vue 3 · TypeScript · Vite 5 · Element Plus · Pinia · vue-router（hash 模式） |
| 后端 | Spring Boot 2.6.8（Java 8）· Druid · HNSP 快速开发框架（`@HnMapper` 运行时生成 SQL） |
| 存储 | PostgreSQL（库 `ai`，schema `public`）· Redis |
| AI 能力 | Dify（对话 / 工作流 / 知识库）· OpenAI 兼容直连模型 |
| 构建部署 | Maven 多模块 · Nginx |

---

## 架构：AI 调用治理网关

AI 请求默认统一收口到业务后端，浏览器不再持有 Dify API Key。

| 通道 | 前缀 | 路径 | 特征 |
|---|---|---|---|
| A · AI 网关（默认） | `/ai/api/aiGateway/{chat\|knowledgeChat\|file\|models}/...` | 浏览器 → Spring Boot → Dify / OpenAI 兼容模型 | 凭据只存服务端；SSE 逐块透传；按用户限流、用户与部门每日 Token 配额、审计日志 |
| B · 平台接口 | `/ai/api/...` | 浏览器 → Spring Boot → PostgreSQL | `@LoginUserId` 注入登录态 + Holder 内权限校验，业务读写与操作日志 |
| C · 直连（兼容） | `/v1/...` | 浏览器 → Dify | 设置 `VITE_AI_GATEWAY=0` 后启用；凭据下发浏览器，无治理能力 |

网关核心在 `AiGatewayService`：转发请求/响应、过滤逐跳响应头、SSE 边读边写、解析上游 usage 并记录请求 ID、首 token 耗时与成本（首 token 按第一行带正文的 SSE 事件计时，不把 role/usage 帧与心跳算进去）；`AiGatewayGuard` 用 Redis 做每分钟限流与每日 Token 配额（配额适用于 `chat` / `knowledgeChat` / `models`，`0` 表示不限制）；`AiGatewayAdminHolder` 提供用量看板、调用日志与配额管理接口，后台页面为「AI 调用治理」。Dify 对话模块的查询、JSON 与文件上传中的 `user` 统一使用后端认证身份。

**取舍**：换来凭据不出服务端、可计量可治理；代价是多一跳网关延迟与 SSE 代理复杂度，且 Dify 超时与断流需要在网关侧兜底。

### 数据归属：Dify 知识库与本地 RAG

Dify 托管知识库通过 `knowledgebasebean.kbId` 引用上游数据集；这一路的原文、分块与向量保存在 Dify。

```
Dify                              本平台 PostgreSQL
├─ dataset    知识库      ←──────  knowledgebasebean
├─ document   文档原文       kbId   ├─ kbId            指向 dataset.id
└─ segment    分块+向量    =       ├─ ownerUid        所有者
                          dataset  ├─ permissionType  可见性
                          .id      └─ unitsnos/roleiids  部门/角色黑名单
```

本地 RAG 是另一条链路，采用**双通道上传 + 异步流水线**：txt/md/markdown/csv/json/log 由浏览器把原始文件以 `multipart` 交给后端 `KbDocTextParser` 解码（UTF-8 / GBK / BOM），PDF/DOCX 由浏览器用 pdfjs / mammoth 提取正文后以 `content` 提交，两条都走 `POST /ai/api/kbRag/uploadFile`。接口只做解析、校验、正文 SHA-256 去重与建任务，分块和向量化交给 `KbDocRunner` 在专用线程池里执行，状态机为 `queued → chunking → embedding → ready / failed`；文档元数据与 `status/progress` 写 `kbdocbean`，原始正文写 `kbdoccontentbean`，分块与向量 JSON 写 `kbchunkbean`，前端每 2 秒轮询真实进度。检索限定当前用户，结合余弦相似度、PostgreSQL 全文候选与 RRF 融合；可选 Rerank 后，将最终顺序的片段注入直连模型请求。它需要同时考虑**数据归属、检索质量与存储一致性**。

### 全局搜索（Ctrl/Cmd + K）

侧边栏「搜索」入口打开命令面板，一次请求聚合五类资源：Agent、知识库、RAG 评估数据集、模型、数据集。
服务端复用各领域 Holder 的可见性判断（Agent / 评估集按所有者，知识库按三种权限模型），
每类最多 6 条；单个数据源失败只标记 `degraded` 并跳过，不影响其他分类。
路由由服务端拼好并做 URL 编码，目标页读取 `?keyword=` / `?datasetKey=` / `?agentKey=` 自动过滤或选中；
同路由 query 变化不会重挂载组件，视图用 `watch` 响应。设计细节见
`docs/学习文档-阶段1-子阶段7C-全局搜索命令面板.md`。

---

## 权限模型

知识库支持三种可见性（`permissionType`）：

| 值 | 含义 |
|---|---|
| `public` | 所有人可见 |
| `only_me` | 仅所有者 |
| `partial_invisible` | 部分不可见——落在 `unitsnos`（部门）/ `roleiids`（角色）黑名单中的用户不可见 |

权限配置解析失败时按**不可见**处理（fail-closed），避免"配错了反而全放开"。

---

## 快速开始

### 环境要求

- **JDK 8**（Java 21 与项目 Lombok 版本不兼容）
- Node.js 22+（单测使用的 jsdom 30 官方要求 ≥22.22）
- PostgreSQL 9+/Redis（仓库内 `hd_redis/` 提供本地可运行的 PG 与 Redis）

### 后端

> **公开克隆无法独立编译后端。** 后端依赖私有框架 `com.heneng.hnsp` / `blxt.qjava`
> （不在 Maven Central），本地离线仓库 `hd_redis/ai-maven/` 也不入库；CI 因此只跑前端。
> 需要完整构建时，请向维护者获取私服访问或离线仓库。

```powershell
# 假设已把私有离线仓库放在 hd_redis/ai-maven（不入库）
$repo     = (Resolve-Path hd_redis/ai-maven/pro).Path
$settings = (Resolve-Path hd_redis/ai-maven/conf/settings.xml).Path
$env:JAVA_HOME = '<你的 JDK 8 路径>'

Set-Location backend/AIProject
mvn -s $settings "-Dmaven.repo.local=$repo" -o package
```

启动 `app-start` 模块，默认监听 **8507**，上下文路径 **`/ai`**。启动时会执行表结构初始化与基础配置注册（幂等）。

### 前端

```bash
cd frontend
npm install
npm run dev      # 开发服务器，/ai 已代理到 8507
npm run build    # 产物输出到 dist/
npm run typecheck
```

### 配置 AI 能力（必填）

不配置也能登录、浏览业务模块；配置后才能使用对话、知识库问答与文档预览。

网关只从**服务端**读取 Dify 配置，来源优先级为：数据库 `difyconfigbean`（后台「Dify 配置管理」页面维护）> 后端环境变量。三个模块分别是问答聊天、知识库对话/工作流、知识库文件。未配置时网关返回 `503 gateway_not_configured`，页面会提示管理员配置。

也可以不改数据库，用环境变量兜底：

```bash
DIFY_CHAT_URL=https://api.dify.ai/v1
DIFY_CHAT_KEY=app-你的应用密钥
DIFY_KB_CHAT_URL=https://api.dify.ai/v1
DIFY_KB_CHAT_KEY=app-你的知识库应用密钥
DIFY_KB_FILE_URL=https://api.dify.ai/v1
DIFY_KB_FILE_KEY=dataset-你的知识库密钥
```

> `app-` 开头的应用密钥用于对话，`dataset-` 开头的知识库密钥用于数据集接口，两者不能混用。
> 密钥只放在数据库或服务端环境变量中，**不要**提交到仓库。

### 多模型直连（可选）

除 Dify 应用外，还可把任一 OpenAI 兼容服务配置成「直连模型」：后台「模型配置」里填写协议（openai）、
服务地址、API Key 与上游模型名，前端聊天页选择该模型即经 `/ai/api/aiGateway/models/{iid}/**` 调用。
请求体中的 `model` 以服务端配置为准，流式响应自动注入 `stream_options.include_usage` 以统计 Token。

前端默认走网关。Dify 凭据只在服务端持有：浏览器直连模式（`VITE_AI_GATEWAY=0`）的密钥也已改为只从后端 `difyconfigbean` 取，**不再读取任何 `VITE_DIFY_*_KEY`**——Vite 会把 `VITE_*` 原样内联进 `dist`，写在 `.env.local` 里不提交只挡住 git、挡不住构建内联。提交前 `npm run check:secrets`（已进 `scripts/verify.ps1` 与 CI）会在构建产物上兜底扫描。

### OpenAI 兼容端点与平台 API 密钥

想用自己的程序（脚本、IDE 插件、任何 OpenAI SDK）调平台模型，不必模拟浏览器登录：
在「用量中心 → API 密钥」自助签发一枚 `sk-` 密钥，然后直接打 `/ai/v1/*`。
页面上能设名称 / 有效期 / 可用模型白名单，列表只回显前缀（`sk-1a2b3c4d…`）与最后使用时间，
创建成功时那一次明文旁边直接给出可复制的 curl 示例；停用与删除都是即时生效的（下一次调用就 401）。

| 端点 | 说明 |
|---|---|
| `GET /ai/v1/models` | 列出该密钥允许调用的直连模型（`id` 可直接填进对话请求的 `model`） |
| `POST /ai/v1/chat/completions` | 标准对话补全，`stream=true` 时逐块透传 SSE，并透传上游的 usage |
| `POST /ai/api/aiApiToken/{list,create,update,delete}` | 密钥管理，走浏览器登录态（`x-access-token`），不是 Bearer |

与浏览器对话同一套账：命中密钥后以 `userId` 的身份走限流、每日 Token 配额与审计（`module=models`、`endpoint=v1/chat/completions`），
密钥停用或过期立刻表现为 401；`model` 支持三种写法——平台 iid、平台模型名、上游模型名，重复名按 iid 最小者。
可选的 `allowModels`（逗号分隔）给单枚密钥限定模型范围，越界返回 403 `model_not_allowed`。

```bash
BASE=http://localhost:8507          # 后端地址，含 context path /ai

# 1) 浏览器登录态换会话 token（/uaccess/login 需要验证码，自助签发用 logintest）
LT=$(curl -s -X POST "$BASE/ai/uaccess/logintest" -H 'Content-Type: application/json' \
     -d '{"uaccess":"<你的本地账号>","upwd":"<你的本地口令>","keepUser":false}' | jq -r .datas.token)

# 2) 签一枚密钥。请求体形状是 {"data":{...}}：框架按参数名映射请求体，
#    裸传 {"name":"..."} 会解析成 null。明文只在这一次响应里出现，库里只有 SHA-256。
KEY=$(curl -s -X POST "$BASE/ai/api/aiApiToken/create" \
      -H 'Content-Type: application/json' -H "x-access-token: $LT" \
      -d '{"data":{"name":"ci-runner"}}' | jq -r .datas.plaintext)

# 3) OpenAI 协议实测（模型名取 /v1/models 里的 id）
curl -s "$BASE/ai/v1/models" -H "Authorization: Bearer $KEY"
curl -s -X POST "$BASE/ai/v1/chat/completions" -H 'Content-Type: application/json' \
     -H "Authorization: Bearer $KEY" \
     -d '{"model":"mock-chat-model","messages":[{"role":"user","content":"say hi"}]}'

# 流式
curl -N -X POST "$BASE/ai/v1/chat/completions" -H 'Content-Type: application/json' \
     -H "Authorization: Bearer $KEY" \
     -d '{"model":"mock-chat-model","stream":true,"messages":[{"role":"user","content":"say hi"}]}'
```

鉴权失败给的是**真 HTTP 状态码 + OpenAI 错误体**（`{"error":{"message","type","code","request_id"}}`，并回 `X-Request-Id`），
不是平台内部的 HTTP 200 + `resp_code`——OpenAI SDK 靠状态码判断是否重试，套平台的形状会让客户端把 401 当成功。
`/v1/**` 不进 `LoginAuthInterceptor`，由 `ApiTokenAuthInterceptor` 单独把关：缺头 / 格式错 / 校验抛异常一律 fail-closed 拒绝。

> 已知取舍：配额按**用户**计，不按密钥计——一枚用户名下多把密钥共享同一份额度；
> 每密钥配额与用量细分需要新表，尚未实现。

---

## 项目结构

```
backend/AIProject/
├─ app/app-start                  启动入口、全局配置、表结构初始化
├─ app-business/
│  ├─ app-business-permission     用户 / 角色 / 部门 / 标签
│  └─ app-business-service        知识库 / 数据集 / 模型 / 能力 / 应用 / 助手 / Dify 配置 / SSO
└─ app-common                     共享 DTO 与工具类

frontend/src/
├─ api/          接口层（axios 走平台接口，fetch + SSE 走 AI 网关）
├─ stores/       主题、Dify 配置缓存、接口文档状态
├─ views/        页面（views/Admin/* 为后台管理，含「AI 调用治理」）
└─ components/   公共组件
```

> 后端业务代码沿用 HNSP 快速开发框架，每个实体固定四件套：`Controller → Holder → Mapper → Bean`。

---

## 安全与质量

| 项 | 说明 |
|---|---|
| 越权防护 | 平台级目录（能力/数据集/模型/应用/Dify 配置）的写操作限超管；用户归属资源逐条校验所有者 |
| 文件下载 | 路径需落在已登记的数据集根目录内，规范化后前缀比对，越界返回 403 |
| 密钥治理 | 默认网关模式下，Dify / 直连模型密钥仅由服务端使用；网关覆盖客户端传入的认证头；平台 API 密钥只存 SHA-256 哈希，明文仅在创建时返回一次，且 `create` 不写操作日志（否则响应体里的明文会落进 `usercontrollerlog`） |
| 操作日志 | 框架写的 `usercontrollerlog` 由本项目自己的 `POST /ai/api/controllerLog/query` 只读暴露：仅超管可查，日期区间严格解析成 `yyyy-MM-dd`，排序固定 `iid desc` 不接受前端传值，实体不映射 `params` 列（请求参数快照，可能含口令），所以那条链路上取不到请求体 |
| AI 调用治理 | Dify、OpenAI 兼容模型与 `/v1` 外部调用共用网关：按用户限流，记录 tokens、耗时、请求 ID 与成本；支持用户与部门 Token 配额；Redis / 配额表不可用时 **fail-closed**（503 `guard_unavailable`），不放行。客户端取消会被识别并关闭上游连接，但 SSE 读循环是同步阻塞的，上游静默时最长要等 `readTimeoutMs`（默认 300s）才进入收尾，已在 `docs/第二轮深度升级-审查报告-2026-11-05.md` §5.2 列为待修项 |
| 统计完整性 | 周报不再在 5 万行处静默截断：读到不满一页才结束；超过 50 万行或分页中途读失败时显式失败且**不落库**，避免保存一份看似正常的残缺统计 |
| 成本缓存 | 模型单价首次加载失败时不再把空表缓存 5 分钟（否则故障窗口内调用全被记 0 成本）；本次返回空、下次调用立即重试，已有旧缓存时仍沿用旧值（设计细节见 `docs/学习文档-阶段1-子阶段7E-周报全量聚合与价格缓存自愈.md`） |
| 上传限制 | **两层限制，勿混用**：AI 网关透传链路为单文件 50MB / 整请求 120MB，超限返回 413 `payload_too_large` 并记审计，不再伪装成 502；本地 RAG 文档流水线另有更严的业务上限——单文件 20MB、解析后 20 万字符、单篇最多 500 块，超限在服务端解析阶段直接拒绝（不静默截断） |
| 上游地址 | Dify 与直连模型的目标地址取服务端配置，由管理员维护 |
| XSS | 助手回答中的原始 HTML 做转义，图片 URL 做协议校验 |
| 前端错误兜底 | Vue 渲染异常与未处理 rejection 统一进顶部横幅，带错误编号、支持关闭/重载；chunk 404 由 `preloadRecovery` 单独重载，axios 错误仍走原 toast，互不重复提示 |

### 运行测试

完整的、可重复执行的命令清单见 **`docs/测试说明.md`**。下面是最常用的两条。

**后端**（必须用 JDK 8，且必须指定项目自带的离线 settings）：

```powershell
$repo     = (Resolve-Path hd_redis/ai-maven/pro).Path
$settings = (Resolve-Path hd_redis/ai-maven/conf/settings.xml).Path
$env:JAVA_HOME = '<你的 JDK 8 路径>'

Set-Location backend/AIProject
mvn -s $settings "-Dmaven.repo.local=$repo" test
```

> **为什么一定要带 `-s`**：默认的 `~/.m2/settings.xml` 里是 `<mirrorOf>*</mirrorOf>`，
> 会把所有仓库请求（含私有框架仓库）转到阿里云，导致 `com.heneng.hnsp` 解析不到。
> 项目自带的 settings 用的是 `<mirrorOf>central</mirrorOf>`，私有仓库不会被镜像拦截。

**前端**：

```bash
cd frontend
npm run typecheck      # TypeScript 类型检查
npm run check:icons    # 图标注册检查（模板图标必须 import 或全局注册）
npm run check:styles   # 硬编码色值棘轮检查（只减不增，基线在 scripts/check-styles.baseline.json）
npm run test            # 单元测试（Vitest），26 个文件 / 303 项
npm run test:component  # 组件测试（真实渲染），30 个文件 / 300 项
npm run test:e2e        # 浏览器冒烟（真实 Chromium + 接口 mock），23 项
npm run check:streaming # 流式超时/取消回归，14 项
npm run check:markdown  # Markdown 与 XSS 消毒回归，10 项
npm run build            # 生产构建
npm run check:bundle     # 首屏 gzip 体积预算（需先 build）
```

本地一键验证使用 `scripts/verify.ps1 -JavaHome '<你的 JDK 8 路径>'`（13 步，含后端；
跑完会打印 `后端单测汇总：N 项 / M 份报告`，一份报告都没生成就直接判失败——这是为了
堵住「测试被静默跳过但 BUILD SUCCESS」这类最危险的绿色。前端同理：`npm run test` /
`npm run test:component` 先扫磁盘上的 spec 文件，再与 Vitest 实际执行的文件对账，
少了、多了或一项没跑都直接失败，不再靠人肉发现漏跑。日志统一 UTF-8，可直接重定向存档。

### 当前测试结果（2026-11-07 实测）

| 项目 | 结果 |
|---|---|
| 后端单元测试 | **713 项，0 失败 / 0 错误 / 0 跳过**（`mvn -o test`，JDK 8 Dragonwell 8；app-common 7、permission 71、service 605、app-start 30） |
| 前端 TypeScript 检查 | 通过 |
| 前端图标注册检查 | 通过（59 个在用图标全部可解析） |
| 硬编码色值棘轮检查 | 通过（45 个文件 / 1777 处，等于基线；新增即失败，只允许减少） |
| 前端逻辑单元测试 | 26 个文件 / **303 项通过**（含文件对账） |
| 前端组件测试 | 30 个文件 / **300 项通过**（@vue/test-utils 真实渲染，含文件对账） |
| 浏览器冒烟（Playwright） | **23 项通过**（真实 Chromium + `page.route` 接口 mock，含 Agent 事件链路、断流续播、模型可观测后台、文档流水线、目录页三态、登录守卫、响应式外壳、SSE、配额、RAG 评估详情、404 兜底） |
| 前端流式回归 | 14 项通过 |
| 前端 Markdown / XSS 回归 | 10 项通过 |
| 前端生产构建 | 通过（element-plus 按路由分包） |
| 产物体积预算 | 通过（首屏 JS ≤260 kB / CSS ≤80 kB，gzip） |
| 产物密钥扫描 | 通过（构建后扫 dist，防止 `VITE_*` 被内联） |
| RAG 评估集自检 | 通过（黄金集与语料一致性 + JS 指标与 Java `RagEvaluator` 口径一致，离线） |

> **环境说明（2026-11-06 再次校准）**：`scripts/verify.ps1` 在带批量删除/写入防护的
> 受限执行环境下，可能出现四类失败，均**不是用例回归**：
> ① Vitest 的 `Proxy.fetch` 写临时文件被 `node-brokered-fs-shim` 拦截（`EPERM`），
>    某个逻辑单测文件会执行失败。`scripts/run-vitest.mjs` 的文件数对账会把它变成
>    **显式的红**（打印缺失文件并 `exit=1`），不再是此前的「静默少跑」——
>    按提示补跑该文件即可；
> ② Vite 清空 `dist/assets` 触发 `SAFE_DELETE_BULK_CONFIRM_REQUIRED`——
>    先把旧 `dist` **移动**到临时目录（不要删除）再 `npm run build` 即 exit=0；
> ③ Playwright 清理 `test-results` 触发同样的批量删除告警——先移走旧 `test-results`；
> ④ 组件测试单独跑不受影响（exit=0）。
>
> 因此看到脚本报红时，请按上面四项逐条复现再下结论，**不要直接改生产代码**。
> 上表数字来自逐项复现 + 后端 Maven 实际输出；正常 CI 环境无此限制，
> 后端仍以 `verify.ps1`（含 Surefire 报告闸门）为唯一门禁。

2026-10-30 前端改进：

1. **逻辑层单元测试**（Vitest + jsdom）：覆盖 ChatView 拆分后的格式化 / 存储 /
   选择器 / 表单 / 助手权限 / 分页竞态 / 发送逻辑，已接入 CI 与 `scripts/verify.ps1`。
2. **图标按需注册**：`main.ts` 原来全量注册 293 个 Element Plus 图标，实际只用到
   56 个。改为组件内 import + 23 个全局图标后，element-plus 分块从 1,052.14 kB
   降到 922.89 kB（gzip 331.25 → 296.83 kB），并新增 `check:icons` 防止模板漏注册
   导致图标静默留白。
3. **引用与上下文同源**：直连 + 知识库模式此前浏览器和网关各检索一次
   （默认 topK 不同，展示的引用不一定等于模型收到的片段）。现在网关在 SSE 流开头
   下发 `event: kb_sources`，附带本轮真正注入的片段，前端删掉重复检索，
   引用与回答共享同一次检索结果。
4. **模型配置缓存主动失效**：Rerank、单价表、向量模型配置与直连模型路由各有
   5 分钟 TTL 缓存（直连路由每次对话都要按 iid 取配置，原来每请求查一次库）。
   后台停用 rerank、换密钥、改价、更换向量模型或删除模型后，靠
   `ModelConfigChangedEvent` 立即失效；并用真实 Spring 上下文验证 `@EventListener`
   接线，防止注解写错时静默退化成旧行为。向量配置的 `null`（未配置）同样缓存，
   读库失败则不缓存、可立即重试。
5. **知识库检索降级可诊断**：空知识库不再先调用一次向量化接口；切换向量模型后，
   旧分块因向量维度不一致会被跳过（此前是静默跳过），现在检索结果返回 `skipped`
   与 `warning`，检索页直接提示重新上传，并分开统计「维度不一致 / 向量数据损坏」。
6. **组件级测试**：引入 `@vue/test-utils` 与独立的 `vitest.component.config.ts`，
   直接渲染登录页、聊天页三个核心组件、知识检索页、个人用量页、分享页、模型仓库页、场景应用页、能力广场页、数据集市场页、知识库广场页、知识库中心页、下载预览页、404 兜底页、AI 调用治理后台、用户/角色/部门管理、模型配置、Dify 配置、调用日志与知识库管理后台（共 184 项）：
   输入区的 Enter / 输入法组词、发送与停止、模型菜单、知识库切换、附件移除；消息区的
   Markdown 渲染、思考块折叠、引用来源、历史分页、点赞反馈与重新生成；侧栏的 Tab 切换、
   置顶/今天分组、置顶/重命名/删除、分享空会话拦截、会话分页、助手菜单与搜索、移动端
   抽屉；治理后台的概览卡片与图表比例、日志筛选/导出、配额增改删与部门共享池校验、
   周报生成；用户管理的角色门控、搜索、新建/编辑表单校验（含角色下拉隐藏超管）、
   重置密码与删除确认；角色管理的菜单权限树勾选与序列化、超管行保护、损坏
   `viewjson` 兜底与删除确认；部门管理的树形表格、上级部门选择、编辑回填与
   删除失败提示；知识库管理的全部/本系统视图切换、可见性过滤、名称必填校验、
   部门与角色权限序列化、编辑补建权限记录与删除确认；登录页的 Cookie 回填、
   用户/超管登录态写入、非超管拒绝与滑动切换防抖；知识检索页的文档列表、
   文件选择（含 20MB 上限）、上传校验、删除确认与检索结果/降级提示；个人用量页的
   配额水位告警、图表比例、明细状态与区间切换竞态；模型仓库页的搜索/标签筛选/
   六种排序、超管新建与编辑、删除确认与失败提示、下载跳转预览面板；场景应用页的
   搜索/标签筛选/四种排序、新建编辑校验、管理权限的部门树与角色勾选（超管角色
   不出现）、损坏权限 JSON 兜底与 `javascript:` 地址拦截；能力广场页的图标上传
   （base64 / 2MB 限制 / 移除释放）、SSO 会话与授权地址跳转、接口失败提示与
   非 SSO 地址消毒；数据集市场的搜索/排序/标签筛选、新建编辑校验、删除提示与
   下载预览；免登录分享页的失效提示与用户消息转义 / 助手 Markdown 消毒，
   已接入 CI 与 `scripts/verify.ps1`。写测试时顺带修掉三类真实缺陷：
   登录页、模型仓库、场景应用、能力广场、数据集市场与预览面板依赖 Vite
   自动导入的 `ElMessage` / `ElLoading` / Vue API（脱离构建环境即
   ReferenceError）、目录页删除接口返回非 0 时静默无提示、个人用量页
   快速切换区间时旧响应覆盖新数据。
7. **首屏只加载当前路由需要的组件**：element-plus 原先被 `manualChunks` 固定成
   单一 vendor 包，管理台才用的 Table / DatePicker / Tree 也随首屏预加载。
   移除该固定分组后由 Rollup 按路由动态 import 切分，首屏 JS gzip 417.0 → 202.8 kB
   （-51%），构建不再有 >500 kB 分包警告；体积预算同步从 470 kB 收紧到 260 kB，
   防止回退。
8. **浏览器冒烟**（Playwright + 真实 Chromium）：组件测试跑在 jsdom 里，看不到
   路由守卫、自动导入缺失、Teleport 菜单与真实 `fetch` 流式解析。新增 8 项浏览器
   用例覆盖登录守卫与登录跳转、聊天页启动无未捕获异常、直连模型 SSE 回答渲染、
   附件图片在真实 Canvas 里压缩后随请求体发出、直连模式隐藏 Dify 应用参数、
   配额 429 时展示服务端原因、知识库模块未配置引导与 404 兜底；接口层用
   `page.route` 拦截，不依赖本机后端，已接入 CI（Chromium）。本地默认复用已安装
   的 Edge，无需额外下载浏览器。
9. **HTTP 错误提示可读**：网关错误体是 `{code,message,status,requestId}`，
   此前直接拼进异常，用户看到「请求失败 (429): {"code":...}」；而且
   `parseChatError` 的 token 关键词会把配额文案「已用 1500 / 1500 tokens」
   误判成「API 密钥失效」。现在 `readErrorMessage` 只取服务端 message，
   `parseChatError` 优先识别 `quota_exceeded` / `额度已用完`（不可重试，原样展示）
   与 `rate_limited`（可重试），401 认证分支保持不变；直连模型在网络不可达时
   不再把英文 `Failed to fetch` 当回答显示，改为中文可操作提示。新增 12 项单测，
   并在浏览器冒烟里断言配额提示原文出现、错误码不外泄。
10. **附件拖拽与点选统一**：拖拽此前固定调用 Dify 上传接口，直连模式下拖入的图片
   虽然提示「上传成功」，却不带本地 `dataUrl`，发送时被静默丢掉。现在拖拽与点选
   共用同一套处理：直连模式压缩成 `dataURL` 并限制 2 张，Dify 模式走上传接口并按
   应用配置限流。新增 10 项 `useChatFiles` 单测，断言直连路径不再调用上传接口；
   浏览器冒烟里再走一遍真实 FileReader / Canvas 压缩，确认 `data:image/jpeg` 的
   dataURL 确实进了请求体的多模态数组。
11. **直连会话删除不再「复活」**：删除原本是「先删本地、再删云端」，云端删除失败时
   用户会看到「删除会话失败」，但本地其实已经删了；更糟的是下次登录合并云端列表会
   把这条会话重新拉回本地。现在删除先写本地墓碑（保留最近 200 条 id），云端删除
   失败只记 warn 并保留墓碑；登录合并时跳过墓碑命中的会话并重试云端删除，成功后
   清理墓碑。新增 16 项 `useChatConversations` 单测覆盖置顶、重命名、编辑消息、
   新建对话、删除与云端合并，其中删除失败容忍与墓碑跳过两处做了变异验证。
12. **重新生成不再丢图片**：直连模式的用户消息此前不记录附件，点「重新生成」后
   第二次请求只带文字，用户会得到与第一次明显不同的回答却不知原因。现在发送时
   在消息上记录本轮附件，重新生成时筛出带 `dataUrl` 的图片重新放进多模态数组
   （非图片附件不进入），本地会话仍只存文本。新增 2 项单测并做变异验证。
13. **会话本地状态一致**：删除 Dify 会话后清理本地置顶记录（此前 id 永久残留，
   同名新会话可能继承置顶）；直连模式编辑消息截断本地会话后也触发云端同步，
   其它设备不会再继续显示已被丢弃的轮次。两处均有断言，置顶清理做了变异验证。
14. **直连模式不再被 Dify 应用参数挡住**：聊天页此前对所有模型都渲染 Dify 应用的
   `user_input_form`，直连模式下这些变量根本不会随请求发送，必填项却仍会拦截发送；
   空状态的应用建议也优先于通用开场问题。现在直连模式整组隐藏应用参数表单、
   不参与必填校验，建议问题按模式取源（直连固定用通用开场）。新增 3 项单测
   （表单隐藏、建议取源、直连不校验必填）与 1 项浏览器冒烟（Dify 通道下表单可见，
   切到直连后表单消失、通用建议出现、未填必填项也能发出请求），表单隐藏做了变异验证。
15. **建议问题点击即发送**：空状态的「建议问题」此前会被拼成「请帮我{问题}」只填入
   输入框，和文案本身叠加成「请帮我帮我写一封请假邮件」；而回答后的「推荐问题」
   是原文直接发送。现在两处统一：点击建议问题原文填入并立即发送，同时删掉多余的
   `handleQuickAction`。单测断言原文直发（变异验证：加回前缀即失败），浏览器冒烟
   点击「用一句话介绍你自己」后断言用户消息与请求体都是原文、且不含「请帮我」。
16. **工作流文件变量不再渲染成文本框**：Dify 工作流的 `file-upload` 输入变量此前
   落到通用文本框分支，用户看到一个莫名的输入框；如果该变量必填，还会因附件不会
   写入 `userInput` 而永远无法发送。现在这类变量不渲染输入框、不按文本校验，改为
   「必填文件变量 + 未附件」时提示「请先上传：xxx」；已附件时文件照旧注入所有检测
   到的文件变量。新增 4 项单测（表单排除、直连不校验、未附件拦截、已附件放行并
   断言注入内容），两处变异验证；浏览器冒烟的应用参数 mock 同时加入必填文件变量，
   断言它不出现在表单里。
17. **提示词兜底不再选中文件变量**：应用没声明 prompt 变量时，参数加载会把「第一个
   非下拉变量」当作提示词变量，若工作流的第一个变量恰是 `file-upload`，助手提示词
   会被写进文件输入，发消息时 `input_file` 变成字符串而不是文件。现在兜底只认文本
   类变量。新增 `useChatModels` 单测（文件变量不当提示词、显式 prompt 优先、加载
   失败降级），提示词选中做了变异验证。

2026-11-02 前端改进：目录页三态与色值棘轮

18. **目录页三态统一**（`StateBlock`）：模型仓库、数据集市场、场景应用、知识库广场
    四个列表页此前只有「数据」这一种状态——请求进行中直接渲染空数组，看起来和
    「真的没有数据」一模一样；接口返回非 0 或抛异常时更糟，列表静默留在原地，
    用户以为后端就是这个结果。知识库广场还把 `loading` 声明了却没接到模板上，
    空态文案写死「没有更多数据了」，搜索无结果与真的没数据共用一句提示。
    现在四处共用一个组件：骨架屏（`aria-busy` + `aria-live`，行数/卡片数可配）
    → 错误态（把服务端 `resp_msg` 留在页面上并给「重试」按钮；拦截器已经弹过
    toast，不再重复弹窗）→ 空态（区分「暂无 X」与「没有符合当前搜索与筛选条件的 X」）
    → 正常插槽。组件本身只用 `var(--xxx)` token，不引入任何字面色。
    新增 `StateBlock` 组件测试 6 项 + 四页各 4 项断言（骨架屏、非 0 可重试、
    异常清空、两种空态文案），浏览器冒烟补 5 项（真实 Chromium 里按住请求看骨架屏、
    非 0 后点重试恢复、搜索无结果文案、加载异常文案、数据集页 abort 后重试成功），
    每条都做过变异验证。
19. **硬编码色值棘轮门禁**（`npm run check:styles`）：token 体系（`tokens.css`
    深色 + `data-theme="light"`）早就建好了，但组件里仍在抄旧文件的字面色，
    新写的越多、token 越收不拢。一次性换 2000 多处色值的风险远大于收益，
    所以改成棘轮：按文件记基线，**任何文件变差即失败，新增文件带色值也失败**，
    改干净了跑 `--write` 重算基线。当前 45 个文件 / 1777 处（`tokens.css` 是色值
    定义处，豁免）。顺带纠正了审查文档里「488 处」这个无法复现的估计。
    已接入 CI 与 `scripts/verify.ps1`。

注：骨架屏只验证到「请求未回来时页面确实是骨架而非空态」——组件测试与浏览器冒烟
的接口层都是 mock，真实后端的列表返回太快，人工点不出这一帧；加载/空态/错误重试
的文案与卡片数量在真实超管会话里逐页确认过。

2026-10-30 后端改进：并发请求的配额预扣

修复前是「检查额度 → 调用上游 → 完成后 `addTokens`」。多个并发请求可以同时通过
检查，结束后一起把用量加上去：瞬时超出每日配额，且超额部分上游已经计费、账上却
永远看不到。现在请求开始前先原子预扣 `ai.gateway.quota-reserve-tokens`（默认 1500，
用户池和部门共享池各一次 Redis 自增，任一超额即回滚并返回 429，不调用上游）；
请求结束按上游返回的真实 usage 结算（多退少补），失败、异常或客户端中断全额释放。
预扣值设 0 可关闭，退回旧行为；**Redis / 配额表读不到时 fail-closed**（返回 503 `guard_unavailable`，
不放行）——这是 2026-11-01 起的行为，旧版文档写的 fail-open 已失效。直连模型在请求体
校验通过后才预扣，非法请求不占额度。预扣是估算值：单次回答远超 1500 tokens 时，
结算后仍可能小幅超出配额——但并发窗口从「整个请求时长」缩短到「一次自增」，
超出量也从「N 个并发请求的总和」收敛到「每个在途请求的实际用量与预扣值之差」。
9 项 Guard 单测覆盖预扣/结算/部门池回滚/Redis 降级，4 项服务单测覆盖 429 提前拒绝、
结算替代重复计费、失败释放与中断释放。

2026-10-30 后端改进：RAG 一致性与云端会话并发

1. **文档入库补偿**：分块逐条写入，任一块失败即按归属删除已写分块与文档并
   返回失败，避免「chunkCount 正常、实际检索为空」的空壳文档。
2. **向量模型标识**：分块记录生成它的模型名与维度；检索时跳过其他模型与维度
   不符的分块并分类计数，页面提示重新上传；历史分块无模型标识时仍按维度兼容。
3. **分块查询索引与扫描上限**：启动时补 `(lastuser, kbName)` 与 `docIid` 索引；
   单次检索最多读取 5000 块，超限只取前 5000 块并提示结果可能不完整。
4. **云端会话并发首次保存**：`(userId, convId)` 唯一索引兜住并发插入，后到者
   重读赢家行并按版本号条件更新，比云端旧的快照仍被拒绝。

后端测试从 206 增至 211（+5），全部通过。

2026-10-31 真实数据库联调修复

用临时 PostgreSQL 12.5 + 真实后端进程做接口级验证时，发现三处「单测覆盖不到、
但一上真库就坏」的问题：

1. **三张表的 Bean 缺少 `@SqlBean`**：`ChatConversation` / `ChatShare` /
   `AiWeeklyReport` 没像其它 15 个 Bean 一样声明
   `@SqlBean(keyId = "iid", ignores = {"key1","key2","key3"})`。框架会把
   `DbEntityBase` 自带的 `key1~key3` 拼进每条 SQL，真库没有这三列——
   云端会话保存/列表、分享创建、周报生成全部失效；建表时主键名还会解析成
   `null`。补上注解后建表与增删改查均正常。
2. **长文本列被建成 `varchar(255)`**：框架按 `String` 自动建表时默认变长 255。
   会话快照、RAG 分块正文与向量 JSON 都远超这个长度，写入直接报
   `value too long`。实体改为 `@SqlColumn(method = "text")`，并在启动时对
   已存在的表补 `ALTER COLUMN ... TYPE text`（可重复执行）。
3. **空请求体导致 500**：`/chatConversation/save|delete` 在请求体未绑定
   （没有按 `{ "data": { ... } }` 包裹）时对 `null` 解引用抛 NPE。
   现在返回明确的参数错误提示。

联调同时验证了：8 个并发首存只落 1 行（唯一索引 + 冲突补偿生效）、
旧版本快照被拒、1247 字会话快照与 3 个 360~440 字分块完整落库、
分块写入失败时文档与已写分块回滚干净、换了向量模型的分块被跳过并提示。
后端 164 项服务单测保持全绿。


2026-10-29 补充的测试类：

| 新增测试类 | 数量 | 覆盖内容 |
|---|---|---|
| `UnitBeanAuthTest` | 7 | 部门写接口的管理员校验（RBAC 支柱，越权可撬动资源可见性） |
| `LableBeanAuthTest` | 7 | 标签组写接口的管理员校验 |
| `LableChildBeanAuthTest` | 6 | 标签子项写接口的管理员校验 |
| `MyRoleQueryScopeTest` | 7 | 角色查询按调用者收敛（不能越权读他人角色权限组） |

> 首次运行需联网下载 `surefire-junit-platform`（JUnit 5 的运行器），之后即可加 `-o` 离线执行。

---

## 已知限制

- **AI 能力依赖外部服务**：Dify 与直连模型各自依赖对应上游；本地 RAG 的向量化仍需可用的向量模型接口。
- **网关需先配置上游**：Dify 需在数据库 `difyconfigbean`（后台「Dify 配置管理」）或服务端 `DIFY_*` 环境变量配置；直连模型需在「模型配置」里配齐地址与密钥。未配置时对应模块返回 503。前端已不读取任何 `VITE_DIFY_*_KEY`，密钥不存在于浏览器侧。
- **流式治理的边界**：Token 统计依赖上游返回 usage（Dify 聊天在 `message_end`，工作流在 `data.total_tokens`）；上游不返回时只统计调用次数与耗时，配额无法按 Token 精确扣减。
- **Agent 主链路已全异步，只剩只读续播占线程**：`/streamRun`（浏览器实时流）交给 `aiGatewayExecutor` 并通过 `AsyncResponseGate` 保证容器回收响应后不再写入；`/run`（外部脚本的同步接口）改为返回 `WebAsyncTask`，复用同一个有界线程池，HTTP 语义仍是「一次请求 = 一个结果」。两处的 Tomcat 请求线程都在提交后立即归还，客户端断开能提前终止运行（不再继续消耗额度）。仍同步的是 `/streamResume`（只读续播，单次最长 30 秒），高并发下受容器线程数约束。分发器不可用或过滤器链不支持异步时自动退回同步。僵死 `running` 记录由每 5 分钟一次的回收任务兜底（30 分钟阈值 + 状态 CAS）。
- **验证边界**：自动测试不等于真实上游与数据库验收；网关与模型协议测试使用本机临时 HTTP 服务，数据库写入使用 Mapper mock，未连接真实 Dify / 向量服务 / 生产数据库。其余待改进项见本次检查记录。
- **依赖版本需锁定**：私有框架 `hnsp-public-common-croe` 把 `mybatis` 声明为 `<version>LATEST</version>`。2026-10-29 实测当天 16:55 构建正常、16:57 拉到 `mybatis 3.6.0`（Java 11 编译）后 JDK 8 直接编译失败。已在根 `pom.xml` 用 `dependencyManagement` 锁定 `3.5.19`（离线仓库中最高的 Java 8 兼容版本）。**不要移除这个锁定。**
- **SSO** 依赖外部 OpenID 服务（默认关闭），本地请使用账号密码登录。

---

## 文档

- `docs/改进检查与学习计划-2026-10-27.md` — 本次修复、验证证据、后续改进优先级与项目学习顺序

- `docs/启动检查清单.md` — **先把项目跑起来**：环境核对、前后端启动、逐页验收、故障排查
- `docs/学习地图.md` — 分阶段学习路线与进度记录
- `docs/面试速查.md` — 项目介绍稿、高频追问答法、简历写法
- `docs/部署手册.md` — 服务器部署说明
