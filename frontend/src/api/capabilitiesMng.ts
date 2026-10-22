import axios from './axios';




export const capabilitiesMngApi = {
  query: async (params: any) => {
    return axios.post(`/ai/api/capabilitiesBean/query`, params);
  },
  update: async (params: any) => {
    return axios.post(`/ai/api/capabilitiesBean/update`, params);
  },
  create: async (params: any) => {
    return axios.post(`/ai/api/capabilitiesBean/create`, params);
  },
  delete: async (params: any) => {
    return axios.post(`/ai/api/capabilitiesBean/delete`, params);
  },
  // 单点登录：获取跳转地址（需要 SSO 的能力点击"查看"时调用）
  // 入参：{ data: { iid } }；返回：{ resp_code, datas: { url } }
  // 由后端判断：未授权 → 万悟登录授权地址；已授权 → 业务页面地址
  ssoJump: async () => {
    return axios.get(`/ai/api/ssoTokenBean/status`);
  }


};

export default capabilitiesMngApi;
