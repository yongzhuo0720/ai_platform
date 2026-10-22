<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from "vue";
import {
  Expand,
  Fold,
  Plus,
  Close,
  Sort,
  Avatar,
  DocumentCopy,
  RefreshRight,
  ArrowDown,
  ArrowUp,
  MoreFilled,
  Edit,
  Delete,
  Lock,
} from "@element-plus/icons-vue";
import { useAgentAssistant } from "./index";
import AgentComposer from "./AgentComposer.vue";

const {
  historyCollapsed,
  inputValue,
  messages,
  chatMessagesRef,
  composerRef,
  topicsForActiveAssistant,
  activeTopicId,
  loading,
  sideTab,
  assistantGreetingName,
  assistants,
  activeAssistantId,
  assistantDialogVisible,
  assistantEditingId,
  assistantForm,
  assistantDeleteConfirm,
  canEditAssistant,
  canDeleteAssistant,
  canManagePermission,
  permissionDialogVisible,
  permissionEditingAssistant,
  permissionForm,
  deptTree,
  roleList,
  permissionTreeRef,
  permissionTreeProps,
  openPermissionDialog,
  closePermissionDialog,
  savePermission,
  handlePermissionDeptCheckChange,
  isEditingAssistant,
  isAdmin,
  showAdvancedApiUrl,
  maskToken,
  toggleAdvancedApiUrl,
  openAssistantDialog,
  openAssistantEditDialog,
  closeAssistantDialog,
  confirmAssistantDialog,
  requestDeleteAssistant,
  cancelDeleteAssistant,
  confirmDeleteAssistant,
  selectAssistant,
  assistantMenuOpenId,
  toggleAssistantMenu,
  skills,
  skillsLoading,
  skillMenuVisible,
  attachedFiles,
  isDragging,
  submitMessage,
  toggleHistorySidebar,
  handleSelectTopic,
  handleDeleteTopic,
  startNewTopic,
  copyMessage,
  canRegenerateMessage,
  handleRegenerateMessage,
  formatContent,
  formatUserContent,
  handleInputChange,
  selectSkill,
  closeSkillMenu,
  handleFileSelected,
  removeAttachedFile,
  handleDragOver,
  handleDragLeave,
  handleDrop,
} = useAgentAssistant();

/** 删除助手二次确认弹窗的可见性（el-dialog v-model 需要布尔） */
const assistantDeleteConfirmVisible = computed({
  get: () => !!assistantDeleteConfirm.value,
  set: (val: boolean) => {
    if (!val) cancelDeleteAssistant();
  },
});

/** 根据文件 MIME 类型返回对应的 emoji 图标 */
const getFileTypeEmoji = (mimeType: string): string => {
  if (mimeType.startsWith("image/")) return "🖼️";
  if (mimeType === "application/pdf") return "📕";
  if (mimeType.includes("word") || mimeType.includes("document")) return "📄";
  if (mimeType.includes("excel") || mimeType.includes("spreadsheet")) return "📊";
  if (mimeType.includes("zip") || mimeType.includes("rar") || mimeType.includes("compressed")) return "📦";
  if (mimeType.startsWith("text/")) return "📝";
  if (mimeType.startsWith("video/")) return "🎬";
  if (mimeType.startsWith("audio/")) return "🎵";
  return "📎";
};

/** 格式化文件大小 */
const formatFileSize = (bytes: number): string => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

// ================= 步骤时间线（Thinking / 工具调用 / 工具输出） =================

/** 各步骤的展开状态（流式进行中自动展开，完成后默认折叠） */
const stepExpandedMap = ref<Record<string, boolean>>({});

const isStepExpanded = (stepId: string): boolean => {
  return stepExpandedMap.value[stepId] ?? false;
};

const toggleStepExpanded = (stepId: string) => {
  stepExpandedMap.value = {
    ...stepExpandedMap.value,
    [stepId]: !(stepExpandedMap.value[stepId] ?? false),
  };
};

