#!/usr/bin/env node
/**
 * 带数据的深度演示：真实跑一轮 RAG 评估 + 一次 Agent 工具调用。
 *
 * 前置（脚本自动探测）：
 * - 向量模型（硅基 BAAI/bge-m3，库中 iid=10 已配好）→ RAG 评估用
 * - 知识库「评估语料」下 6 篇文档 → RAG 评估用
 * - 对话模型：脚本临时建一条 DeepSeek 配置（DS_KEY 环境变量）→ Agent 用，跑完删
 *
 * 安全：key 只从 DS_KEY 读；所有临时数据 QA- 前缀，finally 清理，不碰已有配置。
 * 用法：DS_KEY=sk-xxx node scripts/demo-deep.mjs
 */

import fs from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const BASE = process.env.API_BASE || 'http://localhost:8507/ai'
const DS_KEY = process.env.DS_KEY
const DS_MODEL = process.env.DS_MODEL || 'deepseek-flash'
const DS_BASE = process.env.DS_BASE || 'https://api.deepseek.com'
const QA = 'QA-DEEP-' + Date.now()
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function post(pathname, body, token) {
  const headers = {
    'Content-Type': 'application/json; charset=utf-8',
    'X-Request-Id': `deep-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
    'Idempotency-Key': `deep-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
  }
  if (token) { headers['x-access-token'] = token; headers['account'] = 'admin' }
  const res = await fetch(BASE + pathname, { method: 'POST', headers, body: JSON.stringify(body ?? {}) })
  const text = await res.text()
  let json
  try { json = JSON.parse(text) }
  catch { throw new Error(`${pathname} 非JSON(HTTP ${res.status}): ${text.slice(0, 150)}`) }
  if (json.resp_code !== undefined && json.resp_code !== 0) {
    throw new Error(`${pathname} resp_code=${json.resp_code} ${json.resp_msg || ''}`)
  }
  return json.datas
}

