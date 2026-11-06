import axios from './axios'

export type GlobalSearchCategory =
  | 'agent'
  | 'knowledge-base'
  | 'rag-dataset'
  | 'model'
  | 'dataset'

export interface GlobalSearchItem {
  category: GlobalSearchCategory
  title: string
  subtitle?: string | null
  route: string
}

export interface GlobalSearchGroup {
  category: GlobalSearchCategory
  label: string
  items: GlobalSearchItem[]
}

export interface GlobalSearchResult {
  keyword: string
  total: number
  degraded: boolean
  groups: GlobalSearchGroup[]
}

/**
 * 命令面板只发一个聚合请求；分类、权限与跳转路径都由服务端决定。
 * silent 让调用方在面板内展示错误，避免输入过程中反复弹全局 toast。
 */
export const globalSearchApi = {
  search: (keyword: string, silent = true) =>
    axios.post('/ai/api/globalSearch/search', { data: { keyword } }, { silent }),
}

export default globalSearchApi
