#!/usr/bin/env node
/**
 * 真实模型链路冒烟验证（聊天直连 + RAG 全链路）
 *
 * 目的：用真实的 OpenAI 兼容模型，端到端验证网关转发 / 鉴权 / 模型路由 /
 * 计费记账 / 请求ID / 向量化 / 混合检索 / 知识库注入是否真的通——不是 mock。
 *
 * 安全设计（重要）：
 * - API Key 只从环境变量读（DS_KEY / SF_KEY），绝不写进脚本、日志或仓库文件；
 * - 脚本自建「QA-」前缀的临时模型配置与临时知识库，跑完无论成功失败都清理；
 * - 不改动任何已有模型配置或知识库。
 *
 * 前置：后端 8507 + PostgreSQL 已启动，admin 账号可用（本地默认 admin/123456）。
 *
 * 用法：
 *   # 聊天直连（需要一家 OpenAI 兼容对话模型）
 *   DS_KEY=sk-xxx DS_MODEL=deepseek-flash DS_BASE=https://api.deepseek.com \
 *     node scripts/verify-live-model.mjs chat
 *
 *   # RAG 全链路（对话模型 + embedding 服务两家）
 *   DS_KEY=sk-xxx DS_MODEL=deepseek-flash DS_BASE=https://api.deepseek.com \
 *   SF_KEY=sk-xxx SF_EMBED=BAAI/bge-m3 SF_BASE=https://api.siliconflow.cn/v1 \
 *     node scripts/verify-live-model.mjs rag
 *
 *   # 不传密码时用 DS_TOKEN 环境变量传已有 token
 *
 * Windows PowerShell 示例：
 *   $env:DS_KEY='sk-xxx'; $env:DS_MODEL='deepseek-flash'; $env:DS_BASE='https://api.deepseek.com'
 *   node scripts/verify-live-model.mjs chat
 */

import path from 'node:path'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'

const BASE = process.env.API_BASE || 'http://localhost:8507/ai'
const ACCOUNT = process.env.QA_ACCOUNT || 'admin'
const PASSWORD = process.env.QA_PASSWORD || '123456'
const TOKEN = process.env.DS_TOKEN || process.env.QA_TOKEN || null
const QA_PREFIX = 'QA-LIVE-' + Date.now()

function requireEnv(name) {
  const v = process.env[name]
  if (!v) throw new Error(`缺少环境变量 ${name}，见脚本头部用法说明`)
  return v
}

function log(...args) { console.log(...args) }
function fail(msg) { console.error('✗ ' + msg); process.exitCode = 1 }

async function post(pathname, body, token, extraHeaders = {}) {
  const headers = {
    'Content-Type': 'application/json; charset=utf-8',
    'X-Request-Id': `qa-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    'Idempotency-Key': `qa-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    ...extraHeaders,
  }
  if (token) { headers['x-access-token'] = token; headers['account'] = ACCOUNT }
  const res = await fetch(BASE + pathname, { method: 'POST', headers, body: JSON.stringify(body) })
  const text = await res.text()
  let json
  try { json = JSON.parse(text) }
  catch { throw new Error(`${pathname} 返回非 JSON（HTTP ${res.status}）：${text.slice(0, 200)}`) }
  if (json.resp_code !== undefined && json.resp_code !== 0) {
    throw new Error(`${pathname} 业务失败 resp_code=${json.resp_code} ${json.resp_msg || ''}`)
  }
  return json
}

async function login() {
  if (TOKEN) { log('1) 使用已有 token'); return TOKEN }
  const r = await post('/uaccess/logintest', { keepUser: false, uaccess: ACCOUNT, upwd: PASSWORD })
  const token = r.datas?.token
  if (!token) throw new Error('登录未拿到 token，检查账号密码')
  log('1) 登录成功')
  return token
}

/** 建一条 OpenAI 兼容模型配置，返回 iid。embedModel/upstreamModel 按用途二选一留空 */
async function createModel(token, cfg) {
  await post('/api/modelsConfigBean/create', { data: cfg }, token)
  const listed = await post('/api/modelsConfigBean/query', { data: { name: cfg.name } }, token)
  const row = (listed.datas || []).find((r) => r.name === cfg.name)
  if (!row) throw new Error(`建模型配置后查不到：${cfg.name}`)
  return row.iid
}

async function deleteModel(token, iid) {
  try { await post('/api/modelsConfigBean/delete', { data: { iid } }, token) }
  catch (e) { fail(`清理模型配置 iid=${iid} 失败：${e.message}（需后台手动删除）`) }
}

function extractAnswer(raw) {
  const data = raw?.datas ?? raw
  return {
    content: data?.choices?.[0]?.message?.content ?? data?.content ?? '',
    reasoning: data?.choices?.[0]?.message?.reasoning_content ?? '',
    usage: data?.usage ?? {},
  }
}

/** 聊天直连验证 */
async function verifyChat(token) {
  const dsKey = requireEnv('DS_KEY')
  const dsModel = process.env.DS_MODEL || 'deepseek-flash'
  const dsBase = process.env.DS_BASE || 'https://api.deepseek.com'
  const chatName = `${QA_PREFIX}-chat`
  let chatIid
  try {
    log('2) 建临时对话模型配置...')
    chatIid = await createModel(token, {
      name: chatName, provider: 'QA', protocol: 'openai',
      baseUrl: dsBase, apiKey: dsKey, upstreamModel: dsModel, embedModel: '',
      priceInputPerMillion: 100, priceOutputPerMillion: 200,
    })
    log(`   iid=${chatIid}, 上游模型=${dsModel}`)
    log('3) 走网关真实对话...')
    const r = await post(`/api/aiGateway/models/${chatIid}/chat/completions`, {
      messages: [{ role: 'user', content: '只回复：pong' }], stream: false,
    }, token)
    const { content, reasoning, usage } = extractAnswer(r)
    log(`   回答: ${String(content).slice(0, 80) || '(空)'}`)
    if (reasoning) log(`   推理: ${String(reasoning).slice(0, 60)}...`)
    log(`   用量: ${JSON.stringify(usage)}`)
    if (!content && !reasoning) throw new Error('回答与推理都为空，链路可能有问题')
    if (!usage.prompt_tokens) throw new Error('没有返回 token 用量，计费记账链路可能有问题')
    log('✓ 聊天直连链路验证通过（网关转发/鉴权/路由/计费/请求ID）')
  } finally {
    if (chatIid) await deleteModel(token, chatIid)
  }
}

