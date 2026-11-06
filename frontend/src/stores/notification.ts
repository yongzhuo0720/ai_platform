import { defineStore } from 'pinia'

import notificationApi, {
  type NotificationItem,
  type NotificationListResult,
} from '@/api/notification'

const POLL_INTERVAL_MS = 30_000
const LIST_PAGE_SIZE = 20

let pollTimer: number | null = null
let consumers = 0
let visibilityHandler: (() => void) | null = null

interface ReadStateSnapshot {
  iid: number
  readStatus: string
}

/**
 * 站内通知状态。
 *
 * <p>未读数按 30 秒轮询，页面隐藏时完全跳过请求；面板打开时轮询改为刷新列表，
 * 这样新通知不只更新角标，也能出现在已经打开的列表里。</p>
 *
 * <p>已读操作采用乐观更新：先改本地再发请求，失败回滚。为此每次写操作都会
 * 让在途的读请求失效（序号 +1），避免旧响应把刚标记的已读状态覆盖回去。</p>
 */
export const useNotificationStore = defineStore('notification', {
  state: () => ({
    items: [] as NotificationItem[],
    unreadCount: 0,
    listLoading: false,
    listError: '',
    loadedOnce: false,
    panelOpen: false,
    listSeq: 0,
    countSeq: 0,
  }),
  getters: {
    hasUnread: (state) => state.unreadCount > 0,
  },
  actions: {
    async openPanel() {
      this.panelOpen = true
      await this.fetchList()
    },

    closePanel() {
      this.panelOpen = false
    },

    async fetchList(options: { silent?: boolean } = {}) {
      const silent = options.silent === true
      const seq = ++this.listSeq
      if (!silent) {
        this.listLoading = true
        this.listError = ''
      }
      try {
        const { data: res } = await notificationApi.list({
          pageNum: 1,
          pageSize: LIST_PAGE_SIZE,
        })
        if (seq !== this.listSeq) return
        if (res.resp_code !== 0 || !res.datas) {
          if (!silent) this.listError = res.resp_msg || '获取通知失败'
          return
        }
        const data = res.datas as NotificationListResult
        this.items = Array.isArray(data.items) ? data.items : []
        this.unreadCount = Math.max(0, Number(data.unreadCount) || 0)
        this.loadedOnce = true
        this.listError = ''
      } catch (err) {
        if (seq !== this.listSeq) return
        if (!silent) this.listError = '网络异常，请稍后重试'
      } finally {
        if (seq === this.listSeq) this.listLoading = false
      }
    },

    async fetchUnreadCount() {
      const seq = ++this.countSeq
      try {
        const { data: res } = await notificationApi.unreadCount()
        if (seq !== this.countSeq) return
        if (res.resp_code !== 0) return
        this.unreadCount = Math.max(0, Number(res.datas) || 0)
      } catch (err) {
        // 角标是辅助信息，轮询失败保持上一次结果即可
      }
    },

    async markRead(iid: number) {
      const item = this.items.find((row) => row.iid === iid)
      if (!item) return false
      if (item.readStatus === 'read') return true

      const previousStatus = String(item.readStatus ?? 'unread')
      const previousCount = this.unreadCount
      this.invalidatePendingReads()
      item.readStatus = 'read'
      this.unreadCount = Math.max(0, previousCount - 1)

      try {
        const { data: res } = await notificationApi.markRead(iid)
        if (res.resp_code === 0) return true
        this.restoreReadState([{ iid, readStatus: previousStatus }], previousCount)
        return false
      } catch (err) {
        this.restoreReadState([{ iid, readStatus: previousStatus }], previousCount)
        return false
      }
    },

    async markAllRead() {
      const unreadItems = this.items.filter((row) => row.readStatus !== 'read')
      if (unreadItems.length === 0 && this.unreadCount === 0) return true

      const previousCount = this.unreadCount
      const snapshot: ReadStateSnapshot[] = this.items.map((row) => ({
        iid: row.iid,
        readStatus: String(row.readStatus ?? 'unread'),
      }))
      this.invalidatePendingReads()
      this.items.forEach((row) => {
        row.readStatus = 'read'
      })
      this.unreadCount = 0

      try {
        const { data: res } = await notificationApi.markAllRead()
        if (res.resp_code === 0) {
          // 后端返回的是本次标记的行数，不是剩余未读；全部已读后本地清零，
          // 期间新到的通知交给下一轮计数修正
          this.unreadCount = 0
          return true
        }
        this.restoreReadState(snapshot, previousCount)
        return false
      } catch (err) {
        this.restoreReadState(snapshot, previousCount)
        return false
      }
    },

    startPolling() {
      consumers += 1
      if (consumers > 1) return
      if (typeof document === 'undefined') return

      void this.fetchUnreadCount()
      visibilityHandler = () => {
        if (document.visibilityState === 'visible') void this.pollTick()
      }
      document.addEventListener('visibilitychange', visibilityHandler)
      pollTimer = window.setInterval(() => {
        void this.pollTick()
      }, POLL_INTERVAL_MS)
    },

    stopPolling() {
      consumers = Math.max(0, consumers - 1)
      if (consumers > 0) return
      if (pollTimer !== null) {
        window.clearInterval(pollTimer)
        pollTimer = null
      }
      if (typeof document !== 'undefined' && visibilityHandler) {
        document.removeEventListener('visibilitychange', visibilityHandler)
      }
      visibilityHandler = null
    },

    async pollTick() {
      if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
      if (this.panelOpen) {
        if (this.listLoading) return
        await this.fetchList({ silent: true })
        return
      }
      await this.fetchUnreadCount()
    },

    /** 写操作先让在途读请求作废，防止旧数据覆盖乐观更新 */
    invalidatePendingReads() {
      this.listSeq += 1
      this.countSeq += 1
      this.listLoading = false
    },

    restoreReadState(snapshot: ReadStateSnapshot[], unreadCount: number) {
      snapshot.forEach((saved) => {
        const item = this.items.find((row) => row.iid === saved.iid)
        if (item) item.readStatus = saved.readStatus
      })
      this.unreadCount = unreadCount
    },
  },
})
