<template>
  <div class="kb-chat-panel">
    <!-- Messages area -->
    <div ref="messagesRef" class="kb-chat-messages">
      <!-- Empty state -->
      <div v-if="messages.length === 0" class="kb-chat-empty">
        <div class="kb-chat-empty-brand">
          <img src="@/assets/images/brand-logo.svg" alt="AI" class="kb-chat-empty-logo" />
          <div v-if="kbName">你好，我是{{ kbName }}</div>
          <div v-else>你好，我是知识库助手</div>
        </div>

        <!-- Centered ChatComposer in empty state -->
        <div class="kb-chat-empty-composer">
          <ChatComposer
            v-model="inputValue"
            :attached-files="attachedFiles"
            :model-options="modelOptions"
            :active-model-id="activeModelId"
            :loading="loading"
            :allow-file-upload="allowFileUpload"
            :placeholder="placeholder"
            is-empty
            @submit="handleSubmit"
            @file-selected="(f: File) => $emit('file-selected', f)"
            @remove-file="(id: string) => $emit('remove-file', id)"
            @select-model="(v: string) => $emit('select-model', v)"
          />
        </div>
      </div>

      <!-- Messages list -->
      <div v-else class="kb-chat-messages-list">
        <div
          v-for="msg in messages"
          :key="msg.id"
          class="kb-chat-message-item"
          :class="msg.role"
        >
          <!-- AI avatar -->
          <div v-if="msg.role === 'assistant'" class="kb-chat-message-avatar">
            <img src="@/assets/images/icon-chat-user-head.Cy4P-DEa.svg" alt="AI" />
          </div>

          <div class="kb-chat-message-content">
            <!-- Bubble -->
            <div
              class="kb-chat-message-bubble"
              :class="{ 'is-thinking': msg.role === 'assistant' && loading && !msg.content }"
            >
              <span v-if="msg.role === 'assistant' && loading && !msg.content" class="kb-thinking-text">
                思考中...
              </span>
              <div v-else-if="msg.role === 'assistant'" class="markdown-content" v-html="renderMarkdown(msg.content || '')"></div>
              <div v-else class="user-content" v-html="renderUserContent(msg.content || '')"></div>
              <span
                v-if="loading && msg.id === messages[messages.length - 1]?.id && msg.role === 'assistant' && msg.content"
                class="kb-typing-cursor"
              ></span>
            </div>

            <!-- Actions -->
            <div
              v-if="msg.role === 'assistant' && msg.content && !loading"
              class="kb-chat-message-actions"
            >
              <button class="kb-chat-message-action" @click="$emit('copy', msg.content)" title="复制">
                <el-icon><CopyDocument /></el-icon>
              </button>
              <button class="kb-chat-message-action" @click="$emit('regenerate', msg.id)" title="重新生成">
                <el-icon><RefreshRight /></el-icon>
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- Suggested questions (after answer) -->
      <div
        v-if="suggestedQuestions.length > 0 && messages.length > 0 && !loading"
        class="kb-chat-suggestions"
      >
        <p class="kb-chat-suggestions-title">推荐问题</p>
        <div class="kb-chat-suggestions-list">
          <button
            v-for="(q, i) in suggestedQuestions"
            :key="i"
            class="kb-chat-suggestion-btn"
            @click="$emit('suggest-click', q)"
          >
            {{ q }}
          </button>
        </div>
      </div>
    </div>

    <!-- ChatComposer docked at bottom (only when there are messages) -->
    <div v-if="messages.length > 0" class="kb-chat-composer-docked">
      <ChatComposer
        v-model="inputValue"
        :attached-files="attachedFiles"
        :model-options="modelOptions"
        :active-model-id="activeModelId"
        :loading="loading"
        :allow-file-upload="allowFileUpload"
        :placeholder="loading ? 'AI正在回答中...' : placeholder"
        @submit="handleSubmit"
        @file-selected="(f: File) => $emit('file-selected', f)"
        @remove-file="(id: string) => $emit('remove-file', id)"
        @select-model="(v: string) => $emit('select-model', v)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { CopyDocument, RefreshRight } from '@element-plus/icons-vue'
import { renderMarkdown, renderUserContent } from '@/utils/markdown'
import ChatComposer from '@/views/ChatView/ChatComposer.vue'

interface AttachedFile {
  id: string
  name: string
  type: string
  size: number
}

interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  retrieverResources?: any[]
}

interface ModelOption {
  value: string
  label: string
  title?: string
  subtitle?: string
}

