# AI 聚合平台

面向企业内部的一站式 AI 能力门户：把**智能对话、知识库问答、应用构建、模型与数据集仓库**整合进同一个平台，并按**角色 / 部门**做细粒度的可见性控制。

AI 的向量化、检索与编排由 Dify 承担，本平台负责**用户体系、权限模型、多模块聚合与前端交互体验**。

---

## 技术栈

| 层 | 选型 |
|---|---|
| 前端 | Vue 3 · TypeScript · Vite 5 · Element Plus · Pinia · vue-router（hash 模式） |
| 后端 | Spring Boot 2.6.8（Java 8）· MyBatis-Plus · Druid · Sa-Token |
| 存储 | PostgreSQL（库 `ai`，schema `public`）· Redis |
| AI 能力 | Dify（对话 / 工作流 / 知识库）· QwenPaw 智能体服务 |
| 构建部署 | Maven 多模块 · Nginx |

---

## 架构：AI 调用治理网关

AI 请求默认统一收口到业务后端，浏览器不再持有 Dify API Key 或智能体 Token。

| 通道 | 前缀 | 路径 | 特征 |
|---|---|---|---|
| A · AI 网关（默认） | `/ai/api/aiGateway/{chat\|knowledgeChat\|file\|agent}/...` | 浏览器 → Spring Boot → Dify / 智能体服务 | 凭据只存服务端；SSE 逐块透传；按用户限流、每日 Token 配额（仅 Dify）、审计日志 |
| B · 平台接口 | `/ai/api/...` | 浏览器 → Spring Boot → PostgreSQL | Sa-Token 鉴权，业务读写与操作日志 |
| C · 直连（兼容） | `/v1/...`、`/qwen/...` | 浏览器 → Dify / 智能体服务 | 设置 `VITE_AI_GATEWAY=0` 后启用；凭据下发浏览器，无治理能力 |

网关核心在 `AiGatewayService`：转发请求/响应、过滤逐跳响应头、SSE 边读边写（不破坏逐字输出）、解析上游 usage 并落审计；`AiGatewayGuard` 用 Redis 做每分钟限流与每日 Token 配额（配额只对 `chat` / `knowledgeChat` 生效，`0` 表示不限制）；`AiGatewayAdminHolder` 提供用量看板、调用日志与配额管理接口，后台页面为「AI 调用治理」。

智能体通道 `/ai/api/aiGateway/agent/{default|助手iid}/**`：内置兜底助手的 Token 由后端 `AGENT_*` 环境变量提供；自定义助手从 `assistantbean` 读取 Token 与 Agent ID，并按其可见性校验调用者。上游地址始终取服务端配置，忽略助手记录里的 `apiUrl`，避免 SSRF。浏览器伪造的 `Authorization` / `X-Agent-Id` 会被网关覆盖。

**取舍**：换来凭据不出服务端、可计量可治理；代价是多一跳网关延迟与 SSE 代理复杂度，且 Dify / 智能体超时与断流需要在网关侧兜底。

### 数据归属：kbId 弱引用

文档原文、分块与向量**全部存放在 Dify**，本平台数据库里一个字节的文档内容都没有。

```
Dify                              本平台 PostgreSQL
├─ dataset    知识库      ←──────  knowledgebasebean
├─ document   文档原文       kbId   ├─ kbId            指向 dataset.id
└─ segment    分块+向量    =       ├─ ownerUid        所有者
                          dataset  ├─ permissionType  可见性
                          .id      └─ unitsnos/roleiids  部门/角色黑名单
```

本平台只回答一个问题：**谁能看**。

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
- Node.js 18+
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

### 配置智能体助手（可选）

智能体助手默认也经网关转发。内置兜底助手在服务端配置：

```bash
AGENT_BASE_URL=http://智能体服务地址:端口
AGENT_AUTH_TOKEN=智能体服务的 Bearer Token
AGENT_ID=内置助手的 Agent ID
```

自定义助手在「智能体助手 → 添加/编辑助手」里填写 Token 与 Agent ID，保存在 `assistantbean`。未配置时内置助手返回 `503 gateway_not_configured`；助手不存在或无权使用返回 `404 assistant_unavailable`。

