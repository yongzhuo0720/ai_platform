/**
 * 本地 Mock LLM 服务（OpenAI 兼容协议，零依赖）。
 *
 * 用途：本地开发/演示时替代真实模型服务，让「对话」与「知识库检索问答」
 * 两条链路无需 Dify 或任何外部 API Key 即可完整走通：
 *   - POST /chat/completions  聊天补全，支持 stream=true 的 SSE 逐字输出，
 *                             并按 OpenAI 规范返回 usage（网关按此统计 Token）
 *   - POST /embeddings        向量化（确定性伪随机向量，同文本同向量，
 *                             可用于本地知识库的相似度检索）
 *
 * 网关（AiGatewayService）拼接上游地址的规则是 `服务地址 + /chat/completions`，
 * EmbeddingClient 是 `服务地址 + /embeddings`，因此默认路径不带 /v1；
 * 同时兼容带 /v1 的直连调用（OpenAI SDK 习惯）。
 *
 * 用法：node scripts/mock-llm-server.mjs [端口]   默认 18090
 * 后台「模型配置」指向 http://127.0.0.1:18090 即可（密钥随便填）。
 */

import http from 'node:http'
import crypto from 'node:crypto'

const port = Number(process.argv[2] || process.env.MOCK_LLM_PORT || 18090)
const model = process.env.MOCK_CHAT_MODEL || 'mock-chat-model'
const embedModel = process.env.MOCK_EMBED_MODEL || 'mock-embed-model'
const dimensions = Number(process.env.MOCK_EMBED_DIMS || 1536)

// ---------- 工具 ----------

const log = (...args) => console.log(new Date().toISOString().slice(11, 19), ...args)

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    req.on('data', (c) => {
      size += c.length
      if (size > 10 * 1024 * 1024) {
        reject(new Error('payload too large'))
        req.destroy()
        return
      }
      chunks.push(c)
    })
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
  })
  res.end(body)
}

function estimateTokens(text) {
  // 粗略估计：中文约 1 字 1 token，英文约 4 字符 1 token
  const cjk = (text.match(/[\u4e00-\u9fff]/g) || []).length
  const rest = text.length - cjk
  return Math.max(1, cjk + Math.ceil(rest / 4))
}

/** 确定性伪随机向量：同一文本永远得到同一向量，检索结果才可复现 */
function embed(text) {
  const vec = new Array(dimensions)
  let seed = crypto.createHash('sha256').update(text, 'utf8').digest().readUInt32LE(0)
  for (let i = 0; i < dimensions; i++) {
    seed = (seed * 1664525 + 1013904223) >>> 0
    vec[i] = (seed / 0xffffffff) * 2 - 1
  }
  const norm = Math.sqrt(vec.reduce((s, v) => s + v * v, 0)) || 1
  return vec.map((v) => v / norm)
}

// ---------- 回复生成 ----------

/** 根据最后一条用户消息生成一段有信息量的演示回复 */
function textOf(content) {
  if (Array.isArray(content)) {
    return content.filter((p) => p && p.type === 'text').map((p) => p.text || '').join(' ')
  }
  return content == null ? '' : String(content)
}

function buildReply(messages, injectedContext) {
  const lastUser = [...(messages || [])].reverse().find((m) => m.role === 'user')
  const question = textOf(lastUser && lastUser.content)
  const lines = []
  lines.push(`你说的是：「${question.slice(0, 80)}${question.length > 80 ? '…' : ''}」`)
  lines.push('')
  lines.push('我当前运行在本地 Mock 模型服务上（scripts/mock-llm-server.mjs），')
  lines.push('这条回复经过 AI 网关的完整链路：鉴权 → 限流检查 → 转发上游 → SSE 逐字透传 → usage 解析与审计落库。')
  const imgCount = Array.isArray(lastUser && lastUser.content)
    ? lastUser.content.filter((p) => p && p.type === 'image_url').length : 0
  if (imgCount > 0) {
    lines.push('')
    lines.push('（收到 ' + imgCount + ' 张图片；mock 服务不解析图片内容，真实多模态模型可直接识别）')
  }
  if (injectedContext) {
    lines.push('')
    lines.push('检测到本次请求注入了知识库检索上下文（服务端 RAG 生效），上下文字符数：' + injectedContext.length + '。')
  }
  lines.push('')
  lines.push('把后台「模型配置」指向任意 OpenAI 兼容服务（如 vLLM、Ollama、DeepSeek），即可切换为真实模型。')
  return lines.join('\n')
}

