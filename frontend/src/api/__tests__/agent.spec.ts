import { afterEach, describe, expect, it, vi } from 'vitest'
import { agentApi, streamAgentRun, type AgentStreamCallbacks } from '../agent'
import axios from '../axios'

vi.mock('../axios', () => ({ default: { post: vi.fn() } }))
vi.mock('js-cookie', () => ({ default: { get: (key: string) => ({ loginToken: 'test-token', uaccess: 'test-account' } as Record<string, string>)[key] } }))

const start = 'event:agent_start\ndata:{"runKey":"run-1"}\n\n'
const step = { runKey: 'run-1', stepNo: 1, iteration: 1, stepType: 'llm', status: 'success', toolResult: '你好' }
const detail = { run: { runKey: 'run-1', agentIid: 1, question: '问题', status: 'succeeded' }, steps: [step] }
const frame = (event: string, data: unknown) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`
const payload = { agentKey: 'agent-1', question: '问题' }
const callbacks = (): AgentStreamCallbacks => ({ onStart: vi.fn(), onStep: vi.fn(), onDone: vi.fn(), onError: vi.fn() })

function response(chunks: Uint8Array[]) {
  return new Response(new ReadableStream({ start(controller) {
    chunks.forEach(chunk => controller.enqueue(chunk))
    controller.close()
  } }), { headers: { 'Content-Type': 'text/event-stream; charset=utf-8' } })
}
const encode = (text: string) => new TextEncoder().encode(text)
async function consume(text: string, cb = callbacks()) {
  vi.stubGlobal('fetch', vi.fn(async () => response([encode(text)])))
  streamAgentRun(payload, cb)
  await vi.waitFor(() => expect(Number((cb.onDone as any).mock.calls.length) + Number((cb.onError as any).mock.calls.length)).toBe(1))
  return cb
}

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

describe('Agent API 契约', () => {
  it('CRUD 和历史接口使用 HNSP data 包装', () => {
    agentApi.create({ name: '助手' })
    agentApi.update({ iid: 1, name: '新名称' })
    agentApi.delete(1)
    agentApi.listTools(1)
    agentApi.saveTool({ agentIid: 1, name: 'clock', toolType: 'builtin', jsonSchema: '{}' })
    agentApi.deleteTool(2)
    agentApi.listRuns(1)
    agentApi.detail('run-1')
    expect(axios.post).toHaveBeenCalledWith('/ai/api/agentBean/create', { data: { name: '助手' } })
    expect(axios.post).toHaveBeenCalledWith('/ai/api/agentBean/update', { data: { iid: 1, name: '新名称' } })
    expect(axios.post).toHaveBeenCalledWith('/ai/api/agentBean/delete', { data: { iid: 1 } })
    expect(axios.post).toHaveBeenCalledWith('/ai/api/agentToolBean/list', { data: { agentIid: 1 } })
    expect(axios.post).toHaveBeenCalledWith('/ai/api/agentToolBean/save', { data: expect.objectContaining({ name: 'clock' }) })
    expect(axios.post).toHaveBeenCalledWith('/ai/api/agentToolBean/delete', { data: { iid: 2 } })
    expect(axios.post).toHaveBeenCalledWith('/ai/api/agentRunBean/list', { data: { agentIid: 1 } })
    expect(axios.post).toHaveBeenCalledWith('/ai/api/agentRunBean/detail', { data: { runKey: 'run-1' } })
  })

  it('仅携带平台登录头，顺序派发事件，不依赖 Dify', async () => {
    const cb = await consume(start + frame('agent_step', step) + frame('agent_done', detail))
    expect(fetch).toHaveBeenCalledWith('/ai/api/agentBean/streamRun', expect.objectContaining({
      body: JSON.stringify({ data: payload }),
      headers: expect.objectContaining({ 'x-access-token': 'test-token', account: 'test-account' }),
    }))
    expect(cb.onStart).toHaveBeenCalledWith('run-1')
    expect(cb.onStep).toHaveBeenCalledWith(step)
    expect(cb.onDone).toHaveBeenCalledWith(detail)
    expect(cb.onError).not.toHaveBeenCalled()
  })

  it('逐字节解码中文、CRLF、注释心跳与多行 data', async () => {
    const text = ': heartbeat\r\n\r\n' + start.replace(/\n/g, '\r\n')
      + frame('agent_step', step).replace(/\n/g, '\r\n')
      + `event: agent_done\r\ndata: {"run":${JSON.stringify(detail.run)},\r\ndata: "steps":[]}\r\n\r\n`
    vi.stubGlobal('fetch', vi.fn(async () => response([...encode(text)].map(byte => new Uint8Array([byte])))))
    const cb = callbacks()
    streamAgentRun(payload, cb)
    await vi.waitFor(() => expect(cb.onDone).toHaveBeenCalledTimes(1))
    expect(cb.onStep).toHaveBeenCalledWith(step)
    expect(cb.onError).not.toHaveBeenCalled()
  })

  it('未知事件和无 event 的数据帧不会沿用上一帧类型', async () => {
    const cb = await consume(start + frame('heartbeat', { alive: true })
      + 'data: {"runKey":"wrong-run"}\n\n' + frame('agent_step', step) + frame('agent_done', detail))
    expect(cb.onStart).toHaveBeenCalledTimes(1)
    expect(cb.onStep).toHaveBeenCalledTimes(1)
    expect(cb.onDone).toHaveBeenCalledTimes(1)
    expect(cb.onError).not.toHaveBeenCalled()
  })

  it('EOF 不能冒充运行成功，保留此前步骤', async () => {
    const cb = await consume(start + frame('agent_step', step))
    expect(cb.onStep).toHaveBeenCalledTimes(1)
    expect(cb.onDone).not.toHaveBeenCalled()
    expect(cb.onError).toHaveBeenCalledWith(expect.stringContaining('尚未收到最终结果'))
  })

  it('failed 的业务终态照常返回，不抹掉失败步骤', async () => {
    const failed = { ...detail, run: { ...detail.run, status: 'failed', errorMessage: '模型调用失败' } }
    const cb = await consume(start + frame('agent_done', failed))
    expect(cb.onDone).toHaveBeenCalledWith(failed)
    expect(cb.onError).not.toHaveBeenCalled()
  })

  it.each([
    ['坏 JSON', start + 'event: agent_step\ndata: {bad}\n\n'],
    ['跨运行事件', start + frame('agent_step', { ...step, runKey: 'other' })],
    ['未开始即完成', frame('agent_done', detail)],
    ['缺少终态', start + frame('agent_done', { ...detail, run: { ...detail.run, status: 'running' } })],
  ])('%s 只报一次协议错误', async (_, text) => {
    const cb = await consume(text)
    expect(cb.onDone).not.toHaveBeenCalled()
    expect(cb.onError).toHaveBeenCalledTimes(1)
  })

  it('服务端错误后忽略完成帧，不自动重放收费请求', async () => {
    const cb = await consume(frame('agent_error', { message: 'Agent 不属于当前用户' }) + start + frame('agent_done', detail))
    expect(cb.onError).toHaveBeenCalledWith('Agent 不属于当前用户')
    expect(cb.onDone).not.toHaveBeenCalled()
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it.each([401, 503])('HTTP %s 错误不进入事件读取', async status => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('private upstream details', { status })))
    const cb = callbacks()
    streamAgentRun(payload, cb)
    await vi.waitFor(() => expect(cb.onError).toHaveBeenCalledTimes(1))
    expect(cb.onError).not.toHaveBeenCalledWith(expect.stringContaining('private'))
  })

  it('HTTP 200 的非 SSE 错误页也拒绝', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { headers: { 'Content-Type': 'application/json' } })))
    const cb = callbacks()
    streamAgentRun(payload, cb)
    await vi.waitFor(() => expect(cb.onError).toHaveBeenCalledTimes(1))
    expect(cb.onDone).not.toHaveBeenCalled()
  })

  it('停止接收后迟到响应不触发任何回调', async () => {
    let resolve!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(r => { resolve = r })))
    const cb = callbacks()
    const stop = streamAgentRun(payload, cb)
    stop()
    resolve(response([encode(start + frame('agent_done', detail))]))
    await new Promise(r => setTimeout(r, 0))
    expect(cb.onStart).not.toHaveBeenCalled()
    expect(cb.onDone).not.toHaveBeenCalled()
    expect(cb.onError).not.toHaveBeenCalled()
  })

  it('事件流静默超时，保留开始事件且不假报完成', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn(async () => new Response(new ReadableStream({
      start(controller) { controller.enqueue(encode(start)) },
    }), { headers: { 'Content-Type': 'text/event-stream' } })))
    const cb = callbacks()
    streamAgentRun(payload, cb)
    await vi.advanceTimersByTimeAsync(1)
    expect(cb.onStart).toHaveBeenCalledWith('run-1')
    await vi.advanceTimersByTimeAsync(150_000)
    expect(cb.onError).toHaveBeenCalledTimes(1)
    expect(cb.onDone).not.toHaveBeenCalled()
  })

  it('超大未结束事件有内存上限', async () => {
    const cb = await consume(start + 'data: ' + 'x'.repeat(1_048_576))
    expect(cb.onError).toHaveBeenCalledTimes(1)
    expect(cb.onDone).not.toHaveBeenCalled()
  })

  it('首响应超时只通知一次并终止请求', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
    const cb = callbacks()
    streamAgentRun(payload, cb)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(cb.onError).toHaveBeenCalledTimes(1)
    expect((fetch as any).mock.calls[0][1].signal.aborted).toBe(true)
    await vi.advanceTimersByTimeAsync(150_000)
    expect(cb.onError).toHaveBeenCalledTimes(1)
  })
})
