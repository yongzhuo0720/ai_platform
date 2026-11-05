import axios from './axios'

export interface KbDoc {
  iid: number | null
  kbName: string
  name: string
  charCount: number
  chunkCount: number
  /** queued / chunking / embedding / ready / failed */
  status: string
  /** 处理进度 0-100，仅处理中与就绪时有意义 */
  progress?: number | null
  /** 失败原因，仅 failed 时有值 */
  errorMsg?: string | null
  fileType?: string | null
  sizeBytes?: number | null
  attemptCount?: number | null
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
  /** 向量与当前模型不一致（或数据损坏）而被跳过的分块数 */
  skipped?: number
  /** 检索降级说明，正常检索时为 null */
  warning?: string | null
  items: KbSearchItemDto[]
}

export interface KbUploadFileMeta {
  name?: string
  kbName?: string
}

export interface KbUploadContentParams {
  name: string
  kbName: string
  content: string
  fileType?: string
  fileName?: string
  sizeBytes?: number
}

// 文件与正文都走同一个 multipart 接口：服务端按是否有 file 决定解析方式
const uploadRequest = (form: FormData) =>
  axios.post('/ai/api/kbRag/uploadFile', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })

// HNSP 按方法参数名取字段：请求体需要包一层 data
export const kbRagApi = {
  listDocs: () => axios.post('/ai/api/kbRag/listDocs'),
  /** txt/md/csv/json/log 等文本文件：原文上传，由服务端解析 */
  uploadFile: (file: File, params: KbUploadFileMeta = {}) => {
    const form = new FormData()
    form.append('file', file)
    if (params.name) form.append('name', params.name)
    if (params.kbName) form.append('kbName', params.kbName)
    return uploadRequest(form)
  },
  /** PDF/DOCX：浏览器已提取正文，提交文本；fileType/fileName 仅用于展示与归因 */
  uploadContent: (params: KbUploadContentParams) => {
    const form = new FormData()
    form.append('content', params.content)
    form.append('name', params.name)
    form.append('kbName', params.kbName)
    if (params.fileType) form.append('fileType', params.fileType)
    if (params.fileName) form.append('fileName', params.fileName)
    if (params.sizeBytes) form.append('sizeBytes', String(params.sizeBytes))
    return uploadRequest(form)
  },
  retryDoc: (iid: number) => axios.post('/ai/api/kbRag/retryDoc', { data: { iid } }),
  deleteDoc: (iid: number) => axios.post('/ai/api/kbRag/deleteDoc', { data: { iid } }),
  search: (params: { query: string; kbName?: string; topK?: number }) =>
    axios.post('/ai/api/kbRag/search', { data: params }),
}

export default kbRagApi
