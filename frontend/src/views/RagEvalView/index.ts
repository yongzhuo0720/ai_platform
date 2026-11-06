import { computed, onMounted, onUnmounted, reactive, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ragEvalApi,
  type RagEvalDataset,
  type RagEvalDatasetDetail,
  type RagEvalDatasetRequest,
  type RagEvalDiffStatus,
  type RagEvalRerankMode,
  type RagEvalRun,
  type RagEvalRunDetail,
  type RagEvalRunStatus,
} from '@/api/ragEval'
import { debug } from '@/utils/debug'
import { parseGoldenSetCsv, splitKeywords } from './csv'

const POLL_INTERVAL_MS = 2000
const MAX_CSV_BYTES = 2 * 1024 * 1024

export interface EditorCase {
  question: string
  expectedDoc: string
  keywordsText: string
  category: string
}

type TagType = 'info' | 'warning' | 'success' | 'danger'

const STATUS_META: Record<RagEvalRunStatus, { label: string; type: TagType }> = {
  queued: { label: '排队中', type: 'info' },
  running: { label: '运行中', type: 'warning' },
  success: { label: '成功', type: 'success' },
  partial: { label: '部分成功', type: 'warning' },
  failed: { label: '失败', type: 'danger' },
}

const CHANNEL_META: Record<string, { label: string; type: TagType }> = {
  both: { label: '双路', type: 'success' },
  dense: { label: '向量路', type: 'info' },
  lexical: { label: '关键词路', type: 'warning' },
}

const DIFF_META: Record<RagEvalDiffStatus, { label: string; type: TagType }> = {
  regressed: { label: '退化', type: 'danger' },
  improved: { label: '提升', type: 'success' },
  mixed: { label: '有升有降', type: 'warning' },
  unchanged: { label: '持平', type: 'info' },
  unknown: { label: '不可比', type: 'info' },
}

const isActiveStatus = (status: RagEvalRunStatus) =>
  status === 'queued' || status === 'running'

const blankEditorCase = (): EditorCase => ({
  question: '',
  expectedDoc: '',
  keywordsText: '',
  category: '',
})

const isBlankCase = (item: EditorCase) =>
  !item.question.trim() && !item.expectedDoc.trim() && !item.keywordsText.trim()

const readFileAsText = (file: File) =>
  new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(new Error('读取文件失败'))
    reader.readAsText(file, 'utf-8')
  })

