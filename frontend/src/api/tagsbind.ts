import axios from './axios';




export const tagsBindApi = {
  query: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/lableChildBean/query`, params);
  },
  update: async (params: Record<string, unknown>) => {
    return axios.post(`/ai/api/lableChildBean/update`, params);
  },
  // silent: 让组件自己处理错误提示，避免拦截器 + 组件双重弹红色 Toast
  create: async (params: Record<string, unknown>, silent = false) => {
    return axios.post(`/ai/api/lableChildBean/create`, params, { silent } as any);
  },
  delete: async (params: Record<string, unknown>, silent = false) => {
    return axios.post(`/ai/api/lableChildBean/delete`, params, { silent } as any);
  },

};

export default tagsBindApi;
