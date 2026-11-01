import axios from './axios';

/** 后台操作日志一行（对应后端 UserControllerLogBean，字段名与框架表列名一致，全小写） */
export interface ControllerLog {
  iid: number;
  type: string;
  /** 操作者账号 */
  uaccount: string;
  /** 操作名称，取自接口的 @ApiOperation */
  action: string;
  actionid: number | null;
  /** 操作结果描述，失败时为空 */
  res: string;
  resid: number | null;
  serverip: string;
  clientip: string;
  /** 被调用的接口地址 */
  url: string;
  /** 接口执行耗时（毫秒） */
  time: number | null;
  format: string;
  createtime: number | string | null;
}

export interface ControllerLogQuery {
  pageNum?: number;
  pageSize?: number;
  /** yyyy-MM-dd，与 endDate 同时填写才生效 */
  startDate?: string;
  /** yyyy-MM-dd，与 startDate 同时填写才生效 */
  endDate?: string;
}

export const logApi = {
  // 后端由本项目的 /api/controllerLog/query 提供（原来是框架模块里的
  // /userControllerlog/getControllerLogByTimeAdmin，那个模块在 pom 里是注释掉的，
  // 接口根本不存在，页面一直 404）。
  // HNSP 的 MultiRequestBodyArgumentResolver 按方法形参名取字段，所以请求体要包一层 query。
  getLogList: async (params: ControllerLogQuery = {}) => {
    return axios.post(`/ai/api/controllerLog/query`, { query: params });
  },
};

export default logApi;
