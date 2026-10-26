<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from "vue";
import { ElMessageBox, ElMessage } from "element-plus";
import { chatApi } from "@/api/chat";
import { chatShareApi } from "@/api/chatShare";
import { getCurrentUserId } from "@/utils/user";
import {
  Expand,
  Fold,
  Delete,
  Loading,
  Plus,
  Close,
  Search,
  MoreFilled,
  Avatar,
  DocumentCopy,
  RefreshRight,
  Edit,
  Top,
  CaretTop,
  CaretBottom,
  Share,
} from "@element-plus/icons-vue";
import { useChat } from "./index";
import ChatComposer from "./ChatComposer.vue";

// 思考过程折叠状态：未点击过时跟随默认——没有正文的（流式思考中）自动展开，有正文后收起
const thinkingExpanded = reactive<Record<string, boolean>>({});
const toggleThinking = (id: string) => {
  thinkingExpanded[id] = !thinkingExpanded[id];
};
const isThinkingOpen = (message: { id: string; content?: string }) =>
  thinkingExpanded[message.id] ?? !message.content?.trim();

// 会话搜索框的显隐
const showTopicSearch = ref(false);
// 助手搜索框的显隐
const showAssistantSearch = ref(false);

// 消息反馈（Dify 模式）：messageId -> like/dislike；会话历史不落反馈态
const messageFeedback = reactive<Record<string, 'like' | 'dislike'>>({});

const rateMessage = (messageId: string, rating: 'like' | 'dislike') => {
  const next = messageFeedback[messageId] === rating ? null : rating
  if (next) {
    messageFeedback[messageId] = next
    chatApi.submitFeedback(messageId, { rating: next, user: getCurrentUserId() })
      .catch((e) => console.warn('[反馈] 提交失败:', e))
  } else {
    delete messageFeedback[messageId]
  }
}

/** 分享会话：把消息快照存到后端，返回可复制的只读链接 */
const handleShareConversation = async (item: { id: string; name: string }) => {
  try {
    const conv = directConversations.value.find((c) => c.id === item.id)
    let source: Array<{ role: string; content: string }> = conv ? conv.messages : messages.value
    // Dify 会话：当前页可能没加载它的消息，分享时按需拉取历史
    if (!conv) {
      try {
        const resp = await chatApi.getConversationHistory(item.id, getCurrentUserId(), undefined, 100)
        const history: Array<{ role: string; content: string }> = []
        ;(resp.data || []).forEach((msg) => {
          if (msg.query) history.push({ role: 'user', content: msg.query })
          if (msg.answer) history.push({ role: 'assistant', content: msg.answer })
        })
        source = history
      } catch (e) {
        console.warn('[分享] 拉取会话历史失败:', e)
      }
    }
    const shareMessages = source
      .filter((m) => (m.content || '').trim())
      .map((m) => ({ role: m.role, content: m.content }))
    if (!shareMessages.length) {
      ElMessage.warning('会话还没有内容，无法分享')
      return
    }
    const { data: res } = await chatShareApi.create({
      title: item.name,
      payload: JSON.stringify({ messages: shareMessages }),
      authorName: decodeURIComponent((document.cookie.match(/uname=([^;]+)/) || [])[1] || ''),
    })
    if (res.resp_code === 0) {
      const url = location.origin + location.pathname + '#/share/' + res.datas
      try {
        await navigator.clipboard.writeText(url)
        ElMessage.success('分享链接已复制')
      } catch {
        ElMessageBox.alert(url, '分享链接', { confirmButtonText: '关闭' })
      }
    } else {
      ElMessage.error(res.resp_msg || '分享失败')
    }
  } catch (e) {
    console.error('分享失败:', e)
    ElMessage.error('分享失败，请稍后重试')
  }
}

// RAG 引用来源折叠状态：默认收起
const kbSourcesOpen = reactive<Record<string, boolean>>({});

/** 会话重命名：弹窗输入新名称 */
const handleRenameConversation = (item: { id: string; name: string }) => {
  ElMessageBox.prompt("输入新的会话名称", "重命名会话", {
    confirmButtonText: "确定",
    cancelButtonText: "取消",
    inputValue: item.name,
    inputPattern: /\S+/,
    inputErrorMessage: "名称不能为空",
  })
    .then(({ value }) => {
      if (value) return renameConversationById(item.id, value);
    })
    .catch(() => {});
};

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
  conversationSearch,
  togglePinConversation,
  renameConversationById,
  startEditUserMessage,
  directConversations,
  assistantSearch,
  assistantGroups,
  assistantIntro,
  topicGroups,
  topicPreview,
  formatTopicTime,
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
  directKbName,
  directKbOptions,
} = useChat();

