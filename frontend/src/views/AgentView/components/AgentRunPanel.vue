<script setup lang="ts">
import { computed, useId } from 'vue'
import type { AgentRun, AgentStep } from '@/api/agent'
import StateBlock from '@/components/StateBlock/index.vue'
import { costText, durationText, mergeSteps, numberText, statusMeta, stepLabel } from '../agentPresentation'

const props = withDefaults(defineProps<{
  title?: string; editable?: boolean; question?: string; submittedQuestion?: string; canRun?: boolean
  blockedReason?: string; receiving?: boolean; phase?: string; notice?: string; streamError?: string
  runKey?: string; run?: AgentRun | null; steps: AgentStep[]
}>(), { title: '实时运行', editable: false, question: '', phase: 'idle', notice: '', runKey: '' })
const emit = defineEmits<{ 'update:question': [value: string]; start: []; stop: [] }>()
const id = useId()
const orderedSteps = computed(() => mergeSteps([], props.steps))
const failures = computed(() => orderedSteps.value.filter((step) => step.status === 'failed' || step.status === 'error' || step.errorMessage))
const phaseText = computed(() => ({ idle: '等待提问', connecting: '正在建立连接', receiving: '正在接收步骤',
  done: '运行已结束', interrupted: '连接中断 · 结果待核实', stopped: '已停止接收 · 后台状态未知' } as Record<string, string>)[props.phase])
const active = computed(() => !!props.run || !!props.runKey || props.phase !== 'idle')

function focusStep(stepNo: number) {
  const target = document.getElementById(`${id}-step-${stepNo}`)
  target?.focus()
  target?.scrollIntoView({ block: 'center' })
}
</script>

<template>
  <section class="agent-panel agent-run-panel" :aria-label="title">
    <header class="agent-panel-header">
      <h2>{{ title }}</h2>
      <span v-if="run" class="agent-status" :class="`is-${statusMeta(run.status).tone}`">{{ statusMeta(run.status).label }}</span>
      <span v-else class="agent-status" :class="receiving ? 'is-active' : 'is-muted'" role="status">{{ phaseText }}</span>
    </header>
    <form v-if="editable" class="agent-form" @submit.prevent="emit('start')">
      <div class="agent-field">
        <label :for="`${id}-question`">运行问题</label>
        <textarea :id="`${id}-question`" name="question" :value="question" rows="3" :disabled="receiving"
          :aria-describedby="`${id}-billing`" @input="emit('update:question', ($event.target as HTMLTextAreaElement).value)" />
      </div>
      <p :id="`${id}-billing`" class="agent-muted">运行会真实调用模型、工具并消耗额度。每次点击运行都会发起新请求。</p>
      <div class="agent-actions">
        <button type="submit" class="btn btn-primary" :disabled="!canRun || receiving || !question.trim()">{{ receiving ? '运行中…' : '开始运行' }}</button>
        <button v-if="receiving" type="button" class="btn" @click="emit('stop')">停止接收</button>
      </div>
      <p v-if="receiving" class="agent-muted">运行中禁止切换或编辑 Agent。「停止接收」不会立即中断正在进行的模型调用。</p>
      <p v-else-if="blockedReason" class="agent-muted">{{ blockedReason }}</p>
    </form>
    <p v-if="streamError" class="agent-error" role="alert">{{ streamError }}</p>
    <p v-if="notice" class="agent-notice" role="status">{{ notice }}</p>
    <template v-if="active">
      <p class="agent-muted agent-run-key">运行标识：{{ run?.runKey || runKey || '等待服务端返回' }}</p>
      <details v-if="run?.question || submittedQuestion"><summary>本次问题</summary><pre class="agent-pre">{{ run?.question || submittedQuestion }}</pre></details>
      <dl class="agent-metrics" data-testid="run-metrics">
        <div><dt>迭代次数</dt><dd>{{ numberText(run?.iterations) }}</dd></div>
        <div><dt>总 Token</dt><dd>{{ numberText(run?.totalTokens) }}</dd></div>
        <div><dt>输入 Token</dt><dd>{{ numberText(run?.promptTokens) }}</dd></div>
        <div><dt>输出 Token</dt><dd>{{ numberText(run?.completionTokens) }}</dd></div>
        <div><dt>费用（元）</dt><dd>{{ costText(run?.costCents) }}</dd></div>
        <div><dt>耗时</dt><dd>{{ durationText(run?.durationMs) }}</dd></div>
      </dl>
      <p v-if="!run" class="agent-muted">最终统计待后端返回；缺失字段显示 —，不按零计费。</p>
    </template>
    <p v-if="run?.errorMessage" class="agent-error" role="alert">运行错误：{{ run.errorMessage }}</p>
    <div v-if="failures.length" class="agent-error-links" aria-label="错误定位">
      <button v-for="step in failures" :key="step.stepNo" type="button" class="btn agent-danger-button" @click="focusStep(step.stepNo)">定位到第 {{ step.stepNo }} 步错误</button>
    </div>
    <StateBlock :loading="receiving && !orderedSteps.length" :empty="!orderedSteps.length" :count="2"
      :empty-text="editable && !active ? '输入问题开始运行，步骤将在这里逐条显示。' : '暂未收到步骤；可在运行历史刷新后查看。'">
      <ol class="agent-timeline" aria-label="执行步骤">
        <li v-for="step in orderedSteps" :id="`${id}-step-${step.stepNo}`" :key="step.stepNo" class="agent-step"
          :class="{ 'is-failed': step.status === 'failed' || step.status === 'error' || !!step.errorMessage }" :data-step-no="step.stepNo" tabindex="-1">
          <header class="agent-panel-header">
            <h3>第 {{ step.stepNo }} 步 · {{ stepLabel(step.stepType) }}<template v-if="step.toolName"> · {{ step.toolName }}</template></h3>
            <span class="agent-status" :class="`is-${statusMeta(step.status).tone}`">{{ statusMeta(step.status).label }}</span>
          </header>
          <p class="agent-muted">迭代 {{ numberText(step.iteration) }} · 输入 Token {{ numberText(step.promptTokens) }} · 输出 Token {{ numberText(step.completionTokens) }} · 耗时 {{ durationText(step.latencyMs) }} · 费用 {{ costText(step.costCents) }}</p>
          <p v-if="step.errorMessage" class="agent-error">第 {{ step.stepNo }} 步错误：<span>{{ step.errorMessage }}</span></p>
          <p v-if="step.requestId" class="agent-muted">请求标识：{{ step.requestId }}</p>
          <details v-if="step.toolArgs != null"><summary>输入参数</summary><pre class="agent-pre">{{ step.toolArgs }}</pre></details>
          <details v-if="step.toolResult != null" :open="['llm', 'finish', 'final', 'answer'].includes(step.stepType)"><summary>输出结果</summary><pre class="agent-pre">{{ step.toolResult }}</pre></details>
        </li>
      </ol>
    </StateBlock>
    <section v-if="run" class="agent-answer" aria-label="最终回答">
      <h3>最终回答</h3>
      <pre v-if="run.answer != null && run.answer !== ''" class="agent-pre" data-testid="final-answer">{{ run.answer }}</pre>
      <p v-else class="agent-muted">本次运行未返回最终回答。</p>
    </section>
  </section>
</template>