/** 生成一段演示用"思考过程"，走 delta.reasoning_content 通道 */
function buildThinking(messages, injectedContext) {
  const lastUser = [...(messages || [])].reverse().find((m) => m.role === 'user')
  const question = textOf(lastUser && lastUser.content)
  const lines = []
  lines.push(`用户的问题：「${question.slice(0, 60)}${question.length > 60 ? '…' : ''}」`)
  lines.push('')
  lines.push('先拆解一下意图，判断需要什么信息来回答。')
  if (injectedContext) {
    lines.push('本次请求携带了知识库检索片段，先扫一遍相关性与可引用的点。')
  }
  lines.push('组织回答结构：直接回应问题，再补充说明这次调用链路经过了哪些环节。')
  lines.push('检查有没有遗漏的约束条件——没有，可以开始作答。')
  return lines.join('\n')
}

/** 从消息里提取网关注入的知识库检索上下文（system 消息中的命中片段） */
function findInjectedContext(messages) {
  const sys = (messages || []).filter((m) => m.role === 'system').map((m) => m.content || '')
  const joined = sys.join('\n')
  return joined.length > 0 ? joined : null
}

// ---------- SSE / 响应 ----------

async function handleChatCompletions(req, res) {
  let body
  try {
    body = JSON.parse((await readBody(req)) || '{}')
  } catch {
    return sendJson(res, 400, { error: { message: 'invalid json body', type: 'invalid_request_error' } })
  }

  const requestedModel = body.model || model
  const stream = body.stream === true
  const reply = buildReply(body.messages, findInjectedContext(body.messages))
  const promptTokens = estimateTokens((body.messages || []).map((m) => m.content || '').join('\n'))
  const completionTokens = estimateTokens(reply)
  const usage = {
    prompt_tokens: promptTokens,
    completion_tokens: completionTokens,
    total_tokens: promptTokens + completionTokens,
  }
  const created = Math.floor(Date.now() / 1000)
  const id = 'chatcmpl-mock-' + crypto.randomUUID().slice(0, 8)

  log(`chat/completions model=${requestedModel} stream=${stream} messages=${(body.messages || []).length} usage=${usage.total_tokens}t`)

  if (!stream) {
    return sendJson(res, 200, {
      id,
      object: 'chat.completion',
      created,
      model: requestedModel,
      choices: [{ index: 0, message: { role: 'assistant', content: reply }, finish_reason: 'stop' }],
      usage,
    })
  }

  // SSE 流式：首块 role → 逐段 content → usage 块（choices 为空）→ [DONE]
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no',
  })
  const send = (obj) => res.write(`data: ${JSON.stringify(obj)}\n\n`)

  send({ id, object: 'chat.completion.chunk', created, model: requestedModel, choices: [{ index: 0, delta: { role: 'assistant', content: '' }, finish_reason: null }] })

  // 先输出"思考过程"（delta.reasoning_content），再输出正文——
  // 前端聊天页把它渲染成可折叠的思考面板
  const thinking = buildThinking(body.messages, findInjectedContext(body.messages))
  const thinkingPieces = thinking.match(/[\s\S]{1,6}/g) || []
  const replyPieces = reply.match(/[\s\S]{1,6}/g) || []
  const pieces = [...thinkingPieces.map((t) => ({ kind: 'thinking', text: t })), ...replyPieces.map((t) => ({ kind: 'content', text: t }))]
  let i = 0
  const timer = setInterval(() => {
    if (res.writableEnded) {
      clearInterval(timer)
      return
    }
    if (i < pieces.length) {
      const piece = pieces[i]
      const delta = piece.kind === 'thinking' ? { reasoning_content: piece.text } : { content: piece.text }
      send({ id, object: 'chat.completion.chunk', created, model: requestedModel, choices: [{ index: 0, delta, finish_reason: null }] })
      i++
      return
    }
    clearInterval(timer)
    // 结束块 + usage 块（stream_options.include_usage 的标准应答格式）
    send({ id, object: 'chat.completion.chunk', created, model: requestedModel, choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] })
    send({ id, object: 'chat.completion.chunk', created, model: requestedModel, choices: [], usage })
    res.write('data: [DONE]\n\n')
    res.end()
    log(`chat/completions stream done, ${pieces.length} chunks (thinking ${thinkingPieces.length})`)
  }, 40)

  req.on('close', () => {
    clearInterval(timer)
    if (!res.writableEnded) res.end()
  })
}

