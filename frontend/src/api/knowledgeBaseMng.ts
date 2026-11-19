import axios from './axios';

export const knowledgeBaseMngApi = {
  query: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/knowledgeBaseBean/query`, params);
  },
  update: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/knowledgeBaseBean/update`, params);
  },
  create: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/knowledgeBaseBean/create`, params);
  },
  delete: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/knowledgeBaseBean/delete`, params);
  },
  filterVisible: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/knowledgeBaseBean/filterVisible`, params);
  },
  queryAll: async () => {
    return axios.post(`/ai/api/knowledgeBaseBean/query`, { data: {} });
  },
};

export default knowledgeBaseMngApi;