/** 直连模式的知识库选择器需要模型已就绪才显示（模型列表里才有直连项） */
const chatKbOptions = computed(() => (isDirectMode.value ? directKbOptions.value : []));

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

        <!-- Tab 切换：助手 / 会话 -->
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
            会话
          </button>
        </div>

        <!-- 助手 Tab -->
        <div v-show="sideTab === 'assistant'" class="chat-side-pane chat-side-pane-assistant">
          <div class="chat-side-pane-toolbar">
            <button
              type="button"
              class="chat-side-add"
              @click="openAssistantDialog"
            >
              <el-icon class="chat-side-add-icon"><Plus /></el-icon>
              <span>新建助手</span>
            </button>
            <button
              type="button"
              class="chat-side-icon-btn"
              :aria-label="showAssistantSearch ? '收起搜索' : '搜索助手'"
              @click.stop="showAssistantSearch = !showAssistantSearch"
            >
              <el-icon><Search /></el-icon>
            </button>
          </div>

          <div v-if="showAssistantSearch" class="chat-side-topic-search">
            <input
              v-model="assistantSearch"
              type="text"
              placeholder="搜索助手（名称 / 提示词）…"
              aria-label="搜索助手"
            />
          </div>

          <div
            v-if="assistantGroups.length === 0"
            class="chat-side-empty"
          >
            {{ assistantSearch.trim() ? '没有匹配的助手' : '暂无助手，点击上方「添加助手」创建' }}
          </div>

          <template v-for="group in assistantGroups" :key="group.key">
            <div class="chat-side-group-head">
              <span>{{ group.label }}</span>
              <span class="chat-side-group-count">{{ group.items.length }}</span>
            </div>
            <ul class="chat-side-list">
              <li
                v-for="model in group.items"
                :key="model.id"
                class="chat-side-assistant"
                :class="{ active: activeAssistantId === model.id }"
                @click="selectAssistant(model.id)"
              >
                <span
                  class="chat-side-assistant-avatar"
                  :class="{ 'is-builtin': model.builtin }"
                  aria-hidden="true"
                >{{ model.name.charAt(0) }}</span>
                <span class="chat-side-assistant-body">
                  <span class="chat-side-assistant-name">
                    {{ model.name }}
                    <em v-if="model.builtin" class="chat-side-assistant-tag">内置</em>
                  </span>
                  <span class="chat-side-assistant-intro">{{ assistantIntro(model) }}</span>
                </span>
                <div v-if="!model.builtin" class="chat-side-item-action-wrap" @click.stop>
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
          </template>
        </div>

        <!-- 会话 Tab -->
        <div v-show="sideTab === 'topic'" class="chat-side-pane chat-side-pane-topic">
          <div class="chat-side-pane-toolbar">
            <button
              type="button"
              class="chat-side-add"
              @click="startNewConversation"
            >
              <el-icon class="chat-side-add-icon"><Plus /></el-icon>
              <span>新建会话</span>
            </button>
            <button
              type="button"
              class="chat-side-icon-btn"
              :aria-label="showTopicSearch ? '收起搜索' : '搜索会话'"
              @click.stop="showTopicSearch = !showTopicSearch"
            >
              <el-icon><Search /></el-icon>
            </button>
          </div>

          <div v-if="showTopicSearch" class="chat-side-topic-search">
            <input
              v-model="conversationSearch"
              type="text"
              placeholder="搜索会话…"
              aria-label="搜索会话"
            />
          </div>

          <div v-if="filteredConversations.length === 0" class="chat-side-empty">
            {{ conversationSearch.trim() ? '没有匹配的会话' : '暂无会话' }}
          </div>

          <template v-for="group in topicGroups" :key="group.key">
            <div class="chat-side-group-head">
              <span>{{ group.label }}</span>
              <span class="chat-side-group-count">{{ group.items.length }}</span>
            </div>
            <ul class="chat-side-topic-list">
              <li
                v-for="item in group.items"
                :key="item.id"
                class="chat-side-topic-item"
                :class="{ active: activeConversationId === item.id }"
              >
                <button
                  type="button"
                  class="chat-side-topic-main"
                  @click="handleSelectConversation(item.id)"
                >
                  <span class="chat-side-topic-title">
                    <span v-if="item.pinned" class="chat-side-topic-pin" title="已置顶">📌</span>
                    {{ item.name }}
                  </span>
                  <span v-if="topicPreview(item.id)" class="chat-side-topic-preview">
                    {{ topicPreview(item.id) }}
                  </span>
                  <span class="chat-side-topic-time">{{ formatTopicTime(item.updated_at) }}</span>
                </button>
                <span class="chat-side-topic-actions">
                  <button
                    type="button"
                    class="chat-side-topic-action"
                    :aria-label="'分享会话：' + item.name"
                    title="分享"
                    @click.stop="handleShareConversation(item)"
                  >
                    <el-icon><Share /></el-icon>
                  </button>
                <button
                    type="button"
                    class="chat-side-topic-action"
                    :aria-label="(item.pinned ? '取消置顶：' : '置顶：') + item.name"
                    :title="item.pinned ? '取消置顶' : '置顶'"
                    @click.stop="togglePinConversation(item.id)"
                  >
                    <el-icon><Top /></el-icon>
                  </button>
                  <button
                    type="button"
                    class="chat-side-topic-action"
                    :aria-label="'重命名会话：' + item.name"
                    title="重命名"
                    @click.stop="handleRenameConversation(item)"
                  >
                    <el-icon><Edit /></el-icon>
                  </button>
                  <button
                    type="button"
                    class="chat-side-topic-delete"
                    :aria-label="'删除会话：' + item.name"
                    @click.stop="handleDeleteHistory(item.id)"
                  >
                    <el-icon><Close /></el-icon>
                  </button>
                </span>
              </li>
            </ul>
          </template>
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
                会话
              </button>
            </div>

            <div v-show="sideTab === 'assistant'" class="chat-side-pane chat-side-pane-assistant">
              <button type="button" class="chat-side-add" @click="openAssistantDialog">
                <el-icon class="chat-side-add-icon"><Plus /></el-icon>
                <span>新建助手</span>
              </button>
              <template v-for="group in assistantGroups" :key="'drawer-' + group.key">
                <div class="chat-side-group-head">
                  <span>{{ group.label }}</span>
                  <span class="chat-side-group-count">{{ group.items.length }}</span>
                </div>
                <ul class="chat-side-list">
                  <li
                    v-for="model in group.items"
                    :key="model.id"
                    class="chat-side-assistant"
                    :class="{ active: activeAssistantId === model.id }"
                    @click="selectAssistant(model.id)"
                  >
                    <span class="chat-side-assistant-avatar" :class="{ 'is-builtin': model.builtin }" aria-hidden="true">{{ model.name.charAt(0) }}</span>
                    <span class="chat-side-assistant-body">
                      <span class="chat-side-assistant-name">
                        {{ model.name }}
                        <em v-if="model.builtin" class="chat-side-assistant-tag">内置</em>
                      </span>
                      <span class="chat-side-assistant-intro">{{ assistantIntro(model) }}</span>
                    </span>
                  </li>
                </ul>
              </template>
            </div>

            <div v-show="sideTab === 'topic'" class="chat-side-pane chat-side-pane-topic">
              <div class="chat-side-pane-toolbar">
                <button type="button" class="chat-side-add" @click="startNewConversation">
                  <el-icon class="chat-side-add-icon"><Plus /></el-icon>
                  <span>新建会话</span>
                </button>
              </div>
              <div v-if="filteredConversations.length === 0" class="chat-side-empty">暂无会话</div>
              <template v-else v-for="group in topicGroups" :key="'drawer-' + group.key">
                <div class="chat-side-group-head">
                  <span>{{ group.label }}</span>
                  <span class="chat-side-group-count">{{ group.items.length }}</span>
                </div>
                <ul class="chat-side-topic-list">
                  <li
                    v-for="item in group.items"
                    :key="item.id"
                    class="chat-side-topic-item"
                    :class="{ active: activeConversationId === item.id }"
                  >
                    <button type="button" class="chat-side-topic-main" @click="handleSelectConversation(item.id)">
                      <span class="chat-side-topic-title">
                        <span v-if="item.pinned" class="chat-side-topic-pin">📌</span>
                        {{ item.name }}
                      </span>
                      <span class="chat-side-topic-time">{{ formatTopicTime(item.updated_at) }}</span>
                    </button>
                    <button type="button" class="chat-side-topic-delete" @click.stop="handleDeleteHistory(item.id)">
                      <el-icon><Close /></el-icon>
                    </button>
                  </li>
                </ul>
              </template>
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
            :aria-label="isAssistantEditing ? '更新助手' : '新建助手'"
          >
            <header class="chat-assistant-dialog-header">
              <div class="chat-assistant-dialog-title">
                <h3>{{ isAssistantEditing ? '更新助手' : '新建助手' }}</h3>
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
            ::aria-label="isAssistantEditing ? '更新助手' : '新建助手'"
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
                  <!-- 思考过程：流式期间自动展开，完成后折叠可回看 -->
                  <div
                    v-if="message.thinking"
                    class="thinking-block"
                    :class="{ 'is-open': isThinkingOpen(message) }"
                  >
                    <button
                      type="button"
                      class="thinking-toggle"
                      @click="toggleThinking(message.id)"
                    >
                      <span class="thinking-caret">▸</span>
                      <span>{{ message.content?.trim() ? '已深度思考' : '思考中…' }}</span>
                    </button>
                    <div v-show="isThinkingOpen(message)" class="thinking-body">{{ message.thinking }}</div>
                  </div>
                  <!-- 助手消息：使用 Markdown 渲染 -->
                  <template v-if="message.role === 'assistant'">
                    <div
                      v-show="message.content?.trim()"
                      class="markdown-content"
                      v-html="formatContent(message.content)"
                    ></div>
                    <!-- 服务端 RAG 命中来源 -->
                    <div
                      v-if="message.kbSources?.length"
                      class="kb-sources-block"
                      :class="{ 'is-open': kbSourcesOpen[message.id] }"
                    >
                      <button
                        type="button"
                        class="kb-sources-toggle"
                        @click="kbSourcesOpen[message.id] = !kbSourcesOpen[message.id]"
                      >
                        <span class="thinking-caret">▸</span>
                        <span>引用来源 · {{ message.kbSources.length }} 个片段</span>
                      </button>
                      <ul v-show="kbSourcesOpen[message.id]" class="kb-sources-list">
                        <li
                          v-for="(src, srcIndex) in message.kbSources"
                          :key="srcIndex"
                          class="kb-source-item"
                        >
                          <div class="kb-source-head">
                            <span class="kb-source-doc">{{ src.docName || '未命名文档' }} · 第 {{ src.chunkIndex + 1 }} 段</span>
                            <span class="kb-source-score">相关度 {{ (src.score * 100).toFixed(0) }}%</span>
                          </div>
                          <p class="kb-source-content">{{ src.content }}</p>
                        </li>
                      </ul>
                    </div>
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

                <div v-if="message.role === 'user'" class="chat-message-actions">
                  <button
                    type="button"
                    class="chat-message-action"
                    aria-label="编辑并重发"
                    title="编辑并重发"
                    :disabled="loading"
                    @click="startEditUserMessage(message.id)"
                  >
                    <el-icon><Edit /></el-icon>
                  </button>
                  <button
                    type="button"
                    class="chat-message-action"
                    aria-label="复制"
                    title="复制"
                    @click="copyMessage(message.content)"
                  >
                    <el-icon><DocumentCopy /></el-icon>
                  </button>
                </div>

                <div v-if="message.role === 'assistant'" class="chat-message-actions">
                  <template v-if="!isDirectMode && message.id">
                    <button
                      type="button"
                      class="chat-message-action"
                      :class="{ 'is-active': messageFeedback[message.id] === 'like' }"
                      aria-label="赞同"
                      title="赞同"
                      @click="rateMessage(message.id, 'like')"
                    >
                      <el-icon><CaretTop /></el-icon>
                    </button>
                    <button
                      type="button"
                      class="chat-message-action"
                      :class="{ 'is-active': messageFeedback[message.id] === 'dislike' }"
                      aria-label="不满意"
                      title="不满意"
                      @click="rateMessage(message.id, 'dislike')"
                    >
                      <el-icon><CaretBottom /></el-icon>
                    </button>
                  </template>
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

<style scoped lang="less">
@import "./index.less";
</style>
