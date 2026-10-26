<template>
  <section class="share-page">
    <header class="share-header">
      <img src="@/assets/images/brand-logo.svg" alt="logo" class="share-logo" />
      <div>
        <h1>{{ state.title }}</h1>
        <p class="share-meta">
          来自智汇平台的对话分享<template v-if="state.author"> · {{ state.author }}</template>
          <template v-if="state.createdAt"> · {{ state.createdAt }}</template>
        </p>
      </div>
    </header>

    <div v-if="state.loading" class="share-status">正在加载分享内容…</div>
    <div v-else-if="state.error" class="share-status is-error">{{ state.error }}</div>
    <div v-else class="share-messages">
      <div v-for="(msg, index) in state.messages" :key="index" class="share-message" :class="msg.role">
        <span class="share-role">{{ msg.role === 'user' ? '问' : '答' }}</span>
        <div
          :class="msg.role === 'assistant' ? 'markdown-content' : 'share-user-text'"
          v-html="msg.role === 'assistant' ? formatContent(msg.content) : formatUser(msg.content)"
        ></div>
      </div>
    </div>

    <footer class="share-footer">
      <a href="#/chat">前往智汇平台 →</a>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import { useRoute } from 'vue-router'
import { chatShareApi } from '@/api/chatShare'
import { renderMarkdown, renderUserContent } from '@/utils/markdown'

const route = useRoute()

const state = reactive({
  loading: true,
  error: '',
  title: '对话分享',
  author: '',
  createdAt: '',
  messages: [] as Array<{ role: string; content: string }>,
})

const formatContent = (text: string) => renderMarkdown(text || '')
// 用户消息必须走转义版本：payload 是任意登录用户提交的快照，
// 分享页又免登录打开，直接 v-html 未转义文本就是 XSS 注入点
// （此前误用 preserveNewlines——只透传不转义，主聊天页用的是 renderUserContent）
const formatUser = (text: string) => renderUserContent(text || '')

onMounted(async () => {
  const token = String(route.params.token || '')
  if (!token) {
    state.error = '分享链接无效'
    state.loading = false
    return
  }
  try {
    const { data: res } = await chatShareApi.get(token)
    if (res.resp_code !== 0 || !res.datas) {
      state.error = res.resp_msg || '分享不存在或已失效'
      return
    }
    const row = res.datas
    state.title = row.title || '对话分享'
    state.author = row.authorName || ''
    if (row.createtime) state.createdAt = String(row.createtime).slice(0, 10)
    try {
      const parsed = JSON.parse(row.payload || '{}')
      state.messages = Array.isArray(parsed.messages)
        ? parsed.messages.filter((m: { role: string; content: string }) => m && typeof m.content === 'string' && m.content.trim())
        : []
    } catch {
      state.error = '分享内容已损坏'
    }
  } catch {
    state.error = '加载失败，请稍后重试'
  } finally {
    state.loading = false
  }
})
</script>

<style scoped>
.share-page {
  max-width: 780px;
  margin: 0 auto;
  padding: 40px 20px 80px;
  min-height: 100vh;
  background: linear-gradient(160deg, #f2f6ff 0%, #eef2fb 60%, #f6f1fc 100%);
}

.share-header {
  display: flex;
  align-items: center;
  gap: 14px;
  padding-bottom: 20px;
  border-bottom: 1px solid rgba(20, 24, 74, 0.1);
}

.share-logo {
  width: 44px;
  height: 44px;
  border-radius: 10px;
}

.share-header h1 {
  font-size: 19px;
  color: #14184a;
  margin: 0 0 4px;
}

.share-meta {
  font-size: 12.5px;
  color: #6b7395;
  margin: 0;
}

.share-status {
  padding: 60px 0;
  text-align: center;
  color: #6b7395;
}

.share-status.is-error {
  color: #d64545;
}

.share-messages {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 24px 0;
}

.share-message {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.share-role {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
}

.share-message.user .share-role {
  background: rgba(61, 91, 245, 0.12);
  color: #3d5bf5;
}

.share-message.assistant .share-role {
  background: linear-gradient(135deg, #4f7bff, #9d3bea);
  color: #fff;
}

.share-message > div {
  flex: 1;
  min-width: 0;
  padding: 12px 16px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.7;
}

.share-message.user > div {
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid rgba(20, 24, 74, 0.08);
  color: #23284f;
}

.share-message.assistant > div {
  background: #ffffff;
  border: 1px solid rgba(20, 24, 74, 0.08);
  color: #2a2f55;
}

.share-footer {
  text-align: center;
  padding-top: 24px;
  border-top: 1px solid rgba(20, 24, 74, 0.1);
}

.share-footer a {
  color: #3d5bf5;
  font-size: 13.5px;
  text-decoration: none;
}
</style>
