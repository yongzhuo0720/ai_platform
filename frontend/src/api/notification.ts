import axios from './axios'

export type NotificationLevel = 'success' | 'warning' | 'error' | 'info'

export interface NotificationItem {
  iid: number
  eventKey?: string | null
  category?: string | null
  level?: NotificationLevel | string | null
  title: string
  content?: string | null
  link?: string | null
  readStatus?: 'unread' | 'read' | string | null
  readAt?: number | null
  createtime?: string | number | null
}

export interface NotificationListResult {
  items: NotificationItem[]
  unreadCount: number
  totalSize: number
  pageNum: number
  pageSize: number
}

export interface NotificationListQuery {
  pageNum?: number
  pageSize?: number
  onlyUnread?: boolean
}

// HNSP 按方法形参名映射请求体；silent 用于轮询，避免后台请求失败时反复弹提示
export const notificationApi = {
  list: (query: NotificationListQuery = {}, silent = true) =>
    axios.post('/ai/api/notification/list', { query }, { silent }),
  unreadCount: (silent = true) =>
    axios.post('/ai/api/notification/unreadCount', undefined, { silent }),
  markRead: (iid: number) =>
    axios.post('/ai/api/notification/markRead', { data: { iid } }),
  markAllRead: () =>
    axios.post('/ai/api/notification/markAllRead'),
}

export default notificationApi
