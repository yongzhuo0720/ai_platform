#!/usr/bin/env node
/**
 * 全功能演示脚本：逐个调用项目各模块接口，打印真实返回与耗时。
 *
 * 覆盖原则：
 * - 以只读接口为主（query/list/status/health/summary/detail/search），不污染数据；
 * - 每个接口检查 resp_code，统计成功/失败；
 * - 敏感字段（密钥）只显示「已配置/未配置」，不回显值。
 *
 * 前置：后端 8507 + PostgreSQL 已启动，admin/123456 可用。
 * 用法：node scripts/demo-all-features.mjs
 */

const BASE = process.env.API_BASE || 'http://localhost:8507/ai'
const ACCOUNT = process.env.QA_ACCOUNT || 'admin'
const PASSWORD = process.env.QA_PASSWORD || '123456'

let pass = 0, fail = 0
const failures = []

async function request(method, pathname, body, token) {
  const headers = {
    'Content-Type': 'application/json; charset=utf-8',
    'X-Request-Id': `demo-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
    'Idempotency-Key': `demo-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
  }
  if (token) { headers['x-access-token'] = token; headers['account'] = ACCOUNT }
  const t0 = Date.now()
  const init = method === 'GET' ? { method: 'GET', headers } : { method, headers, body: JSON.stringify(body ?? {}) }
  const res = await fetch(BASE + pathname, init)
  const text = await res.text()
  const ms = Date.now() - t0
  let json
  try { json = JSON.parse(text) }
  catch { throw new Error(`HTTP ${res.status} 非JSON: ${text.slice(0, 120)}`) }
  return { json, ms }
}
async function post(pathname, body, token) { return request('POST', pathname, body, token) }
async function get(pathname, token) { return request('GET', pathname, null, token) }

/** 展示一个接口的演示结果 */
function show(name, result, summarize) {
  const { json, ms } = result
  const code = json.resp_code
  const ok = code === 0 || code === 200
  if (ok) pass++; else { fail++; failures.push(`${name} (resp_code=${code} ${json.resp_msg || ''})`) }
  const mark = ok ? '✓' : '✗'
  let detail
  try { detail = summarize ? summarize(json.datas ?? json) : brief(json.datas ?? json) }
  catch (e) { detail = '(摘要生成失败: ' + e.message + ')' }
  console.log(`${mark} ${name.padEnd(28, ' ')} [${String(ms).padStart(4)}ms] code=${code} ${detail}`)
}

function brief(data) {
  if (data === null || data === undefined) return ''
  if (Array.isArray(data)) return `数组 ${data.length} 条`
  if (typeof data === 'object') {
    const keys = Object.keys(data)
    const parts = keys.slice(0, 6).map((k) => {
      const v = data[k]
      if (Array.isArray(v)) return `${k}=${v.length}条`
      if (typeof v === 'object' && v !== null) return `${k}={...}`
      if (typeof v === 'string' && /^sk-|key|secret/i.test(v)) return `${k}=***`
      return `${k}=${v}`
    })
    return parts.join(', ')
  }
  return String(data).slice(0, 80)
}

async function demo(name, path, body, token, summarize) {
  try { show(name, await post(path, body, token), summarize) }
  catch (e) { fail++; failures.push(`${name} (${e.message})`); console.log(`✗ ${name.padEnd(28, ' ')} [ERR] ${e.message}`) }
}
async function demoGet(name, path, token, summarize) {
  try { show(name, await get(path, token), summarize) }
  catch (e) { fail++; failures.push(`${name} (${e.message})`); console.log(`✗ ${name.padEnd(28, ' ')} [ERR] ${e.message}`) }
}
/** 空库时接口因无前置数据报错，不算功能失败，单独标注 */
function skip(name, reason) {
  console.log(`○ ${name.padEnd(28, ' ')} [SKIP] ${reason}`)
}

