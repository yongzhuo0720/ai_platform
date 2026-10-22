<script setup lang="ts">
import {
  ref,
  computed,
  nextTick,
  onMounted,
  onUnmounted,
} from "vue";
import { ElInput } from "element-plus";
import { Promotion, Plus, Delete } from "@element-plus/icons-vue";
import type { AgentSkillItem } from "@/api/agentAssistant";

interface Props {
  modelValue: string;
  loading?: boolean;
  placeholder?: string;
  isEmpty?: boolean;
  /** 可用技能列表（输入 "/" 时弹出） */
  skills?: AgentSkillItem[];
  /** 技能列表是否加载中 */
  skillsLoading?: boolean;
  /** 技能弹层是否可见 */
  skillMenuVisible?: boolean;
  /** 已上传的附件列表 */
  attachedFiles?: Array<{
    id: string;
    name: string;
    type: string;
    size: number;
    url: string;
    fileName: string;
  }>;
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  placeholder: "请输入问题...",
  isEmpty: false,
  skills: () => [],
  skillsLoading: false,
  skillMenuVisible: false,
  attachedFiles: () => [],
});

const emit = defineEmits<{
  (e: "update:modelValue", value: string): void;
  (e: "submit"): void;
  (e: "select-skill", skillName: string): void;
  (e: "close-skill-menu"): void;
  (e: "file-selected", file: File): void;
  (e: "remove-file", fileId: string): void;
}>();

const textareaRef = ref<InstanceType<typeof ElInput> | null>(null);
const composerRootRef = ref<HTMLElement | null>(null);
const fileInputRef = ref<HTMLInputElement | null>(null);

const canSubmit = computed(
  () =>
    (props.modelValue.trim().length > 0 || props.attachedFiles.length > 0) &&
    !props.loading
);

const onInput = (value: string) => {
  emit("update:modelValue", value);
  nextTick(() => {
    textareaRef.value?.resizeTextarea();
  });
};

const focus = (options?: FocusOptions) => {
  nextTick(() => {
    textareaRef.value?.input?.focus(options);
  });
};

defineExpose({ focus });

const handleSubmit = () => {
  if (!canSubmit.value) return;
  emit("submit");
};

const handleKeydown = (event: Event | KeyboardEvent) => {
  const keyEvent = event as KeyboardEvent;
  if (keyEvent.key === "Enter" && !keyEvent.shiftKey) {
    keyEvent.preventDefault();
    handleSubmit();
    return;
  }
  if (keyEvent.key === "Escape") {
    emit("close-skill-menu");
  }
};

const handleSelectSkill = (skillName: string) => {
  emit("select-skill", skillName);
};

/** 点击加号按钮：打开文件选择器 */
const handleAttachClick = () => {
  fileInputRef.value?.click();
};

/** 文件选择后逐个通知父组件上传 */
const handleFileChange = (event: Event) => {
  const target = event.target as HTMLInputElement;
  const files = Array.from(target.files || []);
  files.forEach((file) => {
    emit("file-selected", file);
  });
  if (target) {
    target.value = "";
  }
};

/** 移除附件 */
const handleRemoveFile = (fileId: string) => {
  emit("remove-file", fileId);
};

/** 点击组件外部时关闭技能弹层 */
const handleDocumentClick = (event: MouseEvent) => {
  const target = event.target as Node | null;
  if (target && composerRootRef.value?.contains(target)) {
    return;
  }
  emit("close-skill-menu");
};

onMounted(() => {
  if (typeof document !== "undefined") {
    document.addEventListener("click", handleDocumentClick);
  }
});

onUnmounted(() => {
  if (typeof document !== "undefined") {
    document.removeEventListener("click", handleDocumentClick);
  }
});
</script>

