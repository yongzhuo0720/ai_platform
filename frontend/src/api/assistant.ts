import axios from './axios'

/**
 * 助手（多智能体）配置。
 * 通过配置不同的接口参数，接入不同类型的智能体服务
 * （当前支持 qwen_paw，预留 wanwu 等扩展）。
 */
export interface AssistantData {
  iid?: number
  name: string
  prompt: string
  userId?: string
  /** 智能体服务类型（qwen_paw / wanwu 等） */
  type?: string
  /** 智能体 ID（对应 Header X-Agent-Id） */
  agentId?: string
  /** 接口地址（API 基础 URL） */
  apiUrl?: string
  /** 认证 Token（Authorization Bearer） */
  authToken?: string
  /** 调用渠道（如 console） */
  channel?: string
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
