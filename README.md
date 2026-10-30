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

网关核心在 `AiGatewayService`：转发请求/响应、过滤逐跳响应头、SSE 边读边写、解析上游 usage 并记录请求 ID、首块响应耗时与成本；`AiGatewayGuard` 用 Redis 做每分钟限流与每日 Token 配额（配额适用于 `chat` / `knowledgeChat` / `models`，`0` 表示不限制）；`AiGatewayAdminHolder` 提供用量看板、调用日志与配额管理接口，后台页面为「AI 调用治理」。Dify 对话模块的查询、JSON 与文件上传中的 `user` 统一使用后端认证身份。

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
| AI 调用治理 | Dify 与 OpenAI 兼容模型共用网关：按用户限流，记录 tokens、耗时、请求 ID 与成本；支持用户与部门 Token 配额 |
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
npm run test            # 逻辑层单元测试（Vitest），7 个文件 / 137 项
npm run check:streaming # 流式超时/取消回归，14 项
npm run check:markdown  # Markdown 与 XSS 消毒回归，10 项
npm run build           # 生产构建
```

本地一键验证使用 `scripts/verify.ps1 -JavaHome '<你的 JDK 8 路径>'`。

### 当前测试结果（2026-10-29 实测）

| 项目 | 结果 |
|---|---|
| 后端单元测试 | **165 项通过，0 失败** |
| 前端 TypeScript 检查 | 通过 |
| 前端流式回归 | 14 项通过 |
| 前端 Markdown / XSS 回归 | 10 项通过（**本次修复前该脚本一直是失败的，见下**） |
| 前端生产构建 | 通过（仍有 element-plus 1.05MB 分包警告） |

2026-10-30 新增：前端逻辑层单元测试（Vitest + jsdom，覆盖 ChatView 拆分后的
格式化 / 存储 / 选择器 / 表单 / 助手权限 / 分页竞态 / 发送逻辑），7 个文件
**137 项通过**，已接入 CI 与 `scripts/verify.ps1`。

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
