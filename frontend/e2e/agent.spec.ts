import { createServer, type ServerResponse } from 'node:http'
import { expect, test, type Page } from '@playwright/test'
import { installBackendMocks, loginAsUser } from './mocks'

const config = { iid: 41, agentKey: 'agent-e2e', name: '业务助手', modelIid: 1, systemPrompt: '使用工具回答问题', maxIterations: 8, tokenBudget: 20000 }
const run = { runKey: 'run-e2e', agentIid: 41, question: '查询当前时间', status: 'succeeded', answer: '工具执行结束', totalTokens: 150, costCents: 123, durationMs: 850, iterations: 2 }
const steps = [
  { runKey: run.runKey, stepNo: 1, iteration: 1, stepType: 'llm', status: 'success', toolResult: '准备查询时间', latencyMs: 320 },
  { runKey: run.runKey, stepNo: 2, iteration: 1, stepType: 'tool_result', toolName: 'clock', toolArgs: '{}', status: 'failed', toolResult: '工具执行失败', errorMessage: '工具暂不可用', latencyMs: 25 },
  { runKey: run.runKey, stepNo: 3, iteration: 2, stepType: 'finish', status: 'success', toolResult: run.answer, latencyMs: 0 },
]
const frame = (event: string, value: unknown) => `event: ${event}\ndata: ${JSON.stringify(value)}\n\n`

async function setup(page: Page) {
  await installBackendMocks(page)
  await loginAsUser(page)
  await page.route('**/ai/myRole/getRids', route => route.fulfill({
    contentType: 'application/json',
    body: JSON.stringify({ resp_code: 0, datas: { content: [{ iid: 2, viewjson: '["chat","agents"]' }] } }),
  }))
  const configs = [{ ...config }]
  const tools: Record<string, unknown>[] = []
  let runs: unknown[] = []
  const requests: { path: string; body: any }[] = []
  await page.route('**/ai/api/agent*/*', async route => {
    const path = new URL(route.request().url()).pathname
    const body = route.request().postData() ? route.request().postDataJSON() : null
    requests.push({ path, body })
    const data = body?.data
    let value: unknown
    if (path.endsWith('/agentBean/list')) value = configs
    else if (path.endsWith('/agentBean/create')) {
      configs.push({ ...data, iid: 42, agentKey: 'created-e2e' })
      value = { ...data, agentKey: 'created-e2e' }
    } else if (path.endsWith('/agentBean/update')) {
      Object.assign(configs.find(item => item.iid === data.iid)!, data)
      value = true
    } else if (path.endsWith('/agentToolBean/list')) value = tools
    else if (path.endsWith('/agentToolBean/save')) { tools.push({ ...data, iid: 51 }); value = data }
    else if (path.endsWith('/agentRunBean/list')) value = runs
    else if (path.endsWith('/agentRunBean/detail')) value = { run, steps }
    else if (path.endsWith('/agentRunBean/streamResume')) {
      // 默认不提供续播流：让前端按「断流、未收到最终结果」收尾，
      // 保持既有「不自动重放执行请求」用例的语义；专门的续播用例会覆盖这个 route。
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ resp_code: 1, resp_msg: '续播不可用' }) })
      return
    }
    else throw new Error(`未定义的 Agent 测试接口: ${path}`)
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ resp_code: 0, datas: value }) })
  })
  return { requests, finish: () => { runs = [run] } }
}

