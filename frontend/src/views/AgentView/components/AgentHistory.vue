<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import type { AgentRun, AgentRunDetail } from '@/api/agent'
import StateBlock from '@/components/StateBlock/index.vue'
import { costText, dateText, durationText, numberText, statusMeta } from '../agentPresentation'
import AgentRunPanel from './AgentRunPanel.vue'

const props = defineProps<{
  runs: AgentRun[]; loading: boolean; error: string; detailKey: string; detailLoading: boolean
  detailError: string; detail: AgentRunDetail | null
}>()
const emit = defineEmits<{ refresh: []; open: [runKey: string]; closeDetail: [] }>()
const filter = ref('')
const id = useId()
const statuses = computed(() => [...new Set(props.runs.map((run) => run.status))])
const filtered = computed(() => props.runs.filter((run) => !filter.value || run.status === filter.value))
</script>

<template>
  <section class="agent-panel agent-history" aria-label="运行历史">
    <header class="agent-panel-header">
      <h2>运行历史</h2>
      <button type="button" class="btn" :disabled="loading" @click="emit('refresh')">刷新历史</button>
    </header>
    <p class="agent-muted">仅展示最近 200 条运行记录，状态筛选仅作用于已加载的最近记录。共 {{ runs.length }} 条。</p>
    <div class="agent-field agent-history-filter">
      <label :for="`${id}-status`">状态筛选</label>
      <select :id="`${id}-status`" v-model="filter" name="runStatus">
        <option value="">全部状态</option>
        <option v-if="filter && !statuses.includes(filter)" :value="filter">{{ statusMeta(filter).label }}</option>
        <option v-for="status in statuses" :key="status" :value="status">{{ statusMeta(status).label }}</option>
      </select>
    </div>
    <StateBlock :loading="loading" :error="error" :empty="!filtered.length" :count="3"
      :empty-text="runs.length ? '最近记录中没有符合此状态的运行。' : '暂无运行记录。发起一次运行后，可在这里查看后端结果。'" @retry="emit('refresh')">
      <ul class="agent-list">
        <li v-for="run in filtered" :key="run.runKey">
          <button type="button" class="agent-history-item" :aria-pressed="detailKey === run.runKey" @click="emit('open', run.runKey)">
            <span class="agent-panel-header"><strong>{{ run.question }}</strong><span class="agent-status" :class="`is-${statusMeta(run.status).tone}`">{{ statusMeta(run.status).label }}</span></span>
            <span class="agent-muted">{{ dateText(run.startedAt) }} · Token {{ numberText(run.totalTokens) }} · {{ durationText(run.durationMs) }} · {{ costText(run.costCents) }}</span>
            <span class="agent-muted">{{ run.runKey }} · 查看详情</span>
          </button>
        </li>
      </ul>
    </StateBlock>
    <section v-if="detailKey" class="agent-history-detail" aria-label="历史运行详情">
      <header class="agent-panel-header"><strong>历史运行详情</strong><button type="button" class="btn" @click="emit('closeDetail')">关闭详情</button></header>
      <StateBlock :loading="detailLoading" :error="detailError" :empty="!detail" :count="2"
        empty-text="未返回运行详情，请刷新历史重试。" @retry="emit('open', detailKey)">
        <AgentRunPanel v-if="detail" title="运行结果" :run="detail.run" :steps="detail.steps" />
      </StateBlock>
    </section>
  </section>
</template>
