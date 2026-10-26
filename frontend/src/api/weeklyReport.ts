/**
 * AI 调用周报 API：后台手动生成与列表查询。
 */
import axios from './axios'

export interface WeeklyReport {
  weekStart: string
  weekEnd: string
  totalCalls: number | null
  successCalls: number | null
  failedCalls: number | null
  totalTokens: number | null
  costCents: number | null
  avgLatencyMs: number | null
  avgFirstTokenMs: number | null
  topUser: string | null
  topModel: string | null
  iid?: number
}

export const weeklyReportApi = {
  generate: (weekStart?: string) =>
    axios.post('/ai/api/aiWeeklyReport/generate', { data: weekStart ? { weekStart } : {} }),
  list: () => axios.post('/ai/api/aiWeeklyReport/list'),
}

export default weeklyReportApi