test('分段 HTTP 事件在完成前展示步骤，失败工具可定位并查看历史', async ({ page }, testInfo) => {
  const state = await setup(page)
  let stream: ServerResponse | undefined
  const incoming: { body: string; token: string | undefined }[] = []
  const server = createServer((req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*')
    res.setHeader('Access-Control-Allow-Headers', 'content-type,x-access-token,account')
    if (req.method === 'OPTIONS') { res.end(); return }
    let body = ''
    req.on('data', chunk => { body += chunk.toString() })
    req.on('end', () => {
      incoming.push({ body, token: req.headers['x-access-token'] as string | undefined })
      stream = res
      res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-cache' })
      res.write(frame('agent_start', { runKey: run.runKey }))
      res.write(frame('agent_step', steps[0]))
    })
  })
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  const address = server.address() as { port: number }
  await page.route('**/ai/api/agentBean/streamRun', route => route.continue({ url: `http://127.0.0.1:${address.port}/streamRun` }))
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  try {
    await page.goto('/#/agents')
    await expect(page.getByRole('main').getByRole('heading', { name: 'Agent 工作台', exact: true })).toBeVisible()
    await expect(page.getByRole('menuitem', { name: 'Agent 工作台', exact: true })).toBeVisible()
    await page.getByRole('textbox', { name: '运行问题' }).fill('查询当前时间')
    await page.getByRole('button', { name: '开始运行', exact: true }).click()
    await expect(page.getByText('准备查询时间', { exact: true })).toBeVisible()
    await expect(page.getByText('工具执行结束', { exact: true })).toHaveCount(0)
    expect(incoming).toHaveLength(1)
    expect(JSON.parse(incoming[0].body)).toEqual({ data: { agentKey: config.agentKey, question: run.question } })
    expect(incoming[0].token).toBe('e2e-token')

    stream!.write(frame('agent_step', steps[1]))
    await expect(page.getByText('工具暂不可用', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '定位到第 2 步错误' }).click()
    await expect(page).toHaveURL(/#\/agents$/)
    await expect(page.locator('[data-step-no="2"]')).toBeFocused()
    const locateError = page.getByRole('button', { name: '定位到第 2 步错误' })
    await locateError.focus()
    await page.keyboard.press('Enter')
    await expect(page.locator('[data-step-no="2"]')).toBeFocused()
    await expect(page).toHaveURL(/#\/agents$/)
    state.finish()
    stream!.end(frame('agent_step', steps[2]) + frame('agent_done', { run, steps }))
    await expect(page.getByText('工具执行结束', { exact: true }).first()).toBeVisible()
    await expect(page.getByText(/0\.00123/).first()).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('agent-steps.png'), fullPage: true })
    await page.getByRole('main').getByRole('heading', { name: 'Agent 工作台', exact: true }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath('agent-desktop.png'), fullPage: true })
    for (const width of [320, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 900 })
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
    }
    await page.setViewportSize({ width: 320, height: 900 })
    await page.getByRole('main').getByRole('heading', { name: 'Agent 工作台', exact: true }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath('agent-mobile.png'), fullPage: true })
    const history = page.getByRole('region', { name: '运行历史', exact: true })
    await history.getByRole('button', { name: /查询当前时间.*查看详情/ }).click()
    const detail = page.getByRole('region', { name: '历史运行详情', exact: true })
    await expect(detail.getByTestId('final-answer')).toHaveText('工具执行结束')
    expect(state.requests.find(request => request.path.endsWith('/agentRunBean/detail'))?.body).toEqual({ data: { runKey: run.runKey } })
    expect(errors).toEqual([])
    expect(state.requests.some(request => request.path.endsWith('/agentRunBean/list'))).toBe(true)
  } finally {
    stream?.end()
    server.closeAllConnections()
    await new Promise<void>(resolve => server.close(() => resolve()))
  }
})