;(async () => {
  const login = await post('/uaccess/logintest', { keepUser: false, uaccess: 'admin', upwd: '123456' })
  const token = login.token
  console.log('✓ 登录成功\n')

  // 临时资源句柄，finally 清理
  let datasetKey = null, runKey = null
  let modelIid = null, agentIid = null, agentKey = null, toolIid = null

  try {
    // ===== 第一部分：RAG 评估 =====
    console.log('='.repeat(70))
    console.log('第一部分：RAG 评估工作台（真实跑一轮）')
    console.log('='.repeat(70))

    // 探测知识库文档，确认 expectedDoc 存在
    const docs = await post('/api/kbRag/listDocs', {}, token)
    const evalDocs = (docs || []).filter((d) => d.kbName === '评估语料')
    console.log(`探测：评估语料知识库有 ${evalDocs.length} 篇文档`)
    if (evalDocs.length < 3) throw new Error('评估语料文档不足 3 篇，无法演示')

    // 用真实文档名建数据集（3 题，来自 seed-corpus 的真实内容）
    const cases = [
      { question: '出差3天以内由谁审批', expectedDoc: '01-差旅报销制度', keywords: ['直属主管'], category: '事实型' },
      { question: '在上海出差住宿每晚上限多少', expectedDoc: '01-差旅报销制度', keywords: ['600'], category: '数值型' },
      { question: '试用期工资是转正后的百分之多少', expectedDoc: '02-入职与转正管理办法', keywords: ['80%'], category: '数值型' },
    ]
    datasetKey = await post('/api/ragEval/createDataset', {
      data: { name: QA + '-评估集', kbName: '评估语料', description: '深度演示', cases },
    }, token)
    console.log(`✓ 建评估数据集: datasetKey=${datasetKey}`)

    runKey = await post('/api/ragEval/startRun', {
      data: { datasetKey, topK: 5, rerankMode: 'off' },
    }, token)
    console.log(`✓ 启动评估(异步): runKey=${runKey}`)

    // 轮询等待完成
    let detail = null
    for (let i = 0; i < 60; i++) {
      await sleep(1000)
      detail = await post('/api/ragEval/getRun', { data: { runKey } }, token)
      const r = detail.run
      if (['success', 'partial', 'failed'].includes(r.status)) {
        console.log(`✓ 评估完成: status=${r.status}, 进度=${r.processed}/${r.total}`)
        break
      }
      if (i % 5 === 0) console.log(`  等待中... ${r.processed}/${r.total} (${r.status})`)
    }
    if (!detail || !['success', 'partial', 'failed'].includes(detail.run.status)) {
      throw new Error('评估超时未完成')
    }
    const r = detail.run
    console.log(`\n评估指标（真实检索 + 硅基 embedding）:`)
    console.log(`  Recall@5=${(r.recall * 100).toFixed(2)}%  Precision@5=${(r.precision * 100).toFixed(2)}%`)
    console.log(`  MRR=${r.mrr?.toFixed(4)}  NDCG@5=${r.ndcg?.toFixed(4)}  关键词命中=${(r.keywordHitRate * 100).toFixed(2)}%`)
    console.log(`  漏召=${r.missCount}/${r.total}  耗时=${r.durationMs}ms  检索模式=${r.retrievalMode}`)
    console.log(`  逐题结果:`)
    ;(detail.results || []).forEach((res) => {
      console.log(`    ${res.question} → 首条=${res.topDoc || '(无)'} R=${res.recall?.toFixed(2)} 关键词=${res.keywordHit ? '✓' : '✗'}`)
    })

    // ===== 第二部分：Agent 工具调用 =====
    console.log('\n' + '='.repeat(70))
    console.log('第二部分：Agent 工作台（真实工具调用）')
    console.log('='.repeat(70))
    if (!DS_KEY) {
      console.log('○ 跳过 Agent：未设 DS_KEY（设了才能建临时对话模型跑 Agent）')
    } else {
      // 临时建对话模型
      const model = await post('/api/modelsConfigBean/create', {
        data: {
          name: QA + '-chat', provider: 'QA', protocol: 'openai',
          baseUrl: DS_BASE, apiKey: DS_KEY, upstreamModel: DS_MODEL, embedModel: '',
          priceInputPerMillion: 100, priceOutputPerMillion: 200,
        },
      }, token)
      const models = await post('/api/modelsConfigBean/query', { data: { name: QA + '-chat' } }, token)
      modelIid = (models || []).find((m) => m.name === QA + '-chat')?.iid
      console.log(`✓ 临时对话模型: iid=${modelIid} (${DS_MODEL})`)

      // 建 Agent
      const agent = await post('/api/agentBean/create', {
        data: {
          name: QA + '-报销助手', description: '深度演示',
          systemPrompt: '你是报销制度助手。回答前必须调用 kb_search 工具检索制度原文，然后基于检索结果给出明确结论（含具体金额/天数）。',
          modelIid, maxIterations: 6, tokenBudget: 20000, permissionType: 'only_me',
        },
      }, token)
      agentIid = agent.iid; agentKey = agent.agentKey
      console.log(`✓ 建 Agent: iid=${agentIid}, agentKey=${agentKey}`)

      // 建 kb_search 工具
      const tool = await post('/api/agentToolBean/save', {
        data: {
          agentIid, name: 'kb_search', toolType: 'kb_search',
          description: '检索本地知识库，参数 query 为检索关键词',
          jsonSchema: JSON.stringify({
            type: 'object',
            properties: { query: { type: 'string', description: '检索关键词' } },
            required: ['query'],
          }),
        },
      }, token)
      toolIid = tool.iid
      console.log(`✓ 建工具: kb_search (iid=${toolIid})`)

      // 运行 Agent（同步返回完整详情）
      console.log('运行 Agent（真实模型 + 工具调用）...')
      const runDetail = await post('/api/agentBean/run', {
        data: { agentKey, question: '在上海出差住宿每晚上限多少钱？请检索制度后回答。' },
      }, token)
      const run = runDetail.run
      console.log(`\nAgent 运行结果:`)
      console.log(`  status=${run.status}  迭代=${run.iterations}  tokens=${run.totalTokens}  成本=${run.costCents}毫分`)
      console.log(`  回答: ${run.answer || '(空)'}`)
      console.log(`  执行步骤:`)
      ;(runDetail.steps || []).forEach((s) => {
        const detail = s.stepType === 'tool_result'
          ? ` tool=${s.toolName} result=${String(s.toolResult || '').slice(0, 60)}`
          : ''
        console.log(`    #${s.stepNo} [${s.stepType}] status=${s.status}${detail}`)
      })
      if (run.status !== 'succeeded') throw new Error('Agent 运行未成功: ' + run.status)
    }

    console.log('\n' + '='.repeat(70))
    console.log('DEEP_DEMO_DONE')
  } finally {
    console.log('\n清理临时数据...')
    const clean = async (label, fn) => {
      try { await fn(); console.log(`  ✓ ${label}`) }
      catch (e) { console.log(`  ✗ ${label}: ${e.message}`) }
    }
    if (runKey) await clean('删除评估运行', () => post('/api/ragEval/deleteRun', { data: { runKey } }, token))
    if (datasetKey) await clean('删除评估数据集', () => post('/api/ragEval/deleteDataset', { data: { datasetKey } }, token))
    if (toolIid) await clean('删除 Agent 工具', () => post('/api/agentToolBean/delete', { data: { iid: toolIid } }, token))
    if (agentIid) await clean('删除 Agent', () => post('/api/agentBean/delete', { data: { iid: agentIid } }, token))
    if (modelIid) await clean('删除临时模型配置', () => post('/api/modelsConfigBean/delete', { data: { iid: modelIid } }, token))
    console.log('CLEANUP_DONE')
  }
})().catch((e) => { console.error('演示失败:', e.message); process.exit(1) })
