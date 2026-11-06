import { describe, expect, it, vi } from 'vitest'
import {
  createErrorCenter,
  installGlobalErrorHandling,
  type GlobalErrorCenter,
} from '../globalError'

const silentLogger = () => ({ error: vi.fn() })

const center = (options: { now?: () => number } = {}): GlobalErrorCenter =>
  createErrorCenter({
    now: options.now ?? (() => 1_000),
    logger: silentLogger(),
    random: () => 0.5,
  })

function fakeTarget() {
  let handler: ((event: Event) => void) | null = null
  const target = {
    addEventListener(type: string, listener: (event: Event) => void) {
      if (type === 'unhandledrejection') handler = listener
    },
    removeEventListener(type: string, listener: (event: Event) => void) {
      if (type === 'unhandledrejection' && handler === listener) handler = null
    },
  }
  return {
    target,
    hasHandler: () => handler !== null,
    fire(reason: unknown) {
      const event = new Event('unhandledrejection') as Event & { reason: unknown }
      event.reason = reason
      handler?.(event)
    },
  }
}

describe('globalError', () => {
  it('Vue 渲染异常进入横幅并生成错误编号', () => {
    const app = { config: { errorHandler: null as null | ((...args: unknown[]) => void) } }
    const errors = center()
    installGlobalErrorHandling(app, { center: errors, target: fakeTarget().target })

    app.config.errorHandler?.(new Error('render exploded'), null, 'render')

    expect(errors.state.visible).toBe(true)
    expect(errors.state.message).toBe('render exploded')
    expect(errors.state.count).toBe(1)
    expect(errors.state.errorId).toMatch(/^E-[0-9A-Z]+-[0-9A-F]{4}$/)
  })

  it('未处理的 Promise rejection 进入横幅', () => {
    const target = fakeTarget()
    const errors = center()
    installGlobalErrorHandling(
      { config: { errorHandler: null } },
      { center: errors, target: target.target },
    )

    target.fire(new Error('async failed'))

    expect(errors.state.visible).toBe(true)
    expect(errors.state.message).toBe('async failed')
  })

  it('chunk 加载失败交给 preloadRecovery，不重复提示', () => {
    const errors = center()
    errors.report(new Error('Failed to fetch dynamically imported module: /assets/x.js'), 'promise')
    expect(errors.state.visible).toBe(false)
  })

  it('axios 错误、主动取消与 ResizeObserver 噪声不进入横幅', () => {
    const errors = center()
    const axiosError = Object.assign(new Error('Request failed'), { isAxiosError: true })
    errors.report(axiosError, 'promise')
    const aborted = Object.assign(new Error('aborted'), { name: 'AbortError' })
    errors.report(aborted, 'promise')
    const canceled = Object.assign(new Error('canceled'), { name: 'CanceledError' })
    errors.report(canceled, 'promise')
    errors.report(new Error('ResizeObserver loop limit exceeded'), 'vue')
    expect(errors.state.visible).toBe(false)
  })

  it('窗口内重复错误累计次数并保持同一编号', () => {
    let now = 1_000
    const errors = center({ now: () => now })
    errors.report(new Error('same'), 'vue')
    const firstId = errors.state.errorId
    now += 3_000
    errors.report(new Error('same'), 'vue')

    expect(errors.state.count).toBe(2)
    expect(errors.state.errorId).toBe(firstId)
  })

  it('超出窗口后按新错误处理，编号与计数重置', () => {
    let now = 1_000
    const errors = center({ now: () => now })
    errors.report(new Error('same'), 'vue')
    const firstId = errors.state.errorId
    now += 9_000
    errors.report(new Error('same'), 'vue')

    expect(errors.state.count).toBe(1)
    expect(errors.state.errorId).not.toBe(firstId)
  })

  it('关闭后同一错误再次出现会重新显示', () => {
    const errors = center()
    errors.report(new Error('boom'), 'vue')
    errors.dismiss()
    expect(errors.state.visible).toBe(false)

    errors.report(new Error('boom'), 'vue')
    expect(errors.state.visible).toBe(true)
    expect(errors.state.count).toBe(2)
  })

  it('超长信息截断，非 Error 的 rejection 给通用文案', () => {
    const errors = center()
    errors.report('x'.repeat(300), 'vue')
    expect(errors.state.message).toHaveLength(161)
    expect(errors.state.message.endsWith('…')).toBe(true)

    errors.report({ weird: true }, 'promise')
    expect(errors.state.message).toBe('未知错误')
  })

  it('卸载后移除 rejection 监听并恢复原有 errorHandler', () => {
    const target = fakeTarget()
    const previous = vi.fn()
    const app = { config: { errorHandler: previous } }
    const errors = center()
    const uninstall = installGlobalErrorHandling(app, { center: errors, target: target.target })

    expect(target.hasHandler()).toBe(true)
    uninstall()

    expect(target.hasHandler()).toBe(false)
    expect(app.config.errorHandler).toBe(previous)
    target.fire(new Error('late'))
    expect(errors.state.visible).toBe(false)
  })

  it('先注册的 errorHandler 仍会被调用', () => {
    const previous = vi.fn()
    const app = { config: { errorHandler: previous } }
    const errors = center()
    installGlobalErrorHandling(app, { center: errors, target: fakeTarget().target })

    app.config.errorHandler?.(new Error('both'), null, 'setup')

    expect(previous).toHaveBeenCalledTimes(1)
    expect(errors.state.visible).toBe(true)
  })
})
