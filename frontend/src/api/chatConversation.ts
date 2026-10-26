/**
 * 云端会话同步 API：直连模型会话的多端持久化。
 *
 * 本地 localStorage 仍是第一写入位（离线可用），这里负责登录后
 * 拉取合并与会话增删改的异步 upsert。
 */
import axios from './axios'

export interface CloudConversation {
  convId: string
  userId?: string
  title?: string | null
  modelName?: string | null
  pinned?: boolean | null
  /** 会话内容 JSON 快照（DirectConversation 序列化） */
  payload?: string | null
  clientUpdatedAt?: number | null
  iid?: number | null
}

export const chatConversationApi = {
  list: () => axios.post('/ai/api/chatConversation/list'),
  save: (data: Partial<CloudConversation>) =>
    // HNSP @RequestBody 按参数名取 body.data 字段，需要包一层
    axios.post('/ai/api/chatConversation/save', { data }),
  delete: (convId: string) =>
    axios.post('/ai/api/chatConversation/delete', { data: { convId } }),
}

export default chatConversationApi
