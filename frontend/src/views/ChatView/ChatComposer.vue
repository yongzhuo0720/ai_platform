<script setup lang="ts">
import {
  ref,
  computed,
  nextTick,
  onMounted,
  onUnmounted,
} from "vue";
import { ElInput } from "element-plus";
import { Promotion, Plus, Delete, ArrowDown, Check, VideoPause, Collection } from "@element-plus/icons-vue";
import type { NormalizedUserInputField } from "@/types/chat";

export interface AttachedFile {
  id: string;
  name: string;
  type: string;
  size: number;
}

interface Props {
  modelValue: string;
  attachedFiles?: AttachedFile[];
  inputFields?: NormalizedUserInputField[];
  inputValues?: Record<string, unknown>;
  selectOptionsMap?: Record<string, Array<{ label: string; value: string }>>;
  modelOptions?: Array<{ label: string; value: string; title?: string; subtitle?: string }>;
  activeModelId?: string;
  loading?: boolean;
  placeholder?: string;
  isEmpty?: boolean;
  allowFileUpload?: boolean;
  /** 可选知识库名列表（直连模式才有值，空数组不渲染选择器） */
  kbOptions?: string[];
  /** 当前选中的知识库名，空串 = 不引用知识库 */
  activeKbName?: string;
  /** 文件选择器过滤；直连模式只收图片 */
  fileAccept?: string;
}

const props = withDefaults(defineProps<Props>(), {
  attachedFiles: () => [],
  inputFields: () => [],
  inputValues: () => ({}),
  selectOptionsMap: () => ({}),
  modelOptions: () => [],
  activeModelId: "",
  loading: false,
  placeholder: "请输入问题...",
  isEmpty: false,
  allowFileUpload: true,
  kbOptions: () => [],
  activeKbName: "",
});

const emit = defineEmits<{
  (e: "update:modelValue", value: string): void;
  (e: "submit"): void;
  (e: "stop"): void;
  (e: "file-selected", file: File): void;
  (e: "remove-file", fileId: string): void;
  (e: "update-input-field", payload: { variable: string; value: string }): void;
  (e: "select-model", value: string): void;
  (e: "select-kb", value: string): void;
}>();

const textareaRef = ref<InstanceType<typeof ElInput> | null>(null);
const fileInputRef = ref<HTMLInputElement | null>(null);
const modelSwitcherRef = ref<HTMLElement | null>(null);
const modelShellRef = ref<HTMLElement | null>(null);
const modelMenuRef = ref<HTMLElement | null>(null);
const modelMenuOpen = ref(false);
const modelMenuStyle = ref<Record<string, string>>({});

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
  if (!(event instanceof KeyboardEvent)) return;
  // 中文/日文等输入法组词过程中按回车是「确认候选词」，不应直接发送消息。
  // 修复前漏了这道判断：用拼音打字时每次选词都会误发一条消息。
  if (event.isComposing) return;
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    handleSubmit();
  }
};

const handleAttachClick = () => {
  fileInputRef.value?.click();
};

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

const handleRemoveFile = (fileId: string) => {
  emit("remove-file", fileId);
};

const getFieldValue = (variable: string) => {
  const value = props.inputValues[variable];
  return typeof value === "string" ? value : value == null ? "" : String(value);
};

const handleInputFieldChange = (variable: string, value: string) => {
  emit("update-input-field", { variable, value });
};

const currentModelValue = computed(() => {
  if (props.activeModelId) {
    return props.activeModelId;
  }
  return props.modelOptions[0]?.value || "";
});

const currentModelOption = computed(() => {
  return (
    props.modelOptions.find((option) => option.value === currentModelValue.value) ||
    props.modelOptions[0] ||
    null
  );
});

/** 菜单打开时若超出视口，临时撑开 body 使浏览器出现滚动条，菜单关闭时恢复 */
const restoreBodyScroll = () => {
  const body = document.body;
  body.style.height = '';
  body.style.overflowY = '';
  body.style.paddingBottom = '';
};

