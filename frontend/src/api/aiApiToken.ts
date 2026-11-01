import axios from './axios'

/** 平台 API 密钥：给外部程序用 OpenAI 协议（/ai/v1/*）调模型用 */
export interface AiApiToken {
  iid: number
  name: string
  /** 展示用前缀，如 sk-1a2b3c4d。库里只有哈希，明文只在创建响应里出现一次 */
  tokenPrefix: string
  userId: string
  /** 允许调用的模型（逗号分隔），空表示全部 */
  allowModels: string | null
  /** 过期时刻（epoch 毫秒），null 表示不过期 */
  expireAt: number | null
  lastUsedAt: number | null
  enable: boolean
  createtime: number | string | null
}

export interface AiApiTokenIssued {
  token: AiApiToken
  plaintext: string
}

export interface AiApiTokenDraft {
  name: string
  allowModels?: string
  expireAt?: number | null
}

// HNSP 的 MultiRequestBodyArgumentResolver 按「方法参数名」取字段，
// 裸传 { name: '...' } 会在后端解析成 null，必须包一层 data。
export const aiApiTokenApi = {
  list: () => axios.post('/ai/api/aiApiToken/list'),
  create: (params: AiApiTokenDraft) =>
    axios.post('/ai/api/aiApiToken/create', { data: params }),
  update: (params: { iid: number; name?: string; allowModels?: string; expireAt?: number | null; enable?: boolean }) =>
    axios.post('/ai/api/aiApiToken/update', { data: params }),
  remove: (iid: number) =>
    axios.post('/ai/api/aiApiToken/delete', { data: { iid } }),
}

export default aiApiTokenApi
