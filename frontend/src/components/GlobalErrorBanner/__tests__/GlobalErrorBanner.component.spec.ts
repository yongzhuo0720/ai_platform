import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'

import GlobalErrorBanner from '../index.vue'
import { globalErrorCenter } from '@/utils/globalError'

enableAutoUnmount(afterEach)

const mountBanner = () => mount(GlobalErrorBanner, { global: { stubs: { teleport: true } } })

beforeEach(() => {
  globalErrorCenter.dismiss()
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})

afterEach(() => {
  globalErrorCenter.dismiss()
})

describe('GlobalErrorBanner', () => {
  it('没有错误时不渲染', () => {
    const wrapper = mountBanner()
    expect(wrapper.find('.global-error-banner').exists()).toBe(false)
  })

  it('上报后展示错误信息、编号与警示语义', async () => {
    const wrapper = mountBanner()
    globalErrorCenter.report(new Error('渲染失败'), 'vue')
    await wrapper.vm.$nextTick()

    const banner = wrapper.get('.global-error-banner')
    expect(banner.attributes('role')).toBe('alert')
    expect(banner.attributes('aria-live')).toBe('assertive')
    expect(banner.text()).toContain('渲染失败')
    expect(banner.text()).toContain(globalErrorCenter.state.errorId)
    expect(banner.text()).not.toContain('已出现')
  })

  it('重复错误展示出现次数', async () => {
    const wrapper = mountBanner()
    globalErrorCenter.report(new Error('重复错误'), 'vue')
    globalErrorCenter.report(new Error('重复错误'), 'vue')
    await wrapper.vm.$nextTick()

    expect(wrapper.get('.global-error-banner').text()).toContain('已出现 2 次')
  })

  it('点击关闭后横幅消失', async () => {
    const wrapper = mountBanner()
    globalErrorCenter.report(new Error('可以关闭'), 'vue')
    await wrapper.vm.$nextTick()

    await wrapper.get('.global-error-dismiss').trigger('click')

    expect(wrapper.find('.global-error-banner').exists()).toBe(false)
  })

  it('提供重新加载按钮', async () => {
    const wrapper = mountBanner()
    globalErrorCenter.report(new Error('需要刷新'), 'vue')
    await wrapper.vm.$nextTick()

    expect(wrapper.get('.global-error-reload').text()).toBe('重新加载')
  })
})
