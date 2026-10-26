# 配置模型并跑通 RAG 评估

> 目标：让本地 RAG 能真正检索，然后跑出真实的 Recall 数字。
> 预计 20 分钟，其中大部分时间在注册第二家服务商。

---

## 零、先删掉这条会坑你的旧配置

数据库里有一条测试遗留：

| iid | name | protocol | baseUrl | upstreamModel | embedModel |
|---|---|---|---|---|---|
| 7 | Mock Direct Model | openai | `http://127.0.0.1:18090` | mock-chat-model | mock-embed-model |

它有 `embedModel`，而 `EmbeddingClient.resolve()` 取的是**第一条**满足条件的启用记录
（代码逻辑：遍历 → 跳过无 embedModel/baseUrl → 跳过 enable=false → 返回第一条）。

也就是说，**它会抢在你新配的向量服务之前被命中**，然后连一个不存在的端口，
RAG 表现为「检索失败」而不是「没配向量模型」——很难排查。

处理方式二选一：
- 后台「模型配置」页删除该行
- 或把它的「启用」关掉（`enable=false` 会被 `resolve()` 跳过）

---

## 一、DeepSeek 这行怎么填（对话用）

**先说个关键变化**：`deepseek-chat` / `deepseek-reasoner` 这两个模型名
已于 **2026-07-24 15:59 UTC 退役**，现在要用 `deepseek-v4-flash`（或 `deepseek-v4-pro`）。
填旧名字会直接调不通。

后台 → 模型配置 → 新增：

| 字段 | 填什么 | 说明 |
|---|---|---|
| 模型ID | `deepseek` | 随便，唯一即可 |
| 模型名称 | `DeepSeek V4` | 下拉里显示的名字 |
| 提供者 | `DeepSeek` | 随便 |
| 直连协议 | **OpenAI 兼容直连（openai）** | 必选，否则不会出现在对话页 |
| 服务地址 | `https://api.deepseek.com` | 不要带 `/v1`，网关自己拼 |
| 上游模型 | **`deepseek-v4-flash`** | ⚠️ 不是 `deepseek-chat`，已退役 |
| 向量模型 | **留空** | DeepSeek 没有 embedding 接口 |
| 服务密钥 | 你的 `sk-...` | 只存服务端，不下发浏览器 |
| 输入单价 | 可选，如 `100` | 单位「分/百万 tokens」，填了治理看板才能算钱 |
| 输出单价 | 可选，如 `200` | 同上 |

---

## 二、第二家：向量服务（RAG 必需）

DeepSeek 的 OpenAI 兼容层**只有 Chat Completions，没有 `/embeddings` 端点**
（官方文档和多篇 2026 年的实测都确认；拿它的 base-url 调 embedding 只会 400）。
所以 RAG 的向量化必须另找一家。三个选择：

### 方案 A：硅基流动（推荐，免费额度够用）

1. 注册 https://siliconflow.cn ，拿 API Key
2. 后台新增一行：

| 字段 | 值 |
|---|---|
| 直连协议 | OpenAI 兼容直连 |
| 服务地址 | `https://api.siliconflow.cn/v1` |
| 上游模型 | **留空**（这行只做向量） |
| 向量模型 | `BAAI/bge-m3` |
| 服务密钥 | 硅基流动的 Key |

### 方案 B：阿里云百炼（通义）

| 字段 | 值 |
|---|---|
| 服务地址 | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| 向量模型 | `text-embedding-v4`（或 `v3`） |
| 上游模型 | 留空 |

> 好处是顺带也能当对话模型用（`qwen-plus`），但对你现在的需求没必要。

### 方案 C：本地 Ollama（不花钱，但要装东西）

1. 装 Ollama，`ollama pull bge-m3`
2. 后台新增一行：

| 字段 | 值 |
|---|---|
| 服务地址 | `http://127.0.0.1:11434/v1` |
| 向量模型 | `bge-m3` |
| 服务密钥 | 随便填，如 `ollama`（Ollama 不校验） |
| 上游模型 | 留空 |

> 优点：完全离线、不花钱、向量数据不出本机。
> 缺点：首次要下载约 1.2GB 模型。

---

## 三、验证配置是否生效

配完后，用 admin 账号在**知识库**页上传一篇文档。判据：

- 上传成功且显示「分块 N 个」→ 向量化通了
- 报「尚未配置向量模型」→ `resolve()` 没找到（检查是否有 enable=false 或 `baseUrl` 为空）
- 报连接失败 / 超时 → 命中了那条 mock 配置，回到第零步删掉它
- 报 401/403 → Key 不对；报 400 → 服务地址或向量模型名不对

---

## 四、跑评估

```bash
# 1) 灌语料 + 跑 55 题 + 出报告（一条命令）
node scripts/rag-eval.mjs \
  --account admin --password 123456 \
  --seed \
  --out docs/eval/report-$(date +%F).md
```

脚本会：登录 → 上传 6 篇种子语料 → 逐题调检索 → 算 Recall@k / Precision@k / MRR / NDCG@k
→ 输出总体指标 + 完全漏召清单 + 逐题明细。

```bash
# 2) 跑完清理语料（可选）
node scripts/rag-eval.mjs --account admin --password 123456 --seed --cleanup
```

拿到数字后填进 `docs/eval/README.md` 的「真实评估记录表」。

---

## 五、跑不出来时的排查顺序

| 症状 | 先查这里 |
|---|---|
| 上传文档报「未配置向量模型」 | 是否有行的 `embedModel` 为空、`baseUrl` 为空、或 `enable=false` |
| 上传报连接失败 | 第零步的 mock 配置是否还在 |
| 上传成功但检索结果为空 | 语料是否传到了同一个 `kbName`（脚本默认「评估语料」） |
| 检索到了但全是错文档 | 看报告里的「完全漏召」清单，先查分块是否把关键段落切断了 |
| 指标明显偏低 | 先确认向量模型够不够用；`bge-m3` / `text-embedding-v4` 属于够用档 |

---

## 附：本次为支持两厂商组合所做的代码调整

原来的实现假设「一行配置 = 一个对话模型」，导致「对话用 A 家、向量用 B 家」配不出来。
DeepSeek 用户恰恰必须这么配。已修：

| 改动 | 位置 | 说明 |
|---|---|---|
| 纯向量行可保存 | `Admin/ModelsConfigView/index.ts` | 校验从「必须有上游模型」放宽为「上游模型或向量模型至少一个」 |
| 对话下拉过滤 | `ChatView/index.ts` | 只把有 `upstreamModel` 的直连配置列为可对话模型，避免用户选中纯向量行后调用失败 |
| 类型补字段 | `ChatView/index.ts` 的 `ChatModel` | 新增 `upstreamModel?: string` |

后端无需改动：`ModelGatewayResolver.build()` 本来就要求 `upstreamModel` 非空，
纯向量行天然不会被当成对话模型；`EmbeddingClient.resolve()` 本来就只要求 `embedModel`。
