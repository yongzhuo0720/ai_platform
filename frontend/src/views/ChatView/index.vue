<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from "vue";
import { Expand, Fold, Loading, ArrowDown } from "@element-plus/icons-vue";
import { useChat } from "./index";
import ChatComposer from "./ChatComposer.vue";
import ChatSidebar from "./ChatSidebar.vue";
import ChatMessageList from "./ChatMessageList.vue";
import ChatAssistantDialogs from "./ChatAssistantDialogs.vue";

const {
  activeModelId,
  historyCollapsed,
  inputValue,
  messages,
  chatMessagesRef,
  composerRef,
  showScrollToBottom,
  handleMessagesScroll,
  scrollToBottom,
  loading,
  parametersLoading,
  chatInputFields,
  chatSelectOptionsMap,
  modelOptions,
  suggestedQuestions,
  postAnswerSuggestions,
  handleSuggestedClick,
  userInput,
  activeAssistant,
  assistantGreetingName,
  submitMessage,
  toggleHistorySidebar,
  attachedFiles,
  isDragging,
  handleDragOver,
  handleDragLeave,
  handleDrop,
  removeAttachedFile,
  handleFileSelected,
  stopGenerating,
  allowFileUpload,
  isDirectMode,
  directKbName,
  directKbOptions,
  updateInputField,
  selectModel,
} = useChat();

/** 直连模式的知识库选择器需要模型已就绪才显示（模型列表里才有直连项） */
const chatKbOptions = computed(() => (isDirectMode.value ? directKbOptions.value : []));

const isMobile = ref(false);
let mobileCheckTimer: number | null = null;

const checkMobile = () => {
  isMobile.value = window.innerWidth < 1024;
};

const handleResize = () => {
  if (mobileCheckTimer) {
    window.clearTimeout(mobileCheckTimer);
  }
  mobileCheckTimer = window.setTimeout(checkMobile, 100);
};

const handleSidebarShortcut = (event: KeyboardEvent) => {
  if (isMobile.value || event.repeat || event.isComposing) {
    return;
  }

  const target = event.target as HTMLElement | null;
  const tagName = target?.tagName?.toLowerCase();
  const isEditableTarget =
    tagName === "input" ||
    tagName === "textarea" ||
    tagName === "select" ||
    target?.isContentEditable;

  if (isEditableTarget) {
    return;
  }

  const isToggleShortcut =
    (event.ctrlKey || event.metaKey) &&
    !event.altKey &&
    event.key.toLowerCase() === "b";

  if (!isToggleShortcut) {
    return;
  }

  event.preventDefault();
  toggleHistorySidebar();
};

const onStageEnter = () => {
  if (messages.value.length === 0 && !parametersLoading.value && !loading.value) {
    composerRef.value?.focus({ preventScroll: true });
  }
};

onMounted(() => {
  checkMobile();
  window.addEventListener("resize", handleResize);
  window.addEventListener("keydown", handleSidebarShortcut);
});

onUnmounted(() => {
  window.removeEventListener("resize", handleResize);
  window.removeEventListener("keydown", handleSidebarShortcut);
  if (mobileCheckTimer) {
    window.clearTimeout(mobileCheckTimer);
  }
});
</script>