;(async () => {
  console.log('='.repeat(80))
  console.log('NexusAI 全功能演示 · ' + new Date().toLocaleString('zh-CN'))
  console.log('='.repeat(80))

  // 0) 登录
  const login = await post('/uaccess/logintest', { keepUser: false, uaccess: ACCOUNT, upwd: PASSWORD })
  const token = login.json.datas?.token
  if (!token) { console.error('登录失败:', JSON.stringify(login.json)); process.exit(1) }
  console.log(`✓ 登录成功 · token 长度 ${token.length}\n`)
  const T = token

  console.log('--- 1. 网关治理 ---')
  await demo('网关配置状态', '/api/aiGatewayStatus/config', {}, T)
  await demo('网关健康面板', '/api/aiGatewayAdmin/health', {}, T, (d) => `模块数=${(d.modules||[]).length}`)
  await demo('用量汇总', '/api/aiGatewayAdmin/summary', { range: undefined }, T, (d) => `总调用=${d.totalCalls}, 成功率=${d.successRate}%, 模型数=${(d.models||[]).length}`)
  await demo('审计日志', '/api/aiGatewayAdmin/logs', {}, T, (d) => `记录数=${(d.content||d.list||d.items||[]).length}${d.totalSize !== undefined ? ', 总计='+d.totalSize : ''}`)
  await demo('我的用量', '/api/aiGatewayAdmin/myUsage', {}, T)
  await demo('配额列表', '/api/aiGatewayAdmin/quotas', {}, T, (d) => `条数=${(Array.isArray(d)?d:[]).length}`)
  await demo('配额用户下拉', '/api/aiGatewayAdmin/users', {}, T, (d) => `用户数=${(Array.isArray(d)?d:[]).length}`)

  console.log('\n--- 2. 模型配置 ---')
  await demo('模型配置列表', '/api/modelsConfigBean/query', { data: {} }, T, (d) => `模型数=${(Array.isArray(d)?d:[]).length}`)
  await demo('Dify 配置列表', '/api/difyConfigBean/query', { data: {} }, T, (d) => `条数=${(Array.isArray(d)?d:[]).length}`)

  console.log('\n--- 3. 知识库与 RAG ---')
  await demo('知识库文档列表', '/api/kbRag/listDocs', {}, T, (d) => `文档数=${(Array.isArray(d)?d:[]).length}`)
  await demo('知识库权限列表', '/api/knowledgeBaseBean/query', { data: {} }, T, (d) => `条数=${(Array.isArray(d)?d:[]).length}`)
  await demo('可见知识库过滤', '/api/knowledgeBaseBean/filterVisible', { data: { context: 'chat' } }, T, (d) => `可见ID数=${(Array.isArray(d)?d:[]).length}`)
  await demo('已加入知识库', '/api/knowledgeBaseMember/joinedIds', {}, T)

  console.log('\n--- 4. RAG 评估工作台 ---')
  await demo('评估数据集列表', '/api/ragEval/listDatasets', {}, T, (d) => `数据集数=${(Array.isArray(d)?d:[]).length}`)
  await demo('评估运行历史', '/api/ragEval/listRuns', {}, T, (d) => `运行数=${(Array.isArray(d)?d:[]).length}`)

  console.log('\n--- 5. Agent 工作台 ---')
  const agents = await post('/api/agentBean/list', {}, T)
  const agentList = agents.json.datas ?? []
  show('Agent 配置列表', agents, (d) => `Agent 数=${(Array.isArray(d)?d:[]).length}`)
  await demo('Agent 运行列表', '/api/agentRunBean/list', {}, T, (d) => `运行数=${(Array.isArray(d)?d:[]).length}`)
  // 工具列表要求显式 agentIid，空库无 Agent 时跳过（非功能缺陷）
  if (Array.isArray(agentList) && agentList.length && agentList[0].iid) {
    await demo('Agent 工具列表', '/api/agentToolBean/list', { agentIid: agentList[0].iid }, T, (d) => `工具数=${(Array.isArray(d)?d:[]).length}`)
  } else {
    skip('Agent 工具列表', '空库无 Agent，接口需 agentIid 前置')
  }

  console.log('\n--- 6. 平台治理 ---')
  await demo('站内通知列表', '/api/notification/list', {}, T, (d) => `通知数=${(d?.items||[]).length}, 未读=${d?.unreadCount}`)
  await demo('未读通知数', '/api/notification/unreadCount', {}, T)
  await demo('周报列表', '/api/aiWeeklyReport/list', {}, T, (d) => `周报数=${(Array.isArray(d)?d:[]).length}`)
  await demo('操作日志', '/api/controllerLog/query', {}, T, (d) => `日志数=${(d?.content||d?.list||d?.items||[]).length}`)
  await demo('全局搜索', '/api/globalSearch/search', { data: { keyword: '模型', limit: 5 } }, T, (d) => `分组数=${Object.keys(d||{}).length}`)

  console.log('\n--- 7. 资源仓库 ---')
  await demo('模型仓库', '/api/modelsBean/query', { data: {} }, T, (d) => `条目数=${(d?.list||d?.records||(Array.isArray(d)?d:[])).length}`)
  await demo('模型周下载总量', '/api/modelsBean/queryWeeklyDownloadsTotal', {}, T)
  await demo('数据集仓库', '/api/datasetsBean/query', { data: {} }, T, (d) => `条目数=${(d?.list||d?.records||(Array.isArray(d)?d:[])).length}`)
  await demo('能力广场', '/api/capabilitiesBean/query', { data: {} }, T)
  await demo('应用构建', '/api/applicationsBean/query', { data: {} }, T)
  await demo('助手列表', '/api/assistantBean/query', { data: {} }, T)

  console.log('\n--- 8. 会话 / 密钥 / SSO ---')
  await demo('云端会话列表', '/api/chatConversation/list', {}, T, (d) => `会话数=${(Array.isArray(d)?d:[]).length}`)
  await demo('平台 API 密钥列表', '/api/aiApiToken/list', {}, T, (d) => `密钥数=${(Array.isArray(d)?d:[]).length}`)
  await demoGet('SSO 会话状态', '/api/ssoTokenBean/status', T, (d) => `hasSession=${d?.hasSession}${d?.authUrl ? ', 有授权URL' : ''}`)

  console.log('\n' + '='.repeat(80))
  console.log(`演示汇总：成功 ${pass} / 失败 ${fail}`)
  if (failures.length) {
    console.log('失败项：')
    failures.forEach((f) => console.log('  - ' + f))
  }
  console.log('DEMO_DONE')
  process.exitCode = fail > 0 ? 1 : 0
})().catch((e) => { console.error('演示异常:', e.message); process.exit(2) })
