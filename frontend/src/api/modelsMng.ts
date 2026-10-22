import axios from './axios';


export const modelsMngApi = {
  query: async (params: any) => {
    return axios.post(`/ai/api/modelsBean/query`, params);
  },
  update: async (params: any) => {
    return axios.post(`/ai/api/modelsBean/update`, params);
  },
  create: async (params: any) => {
    return axios.post(`/ai/api/modelsBean/create`, params);
  },
  delete: async (params: any) => {
    return axios.post(`/ai/api/modelsBean/delete`, params);
  },
  addDownloads: async (params: any) => {
    return axios.post(`/ai/api/modelsBean/addDownloads`, params);
  },
  queryWeeklyDownloads: async (params: any) => {
    return axios.post(`/ai/api/modelsBean/queryWeeklyDownloads`, params);
  },
  getDownloadUrl: async (params: any) => {
    return axios.post(`/ai/api/modelsBean/getDownloadUrl`, params);
  },
  downloadFile: async (params: any) => {
    return axios.post(`/ai/api/datasetsBean/downloadFile`, params);
  },
};

export default modelsMngApi;
