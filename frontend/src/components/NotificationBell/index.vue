<script setup lang="ts">
import {
  Bell,
  CircleCheckFilled,
  CircleCloseFilled,
  InfoFilled,
  WarningFilled,
} from '@element-plus/icons-vue'
import type { Component } from 'vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { useNotificationStore } from '@/stores/notification'
import {
  formatNotificationAbsolute,
  formatNotificationTime,
  normalizeNotificationLink,
} from '@/utils/notification'
import type { NotificationItem } from '@/api/notification'

withDefaults(defineProps<{ collapsed?: boolean }>(), { collapsed: false })

const store = useNotificationStore()
const router = useRouter()

const rootRef = ref<HTMLElement | null>(null)
const triggerRef = ref<HTMLButtonElement | null>(null)
const panelRef = ref<HTMLElement | null>(null)
const now = ref(Date.now())

const LEVEL_META: Record<string, { icon: Component; tone: string }> = {
  success: { icon: CircleCheckFilled, tone: 'success' },
  warning: { icon: WarningFilled, tone: 'warning' },
  error: { icon: CircleCloseFilled, tone: 'error' },
  info: { icon: InfoFilled, tone: 'info' },
}

const levelMeta = (level: string | null | undefined) => LEVEL_META[String(level)] ?? LEVEL_META.info
const badgeText = computed(() => (store.unreadCount > 99 ? '99+' : String(store.unreadCount)))
const triggerLabel = computed(() =>
  store.unreadCount > 0 ? `通知，${store.unreadCount} 条未读` : '通知，没有未读消息',
)
const timeOf = (item: NotificationItem) => formatNotificationTime(item.createtime, now.value)

async function toggle() {
  if (store.panelOpen) {
    store.closePanel()
    return
  }
  await store.openPanel()
  await nextTick()
  panelRef.value?.focus()
}

async function close(restoreFocus = false) {
  if (!store.panelOpen) return
  store.closePanel()
  if (restoreFocus) {
    await nextTick()
    triggerRef.value?.focus()
  }
}

function onDocumentClick(event: MouseEvent) {
  if (!store.panelOpen) return
  if (rootRef.value?.contains(event.target as Node)) return
  store.closePanel()
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && store.panelOpen) void close(true)
}

async function onItemClick(item: NotificationItem) {
  if (item.readStatus !== 'read') void store.markRead(item.iid)
  const link = normalizeNotificationLink(item.link)
  if (link) {
    store.closePanel()
    await router.push(link)
  }
}

function onMarkAllRead() {
  void store.markAllRead()
}

function onRetry() {
  void store.fetchList()
}

let clockTimer: number | null = null

onMounted(() => {
  store.startPolling()
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onKeydown)
  // 相对时间每分钟重算一次，否则「刚刚」会一直停在首次渲染的时刻
  clockTimer = window.setInterval(() => {
    now.value = Date.now()
  }, 60_000)
})

onBeforeUnmount(() => {
  store.stopPolling()
  store.closePanel()
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
  if (clockTimer !== null) {
    window.clearInterval(clockTimer)
    clockTimer = null
  }
})
</script>

<template>
  <div ref="rootRef" class="notification-root">
    <button
      ref="triggerRef"
      type="button"
      class="notification-trigger"
      :class="{ 'is-collapsed': collapsed }"
      :aria-label="triggerLabel"
      :aria-expanded="store.panelOpen"
      aria-haspopup="dialog"
      @click="toggle"
    >
      <span class="notification-bell">
        <el-icon><Bell /></el-icon>
        <span v-if="store.unreadCount > 0" class="notification-badge">{{ badgeText }}</span>
      </span>
      <span v-show="!collapsed" class="notification-trigger-text">通知</span>
      <span
        v-show="!collapsed && store.unreadCount > 0"
        class="notification-trigger-count"
      >{{ badgeText }}</span>
    </button>

    <div
      v-if="store.panelOpen"
      ref="panelRef"
      class="notification-panel"
      role="dialog"
      aria-label="通知列表"
      tabindex="-1"
    >
      <header class="notification-head">
        <div class="notification-head-title">
          <strong>通知</strong>
          <span v-if="store.unreadCount > 0">{{ store.unreadCount }} 条未读</span>
          <span v-else>全部已读</span>
        </div>
        <button
          type="button"
          class="notification-mark-all"
          :disabled="!store.hasUnread"
          @click="onMarkAllRead"
        >
          全部已读
        </button>
      </header>

      <div
        v-if="store.listLoading && !store.items.length"
        class="notification-loading"
        aria-busy="true"
        aria-label="正在加载通知"
      >
        <span v-for="row in 3" :key="row" class="notification-skeleton" />
      </div>

      <div
        v-else-if="store.listError && !store.items.length"
        class="notification-error"
        role="alert"
      >
        <p>{{ store.listError }}</p>
        <button type="button" class="notification-retry" @click="onRetry">重新加载</button>
      </div>

      <p v-else-if="!store.items.length" class="notification-empty" role="status">
        暂无通知，后台任务完成后会在这里提醒你
      </p>

      <ul v-else class="notification-list">
        <li v-for="item in store.items" :key="item.iid">
          <button
            type="button"
            class="notification-item"
            :class="{ 'is-unread': item.readStatus !== 'read' }"
            @click="onItemClick(item)"
          >
            <span
              class="notification-icon"
              :class="`is-${levelMeta(item.level).tone}`"
              aria-hidden="true"
            >
              <el-icon><component :is="levelMeta(item.level).icon" /></el-icon>
            </span>
            <span class="notification-body">
              <span class="notification-title">
                {{ item.title }}
                <span v-if="item.readStatus !== 'read'" class="notification-sr">（未读）</span>
              </span>
              <span v-if="item.content" class="notification-content">{{ item.content }}</span>
              <span class="notification-time" :title="formatNotificationAbsolute(item.createtime)">
                {{ timeOf(item) }}
              </span>
            </span>
            <span
              v-if="item.readStatus !== 'read'"
              class="notification-dot"
              aria-hidden="true"
            />
          </button>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.notification-root {
  position: relative;
  width: 100%;
}