export function useIndex() {
  const route = useRoute()
  const state = reactive({
    datasets: [] as RagEvalDataset[],
    datasetsLoading: false,
    selectedDatasetKey: '',
    detail: null as RagEvalDatasetDetail | null,
    detailLoading: false,
    runs: [] as RagEvalRun[],
    runsLoading: false,
    topK: 5,
    rerankMode: 'auto' as RagEvalRerankMode,
    startingRun: false,
    selectedRunKey: '',
    baselineRunKey: '',
    runDetail: null as RagEvalRunDetail | null,
    runDetailLoading: false,
    runDialogVisible: false,
    editorVisible: false,
    editorSaving: false,
    editor: {
      datasetKey: '',
      name: '',
      kbName: '默认知识库',
      description: '',
      cases: [blankEditorCase()] as EditorCase[],
    },
  })

  let pollTimer: number | null = null
  let pollBusy = false

  const isEditing = computed(() => Boolean(state.editor.datasetKey))
  const hasActiveRun = computed(() => state.runs.some((run) => isActiveStatus(run.status)))
  const selectedDataset = computed(() => state.detail?.dataset ?? null)

  const metricCards = computed(() => {
    const run = state.runDetail?.run
    const baseline = state.runDetail?.baseline
    if (!run) return []
    return [
      {
        key: 'recall',
        label: 'Recall@K',
        value: run.recall,
        base: baseline?.recall,
        hint: '前 K 条覆盖相关文档的比例，RAG 最关键指标',
      },
      {
        key: 'precision',
        label: 'Precision@K',
        value: run.precision,
        base: baseline?.precision,
        hint: '前 K 条中相关文档的占比（同文档只计一次）',
      },
      {
        key: 'mrr',
        label: 'MRR',
        value: run.mrr,
        base: baseline?.mrr,
        hint: '第一条相关结果排名的倒数均值，衡量一次命中能力',
      },
      {
        key: 'ndcg',
        label: 'NDCG@K',
        value: run.ndcg,
        base: baseline?.ndcg,
        hint: '按排名折损的综合指标，越靠前权重越高',
      },
      {
        key: 'keyword',
        label: '关键词命中率',
        value: run.keywordHitRate,
        base: baseline?.keywordHitRate,
        hint: '全部关键词出现在期望文档同一命中分块的比例',
      },
    ]
  })

  /** 基线口径未知（历史运行）不算不一致；两边都有版本且不同才提示 */
  const baselineWarnings = computed(() => state.runDetail?.baselineWarnings ?? [])

  const baselineCandidates = computed(() =>
    state.runs.filter(
      (run) =>
        run.runKey !== state.selectedRunKey &&
        (run.status === 'success' || run.status === 'partial'),
    ),
  )

  const rerankModeLabel = computed(() =>
    state.runDetail?.run.rerankMode === 'off' ? '主动关闭' : '跟随全局',
  )

  /** 区分「主动关闭」与「没生效」：两者 rerankApplied 都是 false，但原因不同 */
  const rerankEffectHint = computed(() => {
    const run = state.runDetail?.run
    if (!run) return ''
    if (isActiveStatus(run.status)) return '运行完成后可确认精排是否生效'
    if (run.rerankMode === 'off') {
      return '本次运行主动关闭精排，可与开启精排的运行做 A/B 对比'
    }
    if (run.rerankApplied) return '精排已在本轮真实生效'
    return '未应用精排：全局未配置精排模型，或调用失败已降级'
  })

  const counterpartRerankMode = computed<RagEvalRerankMode>(() =>
    state.runDetail?.run.rerankMode === 'off' ? 'auto' : 'off',
  )

  const counterpartRerankLabel = computed(() =>
    counterpartRerankMode.value === 'off' ? '关闭精排再跑一次' : '跟随全局再跑一次',
  )

  const canStartCounterpart = computed(() => {
    const run = state.runDetail?.run
    if (!run || state.startingRun || hasActiveRun.value) return false
    return run.status === 'success' || run.status === 'partial'
  })

  onMounted(() => {
    // 站内通知会带 ?datasetKey=xxx 深链，首次加载直接选中对应数据集
    loadDatasets(queryDatasetKey())
  })

  // 已经停留在本页时再点另一条通知，路由复用组件，需要监听查询参数变化
  watch(
    () => route.query.datasetKey,
    (value) => {
      if (typeof value === 'string' && value) {
        void loadDatasets(value)
      }
    },
  )

  function queryDatasetKey(): string | undefined {
    const value = route.query.datasetKey
    return typeof value === 'string' && value ? value : undefined
  }

  onUnmounted(() => {
    stopPolling()
  })

  async function loadDatasets(preferredKey?: string) {
    state.datasetsLoading = true
    try {
      const { data: res } = await ragEvalApi.listDatasets()
      if (res.resp_code !== 0) {
        ElMessage.error(res.resp_msg || '获取数据集失败')
        return
      }
      state.datasets = res.datas ?? []
      const current = state.datasets.find(
        (item) => item.datasetKey === state.selectedDatasetKey,
      )
      const target =
        state.datasets.find((item) => item.datasetKey === preferredKey) ||
        current ||
        state.datasets[0]
      state.selectedDatasetKey = target?.datasetKey ?? ''
      if (!state.selectedDatasetKey) {
        state.detail = null
        state.runs = []
        state.selectedRunKey = ''
        state.baselineRunKey = ''
        state.runDetail = null
        state.runDialogVisible = false
        stopPolling()
        return
      }
      await Promise.all([loadDetail(), loadRuns()])
    } catch (err) {
      debug.error('获取 RAG 评估数据集失败', err)
      ElMessage.error('获取数据集失败，请稍后重试')
    } finally {
      state.datasetsLoading = false
    }
  }

  async function selectDataset(datasetKey: string) {
    if (!datasetKey || datasetKey === state.selectedDatasetKey) return
    state.selectedDatasetKey = datasetKey
    state.detail = null
    state.selectedRunKey = ''
    state.baselineRunKey = ''
    state.runDetail = null
    state.runDialogVisible = false
    stopPolling()
    await Promise.all([loadDetail(), loadRuns()])
  }

  async function loadDetail() {
    const key = state.selectedDatasetKey
    if (!key) {
      state.detail = null
      return
    }
    state.detailLoading = true
    try {
      const { data: res } = await ragEvalApi.getDataset(key)
      if (key !== state.selectedDatasetKey) return
      if (res.resp_code === 0) {
        state.detail = res.datas ?? null
      } else {
        ElMessage.error(res.resp_msg || '获取数据集详情失败')
      }
    } catch (err) {
      debug.error('获取 RAG 评估数据集详情失败', err)
      ElMessage.error('获取数据集详情失败，请稍后重试')
    } finally {
      state.detailLoading = false
    }
  }

  async function loadRuns() {
    const key = state.selectedDatasetKey
    if (!key) return
    state.runsLoading = true
    try {
      const { data: res } = await ragEvalApi.listRuns(key)
      if (key !== state.selectedDatasetKey) return
      if (res.resp_code === 0) {
        state.runs = res.datas ?? []
        if (state.selectedRunKey && !state.runs.some((run) => run.runKey === state.selectedRunKey)) {
          state.selectedRunKey = ''
          state.baselineRunKey = ''
          state.runDetail = null
          state.runDialogVisible = false
        }
        if (state.baselineRunKey && !state.runs.some((run) => run.runKey === state.baselineRunKey)) {
          state.baselineRunKey = ''
        }
        syncPolling()
      } else {
        ElMessage.error(res.resp_msg || '获取运行记录失败')
      }
    } catch (err) {
      debug.error('获取 RAG 评估运行记录失败', err)
      ElMessage.error('获取运行记录失败，请稍后重试')
    } finally {
      state.runsLoading = false
    }
  }

  function syncPolling() {
    if (hasActiveRun.value) {
      if (pollTimer === null) {
        pollTimer = window.setInterval(poll, POLL_INTERVAL_MS)
      }
    } else {
      stopPolling()
    }
  }

  function stopPolling() {
    if (pollTimer !== null) {
      window.clearInterval(pollTimer)
      pollTimer = null
    }
  }

  async function poll() {
    if (pollBusy || !state.selectedDatasetKey) return
    pollBusy = true
    const key = state.selectedDatasetKey
    try {
      const { data: res } = await ragEvalApi.listRuns(key)
      if (res.resp_code === 0 && key === state.selectedDatasetKey) {
        state.runs = res.datas ?? []
        if (state.baselineRunKey && !state.runs.some((run) => run.runKey === state.baselineRunKey)) {
          state.baselineRunKey = ''
        }
        syncPolling()
        if (state.selectedRunKey) {
          await loadRunDetail(state.selectedRunKey, true)
        }
      }
    } catch (err) {
      // 轮询失败不打扰用户，下一轮自动重试
      debug.warn?.('RAG 评估进度轮询失败', err)
    } finally {
      pollBusy = false
    }
  }

  async function startRun() {
    if (!state.detail?.cases.length) {
      ElMessage.warning('数据集中没有用例，请先编辑数据集')
      return
    }
    state.startingRun = true
    try {
      const { data: res } = await ragEvalApi.startRun(
        state.selectedDatasetKey,
        state.topK,
        state.rerankMode,
      )
      if (res.resp_code === 0) {
        ElMessage.success(res.resp_msg || '评估已启动')
        await loadRuns()
        if (res.datas) {
          await openRunByKey(res.datas)
        }
      } else {
        ElMessage.error(res.resp_msg || '启动评估失败')
      }
    } catch (err) {
      debug.error('启动 RAG 评估失败', err)
      ElMessage.error('启动评估失败，请稍后重试')
    } finally {
      state.startingRun = false
    }
  }

  /** 以当前运行的精排策略的相反值复跑，其他变量（数据集、TopK）保持不变 */
  async function startCounterpartRun() {
    const run = state.runDetail?.run
    if (!run || !canStartCounterpart.value) return
    const mode = counterpartRerankMode.value
    state.startingRun = true
    try {
      const { data: res } = await ragEvalApi.startRun(run.datasetKey, run.topK, mode)
      if (res.resp_code === 0) {
        ElMessage.success(mode === 'off' ? '已按「关闭精排」启动复跑' : '已按「跟随全局」启动复跑')
        state.rerankMode = mode
        await loadRuns()
        if (res.datas) {
          await openRunByKey(res.datas)
        }
      } else {
        ElMessage.error(res.resp_msg || '启动复跑失败')
      }
    } catch (err) {
      debug.error('启动 RAG 评估复跑失败', err)
      ElMessage.error('启动复跑失败，请稍后重试')
    } finally {
      state.startingRun = false
    }
  }

  async function openRunByKey(runKey: string) {
    state.selectedRunKey = runKey
    state.baselineRunKey = ''
    state.runDialogVisible = true
    await loadRunDetail(runKey, false)
  }

  function openRun(row: RagEvalRun) {
    openRunByKey(row.runKey)
  }

  async function selectBaseline(runKey: string) {
    state.baselineRunKey = runKey
    if (!state.selectedRunKey) return
    await loadRunDetail(state.selectedRunKey, false)
  }

  async function loadRunDetail(runKey: string, silent: boolean) {
    if (!silent) state.runDetailLoading = true
    const baselineKey = state.baselineRunKey
    try {
      const { data: res } = await ragEvalApi.getRun(runKey, baselineKey || undefined)
      if (res.resp_code === 0) {
        if (state.selectedRunKey === runKey && state.baselineRunKey === baselineKey) {
          state.runDetail = res.datas ?? null
        }
      } else if (!silent) {
        ElMessage.error(res.resp_msg || '获取运行详情失败')
      }
    } catch (err) {
      if (!silent) {
        debug.error('获取 RAG 评估运行详情失败', err)
        ElMessage.error('获取运行详情失败，请稍后重试')
      }
    } finally {
      if (!silent) state.runDetailLoading = false
    }
  }

  async function deleteRun(row: RagEvalRun) {
    try {
      await ElMessageBox.confirm(
        `确定删除 ${formatDateTime(row.startedAt)} 的这次运行记录吗？逐题明细将一并删除。`,
        '删除运行记录',
        { type: 'warning' },
      )
    } catch {
      return
    }
    try {
      const { data: res } = await ragEvalApi.deleteRun(row.runKey)
      if (res.resp_code === 0) {
        ElMessage.success('运行记录已删除')
        if (state.baselineRunKey === row.runKey) {
          state.baselineRunKey = ''
        }
        if (state.selectedRunKey === row.runKey) {
          state.selectedRunKey = ''
          state.runDetail = null
          state.runDialogVisible = false
        }
        await loadRuns()
        if (state.runDialogVisible && state.selectedRunKey) {
          await loadRunDetail(state.selectedRunKey, true)
        }
      } else {
        ElMessage.error(res.resp_msg || '删除运行记录失败')
      }
    } catch (err) {
      debug.error('删除 RAG 评估运行记录失败', err)
      ElMessage.error('删除运行记录失败，请稍后重试')
    }
  }

  async function deleteDataset() {
    const dataset = selectedDataset.value
    if (!dataset) return
    try {
      await ElMessageBox.confirm(
        `确定删除数据集「${dataset.name}」吗？用例与全部运行历史都会一并删除，无法恢复。`,
        '删除数据集',
        { type: 'warning' },
      )
    } catch {
      return
    }
    try {
      const { data: res } = await ragEvalApi.deleteDataset(dataset.datasetKey)
      if (res.resp_code === 0) {
        ElMessage.success('数据集已删除')
        state.selectedDatasetKey = ''
        stopPolling()
        await loadDatasets()
      } else {
        ElMessage.error(res.resp_msg || '删除数据集失败')
      }
    } catch (err) {
      debug.error('删除 RAG 评估数据集失败', err)
      ElMessage.error('删除数据集失败，请稍后重试')
    }
  }

  function openCreateEditor() {
    state.editor = {
      datasetKey: '',
      name: '',
      kbName: state.detail?.dataset.kbName || '默认知识库',
      description: '',
      cases: [blankEditorCase()],
    }
    state.editorVisible = true
  }

  function openEditEditor() {
    const detail = state.detail
    if (!detail) return
    state.editor = {
      datasetKey: detail.dataset.datasetKey,
      name: detail.dataset.name,
      kbName: detail.dataset.kbName,
      description: detail.dataset.description ?? '',
      cases: detail.cases.map((item) => ({
        question: item.question,
        expectedDoc: item.expectedDoc,
        keywordsText: item.keywords.join(', '),
        category: item.category,
      })),
    }
    state.editorVisible = true
  }

  function addCase() {
    state.editor.cases.push(blankEditorCase())
  }

  function removeCase(index: number) {
    state.editor.cases.splice(index, 1)
  }

  async function handleCsvFileChange(event: Event) {
    const input = event.target as HTMLInputElement
    const file = input.files?.[0]
    if (!file) return
    if (file.size > MAX_CSV_BYTES) {
      ElMessage.warning('CSV 文件不能超过 2MB')
      input.value = ''
      return
    }
    try {
      const rows = parseGoldenSetCsv(await readFileAsText(file))
      const kept = state.editor.cases.filter((item) => !isBlankCase(item))
      state.editor.cases = [...kept, ...rows]
      if (!state.editor.name.trim()) {
        state.editor.name = file.name.replace(/\.csv$/i, '')
      }
      ElMessage.success(`已导入 ${rows.length} 条用例`)
    } catch (err) {
      ElMessage.error(err instanceof Error ? err.message : 'CSV 解析失败')
    } finally {
      input.value = ''
    }
  }

  async function submitEditor() {
    const name = state.editor.name.trim()
    if (!name) {
      ElMessage.warning('请填写数据集名称')
      return
    }
    const cases = state.editor.cases.map((item) => ({
      question: item.question.trim(),
      expectedDoc: item.expectedDoc.trim(),
      keywords: splitKeywords(item.keywordsText),
      category: item.category.trim() || '未分类',
    }))
    if (!cases.length) {
      ElMessage.warning('至少需要一条评估用例')
      return
    }
    for (let i = 0; i < cases.length; i++) {
      if (!cases[i].question) {
        ElMessage.warning(`第 ${i + 1} 条用例的问题不能为空`)
        return
      }
      if (!cases[i].expectedDoc) {
        ElMessage.warning(`第 ${i + 1} 条用例的期望文档不能为空`)
        return
      }
    }
    const seen = new Set<string>()
    for (const item of cases) {
      if (seen.has(item.question)) {
        ElMessage.warning(`存在重复问题：${item.question}`)
        return
      }
      seen.add(item.question)
    }

    const payload: RagEvalDatasetRequest = {
      name,
      kbName: state.editor.kbName.trim() || '默认知识库',
      description: state.editor.description.trim(),
      cases,
    }
    const editingKey = state.editor.datasetKey
    if (editingKey) payload.datasetKey = editingKey

    state.editorSaving = true
    try {
      const { data: res } = editingKey
        ? await ragEvalApi.updateDataset(payload)
        : await ragEvalApi.createDataset(payload)
      if (res.resp_code === 0) {
        ElMessage.success(res.resp_msg || '保存成功')
        state.editorVisible = false
        await loadDatasets(editingKey || (res.datas as string | undefined))
      } else {
        ElMessage.error(res.resp_msg || '保存失败')
      }
    } catch (err) {
      debug.error('保存 RAG 评估数据集失败', err)
      ElMessage.error('保存数据集失败，请稍后重试')
    } finally {
      state.editorSaving = false
    }
  }

  function statusMeta(status: RagEvalRunStatus) {
    return STATUS_META[status] ?? { label: status, type: 'info' as const }
  }

  function channelMeta(channel?: string | null) {
    return CHANNEL_META[channel ?? ''] ?? { label: channel || '-', type: 'info' as const }
  }

  /** 分数为 -1 表示该片段没有参与这条召回路径，不能显示成 0 分 */
  function formatScore(value: number | null | undefined) {
    if (value === null || value === undefined) return '-'
    return value < 0 ? '未参与' : value.toFixed(4)
  }

  function formatLexicalScore(value: number | null | undefined) {
    if (value === null || value === undefined) return '-'
    return value < 0 ? '未参与' : String(value)
  }

  function runProgress(row: RagEvalRun) {
    if (!row.total) return row.status === 'success' || row.status === 'partial' ? 100 : 0
    if (!isActiveStatus(row.status)) return 100
    return Math.min(100, Math.round(((row.processed ?? 0) / row.total) * 100))
  }

  function formatMetric(value: number | null | undefined) {
    return value === null || value === undefined ? '-' : value.toFixed(4)
  }

  function formatPercent(value: number | null | undefined) {
    return value === null || value === undefined ? '-' : `${(value * 100).toFixed(1)}%`
  }

  function formatDelta(value: number | null | undefined, base: number | null | undefined) {
    if (value === null || value === undefined) return '-'
    if (base === null || base === undefined) return '首次运行'
    const diff = (value - base) * 100
    if (Math.abs(diff) < 0.05) return '持平'
    return `${diff > 0 ? '+' : ''}${diff.toFixed(1)}pp`
  }

  function deltaClass(value: number | null | undefined, base: number | null | undefined) {
    if (value === null || value === undefined || base === null || base === undefined) {
      return 'rag-eval-delta'
    }
    const diff = (value - base) * 100
    if (Math.abs(diff) < 0.05) return 'rag-eval-delta even'
    return diff > 0 ? 'rag-eval-delta up' : 'rag-eval-delta down'
  }

  function formatDateTime(value: string | number | null | undefined) {
    if (value === null || value === undefined || value === '') return '-'
    const parsed =
      typeof value === 'number'
        ? new Date(value)
        : new Date(String(value).replace(/\//g, '-').replace(' ', 'T'))
    if (Number.isNaN(parsed.getTime())) return String(value)
    const pad = (num: number) => String(num).padStart(2, '0')
    return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`
  }

  function formatDuration(value: number | null | undefined) {
    if (value === null || value === undefined) return '-'
    if (value < 1000) return `${value}ms`
    return `${(value / 1000).toFixed(1)}s`
  }

  function diffStatusMeta(status?: string | null) {
    return DIFF_META[status as RagEvalDiffStatus] ?? { label: status || '-', type: 'info' as const }
  }

  function formatRank(rank: number | null | undefined) {
    return rank && rank > 0 ? `第 ${rank} 位` : '未命中'
  }

  function formatPp(delta: number | null | undefined) {
    if (delta === null || delta === undefined) return '-'
    const pp = delta * 100
    if (Math.abs(pp) < 0.05) return '0.0 pp'
    return `${pp > 0 ? '+' : ''}${pp.toFixed(1)} pp`
  }

  function baselineOptionLabel(run: RagEvalRun) {
    const parts = [formatDateTime(run.startedAt), `TopK ${run.topK}`, statusMeta(run.status).label]
    parts.push(run.rerankMode === 'off' ? '关闭精排' : '跟随全局')
    const currentSet = state.runDetail?.run?.caseSetKey
    if (currentSet && run.caseSetKey === currentSet) parts.push('题目一致')
    if (run.metricSpec) parts.push(run.metricSpec)
    return parts.join(' · ')
  }

  return {
    state,
    isEditing,
    hasActiveRun,
    selectedDataset,
    metricCards,
    baselineWarnings,
    baselineCandidates,
    rerankModeLabel,
    rerankEffectHint,
    counterpartRerankLabel,
    canStartCounterpart,
    loadDatasets,
    selectDataset,
    loadRuns,
    startRun,
    startCounterpartRun,
    openRun,
    openRunByKey,
    selectBaseline,
    deleteRun,
    deleteDataset,
    openCreateEditor,
    openEditEditor,
    addCase,
    removeCase,
    handleCsvFileChange,
    submitEditor,
    statusMeta,
    runProgress,
    channelMeta,
    formatScore,
    formatLexicalScore,
    diffStatusMeta,
    formatRank,
    formatPp,
    baselineOptionLabel,
    formatMetric,
    formatPercent,
    formatDelta,
    deltaClass,
    formatDateTime,
    formatDuration,
  }
}
