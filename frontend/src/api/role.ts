import axios from './axios';

export const roleApi = {
  getDataList: async (params: any) => {
    return axios.post(`/ai/myRole/getRids`, params);
  },
  update: async (params: any) => {
    return axios.post(`/ai/myRole/update`, params);
  },
  create: async (params: any) => {
    return axios.post(`/ai/myRole/create`, params);
  },
  delete: async (params: any) => {
    return axios.post(`/ai/myRole/remove`, params);
  },

};

export default roleApi;
