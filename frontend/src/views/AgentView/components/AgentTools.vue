<script setup lang="ts">
import { computed, reactive, ref, useId, watch } from 'vue'
import type { AgentTool } from '@/api/agent'
import StateBlock from '@/components/StateBlock/index.vue'
import { toolLabel, toolSchema } from '../agentPresentation'

const props = defineProps<{
  tools: AgentTool[]; loading: boolean; error: string; disabled: boolean; saving: boolean
  editor: AgentTool | null; mutationError: string
}>()
const emit = defineEmits<{ retry: []; edit: [tool?: AgentTool]; close: []; save: [tool: AgentTool]; delete: [tool: AgentTool] }>()
const id = useId()
const draft = reactive({ name: '', description: '', toolType: 'builtin' as 'builtin' | 'kb_search' })
const validation = ref('')
const schema = computed(() => toolSchema(draft.toolType))
watch(() => props.editor, (tool) => {
  validation.value = ''
  if (tool) Object.assign(draft, { name: tool.name, description: tool.description ?? '', toolType: tool.toolType })
}, { immediate: true })

function submit() {
  if (!props.editor || props.disabled || props.saving) return
  validation.value = ''
  if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(draft.name.trim())) validation.value = '工具名称须为 ASCII function name：字母或下划线开头，后接字母、数字或下划线，最多 64 字符'
  else if (draft.description.length > 2000) validation.value = '说明最多 2000 字'
  else if (!['builtin', 'kb_search'].includes(draft.toolType)) validation.value = '仅支持当前时间和知识检索'
  if (validation.value) return
  emit('save', { iid: props.editor.iid, agentIid: props.editor.agentIid, name: draft.name.trim(),
    description: draft.description, toolType: draft.toolType, jsonSchema: schema.value })
}
</script>

<template>
  <section class="agent-panel agent-tools" aria-label="Agent 工具">
    <header class="agent-panel-header">
      <h2>工具</h2>
      <button type="button" class="btn" :disabled="disabled || saving || !!editor || loading || !!error" @click="emit('edit')">添加工具</button>
    </header>
    <p class="agent-muted">仅支持当前时间与知识检索。已有 HTTP 记录保留展示，当前不可执行。</p>
    <StateBlock :loading="loading" :error="error" :empty="!tools.length" :count="2"
      empty-text="尚未配置工具。可添加工具，也可直接向模型提问。" @retry="emit('retry')">
      <ul class="agent-list">
        <li v-for="tool in tools" :key="tool.iid ?? tool.name" class="agent-tool">
          <div class="agent-panel-header">
            <div class="agent-actions"><strong>{{ tool.name }}</strong><span class="agent-status" :class="tool.toolType === 'http' ? 'is-muted' : 'is-active'">{{ toolLabel(tool.toolType) }}</span></div>
            <div class="agent-actions">
              <button type="button" class="btn" :disabled="disabled || saving || !!editor || tool.toolType === 'http' || tool.iid == null" @click="emit('edit', tool)">编辑工具</button>
              <button type="button" class="btn agent-danger-button" :disabled="disabled || saving || !!editor || tool.iid == null" @click="emit('delete', tool)">删除工具</button>
            </div>
          </div>
          <p v-if="tool.description" class="agent-text">{{ tool.description }}</p>
          <details><summary>查看已保存的 JSON Schema</summary><pre class="agent-pre">{{ tool.jsonSchema }}</pre></details>
          <p v-if="tool.toolType === 'http'" class="agent-muted">HTTP 工具尚未开放配置或执行；模型调用该记录时会失败。</p>
        </li>
      </ul>
    </StateBlock>
    <form v-if="editor" class="agent-form agent-tool-editor" novalidate @submit.prevent="submit">
      <h3>{{ editor.iid != null ? '编辑工具' : '新建工具' }}</h3>
      <div class="agent-field">
        <label :for="`${id}-type`">工具类型</label>
        <select :id="`${id}-type`" v-model="draft.toolType" name="toolType" :disabled="saving || disabled">
          <option value="builtin">当前时间</option><option value="kb_search">知识检索</option>
        </select>
      </div>
      <div class="agent-field">
        <label :for="`${id}-name`">工具名称（ASCII function name）</label>
        <input :id="`${id}-name`" v-model="draft.name" name="toolName" maxlength="64" :disabled="saving || disabled" :aria-describedby="`${id}-name-hint`" />
        <small :id="`${id}-name-hint`">以字母或下划线开头，仅含字母、数字、下划线。</small>
      </div>
      <div class="agent-field">
        <label :for="`${id}-description`">工具说明</label>
        <textarea :id="`${id}-description`" v-model="draft.description" name="toolDescription" maxlength="2000" rows="2" :disabled="saving || disabled" />
      </div>
      <div><p class="agent-muted">固定 JSON Schema · 随工具类型匹配，不支持自定义编辑</p><pre class="agent-pre" data-testid="tool-schema">{{ schema }}</pre></div>
      <p v-if="validation || mutationError" class="agent-error" role="alert">{{ validation || mutationError }}</p>
      <div class="agent-actions">
        <button type="button" class="btn" :disabled="saving" @click="emit('close')">取消</button>
        <button type="submit" class="btn btn-primary" :disabled="saving || disabled">{{ saving ? '保存中…' : '保存工具' }}</button>
      </div>
    </form>
  </section>
</template>
