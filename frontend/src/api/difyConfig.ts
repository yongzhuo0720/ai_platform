import axios from './axios'

export const difyConfigApi = {
  getList: (params: Record<string, unknown>) => axios.post('/ai/api/difyConfigBean/query', params),
  update: (params: Record<string, unknown>) => axios.post('/ai/api/difyConfigBean/update', params),
}

export default difyConfigApi