/** 是否最后一条助手消息 */
const isLastAssistantMessage = (messageId: string): boolean => {
  const last = messages.value[messages.value.length - 1];
  return last?.id === messageId && last.role === "assistant";
};

/** 步骤实时生成时自动展开（仿 QwenPaw：思考过程实时可见） */
watch(
  messages,
  () => {
    if (!loading.value) return;
    const last = messages.value[messages.value.length - 1];
    if (!last || last.role !== "assistant" || !last.steps) return;
    const nextMap = { ...stepExpandedMap.value };
    let changed = false;
    last.steps.forEach((step) => {
      if (step.status === "running" && !nextMap[step.id]) {
        nextMap[step.id] = true;
        changed = true;
      }
    });
    if (changed) {
      stepExpandedMap.value = nextMap;
    }
  },
  { deep: true }
);

// 回答完成后默认折叠步骤内容（用户可手动展开查看）
watch(loading, (value) => {
  if (!value) {
    stepExpandedMap.value = {};
  }
});

/** 空状态静态建议问题 */
const suggestedQuestions = [
  "介绍一下你自己",
  "你能做什么？",
  "输入 /skills 查看可用技能",
];

/** 点击建议问题：填入输入框并自动发送 */
const handleSuggestionClick = async (question: string) => {
  inputValue.value = question;
  await submitMessage();
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
  if (messages.value.length === 0 && !loading.value) {
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
            <span class="chat-side-logo-name">智能体助手</span>
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

        <!-- 助手 Tab：多智能体列表，支持添加/编辑/删除/切换 -->
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
                v-for="assistant in assistants"
                :key="assistant.id"
                class="chat-side-item"
                :class="{ active: activeAssistantId === assistant.id }"
                @click="selectAssistant(assistant.id)"
              >
                <span class="chat-side-item-icon">
                  <el-icon><Avatar /></el-icon>
                </span>
                <span class="chat-side-item-name">{{ assistant.name }}</span>
                <span
                  v-if="!assistant.builtin"
                  class="chat-side-item-type"
                >{{ assistant.type || 'agent' }}</span>
                <div class="chat-side-item-action-wrap" @click.stop>
                  <button
                    v-if="canEditAssistant(assistant) || canDeleteAssistant(assistant)"
                    type="button"
                    class="chat-side-item-action"
                    aria-label="更多操作"
                    @click.stop="toggleAssistantMenu(assistant.id)"
                  >
                    <el-icon><MoreFilled /></el-icon>
                  </button>
                  <ul
                    v-if="assistantMenuOpenId === assistant.id"
                    class="chat-side-item-menu"
                    role="menu"
                  >
                    <li v-if="canEditAssistant(assistant)">
                      <button
                        type="button"
                        class="chat-side-item-menu-item"
                        role="menuitem"
                        @click="openAssistantEditDialog(assistant)"
                      >
                        <el-icon><Edit /></el-icon>
                        <span>编辑助手</span>
                      </button>
                    </li>
                    <li v-if="canManagePermission(assistant)">
                      <button
                        type="button"
                        class="chat-side-item-menu-item"
                        role="menuitem"
                        @click="openPermissionDialog(assistant)"
                      >
                        <el-icon><Lock /></el-icon>
                        <span>权限管理</span>
                      </button>
                    </li>
                    <li v-if="canDeleteAssistant(assistant)">
                      <button
                        type="button"
                        class="chat-side-item-menu-item is-danger"
                        role="menuitem"
                        @click="requestDeleteAssistant(assistant)"
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
              @click="startNewTopic"
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

          <div v-if="topicsForActiveAssistant.length === 0" class="chat-side-empty">
            暂无话题
          </div>

          <ul v-else class="chat-side-topic-list">
            <li
              v-for="item in topicsForActiveAssistant"
              :key="item.id"
              class="chat-side-topic-item"
              :class="{ active: activeTopicId === item.id }"
            >
              <button
                type="button"
                class="chat-side-topic-main"
                @click="handleSelectTopic(item.id)"
              >
                <span class="chat-side-topic-title">{{ item.name }}</span>
              </button>
              <button
                type="button"
                class="chat-side-topic-delete"
                :aria-label="'关闭话题：' + item.name"
                @click.stop="handleDeleteTopic(item.id)"
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
                <span class="chat-side-logo-name">智能体助手</span>
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
                    v-for="assistant in assistants"
                    :key="assistant.id"
                    class="chat-side-item"
                    :class="{ active: activeAssistantId === assistant.id }"
                    @click="selectAssistant(assistant.id)"
                  >
                    <span class="chat-side-item-icon"><el-icon><Avatar /></el-icon></span>
                    <span class="chat-side-item-name">{{ assistant.name }}</span>
                  </li>
                </ul>
              </div>
            </div>

            <div v-show="sideTab === 'topic'" class="chat-side-pane chat-side-pane-topic">
              <div class="chat-side-pane-toolbar">
                <button type="button" class="chat-side-add" @click="startNewTopic">
                  <el-icon class="chat-side-add-icon"><Plus /></el-icon>
                  <span>新建话题</span>
                </button>
              </div>
              <div v-if="topicsForActiveAssistant.length === 0" class="chat-side-empty">暂无话题</div>
              <ul v-else class="chat-side-topic-list">
                <li
                  v-for="item in topicsForActiveAssistant"
                  :key="item.id"
                  class="chat-side-topic-item"
                  :class="{ active: activeTopicId === item.id }"
                >
                  <button type="button" class="chat-side-topic-main" @click="handleSelectTopic(item.id)">
                    <span class="chat-side-topic-title">{{ item.name }}</span>
                  </button>
                  <button type="button" class="chat-side-topic-delete" @click.stop="handleDeleteTopic(item.id)">
                    <el-icon><Close /></el-icon>
                  </button>
                </li>
              </ul>
            </div>
          </aside>
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
            智能体助手
          </div>
        </div>
      </div>

      <div ref="chatMessagesRef" class="chat-messages">
        <transition name="chat-stage" mode="out-in" @after-enter="onStageEnter">
          <div v-if="messages.length === 0" key="empty" class="chat-empty">
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

            <AgentComposer
              ref="composerRef"
              v-model="inputValue"
              :loading="loading"
              :skills="skills"
              :skills-loading="skillsLoading"
              :skill-menu-visible="skillMenuVisible"
              :attached-files="attachedFiles"
              placeholder="请输入问题，开始对话..."
              is-empty
              @submit="submitMessage"
              @update:model-value="handleInputChange"
              @select-skill="selectSkill"
              @close-skill-menu="closeSkillMenu"
              @file-selected="handleFileSelected"
              @remove-file="removeAttachedFile"
            />

            <div v-if="suggestedQuestions.length > 0" class="suggested-questions">
              <p class="suggested-questions-title">建议问题</p>
              <div class="suggested-questions-list">
                <button
                  v-for="(question, index) in suggestedQuestions"
                  :key="index"
                  type="button"
                  class="suggested-question-btn"
                  @click="handleSuggestionClick(question)"
                >
                  {{ question }}
                </button>
              </div>
            </div>
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
                <div
                  class="chat-message-bubble"
                  :class="{ 'has-thinking': message.thinking && !message.content }"
                >
                  <!-- 助手消息：使用 Markdown 渲染 -->
                  <template v-if="message.role === 'assistant'">
                    <!-- 步骤时间线（仿 QwenPaw Desktop：Thinking / 工具调用 / 工具输出） -->
                    <div
                      v-if="message.steps && message.steps.length > 0"
                      class="agent-steps"
                    >
                      <template v-for="step in message.steps" :key="step.id">
                        <!-- Thinking 折叠块 -->
                        <div
                          v-if="step.kind === 'thinking'"
                          class="agent-thinking-block"
                          :class="{ 'is-collapsed': !isStepExpanded(step.id) }"
                        >
                          <button
                            type="button"
                            class="agent-thinking-header"
                            @click="toggleStepExpanded(step.id)"
                          >
                            <span class="agent-thinking-title">
                              {{ step.status === "running" ? "Thinking..." : "Thinking" }}
                            </span>
                            <el-icon class="agent-thinking-arrow"><ArrowDown /></el-icon>
                          </button>
                          <div v-if="isStepExpanded(step.id)" class="agent-thinking-content">
                            {{ step.content }}
                          </div>
                        </div>
                        <!-- 工具调用步骤（执行 skill） -->
                        <div v-else-if="step.kind === 'tool'" class="agent-tool-step">
                          <span class="agent-tool-step-icon">🔧</span>
                          <span class="agent-tool-step-name">{{ step.name || "工具调用" }}</span>
                          <span v-if="step.status === 'running'" class="agent-tool-step-running">
                            运行中...
                          </span>
                          <span v-else class="agent-tool-step-done">✓</span>
                        </div>
                        <!-- 工具输出折叠块 -->
                        <div
                          v-else
                          class="agent-tool-output"
                          :class="{ 'is-collapsed': !isStepExpanded(step.id) }"
                        >
                          <button
                            type="button"
                            class="agent-thinking-header"
                            @click="toggleStepExpanded(step.id)"
                          >
                            <span class="agent-thinking-title">工具输出</span>
                            <el-icon class="agent-thinking-arrow"><ArrowDown /></el-icon>
                          </button>
                          <div v-if="isStepExpanded(step.id)" class="agent-thinking-content">
                            {{ step.content }}
                          </div>
                        </div>
                      </template>
                    </div>
                    <!-- 旧版消息兼容：thinking 字段 -->
                    <div
                      v-else-if="message.thinking"
                      class="agent-thinking-block"
                      :class="{ 'is-collapsed': !isStepExpanded(message.id) }"
                    >
                      <button
                        type="button"
                        class="agent-thinking-header"
                        @click="toggleStepExpanded(message.id)"
                      >
                        <span class="agent-thinking-title">
                          {{ loading && isLastAssistantMessage(message.id) ? "Thinking..." : "Thinking" }}
                        </span>
                        <el-icon class="agent-thinking-arrow"><ArrowDown /></el-icon>
                      </button>
                      <div v-if="isStepExpanded(message.id)" class="agent-thinking-content">
                        {{ message.thinking }}
                      </div>
                    </div>
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

      <!-- 对话状态底部输入区 -->
      <transition name="chat-dock">
        <div v-if="messages.length > 0" class="chat-composer-docked-wrapper">
          <AgentComposer
            v-model="inputValue"
            :loading="loading"
            :skills="skills"
            :skills-loading="skillsLoading"
            :skill-menu-visible="skillMenuVisible"
            :attached-files="attachedFiles"
            placeholder="请输入问题，继续对话..."
            @submit="submitMessage"
            @update:model-value="handleInputChange"
            @select-skill="selectSkill"
            @close-skill-menu="closeSkillMenu"
            @file-selected="handleFileSelected"
            @remove-file="removeAttachedFile"
          />
        </div>
      </transition>
    </div>
  </section>

  <!-- ============== 添加/编辑助手弹窗 ============== -->
  <el-dialog
    v-model="assistantDialogVisible"
    :title="assistantEditingId ? '编辑助手' : '添加助手'"
    width="620px"
    :close-on-click-modal="false"
    @closed="closeAssistantDialog"
  >
    <el-form label-width="100px" label-position="right">
      <el-form-item label="助手名称" required>
        <el-input
          v-model="assistantForm.name"
          placeholder="例如：数据分析助手"
          maxlength="32"
          clearable
        />
      </el-form-item>

      <el-form-item label="服务类型">
        <el-select
          v-model="assistantForm.type"
          :disabled="isEditingAssistant"
          placeholder="选择智能体服务类型"
          style="width: 100%"
        >
          <el-option label="Qwen Paw Agent" value="qwen_paw" />
          <el-option label="万悟联通（预留）" value="wanwu" disabled />
        </el-select>
      </el-form-item>

      <el-form-item label="智能体 ID" :required="!isEditingAssistant">
        <el-input
          v-model="assistantForm.agentId"
          :disabled="isEditingAssistant"
          placeholder="X-Agent-Id，例如 5wffxE"
          clearable
        />
      </el-form-item>

      <el-form-item label="认证 Token" :required="!isEditingAssistant">
        <el-input
          v-if="!isEditingAssistant"
          v-model="assistantForm.authToken"
          type="textarea"
          :rows="3"
          placeholder="Authorization Bearer Token"
        />
        <el-input
          v-else
          :model-value="isAdmin ? assistantForm.authToken : maskToken(assistantForm.authToken)"
          type="textarea"
          :rows="3"
          disabled
          :placeholder="isAdmin ? 'Authorization Bearer Token' : 'Token 已隐藏（仅管理员可见）'"
        />
      </el-form-item>

      <!-- 高级按钮：点击展开接口地址（添加/编辑均显示） -->
      <el-form-item label=" " class="assistant-advanced-toggle">
        <el-button link type="primary" @click="toggleAdvancedApiUrl">
          {{ showAdvancedApiUrl ? '收起高级设置' : '高级' }}
          <el-icon class="assistant-advanced-toggle-icon">
            <ArrowDown v-if="!showAdvancedApiUrl" />
            <ArrowUp v-else />
          </el-icon>
        </el-button>
      </el-form-item>

      <!-- 接口地址：默认隐藏，点击「高级」后展开；编辑态下只读 -->
      <el-form-item v-if="showAdvancedApiUrl" label="接口地址">
        <el-input
          v-model="assistantForm.apiUrl"
          :disabled="isEditingAssistant"
          placeholder="例如 http://<模型服务地址>（仅记录展示，请求仍走 /qwen 代理）"
          clearable
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="closeAssistantDialog">取消</el-button>
      <el-button type="primary" @click="confirmAssistantDialog">
        {{ assistantEditingId ? '更新' : '添加' }}
      </el-button>
    </template>
  </el-dialog>

  <!-- ============== 删除助手二次确认 ============== -->
  <el-dialog
    v-model="assistantDeleteConfirmVisible"
    title="删除助手"
    width="420px"
    :close-on-click-modal="false"
  >
    <p>
      确认删除助手「<strong>{{ assistantDeleteConfirm?.name }}</strong>」吗？删除后将从助手列表中移除，且无法撤销。
    </p>
    <template #footer>
      <el-button @click="cancelDeleteAssistant">取消</el-button>
      <el-button type="danger" @click="confirmDeleteAssistant">删除</el-button>
    </template>
  </el-dialog>

  <!-- ============== 权限管理弹窗 ============== -->
  <el-dialog
    v-model="permissionDialogVisible"
    :title="`权限管理：${permissionEditingAssistant?.name || ''}`"
    width="700px"
    :close-on-click-modal="false"
    @closed="closePermissionDialog"
  >
    <el-form label-width="90px">
      <el-form-item label="权限类型">
        <el-radio-group v-model="permissionForm.permissionType">
          <el-radio value="public">所有人可见</el-radio>
          <el-radio value="partial_invisible">部分不可见</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>

    <div v-if="permissionForm.permissionType === 'partial_invisible'" class="mng-dialog-content">
      <div class="mng-section">
        <div class="mng-section-title">
          <span class="title-text">部门权限</span>
          <el-tag type="warning" size="small">选中的部门将看不到该智能体</el-tag>
        </div>
        <div class="mng-tree-container">
          <el-tree
            ref="permissionTreeRef"
            :data="deptTree"
            :props="permissionTreeProps"
            node-key="unitsno"
            :default-checked-keys="permissionForm.selectedDepts"
            @check-change="handlePermissionDeptCheckChange"
            show-checkbox
            default-expand-all
          />
        </div>
      </div>
      <div class="mng-section">
        <div class="mng-section-title">
          <span class="title-text">角色权限</span>
          <el-tag type="warning" size="small">选中的角色将看不到该智能体</el-tag>
        </div>
        <div class="mng-role-container">
          <el-checkbox-group v-model="permissionForm.selectedRoles">
            <el-checkbox
              v-for="role in roleList"
              :key="role.iid"
              :label="role.iid"
            >
              {{ role.name }}
            </el-checkbox>
          </el-checkbox-group>
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="closePermissionDialog">取消</el-button>
      <el-button type="primary" @click="savePermission">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="less">
@import "../ChatView/index.less";

/* ================= 助手列表条目服务类型标签 ================= */
.chat-side-item-type {
  margin-left: auto;
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 10px;
  line-height: 1.4;
  color: var(--qwen-text-tertiary);
  background: var(--qwen-surface);
  border: 1px solid var(--qwen-border);
  white-space: nowrap;
}

/* ================= 步骤时间线（仿 QwenPaw Desktop） ================= */

.agent-steps {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 8px;
}

/* ================= Thinking 折叠块 ================= */

.agent-thinking-block,
.agent-tool-output {
  border-radius: 10px;
  background: var(--qwen-surface);
  border: 1px solid var(--qwen-border);
  overflow: hidden;
  transition: background 150ms ease;

  &:hover {
    background: var(--qwen-surface-hover, rgba(26, 74, 140, 0.04));
  }
}

/* 旧版消息（无步骤时间线）的 Thinking 块直接位于气泡内，与正文保持间距 */
.chat-message-bubble > .agent-thinking-block {
  margin-bottom: 8px;
}

/* ================= 工具调用步骤行 ================= */

.agent-tool-step {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 10px;
  background: rgba(26, 74, 140, 0.04);
  border: 1px solid rgba(26, 74, 140, 0.1);
  font-size: 12px;
  color: var(--qwen-text-secondary);
}

.agent-tool-step-icon {
  font-size: 13px;
  line-height: 1;
}

.agent-tool-step-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
  color: var(--qwen-brand-blue);
}

.agent-tool-step-running {
  margin-left: auto;
  font-size: 11px;
  color: var(--qwen-text-tertiary);
}

.agent-tool-step-done {
  margin-left: auto;
  font-size: 12px;
  color: var(--qwen-success, #16a34a);
}

.agent-thinking-header {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px;
  border: none;
  background: transparent;
  color: var(--qwen-text-secondary);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  text-align: left;
  transition: color 150ms ease;

  &:hover {
    color: var(--qwen-brand-blue);
  }
}

.agent-thinking-title {
  letter-spacing: 0.01em;
}

.agent-thinking-arrow {
  font-size: 12px;
  color: var(--qwen-text-tertiary);
  transition: transform 150ms ease;
}

.agent-thinking-block.is-collapsed .agent-thinking-arrow {
  transform: rotate(-90deg);
}

.agent-thinking-content {
  padding: 0 10px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--qwen-text-tertiary);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 320px;
  overflow-y: auto;
}

/* ================= 权限管理弹窗样式 ================= */
.mng-dialog-content {
  padding: 10px 0;
}

.mng-section {
  margin-bottom: 24px;
}

.mng-section:last-child {
  margin-bottom: 0;
}

.mng-section-title {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border-color);
}

.mng-section-title .title-text {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.mng-tree-container {
  max-height: 280px;
  overflow-y: auto;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  padding: 15px;
  background: var(--bg-secondary);
}

.mng-role-container {
  max-height: 200px;
  overflow-y: auto;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  padding: 15px;
  background: var(--bg-secondary);
}

.mng-role-container .el-checkbox-group {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.mng-role-container .el-checkbox {
  margin-right: 0;
  margin-bottom: 8px;
}

/* 浅色模式下 el-tree / el-checkbox 文字颜色适配 */
html[data-theme="light"] {
  .mng-tree-container {
    .el-tree {
      color: var(--text-primary);
      background: transparent;
    }

    .el-tree-node__content {
      &:hover {
        background: rgba(13, 48, 100, 0.06);
      }
    }

    .el-checkbox__label {
      color: var(--text-primary);
    }
  }

  .mng-role-container {
    .el-checkbox__label {
      color: var(--text-primary);
    }
  }
}
</style>
