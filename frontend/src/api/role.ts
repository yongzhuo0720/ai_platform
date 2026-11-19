import axios from './axios';

export const roleApi = {
  getDataList: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/myRole/getRids`, params);
  },
  update: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/myRole/update`, params);
  },
  create: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/myRole/create`, params);
  },
  delete: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/myRole/remove`, params);
  },

};

export default roleApi;
