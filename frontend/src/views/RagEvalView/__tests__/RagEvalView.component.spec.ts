import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reactive } from 'vue'

// 通知深链带来的查询参数；默认空对象保持原有首屏行为
const routeState = reactive({ query: {} as Record<string, string> })

vi.mock('vue-router', () => ({
  useRoute: () => routeState,
}))

vi.mock('@/api/ragEval', () => ({
  ragEvalApi: {
    listDatasets: vi.fn(),
    getDataset: vi.fn(),
    createDataset: vi.fn(),
    updateDataset: vi.fn(),
    deleteDataset: vi.fn(),
    startRun: vi.fn(),
    listRuns: vi.fn(),
    getRun: vi.fn(),
    deleteRun: vi.fn(),
  },
}))

import RagEvalView from '../index.vue'
import { ragEvalApi } from '@/api/ragEval'

enableAutoUnmount(afterEach)

const api = ragEvalApi as unknown as Record<string, Mock>

const ok = (datas: unknown, resp_msg = 'ok') => ({
  data: { resp_code: 0, resp_msg, datas },
})
const fail = (resp_msg: string) => ({ data: { resp_code: 500, resp_msg } })

const datasets = [
  {
    datasetKey: 'd1',
    name: '制度黄金集',
    kbName: '默认知识库',
    description: '差旅与考勤制度问答',
    caseCount: 2,
  },
  {
    datasetKey: 'd2',
    name: '产品 FAQ',
    kbName: '产品库',
    description: '',
    caseCount: 1,
  },
]

const detail1 = {
  dataset: datasets[0],
  cases: [
    {
      question: '出差住宿每晚上限多少',
      expectedDoc: '差旅报销制度',
      keywords: ['600', '住宿'],
      category: '数值型',
    },
    {
      question: '出差 3 天以内由谁审批',
      expectedDoc: '差旅报销制度',
      keywords: [],
      category: '事实型',
    },
  ],
}

const detail2 = {
  dataset: datasets[1],
  cases: [
    {
      question: '如何重置密码',
      expectedDoc: '产品FAQ',
      keywords: ['重置'],
      category: '操作型',
    },
  ],
}

const baseRun = {
  runKey: 'r1',
  datasetKey: 'd1',
  datasetName: '制度黄金集',
  kbName: '默认知识库',
  topK: 5,
  total: 2,
  processed: 2,
  recall: 1,
  precision: 0.6,
  mrr: 0.75,
  ndcg: 0.81,
  keywordHitRate: 0.75,
  missCount: 0,
  keywordEvaluatedCount: 2,
  embeddingModel: 'bge-m3',
  metricSpec: 'rag-eval-v1',
  retrievalMode: 'hybrid-rrf+rerank',
  rerankApplied: true,
  rerankMode: 'auto' as const,
  startedAt: Date.UTC(2026, 10, 5, 9, 0, 0),
  durationMs: 2300,
  status: 'success' as const,
}

const baselineRun = {
  ...baseRun,
  runKey: 'r0',
  recall: 0.9,
  precision: 0.5,
  mrr: 0.7,
  ndcg: 0.75,
  keywordHitRate: 0.5,
  retrievalMode: 'hybrid-rrf',
  rerankApplied: false,
  startedAt: Date.UTC(2026, 10, 4, 9, 0, 0),
}

const runningRun = {
  ...baseRun,
  runKey: 'r2',
  status: 'running' as const,
  processed: 1,
  recall: null,
  precision: null,
  mrr: null,
  ndcg: null,
  keywordHitRate: null,
  missCount: null,
  keywordEvaluatedCount: null,
  embeddingModel: null,
  durationMs: null,
  startedAt: Date.UTC(2026, 10, 5, 10, 0, 0),
}

