import axios from './axios'

export const modelsConfigApi = {
  getList: (params: any) => axios.post('/ai/api/modelsConfigBean/query', params),
  create: (params: any) => axios.post('/ai/api/modelsConfigBean/create', params),
  update: (params: any) => axios.post('/ai/api/modelsConfigBean/update', params),
  delete: (params: any) => axios.post('/ai/api/modelsConfigBean/delete', params),
}

export default modelsConfigApi
