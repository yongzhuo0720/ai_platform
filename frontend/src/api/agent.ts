import Cookies from 'js-cookie'
import axios from './axios'

export interface AgentConfig {
  iid?: number | null
  agentKey: string
  name: string
  description?: string | null
  systemPrompt?: string | null
  modelIid?: number | null
  maxIterations?: number | null
  tokenBudget?: number | null
}

export interface AgentTool {
  iid?: number | null
  agentIid: number
  name: string
  description?: string | null
  toolType: 'builtin' | 'kb_search' | 'http'
  jsonSchema: string
}

export interface AgentStep {
  runKey: string
  stepNo: number
  iteration: number
  stepType: string
  toolName?: string | null
  toolArgs?: string | null
  toolResult?: string | null
  status: string
  promptTokens?: number | null
  completionTokens?: number | null
  costCents?: number | null
  latencyMs?: number | null
  errorMessage?: string | null
  requestId?: string | null
}

export interface AgentRun {
  runKey: string
  agentIid: number
  status: string
  question: string
  answer?: string | null
  errorMessage?: string | null
  iterations?: number | null
  totalTokens?: number | null
  promptTokens?: number | null
  completionTokens?: number | null
  costCents?: number | null
  durationMs?: number | null
  startedAt?: number | null
}

export interface AgentRunDetail {
  run: AgentRun
  steps: AgentStep[]
}

export const agentApi = {
  list: () => axios.post('/ai/api/agentBean/list'),
  create: (payload: Partial<AgentConfig>) => axios.post('/ai/api/agentBean/create', { data: payload }),
  update: (payload: Partial<AgentConfig>) => axios.post('/ai/api/agentBean/update', { data: payload }),
  delete: (iid: number) => axios.post('/ai/api/agentBean/delete', { data: { iid } }),
  listTools: (agentIid: number) => axios.post('/ai/api/agentToolBean/list', { data: { agentIid } }),
  saveTool: (payload: AgentTool) => axios.post('/ai/api/agentToolBean/save', { data: payload }),
  deleteTool: (iid: number) => axios.post('/ai/api/agentToolBean/delete', { data: { iid } }),
  listRuns: (agentIid?: number) => axios.post('/ai/api/agentRunBean/list', { data: { agentIid } }),
  detail: (runKey: string) => axios.post('/ai/api/agentRunBean/detail', { data: { runKey } }),
}

export interface AgentResumeOptions {
  /** 最多续播次数，默认 6 */
  maxAttempts?: number
  /** 每次尝试续播前回调，用于 UI 展示「正在恢复」 */
  onAttempt?: (attempt: number, delayMs: number) => void
}

export interface AgentStreamCallbacks {
  onStart: (runKey: string) => void
  onStep: (step: AgentStep) => void
  onDone: (detail: AgentRunDetail) => void
  onError: (message: string) => void
  /**
   * 只有提供该字段才启用「断线续播」。续播只走只读接口
   * /api/agentRunBean/streamResume（按 Last-Event-ID 补发已落库步骤），
   * 绝不重新 POST /streamRun，因此不会重复执行模型、不会重复计费。
   */
  resume?: AgentResumeOptions
}

const STREAM_EVENTS = ['agent_start', 'agent_step', 'agent_done', 'agent_error']

/** 协议错误不续播（续播只会拿到同样坏的数据）；IO 断线才续播 */
class ProtocolError extends Error {}