const runDetail = {
  run: baseRun,
  baseline: baselineRun,
  baselineExplicit: false,
  baselineCompatible: true,
  baselineWarnings: [] as string[],
  regressedCount: 1,
  improvedCount: 1,
  caseDiffs: [
    {
      caseIndex: 1,
      question: '出差 3 天以内由谁审批',
      baselineFirstHitRank: 1,
      currentFirstHitRank: 2,
      recallDelta: 0,
      baselineKeywordHit: true,
      currentKeywordHit: false,
      status: 'regressed' as const,
      note: '首位命中 第 1 位 → 第 2 位；关键词 命中 → 未命中',
    },
    {
      caseIndex: 0,
      question: '出差住宿每晚上限多少',
      baselineFirstHitRank: 2,
      currentFirstHitRank: 1,
      recallDelta: 0.5,
      baselineKeywordHit: false,
      currentKeywordHit: true,
      status: 'improved' as const,
      note: 'Recall 0.500 → 1.000；首位命中 第 2 位 → 第 1 位；关键词 未命中 → 命中',
    },
  ],
  results: [
    {
      caseIndex: 0,
      question: '出差住宿每晚上限多少',
      expectedDoc: '差旅报销制度',
      keywords: ['600', '住宿'],
      category: '数值型',
      retrievedDocs: ['差旅报销制度', '考勤制度'],
      retrievedHits: [
        {
          rank: 1,
          docName: '差旅报销制度',
          chunkIndex: 3,
          score: 0.0328,
          denseScore: 0.91,
          lexicalScore: 3,
          channel: 'both',
          contentPreview: '住宿标准：一线城市每晚上限 600 元',
        },
        {
          rank: 2,
          docName: '考勤制度',
          chunkIndex: 0,
          score: 0.0161,
          denseScore: 0.42,
          lexicalScore: -1,
          channel: 'dense',
          contentPreview: '迟到与旷工认定',
        },
      ],
      topDoc: '差旅报销制度',
      firstHitRank: 1,
      recall: 1,
      precision: 0.5,
      reciprocalRank: 1,
      ndcg: 1,
      keywordHit: true,
      keywordEvaluated: true,
      durationMs: 320,
      errorMessage: null,
    },
    {
      caseIndex: 1,
      question: '出差 3 天以内由谁审批',
      expectedDoc: '差旅报销制度',
      keywords: ['审批'],
      category: '事实型',
      retrievedDocs: ['考勤制度', '差旅报销制度'],
      retrievedHits: [],
      topDoc: '考勤制度',
      firstHitRank: 2,
      recall: 1,
      precision: 0.5,
      reciprocalRank: 0.5,
      ndcg: 0.6309,
      keywordHit: false,
      keywordEvaluated: true,
      durationMs: 280,
      errorMessage: null,
    },
  ],
}

const messageSpies = () => ({
  success: vi.spyOn(ElMessage, 'success').mockReturnValue({ close: () => {} } as never),
  error: vi.spyOn(ElMessage, 'error').mockReturnValue({ close: () => {} } as never),
  warning: vi.spyOn(ElMessage, 'warning').mockReturnValue({ close: () => {} } as never),
  info: vi.spyOn(ElMessage, 'info').mockReturnValue({ close: () => {} } as never),
})

const clickByText = async (
  wrapper: ReturnType<typeof mount>,
  selector: string,
  text: string,
) => {
  const target = wrapper.findAll(selector).find((item) => item.text().includes(text))
  expect(target, `${selector} ${text}`).toBeTruthy()
  await target!.trigger('click')
  await flushPromises()
}

const mountView = () => mount(RagEvalView)

const stubInitialLoad = () => {
  api.listDatasets.mockResolvedValue(ok(datasets))
  api.getDataset.mockImplementation((key: string) =>
    Promise.resolve(ok(key === 'd2' ? detail2 : detail1)),
  )
  api.listRuns.mockImplementation((key: string) =>
    Promise.resolve(ok(key === 'd2' ? [] : [baseRun])),
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  routeState.query = {}
})

