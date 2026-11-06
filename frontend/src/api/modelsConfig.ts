import axios from './axios'

export interface ModelConnectionTestResult {
  iid: number
  modelName: string | null
  upstreamModel: string
  /** ok / model_missing / auth_failed / rate_limited / unreachable / http_error */
  status: string
  modelAvailable: boolean
  httpStatus: number | null
  latencyMs: number
  message: string
  checkedAt: number
}

export const modelsConfigApi = {
  getList: (params: any) => axios.post('/ai/api/modelsConfigBean/query', params),
  getPageList: (params: any) => axios.post('/ai/api/modelsConfigBean/queryPage', params),
  create: (params: any) => axios.post('/ai/api/modelsConfigBean/create', params),
  update: (params: any) => axios.post('/ai/api/modelsConfigBean/update', params),
  delete: (params: any) => axios.post('/ai/api/modelsConfigBean/delete', params),
  testConnection: (params: { data: { iid: number } }) =>
    axios.post('/ai/api/modelsConfigBean/testConnection', params),
}

export default modelsConfigApi
