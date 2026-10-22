import axios from './axios'

export interface KbDoc {
  iid: number | null
  kbName: string
  name: string
  charCount: number
  chunkCount: number
  status: string
  createtime: string | number | null
}

export interface KbSearchItemDto {
  docIid: number
  docName: string | null
  kbName: string
  chunkIndex: number
  content: string
  score: number
}

export interface KbSearchResultDto {
  query: string
  model: string
  scanned: number
  items: KbSearchItemDto[]
}

// HNSP 按方法参数名取字段：请求体需要包一层 data
export const kbRagApi = {
  listDocs: () => axios.post('/ai/api/kbRag/listDocs'),
  uploadDoc: (params: { name: string; kbName: string; content: string }) =>
    axios.post('/ai/api/kbRag/uploadDoc', { data: params }),
  deleteDoc: (iid: number) => axios.post('/ai/api/kbRag/deleteDoc', { data: { iid } }),
  search: (params: { query: string; kbName?: string; topK?: number }) =>
    axios.post('/ai/api/kbRag/search', { data: params }),
}

export default kbRagApi
