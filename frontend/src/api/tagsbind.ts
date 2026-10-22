import axios from './axios';




export const tagsBindApi = {
  query: async (params: any) => {
    return axios.post(`/ai/api/lableChildBean/query`, params);
  },
  update: async (params: any) => {
    return axios.post(`/ai/api/lableChildBean/update`, params);
  },
  create: async (params: any) => {
    return axios.post(`/ai/api/lableChildBean/create`, params);
  },
  delete: async (params: any) => {
    return axios.post(`/ai/api/lableChildBean/delete`, params);
  },

};

export default tagsBindApi;
