import axios from './axios';




export const tagsBindApi = {
  query: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/lableChildBean/query`, params);
  },
  update: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/lableChildBean/update`, params);
  },
  create: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/lableChildBean/create`, params);
  },
  delete: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/lableChildBean/delete`, params);
  },

};

export default tagsBindApi;