.notification-trigger {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 38px;
  padding: 0 12px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--layout-menu-text);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition:
    background var(--transition-fast),
    color var(--transition-fast);
}

.notification-trigger:hover {
  background: var(--layout-menu-hover-bg);
  color: var(--layout-menu-text-hover);
}

.notification-trigger:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

.notification-trigger.is-collapsed {
  justify-content: center;
  padding: 0;
}

.notification-bell {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  line-height: 1;
}

.notification-badge {
  position: absolute;
  top: -8px;
  left: 8px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--danger);
  color: var(--text-on-accent);
  font-size: 10px;
  font-weight: 600;
  line-height: 16px;
  text-align: center;
  white-space: nowrap;
}

.notification-trigger-text {
  flex: 1;
  text-align: left;
}

.notification-trigger-count {
  min-width: 20px;
  height: 18px;
  padding: 0 6px;
  border-radius: 9px;
  background: var(--danger-soft);
  color: var(--danger);
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  text-align: center;
}

.notification-panel {
  position: absolute;
  bottom: 0;
  left: calc(100% + 16px);
  z-index: 120;
  display: flex;
  flex-direction: column;
  width: 380px;
  max-width: calc(100vw - 96px);
  max-height: min(480px, calc(100vh - 140px));
  overflow: hidden;
  outline: none;
  background: var(--glass-bg1);
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
}

.notification-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border-color-soft);
  flex-shrink: 0;
}

.notification-head-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.notification-head-title strong {
  font-size: 14px;
  color: var(--text-primary);
}

.notification-head-title span {
  font-size: 12px;
  color: var(--text-tertiary);
}

.notification-mark-all {
  padding: 4px 8px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--accent);
  font-size: 12px;
  cursor: pointer;
  transition: background var(--transition-fast);
}

.notification-mark-all:hover:not(:disabled) {
  background: var(--accent-soft);
}

.notification-mark-all:disabled {
  color: var(--text-tertiary);
  cursor: not-allowed;
}

.notification-loading {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
}

.notification-skeleton {
  height: 48px;
  border-radius: 8px;
  background: var(--surface-soft);
  animation: notification-pulse 1.4s ease-in-out infinite;
}

@keyframes notification-pulse {
  0%,
  100% {
    opacity: 0.5;
  }
  50% {
    opacity: 0.9;
  }
}

.notification-error {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 20px 14px;
  color: var(--text-secondary);
  font-size: 13px;
}

.notification-error p {
  margin: 0;
}

.notification-retry {
  padding: 4px 10px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: transparent;
  color: var(--accent);
  font-size: 12px;
  cursor: pointer;
}

.notification-empty {
  margin: 0;
  padding: 32px 14px;
  color: var(--text-tertiary);
  font-size: 13px;
  text-align: center;
}

.notification-list {
  margin: 0;
  padding: 4px;
  list-style: none;
  overflow-y: auto;
}

.notification-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
  padding: 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background var(--transition-fast);
}

.notification-item:hover {
  background: var(--surface-hover);
}

.notification-item:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
}

.notification-item.is-unread {
  background: var(--accent-soft);
}

.notification-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  margin-top: 1px;
  border-radius: 50%;
  font-size: 16px;
  flex-shrink: 0;
}

.notification-icon.is-success {
  color: var(--success);
  background: var(--success-soft);
}

.notification-icon.is-warning {
  color: var(--warning);
  background: var(--warning-soft);
}

.notification-icon.is-error {
  color: var(--danger);
  background: var(--danger-soft);
}

.notification-icon.is-info {
  color: var(--accent);
  background: var(--accent-soft);
}

.notification-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.notification-title {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
  word-break: break-word;
}

.notification-content {
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}

.notification-time {
  color: var(--text-tertiary);
  font-size: 11px;
}

.notification-dot {
  width: 7px;
  height: 7px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--danger);
  flex-shrink: 0;
}

.notification-sr {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

@media (max-width: 768px) {
  .notification-panel {
    max-width: calc(100vw - 96px);
  }
}

:global(.shell.is-mobile-open) .notification-panel {
  position: fixed;
  right: 12px;
  bottom: 12px;
  left: auto;
  width: min(340px, calc(100vw - 24px));
  max-width: none;
  max-height: calc(100dvh - 120px);
}
</style>