<template>
  <section class="chat-page">
    <!-- 桌面端侧栏 + 移动端抽屉 -->
    <ChatSidebar :is-mobile="isMobile" />

    <!-- 助手弹窗 -->
    <ChatAssistantDialogs />

    <!-- 聊天主区域 -->
    <div
      class="chat-main"
      :class="{ 'is-empty': messages.length === 0 }"
      @dragover="handleDragOver"
      @dragleave="handleDragLeave"
      @drop="handleDrop"
    >
      <!-- 左上角：侧栏展开按钮（空状态且侧栏折叠时可见） -->
      <button
        v-if="historyCollapsed && messages.length === 0"
        type="button"
        class="chat-corner-toggle"
        aria-label="展开侧边栏"
        title="展开侧边栏（Ctrl / Command + B）"
        @click="toggleHistorySidebar"
      >
        <el-icon><Expand /></el-icon>
      </button>

      <!-- 拖拽文件时的覆盖层提示 -->
      <div v-if="isDragging" class="drag-overlay">
        <div class="drag-overlay-content">
          <span>释放文件以添加到消息</span>
        </div>
      </div>

      <!-- 头部：仅在对话状态时显示 -->
      <div v-if="messages.length > 0" class="chat-main-header">
        <div class="chat-main-title">
          <button
            type="button"
            class="chat-history-toggle"
            aria-label="展开历史对话"
            @click="toggleHistorySidebar"
          >
            <el-icon class="chat-history-toggle-indicator">
              <Expand v-if="historyCollapsed" />
              <Fold v-else />
            </el-icon>
          </button>
          <div class="chat-main-title-text">
            {{ activeAssistant.name }}
          </div>
        </div>
      </div>

      <div ref="chatMessagesRef" class="chat-messages" @scroll.passive="handleMessagesScroll">
        <transition name="chat-stage" mode="out-in" @after-enter="onStageEnter">
          <div v-if="messages.length === 0" key="empty" class="chat-empty">
            <div v-if="parametersLoading && !isDirectMode" class="chat-empty-loading">
              <el-icon class="is-loading"><Loading /></el-icon>
              <p>加载中...</p>
            </div>
            <template v-else>
              <div
                v-if="historyCollapsed"
                class="chat-empty-sidebar-text"
              >
                <span class="chat-empty-sidebar-text-title">展开侧边栏</span>
                <span class="chat-empty-sidebar-text-hint">Ctrl / Command + B</span>
              </div>
              <div class="chat-empty-brand">
                <img src="@/assets/images/brand-logo.svg" alt="AI" class="chat-empty-logo" />
                <div v-if="isDirectMode">您好，当前对话模型：{{ assistantGreetingName }}</div>
                <div v-else>您好，我是{{ assistantGreetingName }}</div>
              </div>

              <ChatComposer
                ref="composerRef"
                v-model="inputValue"
                :attached-files="attachedFiles"
                :input-fields="chatInputFields"
                :input-values="userInput"
                :select-options-map="chatSelectOptionsMap"
                :model-options="modelOptions"
                :active-model-id="activeModelId"
                :loading="loading"
                :allow-file-upload="allowFileUpload"
                :file-accept="isDirectMode ? 'image/*' : undefined"
                :kb-options="chatKbOptions"
                :active-kb-name="directKbName"
                placeholder="请输入问题，开始对话..."
                is-empty
                @submit="submitMessage"
                @stop="stopGenerating"
                @file-selected="handleFileSelected"
                @remove-file="removeAttachedFile"
                @update-input-field="updateInputField"
                @select-model="selectModel"
                @select-kb="directKbName = $event"
              />

              <div v-if="suggestedQuestions.length > 0" class="suggested-questions">
                <p class="suggested-questions-title">建议问题</p>
                <div class="suggested-questions-list">
                  <button
                    v-for="(question, index) in suggestedQuestions"
                    :key="index"
                    type="button"
                    class="suggested-question-btn"
                    @click="handleSuggestedClick(question)"
                  >
                    {{ question }}
                  </button>
                </div>
              </div>
            </template>
          </div>

          <ChatMessageList v-else key="messages" />
        </transition>

        <transition name="chat-scroll-hint">
          <button
            v-if="showScrollToBottom && messages.length > 0"
            type="button"
            class="chat-scroll-bottom"
            aria-label="回到底部"
            title="回到底部"
            @click="scrollToBottom(true)"
          >
            <el-icon><ArrowDown /></el-icon>
          </button>
        </transition>
      </div>

      <!-- 消息完成后的推荐问题 -->
      <div
        v-if="!isDirectMode && postAnswerSuggestions.length > 0 && messages.length > 0 && !loading"
        class="suggested-questions post-answer-suggestions"
      >
        <p class="suggested-questions-title">推荐问题</p>
        <div class="suggested-questions-list">
          <button
            v-for="(question, index) in postAnswerSuggestions"
            :key="index"
            type="button"
            class="suggested-question-btn"
            @click="handleSuggestedClick(question)"
          >
            {{ question }}
          </button>
        </div>
      </div>

      <!-- 对话状态底部输入区 -->
      <transition name="chat-dock">
        <div v-if="messages.length > 0" class="chat-composer-docked-wrapper">
          <ChatComposer
            v-model="inputValue"
            :attached-files="attachedFiles"
            :input-fields="chatInputFields"
            :input-values="userInput"
            :select-options-map="chatSelectOptionsMap"
            :model-options="modelOptions"
            :active-model-id="activeModelId"
            :loading="loading"
            :allow-file-upload="allowFileUpload"
            :file-accept="isDirectMode ? 'image/*' : undefined"
            :kb-options="chatKbOptions"
            :active-kb-name="directKbName"
            placeholder="请输入问题，继续对话..."
            @submit="submitMessage"
            @stop="stopGenerating"
            @file-selected="handleFileSelected"
            @remove-file="removeAttachedFile"
            @update-input-field="updateInputField"
            @select-model="selectModel"
            @select-kb="directKbName = $event"
          />
        </div>
      </transition>
    </div>
  </section>
</template>

<style lang="less">
@import "./index.less";
</style>
