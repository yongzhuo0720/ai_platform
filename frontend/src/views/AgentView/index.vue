<script setup lang="ts">
import { computed } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import StateBlock from '@/components/StateBlock/index.vue'
import AgentEditor from './components/AgentEditor.vue'
import AgentTools from './components/AgentTools.vue'
import AgentRunPanel from './components/AgentRunPanel.vue'
import AgentHistory from './components/AgentHistory.vue'
import { numberText } from './agentPresentation'
import { useAgentWorkspace } from './useAgentWorkspace'

const { state, selected, configEditor, toolEditor, locked, selectionLocked, canRun, live,
  loadAgents, loadModels, loadTools, loadRuns, selectAgent, openDetail, closeDetail,
  openConfig, openTool, closeEditors, saveConfig, saveTool, deleteItem, start, stop } = useAgentWorkspace()
const modelName = computed(() => state.models.find((model) => model.iid === selected.value?.modelIid)?.name ||
  state.models.find((model) => model.iid === selected.value?.modelIid)?.upstreamModel)
const blockedReason = computed(() => {
  if (selectionLocked.value) return '请先完成配置或工具编辑。'
  if (state.listLoading || state.toolsLoading || state.modelsLoading) return '正在加载配置，请稍候。'
  if (state.listError || state.toolsError || state.modelsError) return '请先重试加载失败的配置。'
  if (selected.value?.iid == null) return '尚未取得 Agent 主键，请刷新列表。'
  if (!canRun.value) return '请编辑 Agent 并选择可用的 OpenAI 协议模型。'
  return ''
})
</script>

<template>
  <section class="page-section agent-ui agent-workbench">
    <div class="page-hero">
      <div class="page-eyebrow">智能体中心</div>
      <div class="page-heading">
        <div><h1>Agent 工作台</h1><p>管理私有 Agent 与工具，逐步观察模型执行，并从运行历史核实结果与用量。</p></div>
        <div class="hero-stats"><button type="button" class="btn btn-primary" :disabled="selectionLocked" @click="openConfig()"><Plus aria-hidden="true" />新建 Agent</button></div>
      </div>
    </div>
    <div class="agent-layout">
      <aside class="agent-panel agent-config-list" aria-label="私有 Agent 列表">
        <header class="agent-panel-header"><h2>我的 Agent</h2><button type="button" class="btn" :disabled="selectionLocked || state.listLoading" @click="loadAgents">刷新列表</button></header>
        <p class="agent-muted">私有配置，仅管理当前账户的 Agent。</p>
        <StateBlock :loading="state.listLoading" :error="state.listError" :empty="!state.agents.length" :count="3"
          empty-text="还没有 Agent" empty-hint="新建一个配置并选择模型，即可在右侧发起运行。" @retry="loadAgents">
          <ul class="agent-list">
            <li v-for="agent in state.agents" :key="agent.agentKey">
              <button type="button" class="agent-config-item" :class="{ 'is-selected': agent.agentKey === state.selectedKey }"
                :aria-pressed="agent.agentKey === state.selectedKey" :disabled="selectionLocked" @click="selectAgent(agent.agentKey)">
                <strong>{{ agent.name }}</strong><span class="agent-muted">{{ agent.description || '未填写说明' }}</span>
              </button>
            </li>
          </ul>
        </StateBlock>
      </aside>
      <div class="agent-main">
        <template v-if="selected">
          <section class="agent-panel agent-summary" aria-label="配置摘要">
            <header class="agent-panel-header"><h2>{{ selected.name }}</h2><div class="agent-actions">
              <button type="button" class="btn" :disabled="selectionLocked || selected.iid == null" @click="openConfig(selected)">编辑 Agent</button>
              <button type="button" class="btn agent-danger-button" :disabled="selectionLocked || selected.iid == null" @click="deleteItem()">删除 Agent</button>
            </div></header>
            <p class="agent-text">{{ selected.description || '未填写说明' }}</p>
            <dl class="agent-metrics">
              <div><dt>模型</dt><dd>{{ modelName || (selected.modelIid == null ? '未选择模型' : `模型 #${selected.modelIid}（未就绪）`) }}</dd></div>
              <div><dt>最大迭代次数</dt><dd>{{ numberText(selected.maxIterations) }}</dd></div>
              <div><dt>Token 预算</dt><dd>{{ numberText(selected.tokenBudget) }}</dd></div>
            </dl>
            <StateBlock v-if="state.modelsLoading || state.modelsError" :loading="state.modelsLoading" :error="state.modelsError" :count="1" @retry="loadModels" />
            <details><summary>System Prompt</summary><pre class="agent-pre">{{ selected.systemPrompt || '未设置 System Prompt' }}</pre></details>
            <p v-if="state.mutationError && !configEditor && !toolEditor" class="agent-error" role="alert">{{ state.mutationError }}</p>
          </section>
          <AgentTools :tools="state.tools" :loading="state.toolsLoading" :error="state.toolsError" :disabled="live.receiving || !!configEditor || selected.iid == null"
            :saving="state.saving" :editor="toolEditor" :mutation-error="state.mutationError" @retry="loadTools" @edit="openTool" @close="closeEditors" @save="saveTool" @delete="deleteItem" />
          <AgentRunPanel v-model:question="live.question" editable :can-run="canRun" :blocked-reason="blockedReason" :receiving="live.receiving"
            :phase="live.phase" :notice="live.notice" :stream-error="live.errorMessage" :run-key="live.runKey" :run="live.detail?.run" :submitted-question="live.submittedQuestion" :steps="live.steps" @start="start" @stop="stop" />
          <AgentHistory :key="selected.agentKey" :runs="state.runs" :loading="state.runsLoading" :error="state.runsError" :detail-key="state.detailKey"
            :detail-loading="state.detailLoading" :detail-error="state.detailError" :detail="state.detail" @refresh="loadRuns" @open="openDetail" @close-detail="closeDetail" />
        </template>
        <section v-else class="agent-panel"><StateBlock :empty="true" empty-text="从列表选择 Agent 查看配置、工具与历史，或点击「新建 Agent」。" /></section>
      </div>
    </div>
    <AgentEditor v-if="configEditor" :config="configEditor" :models="state.models" :models-loading="state.modelsLoading" :models-error="state.modelsError"
      :saving="locked" :error="state.mutationError" @close="closeEditors" @save="saveConfig" @retry-models="loadModels" />
  </section>
</template>

<style src="./agent.css"></style>
