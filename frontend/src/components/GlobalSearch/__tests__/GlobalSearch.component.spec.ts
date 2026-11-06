import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { enableAutoUnmount, mount, type VueWrapper } from '@vue/test-utils'

const push = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))
vi.mock('@/api/globalSearch', () => ({ globalSearchApi: { search: vi.fn() } }))

import GlobalSearch from '../index.vue'
import { globalSearchApi } from '@/api/globalSearch'

/**
 * 全局搜索命令面板：Ctrl+K 开合、防抖、竞态隔离、键盘导航、空/错/降级态与跳转。
 */

enableAutoUnmount(afterEach)

const search = globalSearchApi.search as Mock
const ok = (datas: unknown, resp_msg = 'ok') => ({ data: { resp_code: 0, resp_msg, datas } })
const fail = (resp_msg: string) => ({ data: { resp_code: 500, resp_msg } })

const result = {
  keyword: '制度',
  total: 2,
  degraded: false,
  groups: [
    {
      category: 'agent',
      label: 'Agent',
      items: [
        { category: 'agent', title: '制度助手', subtitle: '查询制度', route: '/agents?agentKey=a' },
      ],
    },
    {
      category: 'model',
      label: '模型',
      items: [{ category: 'model', title: '直连模型', route: '/models?keyword=%E5%88%B6%E5%BA%A6' }],
    },
  ],
}

const deferred = <T,>() => {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

const mountSearch = () =>
  mount(GlobalSearch, { attachTo: document.body, global: { stubs: { teleport: true } } })

const press = async (key: string, init: KeyboardEventInit = {}) => {
  document.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, ...init }))
  await new Promise((resolve) => setTimeout(resolve, 0))
}
const type = async (wrapper: VueWrapper, value: string) => {
  await wrapper.get('.global-search-input').setValue(value)
}
const waitDebounce = () => new Promise((resolve) => setTimeout(resolve, 300))

beforeEach(() => {
  vi.resetAllMocks()
  push.mockResolvedValue(undefined)
  search.mockResolvedValue(ok(result))
})

