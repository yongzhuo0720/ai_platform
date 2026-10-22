import axios from './axios'

export interface AiGatewayModuleStatus {
  module: string
  moduleName: string
  configured: boolean
}

export interface AiGatewayConfigStatus {
  enabled: boolean
  rateLimitPerMinute: number
  defaultDailyTokenQuota: number
  modules: AiGatewayModuleStatus[]
}

export interface AiGatewayTrendPoint {
  date: string
  calls: number
  tokens: number
  failed: number
}

export interface AiGatewayModuleStat {
  module: string
  calls: number
  tokens: number
  failed: number
}

export interface AiGatewayUserStat {
  userId: string
  userName: string
  calls: number
  tokens: number
}

export interface AiGatewayModelStat {
  model: string
  calls: number
  tokens: number
  failed: number
  avgLatencyMs: number
}

export interface AiGatewaySummary {
  totalCalls: number
  successCalls: number
  failedCalls: number
  blockedCalls: number
  totalTokens: number
  avgLatencyMs: number
  p95LatencyMs: number
  successRate: number
  truncated: boolean
  trend: AiGatewayTrendPoint[]
  modules: AiGatewayModuleStat[]
  models: AiGatewayModelStat[]
  topUsers: AiGatewayUserStat[]
}

export interface AiCallLog {
  iid: number
  userId: string
  module: string
  endpoint: string
  method: string
  status: string
  httpStatus: number | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  latencyMs: number | null
  modelName: string | null
  conversationId: string | null
  messageId: string | null
  errorMessage: string | null
  clientIp: string | null
  createtime: number | string | null
}

export interface AiQuotaView {
  userId: string
  userName: string
  dailyTokenQuota: number | null
  effectiveQuota: number
  usedToday: number
  remark: string | null
  custom: boolean
}

export interface AiQuotaOverview {
  defaultDailyTokenQuota: number
  items: AiQuotaView[]
}

export interface AiGatewayUserOption {
  uid: string
  uaccess: string
  uname: string
}

export interface AiMyUsage {
  usedToday: number
  effectiveQuota: number
  /** 不限制时为 -1 */
  remainingTokens: number
  usagePercent: number
  usage: AiGatewaySummary
  recentLogs: AiCallLog[]
}

export interface AiGatewayLogQuery {
  pageNum?: number
  pageSize?: number
  userId?: string
  module?: string
  status?: string
  startDate?: string
  endDate?: string
}

// HNSP 的 MultiRequestBodyArgumentResolver 按「方法参数名」从 JSON 中取字段，
// 因此请求体需要包一层：query 对应 AiGatewayLogQuery，data 对应 AiUserQuotaBean。
export const aiGatewayAdminApi = {
  getConfig: () => axios.post('/ai/api/aiGatewayAdmin/config'),
  getSummary: (params: AiGatewayLogQuery = {}) =>
    axios.post('/ai/api/aiGatewayAdmin/summary', { query: params }),
  getLogs: (params: AiGatewayLogQuery) =>
    axios.post('/ai/api/aiGatewayAdmin/logs', { query: params }),
  getMyUsage: (params: AiGatewayLogQuery = {}) =>
    axios.post('/ai/api/aiGatewayAdmin/myUsage', { query: params }),
  exportLogs: (params: AiGatewayLogQuery = {}) =>
    axios.post('/ai/api/aiGatewayAdmin/exportLogs', { query: params }),
  getQuotas: () => axios.post('/ai/api/aiGatewayAdmin/quotas'),
  saveQuota: (params: { userId: string; dailyTokenQuota: number; remark: string }) =>
    axios.post('/ai/api/aiGatewayAdmin/saveQuota', { data: params }),
  deleteQuota: (params: { userId: string }) =>
    axios.post('/ai/api/aiGatewayAdmin/deleteQuota', { data: params }),
  getUsers: () => axios.post('/ai/api/aiGatewayAdmin/users'),
}

export default aiGatewayAdminApi
