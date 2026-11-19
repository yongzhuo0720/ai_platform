import axios from './axios';


export const modelsMngApi = {
  query: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/modelsBean/query`, params);
  },
  update: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/modelsBean/update`, params);
  },
  create: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/modelsBean/create`, params);
  },
  delete: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/modelsBean/delete`, params);
  },
  addDownloads: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/modelsBean/addDownloads`, params);
  },
  queryWeeklyDownloads: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/modelsBean/queryWeeklyDownloads`, params);
  },
  queryWeeklyDownloadsTotal: async () => {
    return axios.post(`/ai/api/modelsBean/queryWeeklyDownloadsTotal`);
  },
  getDownloadUrl: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/modelsBean/getDownloadUrl`, params);
  },
  downloadFile: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/datasetsBean/downloadFile`, params);
  },
};

export default modelsMngApi;