test('新建配置后重新取得主键并保存真实工具请求', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/#/agents')
  await page.getByRole('button', { name: '新建 Agent', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('textbox', { name: '名称（必填，最多 100 字）', exact: true }).fill('值班助手')
  await dialog.getByRole('combobox', { name: '模型（必填）' }).selectOption('1')
  await dialog.getByRole('button', { name: '保存 Agent', exact: true }).click()
  await expect(page.getByRole('heading', { name: '值班助手', exact: true })).toBeVisible()
  const creation = state.requests.find(request => request.path.endsWith('/agentBean/create'))
  expect(creation?.body.data).toMatchObject({ name: '值班助手', modelIid: 1 })
  expect(state.requests.some(request => request.path.endsWith('/agentToolBean/list') && request.body.data.agentIid === 42)).toBe(true)
  await page.getByRole('button', { name: '添加工具', exact: true }).click()
  const tools = page.getByRole('region', { name: 'Agent 工具' })
  await tools.getByRole('textbox', { name: '工具名称（ASCII function name）' }).fill('current_time')
  await tools.getByRole('button', { name: '保存工具', exact: true }).click()
  await expect(page.getByText('current_time', { exact: true }).first()).toBeVisible()
  const tool = state.requests.find(request => request.path.endsWith('/agentToolBean/save'))
  expect(tool?.body.data).toMatchObject({ agentIid: 42, name: 'current_time', toolType: 'builtin' })
  expect(JSON.parse(tool?.body.data.jsonSchema)).toMatchObject({ type: 'object', additionalProperties: false })
})

test('终态归属异常保留步骤、释放界面且不自动重试', async ({ page }) => {
  await setup(page)
  let calls = 0
  await page.route('**/ai/api/agentBean/streamRun', async route => {
    calls++
    await route.fulfill({ contentType: 'text/event-stream', body:
      frame('agent_start', { runKey: run.runKey }) + frame('agent_step', steps[0]) +
      frame('agent_done', { run: { ...run, agentIid: 999 }, steps }) })
  })
  await page.goto('/#/agents')
  await page.getByRole('textbox', { name: '运行问题' }).fill('查询当前时间')
  await page.getByRole('button', { name: '开始运行', exact: true }).click()
  await expect(page.getByText(/运行详情与当前 Agent 不匹配/)).toBeVisible()
  await expect(page.getByText('准备查询时间', { exact: true })).toBeVisible()
  await expect(page.getByTestId('final-answer')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '开始运行', exact: true })).toBeEnabled()
  expect(calls).toBe(1)
})

test('断流后按 Last-Event-ID 续播补齐步骤，且不重放收费请求', async ({ page }) => {
  await setup(page)
  const frameWithId = (event: string, value: unknown, id: number) =>
    `event: ${event}\nid: ${id}\ndata: ${JSON.stringify(value)}\n\n`
  let streamCalls = 0
  let resumeBody: any = null
  await page.route('**/ai/api/agentBean/streamRun', async route => {
    streamCalls += 1
    // 只发开始 + 第 1 步就断流，模拟网络中断
    await route.fulfill({ contentType: 'text/event-stream', body:
      frame('agent_start', { runKey: run.runKey }) + frameWithId('agent_step', steps[0], 1) })
  })
  await page.route('**/ai/api/agentRunBean/streamResume', async route => {
    resumeBody = route.request().postDataJSON()
    // 补发帧可能与断线前重叠（第 1 步），前端应按 SSE id 去重
    await route.fulfill({ contentType: 'text/event-stream', body:
      frameWithId('agent_step', steps[0], 1) + frameWithId('agent_step', steps[1], 2)
      + frameWithId('agent_step', steps[2], 3) + frame('agent_done', { run, steps }) })
  })
  await page.goto('/#/agents')
  await page.getByRole('textbox', { name: '运行问题' }).fill('查询当前时间')
  await page.getByRole('button', { name: '开始运行', exact: true }).click()
  await expect(page.getByTestId('final-answer')).toContainText('工具执行结束')
  expect(streamCalls).toBe(1)
  expect(resumeBody).toEqual({ data: { runKey: run.runKey, lastEventId: 1 } })
  expect(page.getByText('准备查询时间', { exact: true })).toBeVisible()
})

test('服务端拒绝和断流不显示成功、不自动发起第二次收费请求', async ({ page }) => {
  await setup(page)
  let calls = 0
  await page.route('**/ai/api/agentBean/streamRun', async route => {
    calls += 1
    await route.fulfill({ contentType: 'text/event-stream', body: calls === 1
      ? frame('agent_error', { message: '当前 Agent 已有运行中的任务' })
      : frame('agent_start', { runKey: run.runKey }) + frame('agent_step', steps[0]) })
  })
  await page.goto('/#/agents')
  await page.getByRole('textbox', { name: '运行问题' }).fill('查询当前时间')
  await page.getByRole('button', { name: '开始运行', exact: true }).click()
  await expect(page.getByText('当前 Agent 已有运行中的任务', { exact: true }).first()).toBeVisible()
  expect(calls).toBe(1)
  await page.getByRole('button', { name: '开始运行', exact: true }).click()
  await expect(page.getByText(/尚未收到最终结果/).first()).toBeVisible()
  await expect(page.getByText('准备查询时间', { exact: true })).toBeVisible()
  await expect(page.getByText('工具执行结束', { exact: true })).toHaveCount(0)
  expect(calls).toBe(2)
})