<template>
  <div
    ref="composerRootRef"
    class="chat-composer"
    :class="{
      'chat-composer-empty': isEmpty,
      'chat-composer-docked': !isEmpty,
    }"
  >
    <!-- 技能弹层：输入 "/" 时显示在输入框上方 -->
    <transition name="agent-skill-fade">
      <div
        v-if="skillMenuVisible"
        class="agent-skill-menu"
        role="listbox"
        aria-label="可用技能"
      >
        <div class="agent-skill-menu-header">
          <span>可用技能</span>
          <span class="agent-skill-menu-hint">选择后继续输入问题</span>
        </div>
        <div v-if="skillsLoading" class="agent-skill-menu-loading">
          技能列表加载中...
        </div>
        <template v-else-if="skills.length > 0">
          <button
            v-for="skill in skills"
            :key="skill.name"
            type="button"
            class="agent-skill-menu-item"
            role="option"
            @mousedown.prevent
            @click="handleSelectSkill(skill.name)"
          >
            <span class="agent-skill-menu-item-slash">/</span>
            <span class="agent-skill-menu-item-name">
              <template v-if="skill.emoji">{{ skill.emoji }} </template>{{ skill.name }}
            </span>
            <span v-if="skill.description" class="agent-skill-menu-item-desc">
              {{ skill.description }}
            </span>
          </button>
        </template>
        <div v-else class="agent-skill-menu-empty">
          未获取到技能列表，可直接输入 /skills 查看
        </div>
      </div>
    </transition>

    <input
      ref="fileInputRef"
      type="file"
      multiple
      class="chat-composer-file-input"
      @change="handleFileChange"
    />

    <div v-if="attachedFiles.length > 0" class="attached-files">
      <div
        v-for="file in attachedFiles"
        :key="file.id"
        class="attached-file-item"
      >
        <span class="attached-file-name">{{ file.name }}</span>
        <button
          type="button"
          class="attached-file-remove"
          :aria-label="'移除文件：' + file.name"
          @click="handleRemoveFile(file.id)"
        >
          <el-icon><Delete /></el-icon>
        </button>
      </div>
    </div>

    <div class="chat-composer-input" style="background: transparent; backdrop-filter: none; -webkit-backdrop-filter: none;">
      <el-input
        ref="textareaRef"
        :model-value="modelValue"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 5 }"
        :placeholder="placeholder"
        :disabled="loading"
        resize="none"
        @input="onInput"
        @keydown="handleKeydown"
      />
    </div>

    <div class="chat-composer-toolbar">
      <div class="chat-composer-toolbar-start">
        <button
          type="button"
          class="chat-composer-tool-btn attach-btn"
          aria-label="添加附件"
          title="添加附件"
          @click="handleAttachClick"
        >
          <el-icon><Plus /></el-icon>
        </button>
      </div>

      <div class="chat-composer-toolbar-end">
        <button
          type="button"
          class="chat-send-btn"
          :disabled="!canSubmit"
          :aria-label="loading ? '发送中' : '发送消息'"
          @click="handleSubmit"
        >
          <Promotion />
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped lang="less">
.chat-composer {
  --chat-composer-caret: #1f63d0;
  --chat-composer-selection-bg: rgba(31, 99, 208, 0.18);
  --chat-composer-focus-border: rgba(31, 99, 208, 0.42);
  --chat-composer-focus-ring: 0 0 0 3px rgba(31, 99, 208, 0.14);
  --chat-composer-text-size: 15px;
  --chat-composer-text-weight: 400;
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  padding: 14px 16px;
  border-radius: var(--qwen-radius-xl);
  background: var(--qwen-bg);
  border: 1px solid var(--qwen-border);
  box-shadow: var(--qwen-shadow-sm);
  transition: border-color 150ms cubic-bezier(0.4, 0, 0.2, 1),
    box-shadow 150ms cubic-bezier(0.4, 0, 0.2, 1),
    transform 150ms cubic-bezier(0.4, 0, 0.2, 1);

  &:focus-within {
    border-color: var(--chat-composer-focus-border);
    box-shadow: var(--chat-composer-focus-ring), var(--qwen-shadow-sm);
    transform: translateY(-1px);
  }
}

.chat-composer-empty {
  max-width: 720px;
  margin: 0 auto;
  padding: 18px 20px;
}

.chat-composer-docked {
  margin-top: 8px;
}

