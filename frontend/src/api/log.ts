import axios from './axios';




export const logApi = {
  getLogList: async (params: any) => {
    return axios.post(`/ai/userControllerlog/getControllerLogByTimeAdmin`, params);
  },


};

export default logApi;
