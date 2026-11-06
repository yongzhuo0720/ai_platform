import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { agentApi, type AgentConfig, type AgentRun, type AgentRunDetail, type AgentTool } from '@/api/agent'
import { modelsConfigApi } from '@/api/modelsConfig'
import { errorText, readResult, readRows, toolSchema, type AgentModelOption } from './agentPresentation'
import { useAgentRun } from './useAgentRun'

export function useAgentWorkspace() {
  const state = reactive({
    agents: [] as AgentConfig[], selectedKey: '', listLoading: false, listError: '',
    tools: [] as AgentTool[], toolsLoading: false, toolsError: '',
    runs: [] as AgentRun[], runsLoading: false, runsError: '',
    models: [] as AgentModelOption[], modelsLoading: false, modelsError: '',
    saving: false, mutationError: '',
    detailKey: '', detailLoading: false, detailError: '', detail: null as AgentRunDetail | null,
  })
  const configEditor = ref<Partial<AgentConfig> | null>(null)
  const toolEditor = ref<AgentTool | null>(null)
  const selected = computed(() => state.agents.find((agent) => agent.agentKey === state.selectedKey))
  let alive = true
  let selectionVersion = 0
  let listVersion = 0
  let modelsVersion = 0
  let toolsVersion = 0
  let runsVersion = 0
  let detailVersion = 0
  let pendingSelectionKey = ''

  const run = useAgentRun({
    selected: () => selected.value,
    canStart: () => canRun.value,
    onDone: () => { void loadRuns() },
  })
  const locked = computed(() => run.live.receiving || state.saving)
  const selectionLocked = computed(() => locked.value || !!configEditor.value || !!toolEditor.value)
  const canRun = computed(() => !selectionLocked.value && !state.listLoading && !state.listError &&
    !state.toolsLoading && !state.toolsError && !state.modelsLoading && !state.modelsError &&
    selected.value?.iid != null && state.models.some((model) => model.iid === selected.value?.modelIid))

  function closeDetail() {
    detailVersion++
    Object.assign(state, { detailKey: '', detail: null, detailLoading: false, detailError: '' })
  }

  function selectAgent(key: string, force = false) {
    if (!alive || (!force && selectionLocked.value) || (!force && state.selectedKey === key)) return
    selectionVersion++
    toolsVersion++
    runsVersion++
    state.selectedKey = key
    Object.assign(state, { tools: [], runs: [], toolsError: '', runsError: '', toolsLoading: false, runsLoading: false })
    state.mutationError = ''
    closeDetail()
    run.reset()
    if (selected.value?.iid != null) {
      void loadTools()
      void loadRuns()
    }
  }

  async function loadAgents() {
    if (!alive || run.live.receiving) return
    const version = ++listVersion
    state.listLoading = true
    state.listError = ''
    try {
      const agents = readRows<AgentConfig>(await agentApi.list())
      if (!alive || version !== listVersion) return
      const previousIid = selected.value?.iid
      state.agents = agents
      const preferredKey = pendingSelectionKey || state.selectedKey
      const next = agents.find((agent) => agent.agentKey === preferredKey) ?? agents[0]
      if (pendingSelectionKey && !agents.some((agent) => agent.agentKey === pendingSelectionKey && agent.iid != null)) {
        state.listError = '配置已保存，但尚未取得新 Agent 的主键。请刷新列表，不要重复创建。'
        return
      }
      pendingSelectionKey = ''
      if (state.selectedKey !== (next?.agentKey ?? '') || previousIid !== next?.iid) {
        selectAgent(next?.agentKey ?? '', true)
      }
    } catch (error) {
      if (alive && version === listVersion) state.listError = errorText(error, '加载 Agent 列表失败')
    } finally {
      if (alive && version === listVersion) state.listLoading = false
    }
  }

  async function loadModels() {
    if (!alive) return
    const version = ++modelsVersion
    state.modelsLoading = true
    state.modelsError = ''
    try {
      type ModelRow = AgentModelOption & { iid: number | null; enable?: boolean; protocol?: string }
      const rows = readRows<ModelRow>(await modelsConfigApi.getList({ data: {} }))
      if (!alive || version !== modelsVersion) return
      state.models = rows.filter((model) => model.enable !== false && model.protocol === 'openai' &&
        model.iid != null && typeof model.upstreamModel === 'string' && !!model.upstreamModel.trim())
    } catch (error) {
      if (alive && version === modelsVersion) state.modelsError = errorText(error, '加载模型失败')
    } finally {
      if (alive && version === modelsVersion) state.modelsLoading = false
    }
  }

  async function loadTools() {
    const iid = selected.value?.iid
    if (!alive || iid == null) return
    const version = ++toolsVersion
    const selection = selectionVersion
    const current = () => alive && version === toolsVersion && selection === selectionVersion
    state.toolsLoading = true
    state.toolsError = ''
    try {
      const tools = readRows<AgentTool>(await agentApi.listTools(iid))
      if (current()) state.tools = tools.filter((tool) => tool.agentIid === iid)
    } catch (error) {
      if (current()) state.toolsError = errorText(error, '加载工具失败')
    } finally {
      if (current()) state.toolsLoading = false
    }
  }

  async function loadRuns() {
    const iid = selected.value?.iid
    if (!alive || iid == null) return
    const version = ++runsVersion
    const selection = selectionVersion
    const current = () => alive && version === runsVersion && selection === selectionVersion
    state.runsLoading = true
    state.runsError = ''
    try {
      const runs = readRows<AgentRun>(await agentApi.listRuns(iid))
      if (current()) state.runs = runs.filter((item) => item.agentIid === iid).slice(0, 200)
    } catch (error) {
      if (current()) state.runsError = errorText(error, '加载运行历史失败')
    } finally {
      if (current()) state.runsLoading = false
    }
  }

  async function openDetail(runKey: string) {
    const iid = selected.value?.iid
    if (!alive || iid == null) return
    const version = ++detailVersion
    const selection = selectionVersion
    const current = () => alive && version === detailVersion && selection === selectionVersion
    Object.assign(state, { detailKey: runKey, detail: null, detailLoading: true, detailError: '' })
    try {
      const detail = readResult<AgentRunDetail>(await agentApi.detail(runKey))
      if (!current()) return
      if (!detail?.run || !Array.isArray(detail.steps) || detail.run.agentIid !== iid || detail.run.runKey !== runKey) {
        throw new Error('运行详情与当前 Agent 不匹配，请刷新历史')
      }
      state.detail = detail
    } catch (error) {
      if (current()) state.detailError = errorText(error, '加载运行详情失败')
    } finally {
      if (current()) state.detailLoading = false
    }
  }

  function openConfig(agent?: AgentConfig) {
    if (!alive || selectionLocked.value) return
    state.mutationError = ''
    configEditor.value = agent ? { ...agent } : { name: '', description: '', systemPrompt: '', maxIterations: 8, tokenBudget: 20000 }
  }

  function openTool(tool?: AgentTool) {
    if (!alive || selectionLocked.value || selected.value?.iid == null || tool?.toolType === 'http') return
    state.mutationError = ''
    toolEditor.value = tool ? { ...tool } : {
      agentIid: selected.value.iid, name: '', description: '', toolType: 'builtin', jsonSchema: toolSchema('builtin'),
    }
  }

  function closeEditors() {
    if (state.saving) return
    configEditor.value = null
    toolEditor.value = null
    state.mutationError = ''
  }

  async function saveConfig(payload: Partial<AgentConfig>) {
    if (!alive || locked.value || !configEditor.value) return
    state.saving = true
    state.mutationError = ''
    const selection = selectionVersion
    try {
      if (configEditor.value.iid != null) {
        readResult(await agentApi.update({ ...payload, iid: configEditor.value.iid }))
      } else {
        const created = readResult<AgentConfig>(await agentApi.create(payload))
        if (!alive || selection !== selectionVersion) return
        // install 不回填 iid，只使用服务端 agentKey 重新定位真实记录。
        pendingSelectionKey = created.agentKey
      }
      if (!alive || selection !== selectionVersion) return
      configEditor.value = null
      ElMessage.success('Agent 已保存')
      await loadAgents()
    } catch (error) {
      if (alive && selection === selectionVersion) state.mutationError = errorText(error, '保存 Agent 失败')
    } finally {
      if (alive) state.saving = false
    }
  }

  async function saveTool(payload: AgentTool) {
    if (!alive || locked.value || !toolEditor.value || selected.value?.iid == null || payload.toolType === 'http') return
    const selection = selectionVersion
    state.saving = true
    state.mutationError = ''
    try {
      readResult(await agentApi.saveTool({ ...payload, iid: toolEditor.value.iid,
        agentIid: selected.value.iid, jsonSchema: toolSchema(payload.toolType) }))
      if (!alive || selection !== selectionVersion) return
      toolEditor.value = null
      ElMessage.success('工具已保存')
      await loadTools()
    } catch (error) {
      if (alive && selection === selectionVersion) state.mutationError = errorText(error, '保存工具失败')
    } finally {
      if (alive) state.saving = false
    }
  }

  async function deleteItem(tool?: AgentTool) {
    const agent = selected.value
    if (!alive || selectionLocked.value || agent?.iid == null || (tool && tool.iid == null)) return
    const selection = selectionVersion
    state.saving = true
    state.mutationError = ''
    try {
      try {
        await ElMessageBox.confirm(`确定删除${tool ? '工具' : 'Agent'}「${tool?.name ?? agent.name}」？此操作不可撤销。`,
          tool ? '删除工具' : '删除 Agent', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
      } catch { return }
      if (!alive || selection !== selectionVersion) return
      if (tool) readResult(await agentApi.deleteTool(tool.iid!))
      else readResult(await agentApi.delete(agent.iid))
      if (!alive || selection !== selectionVersion) return
      ElMessage.success(tool ? '工具已删除' : 'Agent 已删除')
      if (tool) await loadTools()
      else {
        selectAgent('', true)
        await loadAgents()
      }
    } catch (error) {
      if (alive && selection === selectionVersion) {
        state.mutationError = errorText(error, '删除失败，请重试')
        ElMessage.error(state.mutationError)
      }
    } finally {
      if (alive) state.saving = false
    }
  }

  onMounted(() => { void loadAgents(); void loadModels() })
  onBeforeUnmount(() => { alive = false; selectionVersion++ })

  return { state, selected, configEditor, toolEditor, locked, selectionLocked, canRun, ...run,
    loadAgents, loadModels, loadTools, loadRuns, selectAgent, openDetail, closeDetail,
    openConfig, openTool, closeEditors, saveConfig, saveTool, deleteItem }
}