.chat-composer-file-input {
  position: absolute;
  opacity: 0;
  width: 0;
  height: 0;
  pointer-events: none;
}

.attached-files {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.attached-file-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  background: var(--qwen-surface);
  border: 1px solid var(--qwen-border);
  border-radius: 999px;
  font-size: 12px;
  color: var(--qwen-brand-blue);
}

.attached-file-name {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attached-file-remove {
  width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--qwen-text-tertiary);
  cursor: pointer;
  transition: background 150ms, color 150ms;

  &:hover {
    background: var(--qwen-danger-soft);
    color: var(--error);
  }

  .el-icon {
    font-size: 12px;
  }
}

.chat-composer-input {
  width: 100%;
  background: transparent;
  backdrop-filter: none;
  -webkit-backdrop-filter: none;

  :deep(.el-textarea__inner) {
    width: 100%;
    min-height: 24px;
    padding: 0;
    border: none;
    border-radius: 0;
    background: transparent;
    font-size: var(--chat-composer-text-size);
    line-height: 1.58;
    font-weight: var(--chat-composer-text-weight);
    letter-spacing: 0.01em;
    color: var(--qwen-text-primary);
    caret-color: var(--chat-composer-caret);
    resize: none;
    outline: none;
    box-shadow: none;
    text-rendering: geometricPrecision;

    &::placeholder {
      color: var(--qwen-text-tertiary);
      font-weight: 400;
    }

    &::selection {
      background: var(--chat-composer-selection-bg);
      color: inherit;
    }

    &:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }
  }
}

:global(html[data-theme="dark"]) .chat-composer .chat-composer-input {
  background: transparent !important;
  backdrop-filter: none !important;
  -webkit-backdrop-filter: none !important;
}

:global(html[data-theme="dark"]) .chat-composer {
  --chat-composer-caret: #9fc5ff;
  --chat-composer-selection-bg: rgba(159, 197, 255, 0.24);
  --chat-composer-focus-border: rgba(159, 197, 255, 0.34);
  --chat-composer-focus-ring: 0 0 0 3px rgba(159, 197, 255, 0.14);
}

:global(html[data-theme="dark"]) .chat-composer:focus-within {
  border-color: var(--chat-composer-focus-border);
  box-shadow: var(--chat-composer-focus-ring), var(--qwen-shadow-sm);
}

:global(html[data-theme="dark"]) .chat-composer .chat-composer-input :deep(.el-textarea__inner)::placeholder {
  color: rgba(223, 231, 255, 0.46);
}

@media (min-width: 1920px) and (min-height: 1080px) {
  .chat-composer {
    --chat-composer-text-size: 16px;
    --chat-composer-text-weight: 400;
    padding: 16px 18px;
  }

  .chat-composer-empty {
    max-width: 760px;
    padding: 20px 22px;
  }

  .chat-composer-input :deep(.el-textarea__inner) {
    min-height: 26px;
    line-height: 1.62;
  }
}

@media (min-width: 2560px) and (min-height: 1400px) {
  .chat-composer {
    --chat-composer-text-size: 16px;
    --chat-composer-text-weight: 400;
    --chat-composer-focus-ring: 0 0 0 3px rgba(31, 99, 208, 0.18);
    padding: 17px 20px;
  }

  :global(html[data-theme="dark"]) .chat-composer {
    --chat-composer-focus-ring: 0 0 0 3px rgba(159, 197, 255, 0.18);
  }

  .chat-composer-empty {
    max-width: 820px;
    padding: 22px 24px;
  }

  .chat-composer-input :deep(.el-textarea__inner) {
    min-height: 28px;
    line-height: 1.66;
  }
}

.chat-composer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 32px;
}

.chat-composer-toolbar-start {
  display: flex;
  align-items: center;
  min-width: 0;
}

.chat-composer-toolbar-end {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  min-width: 0;
}

.chat-composer-tool-btn {
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--qwen-text-secondary);
  cursor: pointer;
  transition: background 150ms, color 150ms;

  &:hover {
    background: var(--qwen-surface);
    color: var(--qwen-brand-blue);
  }

  .el-icon {
    font-size: 18px;
  }
}