const updateMenuPosition = () => {
  const shell = modelShellRef.value;
  if (!shell) return;
  const rect = shell.getBoundingClientRect();
  const scrollX = window.scrollX || document.documentElement.scrollLeft;
  const scrollY = window.scrollY || document.documentElement.scrollTop;
  const menuWidth = Math.min(224, window.innerWidth - 16);
  const menuMaxHeight = Math.min(420, window.innerHeight * 0.6);

  let left = rect.right - menuWidth + scrollX;
  if (left < 8) left = 8;
  if (left + menuWidth > window.innerWidth + scrollX - 8) {
    left = window.innerWidth + scrollX - menuWidth - 8;
  }

  // 输入区贴近视口底部，向下空间不足时向上展开（不再撑开 body 出滚动条——
  // 那会让视口外的选项既看不见也点不到）
  const spaceBelow = window.innerHeight - rect.bottom - 8;
  let top: number;
  if (spaceBelow < Math.min(menuMaxHeight, props.modelOptions.length * 44 + 12) && rect.top > 8) {
    const height = Math.min(menuMaxHeight, rect.top - 8);
    top = rect.top - 8 - height + scrollY;
  } else {
    top = rect.bottom + 8 + scrollY;
  }

  modelMenuStyle.value = {
    position: 'absolute',
    top: `${top}px`,
    left: `${left}px`,
    width: `${menuWidth}px`,
  };
};

const closeModelMenu = () => {
  modelMenuOpen.value = false;
  restoreBodyScroll();
  window.removeEventListener('scroll', updateMenuPosition);
  window.removeEventListener('resize', updateMenuPosition);
};

const toggleModelMenu = () => {
  if (props.loading || props.modelOptions.length === 0) {
    return;
  }
  modelMenuOpen.value = !modelMenuOpen.value;
  if (modelMenuOpen.value) {
    updateMenuPosition();
    window.addEventListener('scroll', updateMenuPosition);
    window.addEventListener('resize', updateMenuPosition);
  } else {
    closeModelMenu();
  }
};

const selectModelOption = (value: string) => {
  emit("select-model", value);
  closeModelMenu();
};

const handleModelKeydown = (event: KeyboardEvent) => {
  if (event.key === "Escape") {
    closeModelMenu();
  }
};

const handleDocumentClick = (event: MouseEvent) => {
  const target = event.target as Node | null;
  if (!target || !modelSwitcherRef.value?.contains(target)) {
    closeModelMenu();
  }
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
  restoreBodyScroll();
  window.removeEventListener("scroll", updateMenuPosition);
  window.removeEventListener("resize", updateMenuPosition);
});
</script>

