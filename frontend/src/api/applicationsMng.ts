import axios from './axios';




export const applicationsMngApi = {
  query: async (params: any) => {
    return axios.post(`/ai/api/applicationsBean/query`, params);
  },
  update: async (params: any) => {
    return axios.post(`/ai/api/applicationsBean/update`, params);
  },
  create: async (params: any) => {
    return axios.post(`/ai/api/applicationsBean/create`, params);
  },
  delete: async (params: any) => {
    return axios.post(`/ai/api/applicationsBean/delete`, params);
  }


};

export default applicationsMngApi;
