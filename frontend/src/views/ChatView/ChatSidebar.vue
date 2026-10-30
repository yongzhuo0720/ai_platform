<script setup lang="ts">
import { ref } from "vue";
import { ElMessageBox, ElMessage } from "element-plus";
import {
  Fold,
  Plus,
  Search,
  MoreFilled,
  Edit,
  Delete,
  Close,
  Share,
  Top,
  Loading,
} from "@element-plus/icons-vue";
import { chatApi } from "@/api/chat";
import { chatShareApi } from "@/api/chatShare";
import { getCurrentUserId } from "@/utils/user";
import {
  historyCollapsed,
  sideTab,
  directConversations,
  activeConversationId,
  conversationsHasMore,
  conversationsLoadingMore,
  isDirectMode,
} from "./chatState";
import {
  conversationSearch,
  filteredConversations,
  assistantSearch,
  assistantGroups,
  assistantIntro,
  topicGroups,
  topicPreview,
  formatTopicTime,
} from "./useChatSelectors";
import {
  activeAssistantId,
  canEditAssistant,
  canDeleteAssistant,
  assistantMenuOpenId,
  toggleAssistantMenu,
  openAssistantDialog,
  openAssistantEditDialog,
  requestDeleteAssistant,
} from "./chatAssistantState";
import {
  toggleHistorySidebar,
  togglePinConversation,
  renameConversationById,
  startNewConversation,
  selectAssistant,
  handleSelectConversation,
  handleDeleteHistory,
} from "./useChatConversations";
import { loadMoreConversations } from "./useChatHistory";

defineProps<{ isMobile: boolean }>();

// 会话搜索框的显隐
const showTopicSearch = ref(false);
// 助手搜索框的显隐
const showAssistantSearch = ref(false);

/** 分享会话：把消息快照存到后端，返回可复制的只读链接 */
const handleShareConversation = async (item: { id: string; name: string }) => {
  try {
    const conv = directConversations.value.find((c) => c.id === item.id)
    let source: Array<{ role: string; content: string }> = conv ? conv.messages : []
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
        ElMessage.error('获取该会话内容失败，请重试')
        return
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
</script>

<template>
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

        <div class="chat-side-topic-scroll">
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

          <div v-if="!isDirectMode && conversationsHasMore" class="chat-side-load-more">
            <button
              type="button"
              :disabled="conversationsLoadingMore"
              @click="loadMoreConversations"
            >
              <el-icon v-if="conversationsLoadingMore" class="is-loading"><Loading /></el-icon>
              <span>{{ conversationsLoadingMore ? '加载中…' : '加载更多会话' }}</span>
            </button>
          </div>
        </div>
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
            <div class="chat-side-topic-scroll">
              <template v-for="group in topicGroups" :key="'drawer-' + group.key">
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
              <div v-if="!isDirectMode && conversationsHasMore" class="chat-side-load-more">
                <button
                  type="button"
                  :disabled="conversationsLoadingMore"
                  @click="loadMoreConversations"
                >
                  <el-icon v-if="conversationsLoadingMore" class="is-loading"><Loading /></el-icon>
                  <span>{{ conversationsLoadingMore ? '加载中…' : '加载更多会话' }}</span>
                </button>
              </div>
            </div>
          </div>
        </aside>
      </div>
    </transition>
  </Teleport>
</template>