<template>
  <div
    class="chat-composer"
    :class="{
      'chat-composer-empty': isEmpty,
      'chat-composer-docked': !isEmpty,
      'has-files': attachedFiles.length > 0,
    }"
  >
    <input
      ref="fileInputRef"
      type="file"
      multiple
      :accept="fileAccept"
      class="chat-composer-file-input"
      @change="handleFileChange"
    />

    <div v-if="inputFields.length > 0" class="chat-composer-form">
      <div
        v-for="field in inputFields"
        :key="field.variable"
        class="chat-composer-form-item"
      >
        <label class="chat-composer-form-label" :for="`composer-field-${field.variable}`">
          <span>{{ field.label }}</span>
          <span v-if="field.required" class="chat-composer-form-required">*</span>
        </label>

        <select
          v-if="field.type === 'select'"
          :id="`composer-field-${field.variable}`"
          class="chat-composer-form-control chat-composer-form-select"
          :value="getFieldValue(field.variable)"
          :disabled="loading"
          @change="handleInputFieldChange(field.variable, ($event.target as HTMLSelectElement).value)"
        >
          <option
            v-for="option in (selectOptionsMap[field.variable] || [])"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </option>
        </select>

        <textarea
          v-else-if="field.type === 'paragraph'"
          :id="`composer-field-${field.variable}`"
          class="chat-composer-form-control chat-composer-form-textarea"
          :value="getFieldValue(field.variable)"
          :disabled="loading"
          rows="3"
          @input="handleInputFieldChange(field.variable, ($event.target as HTMLTextAreaElement).value)"
        />

        <input
          v-else
          :id="`composer-field-${field.variable}`"
          class="chat-composer-form-control"
          type="text"
          :value="getFieldValue(field.variable)"
          :disabled="loading"
          @input="handleInputFieldChange(field.variable, ($event.target as HTMLInputElement).value)"
        />
      </div>
    </div>

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
          v-if="allowFileUpload"
          type="button"
          class="chat-composer-tool-btn attach-btn"
          aria-label="添加附件"
          @click="handleAttachClick"
        >
          <el-icon><Plus /></el-icon>
        </button>

        <label
          v-if="kbOptions.length > 0"
          class="chat-kb-switcher"
          title="回答前先在所选知识库中检索相关片段（服务端 RAG）"
        >
          <el-icon class="chat-kb-switcher-icon"><Collection /></el-icon>
          <select
            class="chat-kb-switcher-select"
            :value="activeKbName"
            :disabled="loading"
            aria-label="知识库检索范围"
            @change="emit('select-kb', ($event.target as HTMLSelectElement).value)"
          >
            <option value="">不引用知识库</option>
            <option v-for="name in kbOptions" :key="name" :value="name">{{ name }}</option>
          </select>
        </label>
      </div>

      <div class="chat-composer-toolbar-end">
        <label
          v-if="modelOptions.length > 0"
          ref="modelSwitcherRef"
          class="chat-model-switcher"
          aria-label="选择模型"
          @keydown="handleModelKeydown"
        >
          <div
            class="chat-model-switcher-shell"
            ref="modelShellRef"
            :class="{ 'is-open': modelMenuOpen }"
          >
            <button
              type="button"
              class="chat-model-switcher-trigger"
              :disabled="loading"
              :aria-expanded="modelMenuOpen"
              aria-haspopup="listbox"
              @click.stop="toggleModelMenu"
            >
              <span class="chat-model-switcher-value">
                {{ currentModelOption?.title || currentModelOption?.label || "选择模型" }}
              </span>
              <el-icon class="chat-model-switcher-arrow" :class="{ 'is-open': modelMenuOpen }">
                <ArrowDown />
              </el-icon>
            </button>

            <Teleport to="body">
              <transition name="chat-model-menu">
                <div
                  v-if="modelMenuOpen"
                  ref="modelMenuRef"
                  class="chat-model-menu"
                  :style="modelMenuStyle"
                  role="listbox"
                  @click.stop
                >
                  <button
                    v-for="option in modelOptions"
                    :key="option.value"
                    type="button"
                    class="chat-model-menu-item"
                    :class="{ 'is-active': option.value === currentModelValue }"
                    @click="selectModelOption(option.value)"
                  >
                    <span class="chat-model-menu-item-content">
                      <span class="chat-model-menu-item-title">
                        {{ option.title || option.label }}
                      </span>
                      <span v-if="option.subtitle" class="chat-model-menu-item-subtitle">
                        {{ option.subtitle }}
                      </span>
                    </span>
                    <span class="chat-model-menu-item-check">
                      <el-icon v-if="option.value === currentModelValue"><Check /></el-icon>
                    </span>
                  </button>
                </div>
              </transition>
            </Teleport>
          </div>
        </label>

        <button
          v-if="loading"
          type="button"
          class="chat-send-btn is-stop"
          aria-label="停止生成"
          title="停止生成"
          @click="emit('stop')"
        >
          <VideoPause />
        </button>
        <button
          v-else
          type="button"
          class="chat-send-btn"
          :disabled="!canSubmit"
          :aria-label="'发送消息'"
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

.chat-composer-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
}

.chat-composer-form-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.chat-composer-form-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--qwen-text-secondary);
}

.chat-composer-form-required {
  color: var(--error);
}

.chat-composer-form-control {
  width: 100%;
  min-height: 38px;
  padding: 8px 12px;
  border-radius: 10px;
  border: 1px solid var(--qwen-border);
  background: var(--glass-bg);
  color: var(--qwen-text-primary);
  font-size: 13px;
  line-height: 1.4;
  outline: none;
  transition: border-color 150ms ease, box-shadow 150ms ease;

  &:focus {
    border-color: var(--qwen-brand-blue);
    box-shadow: 0 0 0 2px rgba(26, 74, 140, 0.1);
  }

  &:disabled {
    opacity: 0.65;
    cursor: not-allowed;
  }
}

.chat-composer-form-textarea {
  min-height: 78px;
  resize: vertical;
}

.chat-composer-form-select {
  appearance: none;
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
  gap: 6px;
  min-width: 0;
}

.chat-kb-switcher {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 30px;
  min-width: 0;
  max-width: 180px;
  padding: 0 8px 0 10px;
  border: 1px solid rgba(15, 23, 42, 0.06);
  border-radius: 999px;
  background: var(--qwen-bg);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.42);
  transition: border-color 150ms ease, background 150ms ease;
  cursor: pointer;

  &:hover,
  &:focus-within {
    border-color: rgba(26, 74, 140, 0.14);
    background: rgba(26, 74, 140, 0.03);
  }
}

.chat-kb-switcher-icon {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--qwen-text-secondary);
}

