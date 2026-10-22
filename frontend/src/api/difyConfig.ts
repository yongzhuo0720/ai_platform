import axios from './axios'

export const difyConfigApi = {
  getList: (params: any) => axios.post('/ai/api/difyConfigBean/query', params),
  update: (params: any) => axios.post('/ai/api/difyConfigBean/update', params),
}

export default difyConfigApi