export function streamAgentRun(
  payload: { agentKey: string; question: string },
  callbacks: AgentStreamCallbacks,
): () => void {
  let closed = false
  let timer: ReturnType<typeof setTimeout> | undefined
  let retryTimer: ReturnType<typeof setTimeout> | undefined
  let controller: AbortController | undefined
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
  let runKey = ''
  let lastEventId = 0
  let attempt = 0
  const seenIds = new Set<number>()
  const maxAttempts = callbacks.resume?.maxAttempts ?? 6

  const stop = () => {
    closed = true
    clearTimeout(timer)
    clearTimeout(retryTimer)
    controller?.abort()
    void reader?.cancel().catch(() => {})
  }
  const fail = (message: string) => {
    if (closed) return
    stop()
    callbacks.onError(message)
  }
  const armTimeout = (ms: number) => {
    clearTimeout(timer)
    timer = setTimeout(() => scheduleResume(
      '执行事件等待超时，请在运行历史中刷新状态；请勿直接重复运行'), ms)
  }

  const authHeaders = () => {
    const prefix = window.location.hash.startsWith('#/admin') ? 'admin_' : ''
    return {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      'x-access-token': Cookies.get(`${prefix}loginToken`) || 'login',
      account: Cookies.get(`${prefix}uaccess`) || '',
    }
  }

  const dispatch = (event: string, data: string, frameId?: number) => {
    if (closed || !STREAM_EVENTS.includes(event)) return
    // 补发流可能与断线前收到的最后一帧重叠，按 SSE id 去重
    if (typeof frameId === 'number' && frameId >= 0) {
      if (seenIds.has(frameId)) return
      seenIds.add(frameId)
      if (frameId > lastEventId) lastEventId = frameId
    }
    let value: any
    try {
      value = JSON.parse(data)
    } catch {
      throw new ProtocolError('bad json')
    }
    if (event === 'agent_error') {
      fail(typeof value?.message === 'string' ? value.message : 'Agent 执行失败')
    } else if (event === 'agent_start') {
      if (runKey || typeof value?.runKey !== 'string' || !value.runKey) throw new ProtocolError('invalid start')
      runKey = value.runKey
      callbacks.onStart(runKey)
    } else if (event === 'agent_step') {
      if (!runKey || value?.runKey !== runKey || !Number.isInteger(value?.stepNo) || value.stepNo < 1) {
        throw new ProtocolError('invalid step')
      }
      callbacks.onStep(value as AgentStep)
    } else {
      if (!runKey || value?.run?.runKey !== runKey || !Array.isArray(value?.steps)
        || !['succeeded', 'failed'].includes(value.run.status)) throw new ProtocolError('invalid done')
      stop()
      callbacks.onDone(value as AgentRunDetail)
    }
  }

  /** 断线后只续播，不重放执行请求 */
  function scheduleResume(reason: string) {
    if (closed) return
    if (!callbacks.resume || !runKey) {
      fail(reason)
      return
    }
    if (attempt >= maxAttempts) {
      fail('连接多次中断，已停止自动恢复；请在运行历史中查看结果')
      return
    }
    attempt += 1
    const delayMs = Math.min(8000, 500 * 2 ** (attempt - 1))
    callbacks.resume.onAttempt?.(attempt, delayMs)
    retryTimer = setTimeout(() => {
      void connect('/ai/api/agentRunBean/streamResume',
        JSON.stringify({ data: { runKey, lastEventId } }),
        { 'Last-Event-ID': String(lastEventId) })
    }, delayMs)
  }

  async function connect(url: string, body: string, extra: Record<string, string> = {}) {
    if (closed) return
    const localController = new AbortController()
    controller = localController
    let localReader: ReadableStreamDefaultReader<Uint8Array> | undefined
    try {
      const response = await fetch(url, {
        method: 'POST',
        credentials: 'same-origin',
        signal: localController.signal,
        headers: { ...authHeaders(), ...extra },
        body,
      })
      if (closed) {
        void response.body?.cancel().catch(() => {})
        return
      }
      if (!response.ok) {
        fail(attempt > 0
          ? '执行连接已中断，尚未收到最终结果；请在运行历史中刷新状态'
          : response.status === 401 || response.status === 403
            ? '登录已失效或没有权限，请重新登录'
            : `执行请求失败（HTTP ${response.status}），请稍后重试`)
        return
      }
      if (!response.headers.get('content-type')?.includes('text/event-stream') || !response.body) {
        fail(attempt > 0
          ? '执行连接已中断，尚未收到最终结果；请在运行历史中刷新状态'
          : '未收到执行事件流，请检查登录状态或服务配置')
        return
      }
      localReader = response.body.getReader()
      reader = localReader
      const decoder = new TextDecoder()
      let buffer = ''
      let event = ''
      let dataLines: string[] = []
      let frameSize = 0
      let frameId: number | undefined
      while (!closed) {
        armTimeout(150_000)
        const { value, done } = await localReader.read()
        if (closed) return
        buffer += decoder.decode(value, { stream: !done })
        if (buffer.length + frameSize > 1_048_576) throw new ProtocolError('frame too large')
        let match: RegExpExecArray | null
        while (!closed && (match = /\r\n|\r(?!$)|\n/.exec(buffer))) {
          const line = buffer.slice(0, match.index)
          buffer = buffer.slice(match.index + match[0].length)
          if (!line) {
            if (dataLines.length) dispatch(event, dataLines.join('\n'), frameId)
            event = ''
            dataLines = []
            frameSize = 0
            frameId = undefined
          } else if (!line.startsWith(':')) {
            frameSize += line.length
            const colon = line.indexOf(':')
            const field = colon < 0 ? line : line.slice(0, colon)
            const text = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
            if (field === 'event') event = text
            if (field === 'data') dataLines.push(text)
            if (field === 'id') {
              const parsed = Number(text)
              if (Number.isInteger(parsed) && parsed >= 0) frameId = parsed
            }
          }
        }
        if (done) {
          scheduleResume('执行连接已中断，尚未收到最终结果；请在运行历史中刷新状态')
          return
        }
      }
    } catch (error) {
      if (closed) return
      if (error instanceof ProtocolError) {
        fail('执行连接或事件解析失败，请在运行历史中查看结果')
      } else {
        scheduleResume('执行连接或事件解析失败，请在运行历史中查看结果')
      }
    } finally {
      clearTimeout(timer)
      try {
        localReader?.releaseLock()
      } catch {
        // 流已关闭时 releaseLock 会抛，忽略即可
      }
    }
  }

  armTimeout(30_000)
  void connect('/ai/api/agentBean/streamRun', JSON.stringify({ data: payload }))
  return stop
}
