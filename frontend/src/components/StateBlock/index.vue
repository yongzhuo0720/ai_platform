<template>
  <div class="state-block">
    <div
      v-if="loading"
      class="state-block__skeleton"
      :class="`is-${variant}`"
      aria-busy="true"
      aria-live="polite"
    >
      <div
        v-for="index in count"
        :key="index"
        class="state-block__sk"
        :class="variant === 'cards' ? 'state-block__sk--card' : 'state-block__sk--row'"
      >
        <span class="state-block__sk-line state-block__sk-line--title"></span>
        <span class="state-block__sk-line"></span>
        <span class="state-block__sk-line state-block__sk-line--short"></span>
      </div>
    </div>

    <div v-else-if="error" class="state-block__error" role="alert">
      <el-icon class="state-block__error-icon"><WarningFilled /></el-icon>
      <p class="state-block__error-text">{{ error }}</p>
      <button type="button" class="btn btn-secondary state-block__retry" @click="emit('retry')">
        {{ retryText }}
      </button>
    </div>

    <div v-else-if="empty" class="state-block__empty">
      <p class="state-block__empty-text">{{ emptyText }}</p>
      <slot name="empty-action" />
    </div>

    <slot v-else />
  </div>
</template>

<script setup lang="ts">
import { WarningFilled } from '@element-plus/icons-vue'

withDefaults(
  defineProps<{
    loading?: boolean
    /** 非空即进入错误态，内容就是后端返回的原因 */
    error?: string
    empty?: boolean
    emptyText?: string
    retryText?: string
    variant?: 'rows' | 'cards'
    count?: number
  }>(),
  {
    loading: false,
    error: '',
    empty: false,
    emptyText: '暂无数据',
    retryText: '重试',
    variant: 'rows',
    count: 6,
  }
)

const emit = defineEmits<{ retry: [] }>()
</script>

<style scoped>
/* 这里只用 token，不写字面色值：公共组件里的一个字面值会被所有引用它的页面复制出去 */
.state-block__skeleton {
  display: grid;
  gap: 12px;
}

.state-block__skeleton.is-cards {
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
}

.state-block__sk {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px 20px;
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-md);
  background: var(--surface-soft);
}

.state-block__sk::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent, var(--surface-hover), transparent);
  transform: translateX(-100%);
  animation: state-block-shimmer 1.4s linear infinite;
}

.state-block__sk-line {
  height: 12px;
  border-radius: var(--radius-xs);
  background: var(--surface-card);
}

.state-block__sk-line--title {
  width: 45%;
  height: 16px;
}

.state-block__sk-line--short {
  width: 30%;
}

@keyframes state-block-shimmer {
  100% {
    transform: translateX(100%);
  }
}

@media (prefers-reduced-motion: reduce) {
  .state-block__sk::after {
    animation: none;
  }
}

.state-block__error,
.state-block__empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 40px 20px;
  text-align: center;
}

.state-block__error-icon {
  font-size: 26px;
  color: var(--danger);
}

.state-block__error-text {
  margin: 0;
  color: var(--text-secondary);
}

.state-block__empty-text {
  margin: 0;
  color: var(--text-tertiary);
}

.state-block__retry {
  margin-top: 4px;
}
</style>
