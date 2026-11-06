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

export interface AgentStreamCallbacks {
  onStart: (runKey: string) => void
  onStep: (step: AgentStep) => void
  onDone: (detail: AgentRunDetail) => void
  onError: (message: string) => void
}

export function streamAgentRun(
  payload: { agentKey: string; question: string },
  callbacks: AgentStreamCallbacks,
): () => void {
  const controller = new AbortController()
  let closed = false
  let timer: ReturnType<typeof setTimeout> | undefined
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
  let runKey = ''
  const stop = () => {
    closed = true
    clearTimeout(timer)
    controller.abort()
    void reader?.cancel().catch(() => {})
  }
  const fail = (message: string) => {
    if (closed) return
    stop()
    callbacks.onError(message)
  }
  const armTimeout = (ms: number) => {
    clearTimeout(timer)
    timer = setTimeout(() => fail('执行事件等待超时，请在运行历史中刷新状态；请勿直接重复运行'), ms)
  }

  const dispatch = (event: string, data: string) => {
    if (closed || !['agent_start', 'agent_step', 'agent_done', 'agent_error'].includes(event)) return
    const value = JSON.parse(data)
    if (event === 'agent_error') {
      fail(typeof value?.message === 'string' ? value.message : 'Agent 执行失败')
    } else if (event === 'agent_start') {
      if (runKey || typeof value?.runKey !== 'string' || !value.runKey) throw new Error('invalid start')
      runKey = value.runKey
      callbacks.onStart(runKey)
    } else if (event === 'agent_step') {
      if (!runKey || value?.runKey !== runKey || !Number.isInteger(value?.stepNo) || value.stepNo < 1) {
        throw new Error('invalid step')
      }
      callbacks.onStep(value as AgentStep)
    } else {
      if (!runKey || value?.run?.runKey !== runKey || !Array.isArray(value?.steps)
        || !['succeeded', 'failed'].includes(value.run.status)) throw new Error('invalid done')
      stop()
      callbacks.onDone(value as AgentRunDetail)
    }
  }

  armTimeout(30_000)
  void (async () => {
    try {
      const prefix = window.location.hash.startsWith('#/admin') ? 'admin_' : ''
      const response = await fetch('/ai/api/agentBean/streamRun', {
        method: 'POST',
        credentials: 'same-origin',
        signal: controller.signal,
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          'x-access-token': Cookies.get(`${prefix}loginToken`) || 'login',
          account: Cookies.get(`${prefix}uaccess`) || '',
        },
        body: JSON.stringify({ data: payload }),
      })
      if (closed) {
        void response.body?.cancel().catch(() => {})
        return
      }
      if (!response.ok) {
        fail(response.status === 401 || response.status === 403
          ? '登录已失效或没有权限，请重新登录'
          : `执行请求失败（HTTP ${response.status}），请稍后重试`)
        return
      }
      if (!response.headers.get('content-type')?.includes('text/event-stream') || !response.body) {
        fail('未收到执行事件流，请检查登录状态或服务配置')
        return
      }
      reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let event = ''
      let dataLines: string[] = []
      let frameSize = 0
      while (!closed) {
        armTimeout(150_000)
        const { value, done } = await reader.read()
        if (closed) return
        buffer += decoder.decode(value, { stream: !done })
        if (buffer.length + frameSize > 1_048_576) throw new Error('frame too large')
        let match: RegExpExecArray | null
        while (!closed && (match = /\r\n|\r(?!$)|\n/.exec(buffer))) {
          const line = buffer.slice(0, match.index)
          buffer = buffer.slice(match.index + match[0].length)
          if (!line) {
            if (dataLines.length) dispatch(event, dataLines.join('\n'))
            event = ''
            dataLines = []
            frameSize = 0
          } else if (!line.startsWith(':')) {
            frameSize += line.length
            const colon = line.indexOf(':')
            const field = colon < 0 ? line : line.slice(0, colon)
            const text = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
            if (field === 'event') event = text
            if (field === 'data') dataLines.push(text)
          }
        }
        if (done) {
          fail('执行连接已中断，尚未收到最终结果；请在运行历史中刷新状态')
          return
        }
      }
    } catch {
      fail('执行连接或事件解析失败，请在运行历史中查看结果')
    } finally {
      clearTimeout(timer)
      reader?.releaseLock()
    }
  })()
  return stop
}
