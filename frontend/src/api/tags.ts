import axios from './axios';




export const tagsApi = {
  query: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/lableBean/query`, params);
  },
  update: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/lableBean/update`, params);
  },
  create: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/lableBean/create`, params);
  },
  delete: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/lableBean/delete`, params);
  },

};

export default tagsApi;