.chat-kb-switcher-select {
  min-width: 0;
  max-width: 132px;
  height: 100%;
  border: none;
  background: transparent;
  color: var(--qwen-text-primary);
  font-size: 11px;
  font-weight: 500;
  outline: none;
  cursor: pointer;
  text-overflow: ellipsis;

  &:disabled {
    cursor: not-allowed;
    opacity: 0.65;
  }

  option {
    font-size: 12px;
  }
}

.chat-composer-toolbar-end {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  min-width: 0;
}

.chat-model-switcher {
  display: inline-flex;
  align-items: center;
  min-width: 0;
}

.chat-model-switcher-shell {
  position: relative;
  display: flex;
  align-items: center;
  min-width: 152px;
  max-width: min(198px, 30vw);
  border: 1px solid rgba(15, 23, 42, 0.06);
  border-radius: 999px;
  background: var(--qwen-bg);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.42);
  transition: border-color 150ms ease, box-shadow 150ms ease, background 150ms ease, transform 150ms ease;

  &:hover {
    border-color: rgba(26, 74, 140, 0.12);
    background: rgba(26, 74, 140, 0.03);
  }

  &.is-open {
    border-color: rgba(26, 74, 140, 0.18);
    background: linear-gradient(180deg, rgba(247, 250, 255, 1) 0%, rgba(237, 244, 255, 0.96) 100%);
    box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.68), 0 4px 12px rgba(26, 74, 140, 0.08);
  }

  &:focus-within {
    border-color: rgba(26, 74, 140, 0.3);
    background: linear-gradient(180deg, rgba(247, 250, 255, 1) 0%, rgba(237, 244, 255, 0.96) 100%);
    box-shadow: 0 0 0 2px rgba(26, 74, 140, 0.08);
    transform: translateY(-1px);
  }
}

.chat-model-switcher-trigger {
  width: 100%;
  min-width: 0;
  height: 30px;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 26px 0 10px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: var(--qwen-text-primary);
  font-size: 12px;
  font-weight: 500;
  outline: none;
  cursor: pointer;
  text-align: left;

  &:disabled {
    cursor: not-allowed;
    opacity: 0.65;
  }
}

.chat-model-switcher-value {
  display: block;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  letter-spacing: 0.01em;
}

.chat-model-switcher-arrow {
  position: absolute;
  right: 8px;
  pointer-events: none;
  font-size: 10px;
  color: var(--qwen-text-secondary);
  transition: transform 150ms ease, color 150ms ease;

  &.is-open {
    transform: rotate(180deg);
  }
}

.chat-model-menu-enter-active,
.chat-model-menu-leave-active {
  transition: opacity 140ms ease, transform 140ms ease;
}

.chat-model-menu-enter-from,
.chat-model-menu-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

.chat-model-menu {
  padding: 6px;
  border-radius: 14px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.98) 0%, rgba(244, 248, 255, 0.98) 100%);
  box-shadow: 0 18px 40px rgba(15, 37, 78, 0.12), 0 6px 14px rgba(15, 37, 78, 0.08);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  z-index: 9999;
  box-sizing: border-box;
  /* 高度上限 + 内部滚动：菜单挂在输入区上方/下方时都不越出视口，
     条目多时靠自身滚动浏览（此前 max-height:none 会延伸出视口，
     视口外的选项既看不见也点不到） */
  max-height: min(420px, 60vh);
  overflow-y: auto;
}

.chat-model-menu-item {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--qwen-text-primary);
  cursor: pointer;
  text-align: left;
  transition: background 140ms ease, color 140ms ease, transform 140ms ease;

  &:hover {
    background: rgba(26, 74, 140, 0.06);
    transform: translateX(-1px);
  }

  &.is-active {
    background: linear-gradient(180deg, rgba(240, 246, 255, 0.98) 0%, rgba(228, 239, 255, 0.94) 100%);
    color: var(--qwen-brand-blue);
    box-shadow: inset 0 0 0 1px rgba(26, 74, 140, 0.08);
  }
}

.chat-model-menu-item-content {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.chat-model-menu-item-title {
  display: block;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.3;
}

.chat-model-menu-item-subtitle {
  display: block;
  font-size: 9px;
  line-height: 1.2;
  color: var(--qwen-text-tertiary);
}

.chat-model-menu-item-check {
  width: 14px;
  height: 14px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 14px;
  color: var(--qwen-brand-blue);

  .el-icon {
    font-size: 12px;
  }
}

:global(html[data-theme="dark"] .chat-composer .chat-model-switcher-shell) {
  border-color: rgba(196, 214, 255, 0.08);
  background: rgba(20, 30, 46, 0.9);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.04);
}