describe('RagEvalView', () => {
  it('通知深链带 datasetKey 时直接选中对应数据集', async () => {
    routeState.query = { datasetKey: 'd2' }
    stubInitialLoad()

    mountView()
    await flushPromises()

    expect(api.getDataset).toHaveBeenCalledWith('d2')
    expect(api.listRuns).toHaveBeenCalledWith('d2')
  })

  it('已在本页时收到新的通知深链会切换数据集', async () => {
    stubInitialLoad()
    mountView()
    await flushPromises()
    expect(api.getDataset).toHaveBeenLastCalledWith('d1')

    routeState.query = { datasetKey: 'd2' }
    await flushPromises()

    expect(api.getDataset).toHaveBeenLastCalledWith('d2')
  })

  it('首屏加载数据集并默认选中第一个，展示用例、历史指标与状态', async () => {
    stubInitialLoad()
    const wrapper = mountView()
    await flushPromises()

    expect(api.listDatasets).toHaveBeenCalledTimes(1)
    expect(api.getDataset).toHaveBeenCalledWith('d1')
    expect(api.listRuns).toHaveBeenCalledWith('d1')

    const items = wrapper.findAll('.rag-eval-dataset-item')
    expect(items).toHaveLength(2)
    expect(items[0].classes()).toContain('active')
    expect(items[0].text()).toContain('制度黄金集')
    expect(items[0].text()).toContain('2 条')

    expect(wrapper.text()).toContain('出差住宿每晚上限多少')
    expect(wrapper.text()).toContain('差旅报销制度')
    expect(wrapper.text()).toContain('数值型')

    const runRow = wrapper.find('.rag-eval-runs .el-table__row')
    expect(runRow.text()).toContain('成功')
    expect(runRow.text()).toContain('1.0000')
    expect(runRow.text()).toContain('0.7500')
    expect(runRow.text()).toContain('2.3s')
  })

  it('切换数据集会按新 datasetKey 重新拉取详情、用例与运行历史', async () => {
    stubInitialLoad()
    const wrapper = mountView()
    await flushPromises()

    await clickByText(wrapper, '.rag-eval-dataset-item', '产品 FAQ')

    expect(api.getDataset).toHaveBeenLastCalledWith('d2')
    expect(api.listRuns).toHaveBeenLastCalledWith('d2')
    expect(wrapper.text()).toContain('如何重置密码')
    expect(wrapper.text()).not.toContain('出差住宿每晚上限多少')
  })

  it('新建数据集：校验必填、规范化关键词并调用真实接口', async () => {
    const spies = messageSpies()
    api.listDatasets.mockResolvedValue(ok([]))
    const wrapper = mountView()
    await flushPromises()

    await clickByText(wrapper, 'button', '新建数据集')
    expect((wrapper.vm as any).state.editorVisible).toBe(true)

    await clickByText(wrapper, '.el-dialog__footer button', '保存')
    expect(spies.warning).toHaveBeenCalledWith('请填写数据集名称')

    const state = (wrapper.vm as any).state
    state.editor.name = '  差旅黄金集  '
    state.editor.kbName = ''
    state.editor.description = '  覆盖差旅制度  '
    state.editor.cases[0].question = '  住宿上限是多少  '
    state.editor.cases[0].expectedDoc = '  差旅报销制度  '
    state.editor.cases[0].keywordsText = '600，住宿, 600'
    state.editor.cases[0].category = ''

    api.createDataset.mockResolvedValue(ok('d3', '数据集已创建，共 1 条用例'))
    api.getDataset.mockResolvedValue(ok(detail1))
    api.listRuns.mockResolvedValue(ok([]))
    await clickByText(wrapper, '.el-dialog__footer button', '保存')

    expect(api.createDataset).toHaveBeenCalledWith({
      name: '差旅黄金集',
      kbName: '默认知识库',
      description: '覆盖差旅制度',
      cases: [
        {
          question: '住宿上限是多少',
          expectedDoc: '差旅报销制度',
          keywords: ['600', '住宿'],
          category: '未分类',
        },
      ],
    })
    expect(spies.success).toHaveBeenCalledWith('数据集已创建，共 1 条用例')
    expect(state.editorVisible).toBe(false)
    expect(api.listDatasets).toHaveBeenCalledTimes(2)
  })

  it('编辑数据集：回填当前用例并整体提交写时复制接口', async () => {
    const spies = messageSpies()
    stubInitialLoad()
    api.updateDataset.mockResolvedValue(ok(true, '保存成功，共 2 条用例'))
    const wrapper = mountView()
    await flushPromises()

    await clickByText(wrapper, '.rag-eval-panel-actions button', '编辑')
    expect((wrapper.vm as any).state.editor.datasetKey).toBe('d1')
    expect((wrapper.vm as any).state.editor.cases[0].keywordsText).toBe('600, 住宿')

    ;(wrapper.vm as any).state.editor.cases[0].question = '住宿每晚上限是多少'
    await clickByText(wrapper, '.el-dialog__footer button', '保存')

    expect(api.updateDataset).toHaveBeenCalledWith(
      expect.objectContaining({
        datasetKey: 'd1',
        name: '制度黄金集',
        cases: expect.arrayContaining([
          expect.objectContaining({
            question: '住宿每晚上限是多少',
            keywords: ['600', '住宿'],
          }),
        ]),
      }),
    )
    expect(spies.success).toHaveBeenCalledWith('保存成功，共 2 条用例')
  })

  it('导入 CSV：替换空白行、自动填名称，并在解析失败时给出明确原因', async () => {
    const spies = messageSpies()
    api.listDatasets.mockResolvedValue(ok([]))
    const wrapper = mountView()
    await flushPromises()
    await clickByText(wrapper, 'button', '新建数据集')

    const csv = [
      'question,expected_doc,expected_keywords,category',
      '"住宿, 交通上限",差旅报销制度,600|住宿,数值型',
      '出差餐补每天多少钱,差旅报销制度,100,数值型',
    ].join('\n')
    const file = new File([csv], '差旅黄金集.csv', { type: 'text/csv' })
    const input = wrapper.find('input.rag-eval-csv-input')
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')

    await vi.waitFor(() => {
      expect((wrapper.vm as any).state.editor.cases).toHaveLength(2)
    })
    const state = (wrapper.vm as any).state
    expect(state.editor.name).toBe('差旅黄金集')
    expect(state.editor.cases[0].keywordsText).toBe('600|住宿')
    expect(spies.success).toHaveBeenCalledWith('已导入 2 条用例')

    const bad = new File(['question,foo\nq,x'], 'bad.csv', { type: 'text/csv' })
    Object.defineProperty(input.element, 'files', { value: [bad], configurable: true })
    await input.trigger('change')
    await vi.waitFor(() => {
      expect(spies.error).toHaveBeenCalledWith('CSV 表头需要包含 question 与 expected_doc 两列')
    })
  })

  it('发起评估：按当前 TopK 调用接口，刷新历史并打开运行详情', async () => {
    const spies = messageSpies()
    stubInitialLoad()
    api.listRuns
      .mockResolvedValueOnce(ok([baseRun]))
      .mockResolvedValue(ok([runningRun, baseRun]))
    api.startRun.mockResolvedValue(ok('r2', '评估已启动，共 2 条用例'))
    api.getRun.mockResolvedValue(ok({ run: runningRun, baseline: baseRun, results: [] }))
    const wrapper = mountView()
    await flushPromises()

    ;(wrapper.vm as any).state.topK = 10
    await clickByText(wrapper, '.rag-eval-run-bar button', '发起评估')

    expect(api.startRun).toHaveBeenCalledWith('d1', 10, 'auto')
    expect(spies.success).toHaveBeenCalledWith('评估已启动，共 2 条用例')
    expect(api.getRun).toHaveBeenCalledWith('r2', undefined)
    expect((wrapper.vm as any).state.runDialogVisible).toBe(true)
    expect((wrapper.vm as any).hasActiveRun).toBe(true)
    expect(wrapper.text()).toContain('评估进行中')
  })

  it('发起评估：选择关闭精排时随请求提交策略', async () => {
    messageSpies()
    stubInitialLoad()
    api.listRuns
      .mockResolvedValueOnce(ok([baseRun]))
      .mockResolvedValue(ok([runningRun, baseRun]))
    api.startRun.mockResolvedValue(ok('r2', '评估已启动，共 2 条用例'))
    api.getRun.mockResolvedValue(ok({ run: runningRun, baseline: baseRun, results: [] }))
    const wrapper = mountView()
    await flushPromises()

    ;(wrapper.vm as any).state.rerankMode = 'off'
    await flushPromises()
    expect(wrapper.text()).toContain('本轮跳过精排')

    await clickByText(wrapper, '.rag-eval-run-bar button', '发起评估')

    expect(api.startRun).toHaveBeenCalledWith('d1', 5, 'off')
  })

  it('运行详情：展示精排策略、实际生效状态与反向复跑入口', async () => {
    stubInitialLoad()
    api.getRun.mockResolvedValue(ok(runDetail))
    const wrapper = mountView()
    await flushPromises()

    await clickByText(wrapper, '.rag-eval-runs button', '详情')
    await flushPromises()

    expect(wrapper.text()).toContain('精排策略 跟随全局')
    expect(wrapper.text()).toContain('精排已在本轮真实生效')
    expect(wrapper.find('.rag-eval-counterpart').text()).toContain('关闭精排再跑一次')
  })

  it('运行详情：主动关闭精排时说明原因，并可一键按跟随全局复跑', async () => {
    stubInitialLoad()
    const offRun = {
      ...baseRun,
      rerankMode: 'off' as const,
      rerankApplied: false,
      retrievalMode: 'hybrid-rrf',
    }
    api.getRun.mockResolvedValue(ok({ ...runDetail, run: offRun }))
    api.startRun.mockResolvedValue(ok('r9', '评估已启动，共 2 条用例'))
    const wrapper = mountView()
    await flushPromises()

    await clickByText(wrapper, '.rag-eval-runs button', '详情')
    await flushPromises()

    expect(wrapper.text()).toContain('精排策略 主动关闭')
    expect(wrapper.text()).toContain('本次运行主动关闭精排')

    await clickByText(wrapper, '.rag-eval-counterpart', '跟随全局再跑一次')

    expect(api.startRun).toHaveBeenCalledWith('d1', 5, 'auto')
    expect((wrapper.vm as any).state.rerankMode).toBe('auto')
  })

  it('运行详情：展示指标、基线差值与逐题命中情况', async () => {
    stubInitialLoad()
    api.getRun.mockResolvedValue(ok(runDetail))
    const wrapper = mountView()
    await flushPromises()

    await clickByText(wrapper, '.rag-eval-runs button', '详情')
    await flushPromises()

    expect(api.getRun).toHaveBeenCalledWith('r1', undefined)
    expect((wrapper.vm as any).state.runDialogVisible).toBe(true)
    expect(wrapper.text()).toContain('对比基线')
    expect(wrapper.text()).toContain('+10.0pp')
    expect(wrapper.text()).toContain('+25.0pp')
    expect(wrapper.text()).toContain('完全漏召 0 条')
    expect(wrapper.text()).toContain('第 2 位')
    expect(wrapper.text()).toContain('命中')
    expect(wrapper.text()).toContain('未命中')
  })

  it('运行详情：展开行展示 Top-K 分数、来源与片段预览', async () => {
    stubInitialLoad()
    api.getRun.mockResolvedValue(ok(runDetail))
    const wrapper = mountView()
    await flushPromises()

    await clickByText(wrapper, '.rag-eval-runs button', '详情')
    await flushPromises()

    expect(wrapper.text()).toContain('口径 rag-eval-v1')
    expect(wrapper.text()).toContain('检索链路 hybrid-rrf+rerank')
    expect(wrapper.text()).not.toContain('口径不同')

    const expandIcons = wrapper.findAll('.el-table__expand-icon')
    expect(expandIcons.length).toBe(2)
    await expandIcons[0].trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Top-2 命中明细')
    expect(wrapper.text()).toContain('融合排序分')
    expect(wrapper.text()).toContain('双路')
    expect(wrapper.text()).toContain('向量路')
    expect(wrapper.text()).toContain('块 3')
    expect(wrapper.text()).toContain('住宿标准：一线城市每晚上限 600 元')
    expect(wrapper.text()).toContain('未参与')

    const iconsAfterFirst = wrapper.findAll('.el-table__expand-icon')
    await iconsAfterFirst[1].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('本次运行未记录 Top-K 明细')
  })

  it('运行详情：展示后端返回的基线不可比告警', async () => {
    stubInitialLoad()
    api.getRun.mockResolvedValue(
      ok({
        ...runDetail,
        baseline: { ...baselineRun, metricSpec: 'rag-eval-v0' },
        baselineCompatible: false,
        baselineWarnings: ['指标口径不同（rag-eval-v1 vs rag-eval-v0），差值仅供参考'],
      }),
    )
    const wrapper = mountView()
    await flushPromises()

    await clickByText(wrapper, '.rag-eval-runs button', '详情')
    await flushPromises()

    expect(wrapper.text()).toContain('对比提示')
    expect(wrapper.text()).toContain('指标口径不同（rag-eval-v1 vs rag-eval-v0）')
    expect(wrapper.text()).toContain('并非严格可比')
  })

  it('运行详情：逐题对比先列退化项并给出差值', async () => {
    stubInitialLoad()
    api.getRun.mockResolvedValue(ok(runDetail))
    const wrapper = mountView()
    await flushPromises()

    await clickByText(wrapper, '.rag-eval-runs button', '详情')
    await flushPromises()

    expect(wrapper.text()).toContain('逐题对比')
    expect(wrapper.text()).toContain('退化 1 条')
    expect(wrapper.text()).toContain('提升 1 条')
    expect(wrapper.text()).toContain('首位命中 第 1 位 → 第 2 位')
    expect(wrapper.text()).toContain('+50.0 pp')
    expect(wrapper.text()).toContain('Recall 0.500 → 1.000')
  })

  it('运行详情：手动选择基线后携带 baselineRunKey 重新请求', async () => {
    stubInitialLoad()
    api.getRun.mockResolvedValueOnce(ok(runDetail))
    const wrapper = mountView()
    await flushPromises()

    await clickByText(wrapper, '.rag-eval-runs button', '详情')
    await flushPromises()

    api.getRun.mockResolvedValueOnce(
      ok({
        ...runDetail,
        baseline: { ...baselineRun, runKey: 'r9' },
        baselineExplicit: true,
        baselineCompatible: false,
        baselineWarnings: ['TopK 不同（5 vs 10），Recall/Precision/NDCG 不可直接对比'],
      }),
    )
    await (wrapper.vm as any).selectBaseline('r9')
    await flushPromises()

    expect(api.getRun).toHaveBeenLastCalledWith('r1', 'r9')
    expect(wrapper.text()).toContain('手动指定')
    expect(wrapper.text()).toContain('TopK 不同（5 vs 10）')
  })

  it('删除数据集：确认后调用接口并回到空态；取消不调用', async () => {
    const spies = messageSpies()
    stubInitialLoad()
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    api.deleteDataset.mockResolvedValue(ok(true, '数据集及其运行记录已删除'))
    const wrapper = mountView()
    await flushPromises()

    await clickByText(wrapper, '.rag-eval-panel-actions button', '删除')
    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining('制度黄金集'),
      '删除数据集',
      expect.objectContaining({ type: 'warning' }),
    )
    expect(api.deleteDataset).toHaveBeenCalledWith('d1')
    expect(spies.success).toHaveBeenCalledWith('数据集已删除')

    confirm.mockRejectedValueOnce('cancel')
    stubInitialLoad()
    await clickByText(wrapper, '.rag-eval-panel-actions button', '删除')
    expect(api.deleteDataset).toHaveBeenCalledTimes(1)
  })

  it('接口失败：列表错误码、请求异常与启动评估失败分别提示', async () => {
    const spies = messageSpies()
    api.listDatasets.mockResolvedValueOnce(fail('数据库繁忙'))
    mountView()
    await flushPromises()
    expect(spies.error).toHaveBeenCalledWith('数据库繁忙')

    api.listDatasets.mockRejectedValueOnce(new Error('network'))
    mountView()
    await flushPromises()
    expect(spies.error).toHaveBeenCalledWith('获取数据集失败，请稍后重试')

    stubInitialLoad()
    api.startRun.mockResolvedValue(fail('知识库文档缺失'))
    const wrapper = mountView()
    await flushPromises()
    await clickByText(wrapper, '.rag-eval-run-bar button', '发起评估')
    expect(spies.error).toHaveBeenCalledWith('知识库文档缺失')
  })
})
