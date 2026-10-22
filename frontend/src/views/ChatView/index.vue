<script setup lang="ts">
import { ref, onMounted, onUnmounted } from "vue";
import {
  Expand,
  Fold,
  Delete,
  Loading,
  Plus,
  Close,
  Sort,
  MoreFilled,
  Avatar,
  DocumentCopy,
  RefreshRight,
  Edit,
} from "@element-plus/icons-vue";
import { useChat } from "./index";
import ChatComposer from "./ChatComposer.vue";

const {
  activeModelId,
  activeAssistantId,
  historyCollapsed,
  inputValue,
  messages,
  chatMessagesRef,
  composerRef,
  filteredConversations,
  activeConversationId,
  loading,
  difyModels,
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
  canEditAssistant,
  canDeleteAssistant,
  handleQuickAction,
  submitMessage,
  toggleHistorySidebar,
  handleSelectConversation,
  handleDeleteHistory,
  startNewConversation,
  attachedFiles,
  isDragging,
  handleDragOver,
  handleDragLeave,
  handleDrop,
  removeAttachedFile,
  handleFileSelected,
  copyMessage,
  stopGenerating,
  exportMessageMarkdown,
  canRegenerateMessage,
  handleRegenerateMessage,
  formatContent,
  formatUserContent,
  sideTab,
  assistantDialogVisible,
  assistantForm,
  isAssistantEditing,
  assistantTemplates,
  canConfirmAssistant,
  openAssistantDialog,
  openAssistantEditDialog,
  closeAssistantDialog,
  confirmAssistantDialog,
  applyAssistantTemplate,
  updateInputField,
  selectModel,
  toggleAssistantMenu,
  selectAssistant,
  assistantMenuOpenId,
  requestDeleteAssistant,
  cancelDeleteAssistant,
  confirmDeleteAssistant,
  assistantDeleteConfirm,
  allowFileUpload,
  isDirectMode,
} = useChat();

/** 根据文件 MIME 类型返回对应的 emoji 图标 */
const getFileTypeEmoji = (mimeType: string): string => {
  if (mimeType.startsWith('image/')) return '🖼️';
  if (mimeType === 'application/pdf') return '📕';
  if (mimeType.includes('word') || mimeType.includes('document')) return '📄';
  if (mimeType.includes('excel') || mimeType.includes('spreadsheet')) return '📊';
  if (mimeType.includes('zip') || mimeType.includes('rar') || mimeType.includes('compressed')) return '📦';
  if (mimeType.startsWith('text/')) return '📝';
  if (mimeType.startsWith('video/')) return '🎬';
  if (mimeType.startsWith('audio/')) return '🎵';
  return '📎';
};

