<script setup lang="ts">
import { WarningFilled } from '@element-plus/icons-vue'
import { globalErrorCenter } from '@/utils/globalError'

const state = globalErrorCenter.state

function reloadPage() {
  window.location.reload()
}

function dismiss() {
  globalErrorCenter.dismiss()
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="state.visible"
      class="global-error-banner"
      role="alert"
      aria-live="assertive"
      aria-atomic="true"
    >
      <el-icon class="global-error-icon" aria-hidden="true"><WarningFilled /></el-icon>
      <div class="global-error-text">
        <p class="global-error-title">
          页面遇到了一点问题
          <span v-if="state.count > 1" class="global-error-count">（已出现 {{ state.count }} 次）</span>
        </p>
        <p class="global-error-detail">
          <span class="global-error-message">{{ state.message }}</span>
          <span class="global-error-id">错误编号 {{ state.errorId }}</span>
        </p>
      </div>
      <div class="global-error-actions">
        <button type="button" class="global-error-reload" @click="reloadPage">重新加载</button>
        <button type="button" class="global-error-dismiss" @click="dismiss">关闭</button>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.global-error-banner {
  position: fixed;
  top: 20px;
  left: 50%;
  z-index: 3000;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  width: min(720px, calc(100vw - 32px));
  padding: 12px 16px;
  border: 1px solid var(--danger-soft-strong);
  border-radius: var(--radius-lg);
  background: var(--surface-card-strong);
  box-shadow: var(--shadow-lg);
  backdrop-filter: var(--glass-blur);
  color: var(--text-primary);
  transform: translateX(-50%);
}

.global-error-icon {
  flex-shrink: 0;
  margin-top: 2px;
  color: var(--danger);
  font-size: 20px;
}

.global-error-text {
  flex: 1;
  min-width: 0;
}

.global-error-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
}

.global-error-count {
  color: var(--text-tertiary);
  font-weight: 400;
}

.global-error-detail {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.5;
}

.global-error-message {
  overflow: hidden;
  color: var(--text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.global-error-id {
  color: var(--text-tertiary);
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
}

.global-error-actions {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
}

.global-error-reload,
.global-error-dismiss {
  height: 30px;
  padding: 0 12px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  transition:
    background var(--transition-fast),
    color var(--transition-fast);
}

.global-error-reload {
  border: none;
  background: var(--danger);
  color: var(--text-on-accent);
}

.global-error-reload:hover {
  background: var(--danger-hover);
}

.global-error-dismiss {
  border: 1px solid var(--border-color-soft);
  background: transparent;
  color: var(--text-secondary);
}

.global-error-dismiss:hover {
  background: var(--surface-hover);
  color: var(--text-primary);
}

.global-error-reload:focus-visible,
.global-error-dismiss:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

@media (max-width: 640px) {
  .global-error-banner {
    top: 12px;
    flex-wrap: wrap;
    padding: 12px;
  }

  .global-error-actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