const props = withDefaults(defineProps<{
  messages: ChatMessage[]
  loading: boolean
  placeholder?: string
  modelValue?: string
  modelOptions?: ModelOption[]
  activeModelId?: string
  allowFileUpload?: boolean
  attachedFiles?: AttachedFile[]
  kbName?: string
  suggestedQuestions?: string[]
}>(), {
  placeholder: '请输入问题...',
  modelValue: '',
  modelOptions: () => [],
  activeModelId: '',
  allowFileUpload: true,
  attachedFiles: () => [],
  kbName: '',
  suggestedQuestions: () => [],
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  submit: []
  copy: [content: string]
  regenerate: [id: string]
  'select-model': [value: string]
  'file-selected': [file: File]
  'remove-file': [fileId: string]
  'suggest-click': [question: string]
}>()

const inputValue = ref(props.modelValue)
const messagesRef = ref<HTMLDivElement | null>(null)

// Sync v-model
watch(() => props.modelValue, (val) => {
  inputValue.value = val
})
watch(inputValue, (val) => {
  emit('update:modelValue', val)
})

function handleSubmit() {
  if (!inputValue.value.trim() || props.loading) return
  emit('submit')
}

// Auto scroll
function scrollToBottom() {
  nextTick(() => {
    requestAnimationFrame(() => {
      if (messagesRef.value) {
        messagesRef.value.scrollTop = messagesRef.value.scrollHeight
      }
    })
  })
}

watch(() => props.messages.length, scrollToBottom)
watch(() => props.messages, scrollToBottom, { deep: true })

const isMounted = ref(false)
watch(() => props.messages, () => {
  if (!isMounted.value && props.messages.length > 0) {
    isMounted.value = true
    scrollToBottom()
  }
}, { immediate: true })

defineExpose({ messagesRef, scrollToBottom })
</script>

<style scoped lang="less">
.kb-chat-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: #fff;
}

// ============== Messages ==============
.kb-chat-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px 16px 8px;

  &::-webkit-scrollbar {
    width: 4px;
  }
  &::-webkit-scrollbar-track {
    background: transparent;
  }
  &::-webkit-scrollbar-thumb {
    background: rgba(13, 48, 100, 0.12);
    border-radius: 2px;
  }
}

.kb-chat-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 300px;
  padding: 48px 24px 80px;
  gap: 20px;
}

.kb-chat-empty-brand {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  margin-bottom: 4px;
}

.kb-chat-empty-logo {
  width: 44px;
  height: 44px;
  object-fit: contain;
  flex-shrink: 0;
}

// ============== Empty state composer ==============
.kb-chat-empty-composer {
  width: 100%;
  max-width: 720px;
}

// ============== Message List ==============
.kb-chat-messages-list {
  display: flex;
  flex-direction: column;
  gap: 24px;
  width: 100%;
  max-width: 800px;
  margin: 0 auto;
  padding-bottom: 8px;
}

.kb-chat-message-item {
  display: flex;
  gap: 12px;
  width: 100%;
  animation: kb-msg-up 0.3s ease;

  &.user {
    flex-direction: row-reverse;

    .kb-chat-message-content {
      align-items: flex-end;
    }
  }

  &.assistant {
    .kb-chat-message-content {
      align-items: flex-start;
    }
  }
}

@keyframes kb-msg-up {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

.kb-chat-message-avatar {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: rgba(13, 48, 100, 0.06);
  border: 1px solid rgba(13, 48, 100, 0.1);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;

  img {
    width: 22px;
    height: 22px;
    object-fit: contain;
  }
}

.kb-chat-message-content {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-width: min(82%, 680px);
  min-width: 0;
}

.kb-chat-message-bubble {
  padding: 12px 16px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.65;
  word-break: break-word;
  width: fit-content;
  max-width: 100%;

  .markdown-content,
  .user-content {
    font-size: 14px;
    line-height: 1.65;
  }
}

.kb-chat-message-item.user .kb-chat-message-bubble {
  background: #2563eb;
  color: #fff;
  border-bottom-right-radius: 4px;
}

.kb-chat-message-item.assistant .kb-chat-message-bubble {
  background: #f4f6fb;
  color: rgba(13, 48, 100, 0.9);
  border: 1px solid rgba(13, 48, 100, 0.06);
  border-bottom-left-radius: 4px;
}

.kb-chat-message-bubble.is-thinking {
  background: #f4f6fb;
  border: 1px dashed rgba(13, 48, 100, 0.1);
  color: rgba(13, 48, 100, 0.45);
}

.kb-thinking-text {
  display: flex;
  align-items: center;
  gap: 8px;

  &::before {
    content: '';
    width: 14px;
    height: 14px;
    border: 2px solid rgba(13, 48, 100, 0.12);
    border-top-color: #2563eb;
    border-radius: 50%;
    animation: kb-spin 0.8s linear infinite;
  }
}

@keyframes kb-spin {
  to { transform: rotate(360deg); }
}

.kb-typing-cursor {
  display: inline-block;
  width: 2px;
  height: 14px;
  background: currentColor;
  margin-left: 2px;
  vertical-align: middle;
  animation: kb-blink 0.8s infinite;
  opacity: 0.7;
}

@keyframes kb-blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}

