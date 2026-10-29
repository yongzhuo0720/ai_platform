/**
 * 直连模型流式客户端（OpenAI 兼容协议）。
 *
 * 与 Dify 的 chat-messages 协议不同，/chat/completions 是无状态接口：
 * 多轮上下文由前端把历史 messages 一并带给网关，网关收敛 model 字段后转发上游。
 * SSE 格式：data: {"choices":[{"delta":{"content"|"reasoning_content":...}}]}，
 * 流末尾可能有一个带 usage 的块（网关注入 stream_options.include_usage），
 * 以 data: [DONE] 结束。
 */

import { getModelsGatewayBase, gatewayAuthHeaders } from '@/stores/difyConfig'

export interface DirectChatMessage {
  role: 'system' | 'user' | 'assistant'
  /**
   * 文本内容；user 消息携带图片时为 OpenAI 多模态数组格式
   * [{type:'text',text},{type:'image_url',image_url:{url}}]。
   * 网关按 JSON 原样透传，多模态能力由上游模型决定。
   */
  content: string | Array<{ type: 'text'; text: string } | { type: 'image_url'; image_url: { url: string } }>
}

/**
 * 附加请求参数：知识库增强（服务端 RAG）。
 * 网关会用最后一条 user 消息在指定知识库里做相似度检索，
 * 把命中片段注入 system 上下文后转发；kb_* 字段不会透传到上游模型。
 */
export interface DirectChatExtras {
  /** 知识库名（kbchunkbean.kb_name），空表示不启用检索 */
  kbName?: string
  /** 检索条数，网关默认 4，上限 20 */
  kbTopK?: number
}

export interface DirectStreamOptions {
  onChunk: (text: string) => void
  onThinking?: (text: string) => void
  onComplete?: () => void
  onError?: (error: Error) => void
}

/** 解析网关错误响应，优先取 JSON 里的 message 字段 */
const parseGatewayError = async (response: Response): Promise<Error> => {
  const text = await response.text().catch(() => '')
  try {
    const parsed = JSON.parse(text)
    if (parsed?.message) {
      return new Error(parsed.message)
    }
  } catch {
    // 非 JSON 响应体，使用原文
  }
  return new Error(text || `请求失败 (${response.status})`)
}

/**
 * 发送直连模型流式对话。
 * @param modelIid 模型配置的 iid（网关路由键）
 * @param messages OpenAI 格式的完整上下文（含本轮用户消息）
 * @param extras 知识库增强参数（服务端检索注入，可选）
 * @returns 取消函数，调用后中止请求
 */
export const streamDirectChat = (
  modelIid: string,
  messages: DirectChatMessage[],
  options: DirectStreamOptions,
  extras?: DirectChatExtras
): (() => void) => {
  const controller = new AbortController()
  const { onChunk, onThinking, onComplete, onError } = options

  let completeCalled = false
  let errorCalled = false
  let firstByteTimer: ReturnType<typeof setTimeout> | null = null
  let idleTimer: ReturnType<typeof setTimeout> | null = null

  const clearTimers = () => {
    if (firstByteTimer) {
      clearTimeout(firstByteTimer)
      firstByteTimer = null
    }
    if (idleTimer) {
      clearTimeout(idleTimer)
      idleTimer = null
    }
  }
  const resetIdleTimer = () => {
    if (idleTimer) clearTimeout(idleTimer)
    idleTimer = setTimeout(() => {
      controller.abort()
      fail(new Error('响应流空闲超时：90 秒内无新数据'))
    }, 90000)
  }
  const complete = () => {
    if (completeCalled || errorCalled) return
    completeCalled = true
    clearTimers()
    onComplete?.()
  }
  const fail = (err: Error) => {
    if (errorCalled || completeCalled) return
    errorCalled = true
    clearTimers()
    onError?.(err)
  }

  // 30 秒未收到首字节则中止（服务不可达时常悬挂）
  firstByteTimer = setTimeout(() => {
    controller.abort()
    fail(new Error('请求超时：30 秒内未收到首个响应字节'))
  }, 30000)

  const run = async () => {
    const body: Record<string, unknown> = { messages, stream: true }
    if (extras?.kbName) {
      body.kb_name = extras.kbName
      if (extras.kbTopK) {
        body.kb_top_k = extras.kbTopK
      }
    }
    const response = await fetch(`${getModelsGatewayBase()}/${modelIid}/chat/completions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...gatewayAuthHeaders(),
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    })

    if (!response.ok) {
      throw await parseGatewayError(response)
    }

    // X-KB-Hits：本轮实际注入的知识库片段数（0 = 未命中或检索降级）
    const kbHits = response.headers.get('X-KB-Hits')
    if (kbHits !== null) {
      console.debug('[directModel] 知识库注入片段数:', kbHits)
    }

    const reader = response.body?.getReader()
    if (!reader) {
      throw new Error('无法读取响应流')
    }

    const decoder = new TextDecoder()
    let buffer = ''
    let eventType = ''
    // reasoning_content 是增量片段；上层回调按"覆盖式"使用（thinking.value = thought），
    // 因此这里累积成完整思考文本后再回调
    let reasoningBuffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      if (!value?.byteLength) continue
      if (firstByteTimer) {
        clearTimeout(firstByteTimer)
        firstByteTimer = null
      }
      resetIdleTimer()

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split(/\r\n|\r|\n/)
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (line.startsWith('event: ')) {
          eventType = line.slice(7).trim()
          continue
        }
        if (!line.startsWith('data:')) {
          continue
        }
        const payload = line.slice(5).trim()
        if (!payload || payload === '[DONE]') {
          if (payload === '[DONE]') {
            complete()
            return
          }
          continue
        }
        let parsed: any
        try {
          parsed = JSON.parse(payload)
        } catch {
          continue
        }
        if (eventType === 'error' || parsed.error) {
          const err = parsed.error
          const message =
            (typeof err === 'string' ? err : err?.message) ||
            parsed.message ||
            '模型调用失败，请稍后重试'
          const code = typeof err === 'object' && err?.code ? ` (${err.code})` : ''
          throw new Error(`${message}${code}`)
        }
        const delta = parsed.choices?.[0]?.delta
        if (!delta) {
          continue
        }
        const reasoning = delta.reasoning_content ?? delta.reasoning
        if (reasoning) {
          reasoningBuffer += reasoning
          onThinking?.(reasoningBuffer)
        }
        if (delta.content) {
          onChunk(delta.content)
        }
      }
    }
    complete()
  }

  run().catch((err) => {
    if (err?.name === 'AbortError') {
      // 主动取消：由调用方通过 token 校验静默处理
      complete()
      return
    }
    fail(err instanceof Error ? err : new Error(String(err)))
  })

  return () => {
    controller.abort()
    clearTimers()
  }
}