/** 格式化文件大小 */
const formatFileSize = (bytes: number): string => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

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
    <!-- 桌面端侧栏 -->
    <transition name="chat-sidebar-collapse">
      <aside v-show="!historyCollapsed" class="chat-sidebar">
        <!-- 顶部：可折叠 Logo 按钮 -->
        <header class="chat-side-header">
          <button
            type="button"
            class="chat-side-logo"
            :aria-label="historyCollapsed ? '展开侧栏' : '折叠侧栏'"
            @click="toggleHistorySidebar"
          >
            <img src="@/assets/images/brand-logo.svg" alt="logo" class="chat-side-logo-mark"/>
            <span class="chat-side-logo-name">智能问答</span>
            <el-icon class="chat-side-logo-arrow"><Fold /></el-icon>
          </button>
        </header>

        <!-- Tab 切换：助手 / 话题 -->
        <div class="chat-side-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            class="chat-side-tab"
            :class="{ active: sideTab === 'assistant' }"
            :aria-selected="sideTab === 'assistant'"
            @click="sideTab = 'assistant'"
          >
            助手
          </button>
          <button
            type="button"
            role="tab"
            class="chat-side-tab"
            :class="{ active: sideTab === 'topic' }"
            :aria-selected="sideTab === 'topic'"
            @click="sideTab = 'topic'"
          >
            话题
          </button>
        </div>

        <!-- 助手 Tab -->
        <div v-show="sideTab === 'assistant'" class="chat-side-pane chat-side-pane-assistant">
          <button
            type="button"
            class="chat-side-add"
            @click="openAssistantDialog"
          >
            <el-icon class="chat-side-add-icon"><Plus /></el-icon>
            <span>添加助手</span>
          </button>

          <div class="chat-side-section">
            <div class="chat-side-section-head">
              <span>助手列表</span>
            </div>
            <ul class="chat-side-list">
              <li
                v-for="model in difyModels"
                :key="model.id"
                class="chat-side-item"
                :class="{ active: activeAssistantId === model.id }"
                @click="selectAssistant(model.id)"
              >
                <span class="chat-side-item-icon">
                  <el-icon><Avatar /></el-icon>
                </span>
                <span class="chat-side-item-name">{{ model.name }}</span>
                <div class="chat-side-item-action-wrap" @click.stop>
                  <button
                    v-if="canEditAssistant(model) || canDeleteAssistant(model)"
                    type="button"
                    class="chat-side-item-action"
                    :aria-label="'更多操作：' + model.name"
                    :aria-expanded="assistantMenuOpenId === model.id"
                    @click.stop="toggleAssistantMenu(model.id)"
                  >
                    <el-icon><MoreFilled /></el-icon>
                  </button>
                  <ul
                    v-if="assistantMenuOpenId === model.id"
                    class="chat-side-item-menu"
                    role="menu"
                  >
                    <li v-if="canEditAssistant(model)">
                      <button
                        type="button"
                        class="chat-side-item-menu-item"
                        role="menuitem"
                        @click="openAssistantEditDialog(model)"
                      >
                        <el-icon><Edit /></el-icon>
                        <span>编辑助手</span>
                      </button>
                    </li>
                    <li v-if="canDeleteAssistant(model)">
                      <button
                        type="button"
                        class="chat-side-item-menu-item is-danger"
                        role="menuitem"
                        @click="requestDeleteAssistant(model)"
                      >
                        <el-icon><Delete /></el-icon>
                        <span>删除助手</span>
                      </button>
                    </li>
                  </ul>
                </div>
              </li>
            </ul>
          </div>
        </div>

        <!-- 话题 Tab -->
        <div v-show="sideTab === 'topic'" class="chat-side-pane chat-side-pane-topic">
          <div class="chat-side-pane-toolbar">
            <button
              type="button"
              class="chat-side-add"
              @click="startNewConversation"
            >
              <el-icon class="chat-side-add-icon"><Plus /></el-icon>
              <span>新建话题</span>
            </button>
            <button
              type="button"
              class="chat-side-icon-btn"
              aria-label="筛选 / 排序"
              @click.stop
            >
              <el-icon><Sort /></el-icon>
            </button>
          </div>

          <div v-if="filteredConversations.length === 0" class="chat-side-empty">
            暂无话题
          </div>

          <ul v-else class="chat-side-topic-list">
            <li
              v-for="item in filteredConversations"
              :key="item.id"
              class="chat-side-topic-item"
              :class="{ active: activeConversationId === item.id }"
            >
              <button
                type="button"
                class="chat-side-topic-main"
                @click="handleSelectConversation(item.id)"
              >
                <span class="chat-side-topic-title">{{ item.name }}</span>
              </button>
              <button
                type="button"
                class="chat-side-topic-delete"
                :aria-label="'关闭话题：' + item.name"
                @click.stop="handleDeleteHistory(item.id)"
              >
                <el-icon><Close /></el-icon>
              </button>
            </li>
          </ul>
        </div>
      </aside>
    </transition>

    <!-- 移动端侧栏抽屉 -->
    <Teleport to="body">
      <transition name="chat-drawer">
        <div
          v-if="isMobile && !historyCollapsed"
          class="chat-sidebar-drawer-overlay"
          @click.self="toggleHistorySidebar"
        >
          <aside class="chat-sidebar chat-sidebar-drawer" @click.stop>
            <!-- 复用桌面侧栏内容 -->
            <header class="chat-side-header">
              <button
                type="button"
                class="chat-side-logo"
                aria-label="折叠侧栏"
                @click="toggleHistorySidebar"
              >
                <img src="@/assets/images/brand-logo.svg" alt="logo" class="chat-side-logo-mark"/>
                <span class="chat-side-logo-name">智能问答</span>
                <el-icon class="chat-side-logo-arrow"><Fold /></el-icon>
              </button>
            </header>

            <div class="chat-side-tabs" role="tablist">
              <button
                type="button"
                role="tab"
                class="chat-side-tab"
                :class="{ active: sideTab === 'assistant' }"
                @click="sideTab = 'assistant'"
              >
                助手
              </button>
              <button
                type="button"
                role="tab"
                class="chat-side-tab"
                :class="{ active: sideTab === 'topic' }"
                @click="sideTab = 'topic'"
              >
                话题
              </button>
            </div>

            <div v-show="sideTab === 'assistant'" class="chat-side-pane chat-side-pane-assistant">
              <button type="button" class="chat-side-add" @click="openAssistantDialog">
                <el-icon class="chat-side-add-icon"><Plus /></el-icon>
                <span>添加助手</span>
              </button>
              <div class="chat-side-section">
                <div class="chat-side-section-head"><span>助手列表</span></div>
                <ul class="chat-side-list">
                  <li
                    v-for="model in difyModels"
                    :key="model.id"
                    class="chat-side-item"
                    :class="{ active: activeAssistantId === model.id }"
                    @click="selectAssistant(model.id)"
                  >
                    <span class="chat-side-item-icon"><el-icon><Avatar /></el-icon></span>
                    <span class="chat-side-item-name">{{ model.name }}</span>
                  </li>
                </ul>
              </div>
            </div>

            <div v-show="sideTab === 'topic'" class="chat-side-pane chat-side-pane-topic">
              <div class="chat-side-pane-toolbar">
                <button type="button" class="chat-side-add" @click="startNewConversation">
                  <el-icon class="chat-side-add-icon"><Plus /></el-icon>
                  <span>新建话题</span>
                </button>
              </div>
              <div v-if="filteredConversations.length === 0" class="chat-side-empty">暂无话题</div>
              <ul v-else class="chat-side-topic-list">
                <li
                  v-for="item in filteredConversations"
                  :key="item.id"
                  class="chat-side-topic-item"
                  :class="{ active: activeConversationId === item.id }"
                >
                  <button type="button" class="chat-side-topic-main" @click="handleSelectConversation(item.id)">
                    <span class="chat-side-topic-title">{{ item.name }}</span>
                  </button>
                  <button type="button" class="chat-side-topic-delete" @click.stop="handleDeleteHistory(item.id)">
                    <el-icon><Close /></el-icon>
                  </button>
                </li>
              </ul>
            </div>
          </aside>
        </div>
      </transition>
    </Teleport>

    <!-- ============== 添加助手弹窗 ============== -->
    <Teleport to="body">
      <transition name="chat-assistant-dialog-fade">
        <div
          v-if="assistantDialogVisible"
          class="chat-assistant-dialog-overlay"
          @click.self="closeAssistantDialog"
        >
          <div
            class="chat-assistant-dialog"
            role="dialog"
            aria-modal="true"
            :aria-label="isAssistantEditing ? '更新助手' : '添加助手'"
          >
            <header class="chat-assistant-dialog-header">
              <div class="chat-assistant-dialog-title">
                <h3>{{ isAssistantEditing ? '更新助手' : '创建助手' }}</h3>
              </div>
              <button
                type="button"
                class="chat-assistant-dialog-close"
                aria-label="关闭"
                @click="closeAssistantDialog"
              >
                <el-icon><Close /></el-icon>
              </button>
            </header>

            <div class="chat-assistant-dialog-body">
              <div class="chat-assistant-field">
                <label class="chat-assistant-label" for="chat-assistant-name">助手名称</label>
                <div class="chat-assistant-input-wrap">
                  <el-icon class="chat-assistant-input-icon"><Avatar /></el-icon>
                  <input
                    id="chat-assistant-name"
                    v-model="assistantForm.name"
                    type="text"
                    class="chat-assistant-input"
                    placeholder="例如：商业数据分析"
                    maxlength="32"
                    @keydown.enter.prevent="confirmAssistantDialog"
                  />
                </div>
              </div>

              <div class="chat-assistant-field">
                <label class="chat-assistant-label" for="chat-assistant-desc">提示词</label>
                <textarea
                  id="chat-assistant-desc"
                  v-model="assistantForm.description"
                  class="chat-assistant-textarea"
                  placeholder="描述助手角色与行为规范，将作为系统提示词发送给模型"
                  rows="6"
                />
              </div>

              <div class="chat-assistant-quick">
                <p class="chat-assistant-quick-title">常用助手模板</p>
                <div class="chat-assistant-quick-list">
                  <button
                    v-for="template in assistantTemplates"
                    :key="template.name"
                    type="button"
                    class="chat-assistant-quick-chip"
                    @click="applyAssistantTemplate(template)"
                  >
                    <span class="chat-assistant-quick-name">{{ template.name }}</span>
                    <span class="chat-assistant-quick-desc">{{ template.description }}</span>
                  </button>
                </div>
              </div>
            </div>

            <footer class="chat-assistant-dialog-footer">
              <button
                type="button"
                class="chat-assistant-btn chat-assistant-btn-ghost"
                @click="closeAssistantDialog"
              >
                取消
              </button>
              <button
                type="button"
                class="chat-assistant-btn chat-assistant-btn-primary"
                :disabled="!canConfirmAssistant"
                @click="confirmAssistantDialog"
              >
                {{ isAssistantEditing ? '更新' : '添加' }}
              </button>
            </footer>
          </div>
        </div>
      </transition>
    </Teleport>

    <!-- ============== 删除助手二次确认弹窗 ============== -->
    <Teleport to="body">
      <transition name="chat-assistant-dialog-fade">
        <div
          v-if="assistantDeleteConfirm"
          class="chat-assistant-dialog-overlay"
          @click.self="cancelDeleteAssistant"
        >
          <div
            class="chat-assistant-dialog chat-assistant-confirm-dialog"
            role="alertdialog"
            aria-modal="true"
            ::aria-label="isAssistantEditing ? '更新助手' : '添加助手'"
          >
            <header class="chat-assistant-dialog-header">
              <div class="chat-assistant-dialog-title">
                <span class="chat-assistant-confirm-mark">!</span>
                <h3>删除助手</h3>
              </div>
            </header>
            <div class="chat-assistant-dialog-body">
              <p class="chat-assistant-confirm-text">
                确认删除助手「<strong>{{ assistantDeleteConfirm.name }}</strong>」吗？删除后将不再出现在当前侧栏列表中，且无法撤销。
              </p>
            </div>
            <footer class="chat-assistant-dialog-footer">
              <button
                type="button"
                class="chat-assistant-btn chat-assistant-btn-ghost"
                @click="cancelDeleteAssistant"
              >
                取消
              </button>
              <button
                type="button"
                class="chat-assistant-btn chat-assistant-btn-danger"
                @click="confirmDeleteAssistant"
              >
                删除
              </button>
            </footer>
          </div>
        </div>
      </transition>
    </Teleport>

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

      <div ref="chatMessagesRef" class="chat-messages">
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
                <div>您好，我是{{ assistantGreetingName }}</div>
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
                placeholder="请输入问题，开始对话..."
                is-empty
                @submit="submitMessage"
                @stop="stopGenerating"
                @file-selected="handleFileSelected"
                @remove-file="removeAttachedFile"
                @update-input-field="updateInputField"
                @select-model="selectModel"
              />

              <div v-if="!isDirectMode && suggestedQuestions.length > 0" class="suggested-questions">
                <p class="suggested-questions-title">建议问题</p>
                <div class="suggested-questions-list">
                  <button
                    v-for="(question, index) in suggestedQuestions"
                    :key="index"
                    type="button"
                    class="suggested-question-btn"
                    @click="handleQuickAction(question)"
                  >
                    {{ question }}
                  </button>
                </div>
              </div>
            </template>
          </div>

          <div v-else key="messages" class="chat-messages-list">
            <div
              v-for="message in messages"
              :key="message.id"
              class="chat-message-item"
              :class="message.role"
            >
              <div v-if="message.role === 'assistant'" class="chat-message-avatar">
                <img src="@/assets/images/icon-chat-user-head.Cy4P-DEa.svg" alt="AI" class="chat-message-avatar-image" />
              </div>

              <div class="chat-message-content">
                <div class="chat-message-bubble" :class="{ 'has-thinking': message.thinking && !message.content }">
                  <span v-if="message.thinking && !message.content" class="thinking-text">
                    {{ message.thinking }}
                  </span>
                  <!-- 助手消息：使用 Markdown 渲染 -->
                  <template v-else-if="message.role === 'assistant'">
                    <div class="markdown-content" v-html="formatContent(message.content)"></div>
                    <span
                      v-if="loading && message.id === messages[messages.length - 1]?.id && !message.content"
                      class="typing-cursor"
                    ></span>
                  </template>
                  <!-- 用户消息：仅保留换行，XSS 安全 -->
                  <div v-else class="user-content" v-html="formatUserContent(message.content)"></div>
                  <!-- 用户消息附件文件 -->
                  <div v-if="message.requestFiles && message.requestFiles.length > 0" class="user-files">
                    <div v-for="file in message.requestFiles" :key="file.id" class="user-file-card">
                      <span class="user-file-type-icon">{{ getFileTypeEmoji(file.type) }}</span>
                      <div class="user-file-info">
                        <span class="user-file-name">{{ file.name }}</span>
                        <span class="user-file-size">{{ formatFileSize(file.size) }}</span>
                      </div>
                    </div>
                  </div>
                </div>

                <div v-if="message.role === 'assistant'" class="chat-message-actions">
                  <button
                    type="button"
                    class="chat-message-action"
                    aria-label="复制"
                    title="复制"
                    @click="copyMessage(message.content)"
                  >
                    <el-icon><DocumentCopy /></el-icon>
                  </button>
                  <button
                    type="button"
                    class="chat-message-action"
                    aria-label="导出 Markdown"
                    title="导出 Markdown"
                    @click="exportMessageMarkdown(message.id)"
                  >
                    <el-icon><Download /></el-icon>
                  </button>
                  <button
                    type="button"
                    class="chat-message-action"
                    aria-label="重新生成"
                    title="重新生成"
                    :disabled="!canRegenerateMessage(message.id)"
                    @click="handleRegenerateMessage(message.id)"
                  >
                    <el-icon><RefreshRight /></el-icon>
                  </button>
                </div>
              </div>
            </div>
          </div>
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
            placeholder="请输入问题，继续对话..."
            @submit="submitMessage"
            @stop="stopGenerating"
            @file-selected="handleFileSelected"
            @remove-file="removeAttachedFile"
            @update-input-field="updateInputField"
            @select-model="selectModel"
          />
        </div>
      </transition>
    </div>
  </section>
</template>

<style scoped lang="less">
@import "./index.less";
</style>
