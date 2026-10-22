import axios from './axios';




export const userApi = {
  logintest: async (params: any) => {
    return axios.post(`/ai/uaccess/logintest`, params);
  },
  getUserList: async (params: any) => {
    return axios.post(`/ai/myuser/query`, params);
  },
  update: async (params: any) => {
    return axios.post(`/ai/myuser/update`, params);
  },
  create: async (params: any) => {
    return axios.post(`/ai/myuser/create`, params);
  },
  delete: async (params: any) => {
    return axios.post(`/ai/myuser/delete`, params);
  },

};

export default userApi;