// ============== Source references ==============
.kb-chat-source {
  background: rgba(13, 48, 100, 0.04);
  border: 1px solid rgba(13, 48, 100, 0.08);
  border-radius: 8px;
  padding: 10px 14px;
  margin-bottom: 4px;
  max-width: 100%;
}

.kb-chat-source-header {
  font-size: 12px;
  color: rgba(13, 48, 100, 0.55);
  margin-bottom: 6px;
}

.kb-chat-source-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: rgba(13, 48, 100, 0.5);
  padding: 2px 0;
}

.kb-chat-source-icon {
  font-size: 13px;
  flex-shrink: 0;
}

.kb-chat-source-name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

// ============== Message Actions ==============
.kb-chat-message-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 0 2px;
  opacity: 0;
  transition: opacity 0.15s;

  .kb-chat-message-item:hover & {
    opacity: 0.7;
  }
}

.kb-chat-message-action {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: rgba(13, 48, 100, 0.3);
  cursor: pointer;
  transition: all 0.15s;

  &:hover {
    background: rgba(13, 48, 100, 0.06);
    color: rgba(13, 48, 100, 0.6);
  }
}

// ============== Docked composer ==============
.kb-chat-composer-docked {
  flex-shrink: 0;
  padding: 8px 16px 16px;
  border-top: 1px solid rgba(13, 48, 100, 0.06);
  background: #fff;
}

// ============== Suggested Questions ==============
.kb-chat-suggestions {
  width: 100%;
  max-width: 800px;
  margin: 0 auto;
  padding: 0 0 12px;
}

.kb-chat-suggestions-title {
  font-size: 12px;
  color: rgba(13, 48, 100, 0.45);
  margin: 0 0 8px;
  padding: 0 4px;
}

.kb-chat-suggestions-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.kb-chat-suggestion-btn {
  display: inline-flex;
  align-items: center;
  padding: 6px 14px;
  font-size: 13px;
  line-height: 1.5;
  color: rgba(13, 48, 100, 0.75);
  background: rgba(13, 48, 100, 0.04);
  border: 1px solid rgba(13, 48, 100, 0.08);
  border-radius: 16px;
  cursor: pointer;
  transition: all 0.2s ease;
  white-space: nowrap;

  &:hover {
    color: #2563eb;
    background: rgba(37, 99, 235, 0.08);
    border-color: rgba(37, 99, 235, 0.25);
  }
}

// ============== Dark Theme ==============
html[data-theme="dark"] {
  .kb-chat-panel {
    background: transparent;
  }

  .kb-chat-empty-brand {
    color: rgba(232, 238, 255, 0.85);
  }

  .kb-chat-messages {
    &::-webkit-scrollbar-thumb {
      background: rgba(77, 166, 255, 0.2);
    }
  }

  .kb-chat-message-avatar {
    background: rgba(77, 166, 255, 0.12);
    border-color: rgba(77, 166, 255, 0.2);
  }

  .kb-chat-message-item.user .kb-chat-message-bubble {
    background: var(--qwen-brand-blue);
  }

  .kb-chat-message-item.assistant .kb-chat-message-bubble {
    background: rgba(255, 255, 255, 0.06);
    color: rgba(255, 255, 255, 0.9);
    border-color: rgba(255, 255, 255, 0.08);
  }

  .kb-chat-message-bubble.is-thinking {
    background: rgba(255, 255, 255, 0.04);
    border-color: rgba(255, 255, 255, 0.1);
    color: rgba(255, 255, 255, 0.45);
  }

  .kb-chat-source {
    background: rgba(77, 166, 255, 0.08);
    border-color: rgba(77, 166, 255, 0.15);
  }

  .kb-chat-source-header {
    color: rgba(77, 166, 255, 0.7);
  }

  .kb-chat-source-item {
    color: rgba(255, 255, 255, 0.5);
  }

  .kb-chat-message-action {
    color: rgba(255, 255, 255, 0.35);

    &:hover {
      background: rgba(255, 255, 255, 0.08);
      color: rgba(255, 255, 255, 0.7);
    }
  }

  .kb-chat-composer-docked {
    border-top-color: rgba(255, 255, 255, 0.06);
    background: transparent;
  }

  .kb-chat-suggestions-title {
    color: rgba(255, 255, 255, 0.4);
  }

  .kb-chat-suggestion-btn {
    color: rgba(255, 255, 255, 0.65);
    background: rgba(255, 255, 255, 0.06);
    border-color: rgba(255, 255, 255, 0.1);

    &:hover {
      color: var(--qwen-brand-blue);
      background: rgba(77, 166, 255, 0.12);
      border-color: rgba(77, 166, 255, 0.3);
    }
  }
}
</style>
