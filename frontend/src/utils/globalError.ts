import { reactive } from 'vue'
import { isChunkLoadError } from './preloadRecovery'

/**
 * 全局错误中心：接住 Vue 渲染/生命周期异常与未处理的 Promise rejection，
 * 统一显示一条可关闭的顶部横幅，并给出错误编号，便于用户反馈与日志检索。
 *
 * 不接管的情况：
 * - chunk 加载失败（由 preloadRecovery 自动重载，横幅只会添乱）
 * - axios 错误（拦截器已经弹过 toast 或按 silent 静默）
 * - AbortError / CanceledError（请求被主动取消属于正常流程）
 * - ResizeObserver 循环告警（浏览器噪声，不影响功能）
 */

export type GlobalErrorSource = 'vue' | 'promise'

export interface GlobalErrorState {
  visible: boolean
  errorId: string
  message: string
  count: number
  at: number
}

export interface GlobalErrorCenter {
  state: GlobalErrorState
  report(error: unknown, source?: GlobalErrorSource, info?: string): void
  dismiss(): void
}

interface CenterOptions {
  now?: () => number
  dedupeWindowMs?: number
  maxMessageLength?: number
  logger?: Pick<Console, 'error'>
  random?: () => number
}

const DEFAULT_DEDUPE_WINDOW_MS = 8000
const DEFAULT_MAX_MESSAGE_LENGTH = 160

function messageOf(error: unknown): string {
  if (error instanceof Error) {
    return error.message || error.name
  }
  if (typeof error === 'string') {
    return error
  }
  return ''
}

function isIgnored(error: unknown): boolean {
  if (isChunkLoadError(error)) {
    return true
  }
  const candidate = error as
    | { isAxiosError?: boolean; name?: string }
    | null
    | undefined
  if (candidate?.isAxiosError) {
    return true
  }
  if (candidate?.name === 'AbortError' || candidate?.name === 'CanceledError') {
    return true
  }
  return /ResizeObserver loop (limit exceeded|completed with undelivered notifications)/.test(
    messageOf(error),
  )
}

function nextErrorId(at: number, random: () => number): string {
  const suffix = Math.floor(random() * 0x10000)
    .toString(16)
    .toUpperCase()
    .padStart(4, '0')
  return `E-${at.toString(36).toUpperCase()}-${suffix}`
}

export function createErrorCenter(options: CenterOptions = {}): GlobalErrorCenter {
  const now = options.now ?? Date.now
  const dedupeWindowMs = options.dedupeWindowMs ?? DEFAULT_DEDUPE_WINDOW_MS
  const maxMessageLength = options.maxMessageLength ?? DEFAULT_MAX_MESSAGE_LENGTH
  const logger = options.logger ?? console
  const random = options.random ?? Math.random

  const state = reactive<GlobalErrorState>({
    visible: false,
    errorId: '',
    message: '',
    count: 0,
    at: 0,
  })

  let lastKey = ''
  let lastAt = 0

  function report(error: unknown, source: GlobalErrorSource = 'promise', info?: string): void {
    if (isIgnored(error)) {
      return
    }
    const raw = messageOf(error)
    const message = raw.length > maxMessageLength ? `${raw.slice(0, maxMessageLength)}…` : raw
    const key = message || 'unknown'
    const at = now()

    logger.error(`[GlobalError] source=${source}${info ? ` info=${info}` : ''}`, error)

    // 同一个错误在窗口内重复出现时只累计次数，不换编号——
    // 用户反馈时看到的编号应始终指向同一个问题。
    if (lastKey === key && at - lastAt < dedupeWindowMs) {
      state.visible = true
      state.count += 1
      state.at = at
      return
    }

    lastKey = key
    lastAt = at
    state.visible = true
    state.errorId = nextErrorId(at, random)
    state.message = message || '未知错误'
    state.count = 1
    state.at = at
  }

  function dismiss(): void {
    state.visible = false
  }

  return { state, report, dismiss }
}

export const globalErrorCenter = createErrorCenter()

interface ErrorAppLike {
  config: {
    // instance 用 any：Vue 的 App.config.errorHandler 签名收窄为 ComponentPublicInstance，
    // 这里要同时接受 Vue 实例与测试里的极简对象，用 unknown 会因逆变无法赋值。
    errorHandler?: ((error: unknown, instance: any, info: string) => void) | null
  }
}

interface ErrorEventTarget {
  addEventListener(type: string, listener: (event: Event) => void): void
  removeEventListener?(type: string, listener: (event: Event) => void): void
}

interface InstallOptions {
  center?: GlobalErrorCenter
  target?: ErrorEventTarget
}

export function installGlobalErrorHandling(app: ErrorAppLike, options: InstallOptions = {}): () => void {
  const center = options.center ?? globalErrorCenter
  const target = options.target ?? window
  const previous = app.config.errorHandler

  const handler = (error: unknown, instance: unknown, info: string) => {
    // 保留之前注册的处理器（测试或插件可能先装了一个）
    previous?.(error, instance, info)
    center.report(error, 'vue', info)
  }
  app.config.errorHandler = handler

  const onRejection = (event: Event) => {
    center.report((event as PromiseRejectionEvent).reason, 'promise')
  }
  target.addEventListener('unhandledrejection', onRejection)

  return () => {
    if (app.config.errorHandler === handler) {
      app.config.errorHandler = previous ?? undefined
    }
    target.removeEventListener?.('unhandledrejection', onRejection)
  }
}
