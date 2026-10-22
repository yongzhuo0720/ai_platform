import axios from './axios';


export const datasetsMngApi = {
  query: async (params: any) => {
    return axios.post(`/ai/api/datasetsBean/query`, params);
  },
  update: async (params: any) => {
    return axios.post(`/ai/api/datasetsBean/update`, params);
  },
  create: async (params: any) => {
    return axios.post(`/ai/api/datasetsBean/create`, params);
  },
  delete: async (params: any) => {
    return axios.post(`/ai/api/datasetsBean/delete`, params);
  },
  getDownloadUrl: async (params: any) => {
    return axios.post(`/ai/api/datasetsBean/getDownloadUrl`, params);
  },
  downloadFile: async (params: any) => {
    return axios.post(`/ai/api/datasetsBean/downloadFile`, params);
  },
};

export default datasetsMngApi;