describe('GlobalSearch', () => {
  it('点击触发器打开面板并聚焦输入框，Ctrl+K 再次按下关闭', async () => {
    const wrapper = mountSearch()
    await wrapper.get('.global-search-trigger').trigger('click')
    expect(wrapper.find('.global-search-panel').exists()).toBe(true)
    expect(wrapper.text()).toContain('输入关键词')
    expect(document.activeElement).toBe(wrapper.get('.global-search-input').element)

    await press('k', { ctrlKey: true })
    expect(wrapper.find('.global-search-panel').exists()).toBe(false)
  })

  it('Ctrl+K 打开、Esc 关闭并把焦点还给触发器', async () => {
    const wrapper = mountSearch()
    await press('k', { ctrlKey: true })
    expect(wrapper.find('.global-search-panel').exists()).toBe(true)

    await press('Escape')
    expect(wrapper.find('.global-search-panel').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('.global-search-trigger').element)
  })

  it('输入经过 250ms 防抖后只发一次请求，空关键词不发请求', async () => {
    const wrapper = mountSearch()
    await press('k', { ctrlKey: true })
    await type(wrapper, '   ')
    await waitDebounce()
    expect(search).not.toHaveBeenCalled()

    await type(wrapper, '制度')
    await type(wrapper, '制度 ')
    await waitDebounce()
    expect(search).toHaveBeenCalledTimes(1)
    expect(search).toHaveBeenCalledWith('制度')
  })

  it('按分组渲染结果、展示总数，并支持 ↑↓ 与 Enter 跳转', async () => {
    const wrapper = mountSearch()
    await press('k', { ctrlKey: true })
    await type(wrapper, '制度')
    await waitDebounce()

    expect(wrapper.findAll('.global-search-group')).toHaveLength(2)
    expect(wrapper.text()).toContain('2 条结果')
    expect(wrapper.findAll('.global-search-option.is-active')).toHaveLength(1)

    await press('ArrowDown')
    expect(wrapper.findAll('.global-search-option')[1].classes()).toContain('is-active')
    await press('ArrowDown')
    expect(wrapper.findAll('.global-search-option')[0].classes()).toContain('is-active')
    await press('ArrowUp')
    expect(wrapper.findAll('.global-search-option')[1].classes()).toContain('is-active')

    await press('Enter')
    expect(push).toHaveBeenCalledWith('/models?keyword=%E5%88%B6%E5%BA%A6')
    expect(wrapper.find('.global-search-panel').exists()).toBe(false)
  })

  it('点击结果项直接跳转，并清空关键词', async () => {
    const wrapper = mountSearch()
    await press('k', { ctrlKey: true })
    await type(wrapper, '制度')
    await waitDebounce()

    await wrapper.findAll('.global-search-option')[0].trigger('click')
    expect(push).toHaveBeenCalledWith('/agents?agentKey=a')
    expect(wrapper.find('.global-search-panel').exists()).toBe(false)

    await press('k', { ctrlKey: true })
    expect((wrapper.get('.global-search-input').element as HTMLInputElement).value).toBe('')
  })

  it('空结果显示提示，不渲染 listbox', async () => {
    search.mockResolvedValue(ok({ keyword: '无', total: 0, degraded: false, groups: [] }))
    const wrapper = mountSearch()
    await press('k', { ctrlKey: true })
    await type(wrapper, '无')
    await waitDebounce()

    expect(wrapper.text()).toContain('没有找到与「无」匹配的内容')
    expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
  })

  it('失败显示原因并可重试，成功后渲染结果', async () => {
    search.mockResolvedValueOnce(fail('搜索服务繁忙'))
    const wrapper = mountSearch()
    await press('k', { ctrlKey: true })
    await type(wrapper, '制度')
    await waitDebounce()

    expect(wrapper.get('[role="alert"]').text()).toContain('搜索服务繁忙')
    search.mockResolvedValue(ok(result))
    await wrapper.get('.global-search-retry').trigger('click')
    await waitDebounce()
    expect(wrapper.findAll('.global-search-option')).toHaveLength(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('请求抛异常时显示网络兜底文案', async () => {
    search.mockRejectedValueOnce(new Error('boom'))
    const wrapper = mountSearch()
    await press('k', { ctrlKey: true })
    await type(wrapper, '制度')
    await waitDebounce()

    expect(wrapper.get('[role="alert"]').text()).toContain('搜索失败，请检查网络后重试')
  })

  it('迟到的旧响应不覆盖新关键词的结果', async () => {
    const first = deferred<ReturnType<typeof ok>>()
    search.mockReturnValueOnce(first.promise)
    const wrapper = mountSearch()
    await press('k', { ctrlKey: true })
    await type(wrapper, '旧')
    await waitDebounce()

    search.mockResolvedValue(ok(result))
    await type(wrapper, '制度')
    await waitDebounce()
    expect(wrapper.text()).toContain('制度助手')

    first.resolve(ok({ keyword: '旧', total: 1, degraded: false, groups: [
      { category: 'agent', label: 'Agent', items: [{ category: 'agent', title: '旧结果', route: '/agents' }] },
    ] }))
    await waitDebounce()
    expect(wrapper.text()).not.toContain('旧结果')
    expect(wrapper.text()).toContain('制度助手')
  })

  it('部分数据源不可用时展示降级提示但仍给出结果', async () => {
    search.mockResolvedValue(ok({ ...result, degraded: true }))
    const wrapper = mountSearch()
    await press('k', { ctrlKey: true })
    await type(wrapper, '制度')
    await waitDebounce()

    expect(wrapper.text()).toContain('部分数据源暂时不可用')
    expect(wrapper.findAll('.global-search-option')).toHaveLength(2)
  })

  it('点击面板外部关闭，卸载后移除全局监听', async () => {
    const wrapper = mountSearch()
    await press('k', { ctrlKey: true })
    expect(wrapper.find('.global-search-panel').exists()).toBe(true)

    document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(wrapper.find('.global-search-panel').exists()).toBe(false)

    wrapper.unmount()
    await press('k', { ctrlKey: true })
    expect(document.querySelector('.global-search-panel')).toBeNull()
  })
})
