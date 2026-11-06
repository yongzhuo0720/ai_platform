import type { AgentStep, AgentTool } from '@/api/agent'

export interface AgentModelOption {
  iid: number
  name?: string
  upstreamModel: string
}

export function statusMeta(status: string) {
  const statuses: Record<string, { label: string; tone: string }> = {
    running: { label: '运行中', tone: 'active' },
    pending: { label: '等待中', tone: 'active' },
    succeeded: { label: '成功', tone: 'success' },
    success: { label: '成功', tone: 'success' },
    failed: { label: '失败', tone: 'danger' },
    error: { label: '失败', tone: 'danger' },
    cancelled: { label: '已终止', tone: 'muted' },
  }
  return statuses[status] ?? { label: status || '未知状态', tone: 'muted' }
}

export function stepLabel(type: string) {
  return ({ llm: '模型调用', model: '模型调用', tool: '工具调用', final: '最终回答',
    answer: '最终回答', finish: '最终回答', tool_result: '工具结果', guard: '执行护栏', error: '执行错误' } as Record<string, string>)[type] ?? type
}

export const numberText = (value?: number | null) =>
  value == null || !Number.isFinite(value) ? '—' : String(value)

// 后端历史字段名为 costCents，实际单位为毫分（十万分之一元）。
export const costText = (value?: number | null) =>
  value == null || !Number.isFinite(value) ? '—' : `¥${(value / 100000).toFixed(5)}`

export const durationText = (value?: number | null) =>
  value == null || !Number.isFinite(value) ? '—' : `${value} ms`

export function dateText(value?: number | null) {
  if (value == null || !Number.isFinite(value)) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false })
}

export function mergeSteps(previous: AgentStep[], incoming: AgentStep[]) {
  const steps = new Map(previous.map((step) => [step.stepNo, step]))
  incoming.forEach((step) => steps.set(step.stepNo, step))
  return [...steps.values()].sort((a, b) => a.stepNo - b.stepNo)
}

export function toolSchema(type: AgentTool['toolType']) {
  return JSON.stringify(type === 'kb_search'
    ? { type: 'object', properties: { query: { type: 'string' } }, required: ['query'], additionalProperties: false }
    : { type: 'object', properties: {}, additionalProperties: false }, null, 2)
}

export const toolLabel = (type: AgentTool['toolType']) =>
  ({ builtin: '当前时间', kb_search: '知识检索', http: 'HTTP · 不可执行' })[type]

export function readResult<T>(response: { data: { resp_code: number; resp_msg?: string; datas?: T } }): T {
  if (response.data.resp_code !== 0) throw new Error(response.data.resp_msg || '请求失败，请重试')
  return response.data.datas as T
}

export function readRows<T>(response: { data: { resp_code: number; resp_msg?: string; datas?: T[] } }): T[] {
  const rows = readResult(response)
  if (!Array.isArray(rows)) throw new Error('服务未返回有效列表，请重试')
  return rows
}

export const errorText = (error: unknown, fallback: string) =>
  error instanceof Error && error.message ? error.message : fallback
