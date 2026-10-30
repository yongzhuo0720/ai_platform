# 智汇平台 NexusAI

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

本地 RAG 是另一条链路：浏览器提取 TXT / Markdown / PDF / Word 文本，后端分块并调用向量模型，把文档元数据写入 `kbdocbean`，把文本分块与向量 JSON 写入 `kbchunkbean`。检索限定当前用户，结合余弦相似度、PostgreSQL 全文候选与 RRF 融合；可选 Rerank 后，将最终顺序的片段注入直连模型请求。它需要同时考虑**数据归属、检索质量与存储一致性**。

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

```powershell
# 私有依赖位于仓库内的 hd_redis/ai-maven（离线可用）
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

前端默认走网关，`frontend/.env.local` 里的 `VITE_DIFY_*` 不再生效；只有在排障或兼容旧行为时才设置 `VITE_AI_GATEWAY=0` 切回浏览器直连（此时凭据会下发浏览器，且没有限流/配额/审计）。

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
| 密钥治理 | 默认网关模式下，Dify / 直连模型密钥仅由服务端使用；网关覆盖客户端传入的认证头 |
| AI 调用治理 | Dify 与 OpenAI 兼容模型共用网关：按用户限流，记录 tokens、耗时、请求 ID 与成本；支持用户与部门 Token 配额；客户端停止生成时立即关闭上游连接 |
| 上游地址 | Dify 与直连模型的目标地址取服务端配置，由管理员维护 |
| XSS | 助手回答中的原始 HTML 做转义，图片 URL 做协议校验 |

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
npm run test            # 单元测试（Vitest），14 个文件 / 196 项
npm run test:component  # 组件测试（真实渲染），23 个文件 / 184 项
npm run test:e2e        # 浏览器冒烟（真实 Chromium + 接口 mock），7 项
npm run check:streaming # 流式超时/取消回归，14 项
npm run check:markdown  # Markdown 与 XSS 消毒回归，10 项
npm run build            # 生产构建
npm run check:bundle     # 首屏 gzip 体积预算（需先 build）
```

本地一键验证使用 `scripts/verify.ps1 -JavaHome '<你的 JDK 8 路径>'`。

### 当前测试结果（2026-10-30 实测）

| 项目 | 结果 |
|---|---|
| 后端单元测试 | **206 项通过，0 失败** |
| 前端 TypeScript 检查 | 通过 |
| 前端图标注册检查 | 通过（56 个在用图标全部可解析） |
| 前端单元测试 | 14 个文件 / **196 项通过** |
| 前端组件测试 | 23 个文件 / **184 项通过**（@vue/test-utils 真实渲染 LoginView / ChatComposer / ChatMessageList / ChatSidebar / KbSearchView / MyUsageView / ShareView / AiGatewayView / UserView / RoleView / UnitView / ModelsConfigView / DifyConfigView / LogView / KnowledgeBaseMngView / ModelsView / ApplicationsView / CapabilitiesView / DatasetView / KnowledgeBaseSquareView / KnowledgeBaseView / PreviewView / NotFoundView） |
| 浏览器冒烟（Playwright） | **7 项通过**（真实 Chromium：登录守卫与登录跳转、聊天页启动无异常、直连模型 SSE 流式回答、直连图片附件带压缩 dataURL、配额 429 展示服务端原因、知识库模块未配置引导、404 兜底；接口层由 `page.route` 拦截） |
| 前端流式回归 | 14 项通过 |
| 前端 Markdown / XSS 回归 | 10 项通过（**2026-10-29 前该脚本一直是失败的，见文档**） |
| 前端生产构建 | 通过（element-plus 按路由分包，入口 gzip 152.5 kB，已无 >500 kB 分包警告） |
| 产物体积预算 | 通过（首屏 JS 202.8 kB / 预算 260 kB，gzip） |

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
   路由守卫、自动导入缺失、Teleport 菜单与真实 `fetch` 流式解析。新增 7 项浏览器
   用例覆盖登录守卫与登录跳转、聊天页启动无未捕获异常、直连模型 SSE 回答渲染、
   附件图片在真实 Canvas 里压缩后随请求体发出、配额 429 时展示服务端原因、
   知识库模块未配置引导与 404 兜底；接口层用
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
   （表单隐藏、建议取源、直连不校验必填），表单隐藏做了变异验证。

2026-10-30 后端改进：并发请求的配额预扣

修复前是「检查额度 → 调用上游 → 完成后 `addTokens`」。多个并发请求可以同时通过
检查，结束后一起把用量加上去：瞬时超出每日配额，且超额部分上游已经计费、账上却
永远看不到。现在请求开始前先原子预扣 `ai.gateway.quota-reserve-tokens`（默认 1500，
用户池和部门共享池各一次 Redis 自增，任一超额即回滚并返回 429，不调用上游）；
请求结束按上游返回的真实 usage 结算（多退少补），失败、异常或客户端中断全额释放。
预扣值设 0 可关闭，退回旧行为；Redis 异常时 fail-open 并打 warn。直连模型在请求体
校验通过后才预扣，非法请求不占额度。预扣是估算值：单次回答远超 1500 tokens 时，
结算后仍可能小幅超出配额——但并发窗口从「整个请求时长」缩短到「一次自增」，
超出量也从「N 个并发请求的总和」收敛到「每个在途请求的实际用量与预扣值之差」。
9 项 Guard 单测覆盖预扣/结算/部门池回滚/Redis 降级，4 项服务单测覆盖 429 提前拒绝、
结算替代重复计费、失败释放与中断释放。

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
- **网关需先配置上游**：Dify 需在数据库 `difyconfigbean`（后台「Dify 配置管理」）或服务端 `DIFY_*` 环境变量配置；直连模型需在「模型配置」里配齐地址与密钥。未配置时对应模块返回 503；前端 `.env.local` 的 `VITE_DIFY_*` 在默认网关模式下不生效。
- **流式治理的边界**：Token 统计依赖上游返回 usage（Dify 聊天在 `message_end`，工作流在 `data.total_tokens`）；上游不返回时只统计调用次数与耗时，配额无法按 Token 精确扣减。
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