/**
 * Jina/Cohere 通用的 /rerank 端点：确定性伪交叉编码器。
 * 按 query 与 doc 的字符 bigram 重叠度打分——同一输入永远同一排序，
 * 供本地验证两阶段检索与评估对比。
 */
async function handleRerank(req, res) {
  let body
  try {
    body = JSON.parse((await readBody(req)) || '{}')
  } catch {
    return sendJson(res, 400, { error: { message: 'invalid json body', type: 'invalid_request_error' } })
  }
  const query = String(body.query || '')
  const docs = Array.isArray(body.documents) ? body.documents.map(String) : []
  log(`rerank model=${body.model || 'mock-rerank'} query="${query.slice(0, 24)}" docs=${docs.length}`)
  const qBigrams = bigrams(query)
  const scored = docs
    .map((doc, index) => {
      const dBigrams = bigrams(doc)
      let hit = 0
      for (const g of qBigrams) if (dBigrams.has(g)) hit++
      const score = qBigrams.size ? hit / qBigrams.size : 0
      return { index, relevance_score: Number(score.toFixed(4)) }
    })
    .sort((a, b) => b.relevance_score - a.relevance_score)
  return sendJson(res, 200, { model: body.model || 'mock-rerank', results: scored })
}

function bigrams(text) {
  const set = new Set()
  const clean = String(text || '').replace(/\s+/g, '')
  for (let i = 0; i + 2 <= clean.length; i++) set.add(clean.slice(i, i + 2))
  return set
}

async function handleEmbeddings(req, res) {
  let body
  try {
    body = JSON.parse((await readBody(req)) || '{}')
  } catch {
    return sendJson(res, 400, { error: { message: 'invalid json body', type: 'invalid_request_error' } })
  }
  const inputs = Array.isArray(body.input) ? body.input : [String(body.input || '')]
  log(`embeddings model=${body.model || embedModel} inputs=${inputs.length}`)
  return sendJson(res, 200, {
    object: 'list',
    model: body.model || embedModel,
    data: inputs.map((text, i) => ({ object: 'embedding', index: i, embedding: embed(String(text)) })),
    usage: { prompt_tokens: estimateTokens(inputs.join('\n')), total_tokens: estimateTokens(inputs.join('\n')) },
  })
}

// ---------- 服务入口 ----------

const server = http.createServer(async (req, res) => {
  const path = (req.url || '/').split('?')[0].replace(/\/+$/, '') || '/'
  try {
    if (req.method === 'GET' && (path === '/healthz' || path === '/')) {
      return sendJson(res, 200, { ok: true, service: 'mock-llm-server', models: [model, embedModel] })
    }
    if (req.method === 'POST' && path.endsWith('/chat/completions')) {
      return await handleChatCompletions(req, res)
    }
    if (req.method === 'POST' && path.endsWith('/rerank')) {
      return await handleRerank(req, res)
    }
    if (req.method === 'POST' && path.endsWith('/embeddings')) {
      return await handleEmbeddings(req, res)
    }
    return sendJson(res, 404, { error: { message: `no route: ${req.method} ${path}`, type: 'not_found' } })
  } catch (err) {
    log('error:', err.message)
    if (!res.headersSent) {
      sendJson(res, 500, { error: { message: err.message, type: 'internal_error' } })
    } else {
      res.end()
    }
  }
})

server.listen(port, '127.0.0.1', () => {
  log(`mock-llm-server listening on http://127.0.0.1:${port}`)
  log(`chat model: ${model} | embedding model: ${embedModel} (${dimensions}d)`)
})
