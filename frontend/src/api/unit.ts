import axios from './axios';




export const unitApi = {
  getDataList: async (params: any) => {
    return axios.post(`/ai/api/unitBean/queryTree`, params);
  },
  update: async (params: any) => {
    return axios.post(`/ai/api/unitBean/update`, params);
  },
  create: async (params: any) => {
    return axios.post(`/ai/api/unitBean/create`, params);
  },
  delete: async (params: any) => {
    return axios.post(`/ai/api/unitBean/delete`, params);
  },

};

export default unitApi;
