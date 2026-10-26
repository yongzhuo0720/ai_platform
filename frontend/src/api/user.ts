import axios from './axios';




export const userApi = {
  /**
   * 登录。
   *
   * <p>必须显式带 {@code keepUser}：框架的 logintest 第 4 个参数是
   * <b>primitive boolean</b>，请求体缺这个字段时参数解析器会传 null 进去，
   * {@code Method.invoke} 无法把 null 赋给基本类型，直接抛
   * {@code IllegalArgumentException} → 整个登录接口返回 HTTP 500。
   * 这个坑很隐蔽：日志里只看到 "Illegal argument"，看不出是哪个字段。</p>
   *
   * <p>{@code app} 不要传：它是应用级账号隔离标识，本项目没有按 app 隔离用户，
   * 传任何非空值都会被当成「该应用下不存在这个账号」，返回
   * 「用户不存在或密码错误」。留空即可正常登录。</p>
   */
  logintest: async (params: any) => {
    return axios.post(`/ai/uaccess/logintest`, { keepUser: false, ...params });
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
