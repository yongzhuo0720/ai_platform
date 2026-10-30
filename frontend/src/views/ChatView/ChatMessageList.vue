<script setup lang="ts">
import { reactive } from "vue";
import {
  Loading,
  Edit,
  DocumentCopy,
  Download,
  RefreshRight,
  CaretTop,
  CaretBottom,
} from "@element-plus/icons-vue";
import { chatApi } from "@/api/chat";
import { getCurrentUserId } from "@/utils/user";
import {
  messages,
  loading,
  isDirectMode,
  messagesHasMore,
  messagesLoadingMore,
} from "./chatState";
import { loadEarlierMessages } from "./useChatHistory";
import { startEditUserMessage } from "./useChatConversations";
import { copyMessage, exportMessageMarkdown } from "./useChatFiles";
import { canRegenerateMessage, handleRegenerateMessage } from "./useChatSend";
import {
  formatContent,
  formatUserContent,
  getFileTypeEmoji,
  formatFileSize,
} from "./chatFormat";

// 思考过程折叠状态：未点击过时跟随默认——没有正文的（流式思考中）自动展开，有正文后收起
const thinkingExpanded = reactive<Record<string, boolean>>({});
const toggleThinking = (id: string) => {
  thinkingExpanded[id] = !thinkingExpanded[id];
};
const isThinkingOpen = (message: { id: string; content?: string }) =>
  thinkingExpanded[message.id] ?? !message.content?.trim();

// RAG 引用来源折叠状态：默认收起
const kbSourcesOpen = reactive<Record<string, boolean>>({});

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
</script>

<template>
  <div class="chat-messages-list">
    <div v-if="!isDirectMode && messagesHasMore" class="chat-load-older">
      <button
        type="button"
        :disabled="messagesLoadingMore"
        @click="loadEarlierMessages"
      >
        <el-icon v-if="messagesLoadingMore" class="is-loading"><Loading /></el-icon>
        <span>{{ messagesLoadingMore ? '加载中…' : '加载更早消息' }}</span>
      </button>
    </div>
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
</template>