/** RAG 全链路验证 */
async function verifyRag(token) {
  const dsKey = requireEnv('DS_KEY')
  const sfKey = requireEnv('SF_KEY')
  const dsModel = process.env.DS_MODEL || 'deepseek-flash'
  const dsBase = process.env.DS_BASE || 'https://api.deepseek.com'
  const sfBase = process.env.SF_BASE || 'https://api.siliconflow.cn/v1'
  const sfEmbed = process.env.SF_EMBED || 'BAAI/bge-m3'
  const chatName = `${QA_PREFIX}-chat`
  const embedName = `${QA_PREFIX}-embed`
  const kbName = `${QA_PREFIX}-KB`
  const modelIids = []
  const docIids = []
  try {
    log('2) 建两条临时模型配置（对话 + 向量）...')
    const chatIid = await createModel(token, {
      name: chatName, provider: 'QA', protocol: 'openai',
      baseUrl: dsBase, apiKey: dsKey, upstreamModel: dsModel, embedModel: '',
      priceInputPerMillion: 100, priceOutputPerMillion: 200,
    })
    modelIids.push(chatIid)
    const embedIid = await createModel(token, {
      name: embedName, provider: 'QA', protocol: 'openai',
      baseUrl: sfBase, apiKey: sfKey, upstreamModel: '', embedModel: sfEmbed,
    })
    modelIids.push(embedIid)
    log(`   对话 iid=${chatIid}, 向量 iid=${embedIid}(${sfEmbed})`)

    log('3) 上传种子语料并等待向量化就绪...')
    const __dirname = path.dirname(fileURLToPath(import.meta.url))
    const corpusPath = path.join(__dirname, '..', 'docs', 'eval', 'seed-corpus', '01-差旅报销制度.md')
    const docContent = fs.readFileSync(corpusPath, 'utf-8')
    const up = await post('/api/kbRag/uploadDoc', {
      data: { name: '01-差旅报销制度.md', kbName, content: docContent },
    }, token)
    const docIid = up.datas?.iid
    if (!docIid) throw new Error('上传未返回 iid')
    docIids.push(docIid)

    let ready = false
    let chunkCount = 0
    for (let i = 0; i < 60; i++) {
      await new Promise((r) => setTimeout(r, 1500))
      const docs = await post('/api/kbRag/listDocs', {}, token)
      const d = (docs.datas || []).find((x) => x.iid === docIid)
      if (d?.status === 'failed') throw new Error('文档处理失败：' + (d.errorMsg || ''))
      if (d?.status === 'ready' && (d.chunkCount || 0) > 0) {
        ready = true; chunkCount = d.chunkCount; break
      }
    }
    if (!ready) throw new Error('等待向量化就绪超时')
    log(`   就绪，分块 ${chunkCount} 个（真实调 embedding ${sfEmbed}）`)

    log('4) 真实检索...')
    const search = await post('/api/kbRag/search', {
      data: { query: '在上海出差住宿每晚上限多少', kbName, topK: 3 },
    }, token)
    const items = search.datas?.items || []
    if (!items.length) throw new Error('检索无命中，RAG 链路可能有问题')
    log(`   命中 ${items.length} 条，首条：${items[0]?.docName} | ${String(items[0]?.content || '').slice(0, 50)}`)

    log('5) 带知识库上下文的真实对话...')
    const r = await post(`/api/aiGateway/models/${chatIid}/chat/completions`, {
      messages: [{ role: 'user', content: '在上海出差住宿每晚上限多少钱？只回答金额。' }],
      stream: false, kb_name: kbName, kb_top_k: 3,
    }, token)
    const { content, usage } = extractAnswer(r)
    log(`   回答: ${String(content).slice(0, 100) || '(空)'}`)
    log(`   用量: ${JSON.stringify(usage)}`)
    if (!String(content).includes('600')) fail('回答未包含语料中的「600」，RAG 注入可能未生效（注意模型可能改述）')
    const promptTokens = usage.prompt_tokens || 0
    if (promptTokens < 100) fail(`prompt_tokens=${promptTokens} 偏低，知识库片段可能未注入（纯对话通常 ~35）`)
    log('✓ RAG 全链路验证通过（向量化/混合检索/KB注入/生成/计费）')
  } finally {
    log('6) 清理临时语料与配置...')
    for (const iid of docIids) {
      try { await post('/api/kbRag/deleteDoc', { data: { iid } }, token) } catch (e) { fail(`删语料 ${iid} 失败：${e.message}`) }
    }
    for (const iid of modelIids) await deleteModel(token, iid)
  }
}

const mode = process.argv[2] || 'chat'
if (!['chat', 'rag'].includes(mode)) {
  console.error('用法：node scripts/verify-live-model.mjs <chat|rag>，见脚本头部说明')
  process.exit(2)
}
;(async () => {
  try {
    const token = await login()
    if (mode === 'chat') await verifyChat(token)
    else await verifyRag(token)
    log('VERIFY_DONE')
  } catch (e) {
    fail(e.message)
  }
})()
