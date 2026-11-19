import axios from './axios';


export const datasetsMngApi = {
  query: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/datasetsBean/query`, params);
  },
  update: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/datasetsBean/update`, params);
  },
  create: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/datasetsBean/create`, params);
  },
  delete: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/datasetsBean/delete`, params);
  },
  getDownloadUrl: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/datasetsBean/getDownloadUrl`, params);
  },
  downloadFile: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/datasetsBean/downloadFile`, params);
  },
};

export default datasetsMngApi;