.chat-send-btn {
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 50%;
  background: var(--qwen-brand-blue);
  color: #ffffff;
  cursor: pointer;
  transition: background 150ms, transform 150ms, box-shadow 150ms;
  flex-shrink: 0;

  &:hover:not(:disabled) {
    background: var(--qwen-brand-blue-hover);
    transform: scale(1.05);
    box-shadow: 0 4px 10px rgba(26, 74, 140, 0.25);
  }

  &:active:not(:disabled) {
    transform: scale(0.95);
  }

  &:disabled {
    background: var(--qwen-border-strong);
    color: #ffffff;
    cursor: not-allowed;
  }

  svg {
    width: 16px;
    height: 16px;
  }
}

/* ================= 技能弹层 ================= */

.agent-skill-menu {
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: calc(100% + 10px);
  z-index: 30;
  max-height: 280px;
  overflow-y: auto;
  padding: 6px;
  border-radius: 14px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.98) 0%, rgba(244, 248, 255, 0.98) 100%);
  box-shadow: 0 18px 40px rgba(15, 37, 78, 0.12), 0 6px 14px rgba(15, 37, 78, 0.08);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  box-sizing: border-box;
}

.agent-skill-menu-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px 8px;
  font-size: 11px;
  font-weight: 600;
  color: var(--qwen-text-secondary);
  letter-spacing: 0.01em;
}

.agent-skill-menu-hint {
  font-size: 10px;
  font-weight: 400;
  color: var(--qwen-text-tertiary);
}

.agent-skill-menu-loading,
.agent-skill-menu-empty {
  padding: 10px 12px;
  font-size: 12px;
  color: var(--qwen-text-tertiary);
}

.agent-skill-menu-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--qwen-text-primary);
  font-size: 13px;
  cursor: pointer;
  text-align: left;
  transition: background 140ms ease, color 140ms ease, transform 140ms ease;

  &:hover {
    background: rgba(26, 74, 140, 0.06);
    transform: translateX(-1px);
  }
}

.agent-skill-menu-item-slash {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  flex: 0 0 18px;
  border-radius: 6px;
  background: linear-gradient(180deg, rgba(240, 246, 255, 0.98) 0%, rgba(228, 239, 255, 0.94) 100%);
  color: var(--qwen-brand-blue);
  font-size: 12px;
  font-weight: 700;
}

.agent-skill-menu-item-name {
  flex: 0 0 auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}

.agent-skill-menu-item-desc {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--qwen-text-tertiary);
}

.agent-skill-fade-enter-active,
.agent-skill-fade-leave-active {
  transition: opacity 140ms ease, transform 140ms ease;
}

.agent-skill-fade-enter-from,
.agent-skill-fade-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

:global(html[data-theme="dark"]) .agent-skill-menu {
  border-color: rgba(196, 214, 255, 0.1);
  background: linear-gradient(180deg, rgba(28, 39, 58, 0.98) 0%, rgba(18, 27, 41, 0.98) 100%);
  box-shadow: 0 18px 40px rgba(4, 12, 26, 0.42), 0 6px 14px rgba(4, 12, 26, 0.26);
}

:global(html[data-theme="dark"]) .agent-skill-menu-item {
  color: rgba(232, 238, 255, 0.92);
}

:global(html[data-theme="dark"]) .agent-skill-menu-item:hover {
  background: rgba(96, 135, 214, 0.1);
}

:global(html[data-theme="dark"]) .agent-skill-menu-item-slash {
  background: linear-gradient(180deg, rgba(63, 94, 149, 0.78) 0%, rgba(48, 72, 113, 0.76) 100%);
  color: rgba(244, 248, 255, 0.98);
}

@media (max-width: 768px) {
  .chat-composer-empty,
  .chat-composer-docked {
    padding: 12px 14px;
  }

  .chat-composer-tool-btn,
  .chat-send-btn {
    width: 36px;
    height: 36px;
  }

  .chat-composer-input textarea {
    font-size: 16px;
  }

  .agent-skill-menu {
    left: 8px;
    right: 8px;
  }
}
</style>
