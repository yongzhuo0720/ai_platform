<script setup lang="ts">
import { reactive, ref, useId, watch } from 'vue'
import type { AgentConfig } from '@/api/agent'
import StateBlock from '@/components/StateBlock/index.vue'
import type { AgentModelOption } from '../agentPresentation'

const props = defineProps<{
  config: Partial<AgentConfig>
  models: AgentModelOption[]
  modelsLoading: boolean
  modelsError: string
  saving: boolean
  error: string
}>()
const emit = defineEmits<{ close: []; save: [payload: Partial<AgentConfig>]; retryModels: [] }>()
const id = useId()
const validation = ref('')
const draft = reactive({ name: '', description: '', systemPrompt: '', modelIid: '' as number | '', maxIterations: 8, tokenBudget: 20000 })
watch(() => props.config, (config) => {
  Object.assign(draft, { name: config.name ?? '', description: config.description ?? '', systemPrompt: config.systemPrompt ?? '',
    modelIid: config.modelIid ?? '', maxIterations: config.maxIterations ?? 8, tokenBudget: config.tokenBudget ?? 20000 })
}, { immediate: true })

function submit() {
  if (props.saving) return
  validation.value = ''
  if (!draft.name.trim() || draft.name.trim().length > 100) validation.value = '名称必填，最多 100 字'
  else if (draft.description.length > 2000) validation.value = '说明最多 2000 字'
  else if (draft.systemPrompt.length > 20000) validation.value = 'System Prompt 最多 20000 字'
  else if (!props.models.some((model) => model.iid === draft.modelIid)) validation.value = '请选择可用的 OpenAI 协议模型'
  else if (!Number.isInteger(draft.maxIterations) || draft.maxIterations < 1 || draft.maxIterations > 20) validation.value = '最大迭代次数须为 1–20 的整数'
  else if (!Number.isInteger(draft.tokenBudget) || draft.tokenBudget < 1 || draft.tokenBudget > 1000000) validation.value = 'Token 预算须为 1–1000000 的整数'
  if (validation.value || props.modelsLoading || props.modelsError) return
  emit('save', { name: draft.name.trim(), description: draft.description, systemPrompt: draft.systemPrompt,
    modelIid: Number(draft.modelIid), maxIterations: draft.maxIterations, tokenBudget: draft.tokenBudget })
}
</script>

<template>
  <el-dialog class="agent-ui agent-editor-dialog" :model-value="true" :title="config.iid != null ? '编辑 Agent' : '新建 Agent'"
    :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving"
    @update:model-value="!saving && emit('close')">
    <form :id="id" class="agent-form" novalidate @submit.prevent="submit">
      <div class="agent-field">
        <label :for="`${id}-name`">名称（必填，最多 100 字）</label>
        <input :id="`${id}-name`" v-model="draft.name" name="name" maxlength="100" :disabled="saving" required />
      </div>
      <div class="agent-field">
        <label :for="`${id}-description`">说明（最多 2000 字）</label>
        <textarea :id="`${id}-description`" v-model="draft.description" name="description" rows="2" maxlength="2000" :disabled="saving" />
      </div>
      <div class="agent-field">
        <label :for="`${id}-prompt`">System Prompt（最多 20000 字）</label>
        <textarea :id="`${id}-prompt`" v-model="draft.systemPrompt" name="systemPrompt" rows="5" maxlength="20000" :disabled="saving" />
      </div>
      <div class="agent-field">
        <label :for="`${id}-model`">模型（必填）</label>
        <StateBlock :loading="modelsLoading" :error="modelsError" :empty="!models.length" :count="1"
          empty-text="没有可选模型。请先配置已启用、包含上游模型名的 OpenAI 协议模型。" @retry="emit('retryModels')">
          <select :id="`${id}-model`" v-model="draft.modelIid" name="modelIid" :disabled="saving" required>
            <option disabled value="">请选择模型</option>
            <option v-if="draft.modelIid !== '' && !models.some(m => m.iid === draft.modelIid)" :value="draft.modelIid" disabled>
              原模型 #{{ draft.modelIid }} 不可用，请重新选择
            </option>
            <option v-for="model in models" :key="model.iid" :value="model.iid">{{ model.name || model.upstreamModel }} · {{ model.upstreamModel }}</option>
          </select>
          <template #empty-action><button type="button" class="btn" @click="emit('retryModels')">刷新模型</button></template>
        </StateBlock>
      </div>
      <div class="agent-form-grid">
        <div class="agent-field">
          <label :for="`${id}-iterations`">最大迭代次数（1–20）</label>
          <input :id="`${id}-iterations`" v-model.number="draft.maxIterations" name="maxIterations" type="number" min="1" max="20" step="1" :disabled="saving" />
        </div>
        <div class="agent-field">
          <label :for="`${id}-budget`">Token 预算（1–1000000）</label>
          <input :id="`${id}-budget`" v-model.number="draft.tokenBudget" name="tokenBudget" type="number" min="1" max="1000000" step="1" :disabled="saving" />
        </div>
      </div>
      <p v-if="validation || error" class="agent-error" role="alert">{{ validation || error }}</p>
    </form>
    <template #footer>
      <div class="agent-actions">
        <button type="button" class="btn" :disabled="saving" @click="emit('close')">取消</button>
        <button type="submit" :form="id" class="btn btn-primary" :disabled="saving || modelsLoading || !!modelsError || !models.length">{{ saving ? '保存中…' : '保存 Agent' }}</button>
      </div>
    </template>
  </el-dialog>
</template>