:global(html[data-theme="dark"] .chat-composer .chat-kb-switcher) {
  border-color: rgba(196, 214, 255, 0.08);
  background: rgba(20, 30, 46, 0.9);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.04);

  &:hover,
  &:focus-within {
    border-color: rgba(142, 179, 255, 0.14);
    background: rgba(96, 135, 214, 0.08);
  }
}

:global(html[data-theme="dark"] .chat-composer .chat-kb-switcher-select) {
  color: rgba(232, 238, 255, 0.94);
}

:global(html[data-theme="dark"] .chat-composer .chat-model-switcher-shell.is-open) {
  border-color: rgba(142, 179, 255, 0.2);
  background: linear-gradient(180deg, rgba(47, 67, 102, 0.96) 0%, rgba(33, 49, 76, 0.94) 100%);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06), 0 4px 12px rgba(6, 16, 34, 0.28);
}

:global(html[data-theme="dark"] .chat-composer .chat-model-switcher-shell:hover) {
  border-color: rgba(142, 179, 255, 0.14);
  background: rgba(96, 135, 214, 0.08);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.05);
}

:global(html[data-theme="dark"] .chat-composer .chat-model-switcher-shell:focus-within) {
  border-color: rgba(142, 179, 255, 0.28);
  background: linear-gradient(180deg, rgba(47, 67, 102, 0.96) 0%, rgba(33, 49, 76, 0.94) 100%);
  box-shadow: 0 0 0 2px rgba(142, 179, 255, 0.1);
}

:global(html[data-theme="dark"] .chat-composer .chat-model-switcher-trigger) {
  color: rgba(232, 238, 255, 0.94);
}

:global(html[data-theme="dark"] .chat-composer .chat-model-switcher-arrow) {
  color: rgba(208, 219, 255, 0.72);
}

:global(html[data-theme="dark"]) .chat-model-menu {
  border-color: rgba(196, 214, 255, 0.1);
  background: linear-gradient(180deg, rgba(28, 39, 58, 0.98) 0%, rgba(18, 27, 41, 0.98) 100%);
  box-shadow: 0 18px 40px rgba(4, 12, 26, 0.42), 0 6px 14px rgba(4, 12, 26, 0.26);
}

:global(html[data-theme="dark"]) .chat-model-menu-item {
  color: rgba(232, 238, 255, 0.92);
}

:global(html[data-theme="dark"]) .chat-model-menu-item:hover {
  background: rgba(96, 135, 214, 0.1);
}

:global(html[data-theme="dark"]) .chat-model-menu-item.is-active {
  background: linear-gradient(180deg, rgba(63, 94, 149, 0.78) 0%, rgba(48, 72, 113, 0.76) 100%);
  color: rgba(244, 248, 255, 0.98);
  box-shadow: inset 0 0 0 1px rgba(170, 198, 255, 0.14);
}

:global(html[data-theme="dark"]) .chat-model-menu-item-subtitle {
  color: rgba(208, 219, 255, 0.66);
}

:global(html[data-theme="dark"]) .chat-model-menu-item-check {
  color: rgba(192, 214, 255, 0.96);
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

  &.is-stop {
    background: #d93a3a;

    &:hover {
      background: #b52f2f;
      transform: scale(1.05);
      box-shadow: 0 4px 10px rgba(217, 58, 58, 0.25);
    }
  }

  svg {
    width: 16px;
    height: 16px;
  }
}

@media (max-width: 768px) {
  .chat-composer-empty,
  .chat-composer-docked {
    padding: 12px 14px;
  }

  .chat-composer-form {
    grid-template-columns: 1fr;
  }

  .chat-composer-toolbar {
    align-items: center;
    flex-wrap: wrap;
  }

  .chat-composer-toolbar-start {
    order: 1;
  }

  .chat-composer-toolbar-end {
    order: 2;
    flex: 1;
    min-width: 0;
    justify-content: flex-end;
  }

  .chat-model-switcher-shell {
    min-width: 0;
    max-width: min(100%, 198px);
  }

  .chat-composer-tool-btn,
  .chat-send-btn {
    width: 36px;
    height: 36px;
  }

  .chat-composer-input textarea {
    font-size: 16px;
  }
}
</style>
