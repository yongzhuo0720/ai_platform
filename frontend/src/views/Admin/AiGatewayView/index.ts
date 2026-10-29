import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  aiGatewayAdminApi,
  type AiCallLog,
  type AiGatewayConfigStatus,
  type AiGatewaySummary,
  type AiGatewayUserOption,
  type AiQuotaOverview,
  type AiQuotaView,
} from '@/api/aiGatewayAdmin'
import { weeklyReportApi, type WeeklyReport } from '@/api/weeklyReport'
import { debug } from '@/utils/debug'
import { isConfirmCancelled } from '@/utils/confirm'

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

function formatDate(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function defaultRange(): string[] {
  const end = new Date()
  const start = new Date()
  start.setDate(start.getDate() - 6)
  return [formatDate(start), formatDate(end)]
}

export function useIndex() {
  const state = reactive({
    activeTab: 'overview',
    config: null as AiGatewayConfigStatus | null,
    range: defaultRange(),
    summary: null as AiGatewaySummary | null,
    summaryLoading: false,
    logs: [] as AiCallLog[],
    logsLoading: false,
    logQuery: {
      userId: '',
      module: '',
      status: '',
    },
    pagination: {
      pageNum: 1,
      pageSize: 20,
      total: 0,
    },
    quotas: null as AiQuotaOverview | null,
    quotasLoading: false,
    users: [] as AiGatewayUserOption[],
    usersLoaded: false,
    quotaDialogVisible: false,
    quotaEditing: false,
    quotaSaving: false,
    quotaForm: {
      userId: '',
      dailyTokenQuota: 100000,
      remark: '',
      scope: 'user' as 'user' | 'unit',
    unitGid: '',
},
    reports: [] as WeeklyReport[],
    reportGenerating: false,
  })

  const trendMax = computed(() =>
    Math.max(1, ...(state.summary?.trend ?? []).map((point) => point.calls))
  )
  const moduleMax = computed(() =>
    Math.max(1, ...(state.summary?.modules ?? []).map((item) => item.calls))
  )
  const modelMax = computed(() =>
    Math.max(1, ...(state.summary?.models ?? []).map((item) => item.calls))
  )
  const userMax = computed(() =>
    Math.max(1, ...(state.summary?.topUsers ?? []).map((item) => item.calls))
  )

  onMounted(() => {
    loadConfig()
    loadSummary()
    loadReports()
})

  async function loadConfig() {
    try {
      const { data: res } = await aiGatewayAdminApi.getConfig()
      if (res.resp_code === 0) {
        state.config = res.datas
      } else {
        ElMessage.error(res.resp_msg || '获取 AI 网关配置失败')
      }
    } catch (err) {
      debug.error('获取 AI 网关配置失败', err)
      ElMessage.error('获取 AI 网关配置失败，请稍后重试')
    }
  }

  function retryOverview() {
    loadConfig()
    loadSummary()
  }

  function dateParams() {
    if (state.range.length !== 2) return {}
    return { startDate: state.range[0], endDate: state.range[1] }
  }

  async function loadSummary() {
    state.summaryLoading = true
    try {
      const { data: res } = await aiGatewayAdminApi.getSummary(dateParams())
      if (res.resp_code === 0) {
        state.summary = res.datas
      } else {
        ElMessage.error(res.resp_msg || '获取用量汇总失败')
      }
    } catch (err) {
      debug.error('获取 AI 用量汇总失败', err)
      ElMessage.error('获取用量汇总失败，请稍后重试')
    } finally {
      state.summaryLoading = false
    }
  }

  async function loadLogs(reset = false) {
    if (reset) {
      state.pagination.pageNum = 1
    }
    state.logsLoading = true
    try {
      const { data: res } = await aiGatewayAdminApi.getLogs({
        pageNum: state.pagination.pageNum,
        pageSize: state.pagination.pageSize,
        userId: state.logQuery.userId.trim() || undefined,
        module: state.logQuery.module || undefined,
        status: state.logQuery.status || undefined,
        ...dateParams(),
      })
      if (res.resp_code === 0) {
        state.logs = res.datas?.content ?? []
        state.pagination.total = res.datas?.totalSize ?? 0
      } else {
        ElMessage.error(res.resp_msg || '获取调用日志失败')
      }
    } catch (err) {
      debug.error('获取 AI 调用日志失败', err)
      ElMessage.error('获取调用日志失败，请稍后重试')
    } finally {
      state.logsLoading = false
    }
  }

  async function loadQuotas() {
    state.quotasLoading = true
    try {
      const { data: res } = await aiGatewayAdminApi.getQuotas()
      if (res.resp_code === 0) {
        state.quotas = res.datas
      } else {
        ElMessage.error(res.resp_msg || '获取配额列表失败')
      }
    } catch (err) {
      debug.error('获取 AI 配额列表失败', err)
      ElMessage.error('获取配额列表失败，请稍后重试')
    } finally {
      state.quotasLoading = false
    }
  }

  const exportLoading = ref(false)

  /** 导出当前过滤条件的审计日志 CSV（后端已带 BOM，Excel 可直接打开） */
  async function exportLogs() {
    exportLoading.value = true
    try {
      const { data: res } = await aiGatewayAdminApi.exportLogs({
        userId: state.logQuery.userId.trim() || undefined,
        module: state.logQuery.module || undefined,
        status: state.logQuery.status || undefined,
        ...dateParams(),
      })
      if (res.resp_code !== 0) {
        ElMessage.error(res.resp_msg || '导出失败')
        return
      }
      const blob = new Blob([res.datas ?? ''], { type: 'text/csv;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `ai-audit-logs-${state.range[0]}_${state.range[1]}.csv`
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      URL.revokeObjectURL(url)
      ElMessage.success(res.resp_msg || '导出成功')
    } catch (err) {
      if (!isConfirmCancelled(err)) {
        debug.error('导出 AI 调用日志失败', err)
        ElMessage.error('导出失败，请稍后重试')
      }
    } finally {
      exportLoading.value = false
    }
  }

  async function loadUsers() {
    if (state.usersLoaded) return
    try {
      const { data: res } = await aiGatewayAdminApi.getUsers()
      if (res.resp_code === 0) {
        state.users = res.datas ?? []
        state.usersLoaded = true
      }
    } catch (err) {
      debug.error('获取用户列表失败', err)
    }
  }

  function onTabChange(name: string | number) {
    if (name === 'logs' && state.logs.length === 0) {
      loadLogs(true)
    }
    if (name === 'quotas' && !state.quotas) {
      loadQuotas()
    }
  }

  function handleSizeChange(size: number) {
    state.pagination.pageSize = size
    loadLogs()
  }

  function handleCurrentChange(page: number) {
    state.pagination.pageNum = page
    loadLogs()
  }

  function openQuotaDialog(row?: AiQuotaView) {
    state.quotaEditing = Boolean(row)
    state.quotaForm.userId = row?.userId ?? ''
    state.quotaForm.dailyTokenQuota =
      row?.dailyTokenQuota ?? state.quotas?.defaultDailyTokenQuota ?? 200000
    state.quotaForm.remark = row?.remark ?? ''
    state.quotaDialogVisible = true
    loadUsers()
  }

  async function saveQuota() {
    // 部门配额：提交时把 gid 拼成 unit: 前缀存入 userId 字段
    const userId = state.quotaForm.scope === 'unit'
      ? 'unit:' + state.quotaForm.unitGid.trim()
      : state.quotaForm.userId.trim()
    if (!userId || (state.quotaForm.scope === 'unit' && userId === 'unit:')) {
      ElMessage.warning(state.quotaForm.scope === 'unit' ? '请填写部门编号' : '请选择用户')
      return
    }
    state.quotaSaving = true
    try {
      const { data: res } = await aiGatewayAdminApi.saveQuota({
        userId,
        dailyTokenQuota: Math.max(0, Number(state.quotaForm.dailyTokenQuota) || 0),
        remark: state.quotaForm.remark.trim(),
      })
      if (res.resp_code === 0) {
        ElMessage.success('配额已保存')
        state.quotaDialogVisible = false
        loadQuotas()
      } else {
        ElMessage.error(res.resp_msg || '保存配额失败')
      }
    } catch (err) {
      debug.error('保存 AI 配额失败', err)
      ElMessage.error('保存配额失败，请稍后重试')
    } finally {
      state.quotaSaving = false
    }
  }

  async function deleteQuota(row: AiQuotaView) {
    try {
      await ElMessageBox.confirm(
        `确定删除「${row.userName}」的配额覆盖吗？删除后恢复默认配额。`,
        '删除确认',
        { type: 'warning' }
      )
    } catch {
      return
    }
    try {
      const { data: res } = await aiGatewayAdminApi.deleteQuota({ userId: row.userId })
      if (res.resp_code === 0) {
        ElMessage.success('已恢复默认配额')
        loadQuotas()
      } else {
        ElMessage.error(res.resp_msg || '删除失败')
      }
    } catch (err) {
      debug.error('删除 AI 配额失败', err)
      ElMessage.error('删除失败，请稍后重试')
    }
  }

  function moduleLabel(module: string): string {
    return MODULE_LABELS[module] ?? module
  }

  function statusLabel(status: string): string {
    return STATUS_LABELS[status] ?? status
  }

  function mondayOfThisWeek(): string {
    const now = new Date()
    const day = now.getDay() === 0 ? 7 : now.getDay()
    const monday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - day + 1)
    const mm = String(monday.getMonth() + 1).padStart(2, '0')
    const dd = String(monday.getDate()).padStart(2, '0')
    return `${monday.getFullYear()}-${mm}-${dd}`
  }

  async function loadReports() {
    try {
      const { data: res } = await weeklyReportApi.list()
      if (res.resp_code === 0) {
        state.reports = res.datas || []
      }
    } catch (e) {
      console.warn('加载周报失败', e)
    }
  }

  async function generateWeeklyReport() {
    state.reportGenerating = true
    try {
      // 手动触发时生成「本周至今」的快照；周一自动任务生成上一完整周
      const { data: res } = await weeklyReportApi.generate(mondayOfThisWeek())
      if (res.resp_code === 0) {
        ElMessage.success('周报已生成')
        await loadReports()
      } else {
        ElMessage.error(res.resp_msg || '生成失败')
      }
    } catch (e) {
      ElMessage.error('生成失败，请重试')
    } finally {
      state.reportGenerating = false
    }
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

  /**
   * 成本以「毫分」为单位整数存储（1 分 = 1000 毫分）——
   * 单次对话常不足 1 分，用分会把小调用全部取整成 0。
   * 展示时折算为元：不足 1 分保留 4 位小数，避免显示成 ¥0.00。
   */
  function formatCost(milliCents: number | null | undefined): string {
    const yuan = Number(milliCents ?? 0) / 100_000
    if (yuan > 0 && yuan < 0.01) {
      return `¥${yuan.toFixed(4)}`
    }
    return `¥${yuan.toFixed(2)}`
  }

  function formatTime(value: number | string | null | undefined): string {
    if (value === null || value === undefined || value === '') return '-'
    if (typeof value === 'number') {
      return formatDateValue(new Date(value))
    }
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

  function userWidth(calls: number): string {
    if (calls <= 0) return '0%'
    return `${Math.max(2, (calls / userMax.value) * 100)}%`
  }

  function modelWidth(calls: number): string {
    if (calls <= 0) return '0%'
    return `${Math.max(2, (calls / modelMax.value) * 100)}%`
  }

  function usagePercent(row: AiQuotaView): number {
    if (row.effectiveQuota <= 0) return 0
    return Math.min(100, Math.round((row.usedToday / row.effectiveQuota) * 100))
  }

  function quotaText(quota: number | null | undefined): string {
    if (quota === null || quota === undefined) return '默认'
    return quota <= 0 ? '不限制' : `${formatTokens(quota)} tokens/日`
  }

  function userLabel(option: AiGatewayUserOption): string {
    const name = option.uname || option.uaccess || option.uid
    return option.uaccess ? `${name}（${option.uaccess}）` : name
  }

  return {
    state,
    trendMax,
    loadConfig,
    loadSummary,
    retryOverview,
    loadLogs,
    loadQuotas,
    exportLoading,
    exportLogs,
    onTabChange,
    handleSizeChange,
    handleCurrentChange,
    openQuotaDialog,
    saveQuota,
    deleteQuota,
    moduleLabel,
    statusLabel,
    statusTagType,
    formatTokens,
    formatDuration,
    formatCost,
    loadReports,
    generateWeeklyReport,
    formatTime,
    trendHeight,
    failedHeight,
    moduleWidth,
    modelWidth,
    userWidth,
    usagePercent,
    quotaText,
    userLabel,
  }
}
