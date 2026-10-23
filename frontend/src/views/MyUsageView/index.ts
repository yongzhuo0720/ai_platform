import { computed, onMounted, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { aiGatewayAdminApi, type AiMyUsage } from '@/api/aiGatewayAdmin'
import { debug } from '@/utils/debug'

const MODULE_LABELS: Record<string, string> = {
  chat: '问答聊天',
  knowledgeChat: '知识库对话',
  file: '知识库文件',
  models: '模型直连',
}

const STATUS_LABELS: Record<string, string> = {
  success: '成功',
  failed: '失败',
  blocked: '已拦截',
  aborted: '已中断',
}

const RANGE_OPTIONS = [7, 14, 30]

function formatDate(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function rangeFromDays(days: number): string[] {
  const end = new Date()
  const start = new Date()
  start.setDate(start.getDate() - (days - 1))
  return [formatDate(start), formatDate(end)]
}

export function useIndex() {
  const state = reactive({
    rangeDays: 7,
    usage: null as AiMyUsage | null,
    loading: false,
  })

  const range = computed(() => rangeFromDays(state.rangeDays))

  const quotaLimited = computed(() => !!state.usage && state.usage.effectiveQuota > 0)
  const quotaWarn = computed(() => quotaLimited.value && (state.usage?.usagePercent ?? 0) >= 80)
  const quotaExceeded = computed(
    () => quotaLimited.value && (state.usage?.usedToday ?? 0) >= (state.usage?.effectiveQuota ?? 0)
  )

  onMounted(() => {
    load()
  })

  async function load() {
    state.loading = true
    try {
      const { data: res } = await aiGatewayAdminApi.getMyUsage({
        startDate: range.value[0],
        endDate: range.value[1],
      })
      if (res.resp_code === 0) {
        state.usage = res.datas
      } else {
        ElMessage.error(res.resp_msg || '获取用量失败')
      }
    } catch (err) {
      debug.error('获取我的用量失败', err)
      ElMessage.error('获取用量失败，请稍后重试')
    } finally {
      state.loading = false
    }
  }

  function setRangeDays(days: number) {
    if (state.rangeDays === days) return
    state.rangeDays = days
    load()
  }

  const trendMax = computed(() =>
    Math.max(1, ...(state.usage?.usage.trend ?? []).map((point) => point.calls))
  )
  const moduleMax = computed(() =>
    Math.max(1, ...(state.usage?.usage.modules ?? []).map((item) => item.calls))
  )

  function moduleLabel(module: string): string {
    return MODULE_LABELS[module] ?? module
  }

  function statusLabel(status: string): string {
    return STATUS_LABELS[status] ?? status
  }

  function statusTagType(status: string): 'success' | 'danger' | 'warning' | 'info' {
    if (status === 'success') return 'success'
    if (status === 'failed') return 'danger'
    if (status === 'blocked') return 'warning'
    return 'info'
  }

  function formatTokens(value: number | null | undefined): string {
    const num = Number(value ?? 0)
    if (num >= 1000000) {
      return `${(num / 1000000).toFixed(num >= 10000000 ? 0 : 1)}M`
    }
    if (num >= 1000) {
      return `${(num / 1000).toFixed(num >= 10000 ? 0 : 1)}K`
    }
    return String(num)
  }

  function formatDuration(ms: number | null | undefined): string {
    if (ms === null || ms === undefined) return '-'
    if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
    return `${ms}ms`
  }

  function formatTime(value: number | string | null | undefined): string {
    if (value === null || value === undefined || value === '') return '-'
    if (typeof value === 'number') return formatDateValue(new Date(value))
    const text = String(value)
    const normalized = text.replace(/\//g, '-').replace(' ', 'T')
    const parsed = new Date(normalized)
    if (Number.isNaN(parsed.getTime())) return text
    return formatDateValue(parsed)
  }

  function formatDateValue(date: Date): string {
    const pad = (num: number) => String(num).padStart(2, '0')
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  }

  function trendHeight(calls: number): string {
    if (calls <= 0) return '0%'
    return `${Math.max(4, (calls / trendMax.value) * 100)}%`
  }

  function failedHeight(calls: number, failed: number): string {
    if (calls <= 0 || failed <= 0) return '0%'
    return `${Math.min(100, (failed / calls) * 100)}%`
  }

  function moduleWidth(calls: number): string {
    if (calls <= 0) return '0%'
    return `${Math.max(2, (calls / moduleMax.value) * 100)}%`
  }

  return {
    state,
    range,
    RANGE_OPTIONS,
    quotaLimited,
    quotaWarn,
    quotaExceeded,
    load,
    setRangeDays,
    moduleLabel,
    statusLabel,
    statusTagType,
    formatTokens,
    formatDuration,
    formatTime,
    trendHeight,
    failedHeight,
    moduleWidth,
  }
}
