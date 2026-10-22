import axios from './axios';




export const tagsApi = {
  query: async (params: any) => {
    return axios.post(`/ai/api/lableBean/query`, params);
  },
  update: async (params: any) => {
    return axios.post(`/ai/api/lableBean/update`, params);
  },
  create: async (params: any) => {
    return axios.post(`/ai/api/lableBean/create`, params);
  },
  delete: async (params: any) => {
    return axios.post(`/ai/api/lableBean/delete`, params);
  },

};

export default tagsApi;
