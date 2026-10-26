/**
 * 对话分享 API：create/delete 需登录；get 免登录（访客打开分享链接）。
 */
import axios from './axios'

export const chatShareApi = {
  create: (data: { title: string; payload: string; authorName?: string }) =>
    axios.post('/ai/api/chatShare/create', { data }),
  get: (shareToken: string) =>
    axios.post('/ai/api/chatShare/get', { data: { shareToken } }),
  delete: (shareToken: string) =>
    axios.post('/ai/api/chatShare/delete', { data: { shareToken } }),
}

export default chatShareApi
