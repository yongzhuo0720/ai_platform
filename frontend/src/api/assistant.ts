import axios from './axios'

/**
 * 助手配置：名称 + 提示词，底层模型与智能体分发由服务端统一处理。
 * 权限字段由后端维护，前端只在列表里读取 userId 判断可否编辑。
 */
export interface AssistantData {
  iid?: number
  name: string
  prompt: string
  userId?: string
  /** 权限类型：public=所有人可见 / partial_invisible=部分不可见 */
  permissionType?: string
  /** 不可见部门编号（JSON 数组字符串） */
  unitsnos?: string
  /** 不可见角色 id（JSON 数组字符串） */
  roleiids?: string
}

export const assistantApi = {
  query: () => axios.post('/ai/api/assistantBean/query'),

  create: (data: AssistantData) => axios.post('/ai/api/assistantBean/create', { data }),

  delete: (data: { iid: number }) => axios.post('/ai/api/assistantBean/delete', { data }),

  update: (data: AssistantData, where: { iid: number }) =>
    axios.post('/ai/api/assistantBean/update', { data, where }),
}

export default assistantApi