前端默认走网关，`frontend/.env.local` 里的 `VITE_DIFY_*` / `VITE_AGENT_*` 不再生效；只有在排障或兼容旧行为时才设置 `VITE_AI_GATEWAY=0` 切回浏览器直连（此时凭据会下发浏览器，且没有限流/配额/审计）。

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
| 密钥治理 | Dify Key 与智能体 Token 只存服务端（数据库或环境变量），浏览器不持有；网关覆盖客户端传入的认证头；源码中历史遗留的硬编码密钥已全部移除 |
| AI 调用治理 | Dify 与智能体共用网关：按用户每分钟限流、完整审计（模块 / 状态 / tokens / 耗时 / 会话 ID / 客户端 IP）；每日 Token 配额仅对 Dify 模块生效；后台可视化与配额管理 |
| SSRF 防护 | 智能体上游地址只取服务端配置，忽略 `assistantbean.apiUrl`，避免把任意 URL 写进请求 |
| XSS | 助手回答中的原始 HTML 做转义，图片 URL 做协议校验 |

### 运行测试

```powershell
$repo     = (Resolve-Path hd_redis/ai-maven/pro).Path
$settings = (Resolve-Path hd_redis/ai-maven/conf/settings.xml).Path
$env:JAVA_HOME = '<你的 JDK 8 路径>'

Set-Location backend/AIProject
mvn -s $settings "-Dmaven.repo.local=$repo" test
```

现有用例共 **30 个，全部通过**：

| 测试类 | 数量 | 覆盖内容 |
|---|---|---|
| `MyUserBaseDbHolderTest` | 5 | 超管判定边界：超管 / 普通角色 / rid 为空 / 账号不存在 / 未登录 |
| `KnowledgeBaseBeanHolderTest` | 11 | 知识库可见性：public / only_me / 部门与角色黑名单、**权限配置解析失败的 fail-closed**、账号缺失与 rid 为空不 NPE、超管放行 |
| `SsoStateSigningTest` | 6 | SSO state 签名：正常往返、伪造签名、篡改 uid、错误密钥、畸形 state、签名稳定性 |
| `AiGatewayUsageParserTest` | 8 | 网关 usage 解析：`message_end`、工作流 `data.total_tokens`、input/output 别名、SSE error、阻断错误、畸形行，以及智能体的 `response.status=failed` 与 `session_id` / `message.id` 提取 |

> 首次运行需联网下载 `surefire-junit-platform`（JUnit 5 的运行器），之后即可加 `-o` 离线执行。

---

## 已知限制

- **AI 能力依赖外部服务**：Dify 与智能体服务不可达时，对话、知识库检索、文档预览、智能体助手等功能不可用（业务模块不受影响）。
- **网关需先配置上游**：Dify 需在数据库 `difyconfigbean`（后台「Dify 配置管理」）或服务端 `DIFY_*` 环境变量配置；智能体需 `AGENT_*` 环境变量或助手记录里的 Token / Agent ID。未配置时对应模块返回 503；前端 `.env.local` 的 `VITE_DIFY_*` / `VITE_AGENT_*` 在默认网关模式下不生效。
- **流式治理的边界**：Token 统计依赖上游返回 usage（Dify 聊天在 `message_end`，工作流在 `data.total_tokens`）；上游不返回时只统计调用次数与耗时，配额无法按 Token 精确扣减。智能体通道目前只计量调用次数与耗时，不参与 Token 配额。
- **助手凭据仍是明文**：`assistantbean` 中的 Token 以明文保存，后续应做加密存储或改为统一托管。
- **测试覆盖有限**：已补上核心鉴权、权限模型、SSO state 与网关 usage 解析的单元测试（30 个用例），其余模块尚无覆盖。离线跑测试前需先联网一次下载 `surefire-junit-platform`。
- **SSO** 依赖外部 OpenID 服务（默认关闭），本地请使用账号密码登录。

---

## 文档

- `docs/启动检查清单.md` — **先把项目跑起来**：环境核对、前后端启动、逐页验收、故障排查
- `docs/学习地图.md` — 分阶段学习路线与进度记录
- `docs/面试速查.md` — 项目介绍稿、高频追问答法、简历写法
- `docs/部署手册.md` — 服务器部署说明
