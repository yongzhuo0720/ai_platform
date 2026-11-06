<script setup lang="ts">
import { Search, Loading, Warning, ArrowRight } from '@element-plus/icons-vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import {
  globalSearchApi,
  type GlobalSearchItem,
  type GlobalSearchResult,
} from '@/api/globalSearch'

withDefaults(defineProps<{ collapsed?: boolean }>(), { collapsed: false })

const DEBOUNCE_MS = 250

const router = useRouter()
const rootRef = ref<HTMLElement | null>(null)
const triggerRef = ref<HTMLButtonElement | null>(null)
const inputRef = ref<HTMLInputElement | null>(null)
const panelRef = ref<HTMLElement | null>(null)

const open = ref(false)
const keyword = ref('')
const loading = ref(false)
const error = ref('')
const result = ref<GlobalSearchResult | null>(null)
const searched = ref(false)
const activeIndex = ref(-1)

let debounceTimer: number | null = null
let requestSeq = 0

const groups = computed(() => {
  let index = 0
  return (result.value?.groups ?? []).map((group) => ({
    ...group,
    items: group.items.map((item) => ({ ...item, index: index++ })),
  }))
})
const flatItems = computed(() => groups.value.flatMap((group) => group.items))
const activeItem = computed<GlobalSearchItem | null>(
  () => flatItems.value[activeIndex.value] ?? null,
)
const hasQuery = computed(() => keyword.value.trim().length > 0)
const total = computed(() => result.value?.total ?? 0)

function optionId(index: number) {
  return `global-search-option-${index}`
}

async function openPanel() {
  open.value = true
  await nextTick()
  inputRef.value?.focus()
  inputRef.value?.select()
  if (hasQuery.value && !result.value && !loading.value) {
    void runSearch()
  }
}

function closePanel(restoreFocus = false) {
  if (!open.value) return
  open.value = false
  requestSeq++
  loading.value = false
  if (debounceTimer !== null) {
    window.clearTimeout(debounceTimer)
    debounceTimer = null
  }
  if (restoreFocus) void nextTick(() => triggerRef.value?.focus())
}

async function runSearch() {
  const query = keyword.value.trim()
  if (!query) {
    result.value = null
    searched.value = false
    activeIndex.value = -1
    return
  }
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  try {
    const { data: res } = await globalSearchApi.search(query)
    if (seq !== requestSeq) return
    if (res.resp_code === 0) {
      const datas: GlobalSearchResult = res.datas ?? { keyword: query, total: 0, degraded: false, groups: [] }
      result.value = datas
      searched.value = true
      activeIndex.value = datas.groups.length ? 0 : -1
    } else {
      result.value = null
      searched.value = false
      error.value = res.resp_msg || '搜索失败，请稍后重试'
    }
  } catch (err) {
    if (seq !== requestSeq) return
    result.value = null
    searched.value = false
    error.value = '搜索失败，请检查网络后重试'
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function scheduleSearch() {
  if (debounceTimer !== null) window.clearTimeout(debounceTimer)
  if (!hasQuery.value) return
  debounceTimer = window.setTimeout(() => {
    debounceTimer = null
    void runSearch()
  }, DEBOUNCE_MS)
}

function resetQueryState() {
  requestSeq++
  loading.value = false
  error.value = ''
  result.value = null
  searched.value = false
  activeIndex.value = -1
}

watch(keyword, () => {
  resetQueryState()
  scheduleSearch()
})

function scrollActiveIntoView() {
  void nextTick(() => {
    const el = document.getElementById(optionId(activeIndex.value))
    el?.scrollIntoView?.({ block: 'nearest' })
  })
}

function moveActive(step: number) {
  const count = flatItems.value.length
  if (!count) return
  const current = activeIndex.value
  const next = current < 0
    ? (step > 0 ? 0 : count - 1)
    : (current + step + count) % count
  activeIndex.value = next
  scrollActiveIntoView()
}

async function choose(item: GlobalSearchItem | null = activeItem.value) {
  if (!item) return
  open.value = false
  keyword.value = ''
  resetQueryState()
  await router.push(item.route)
}

function onDocumentClick(event: MouseEvent) {
  if (!open.value) return
  const target = event.target as Node
  if (rootRef.value?.contains(target) || panelRef.value?.contains(target)) return
  closePanel(false)
}

function onDocumentKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    // 输入框里也允许打开；阻止浏览器默认的地址栏搜索
    event.preventDefault()
    if (event.repeat) return
    if (open.value) closePanel(false)
    else void openPanel()
    return
  }
  if (!open.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    closePanel(true)
    return
  }
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    moveActive(1)
    return
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    moveActive(-1)
    return
  }
  if (event.key === 'Enter' && activeIndex.value >= 0) {
    event.preventDefault()
    void choose()
  }
}

function onRetry() {
  void runSearch()
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onDocumentKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onDocumentKeydown)
  requestSeq++
  if (debounceTimer !== null) {
    window.clearTimeout(debounceTimer)
    debounceTimer = null
  }
})
</script>

<template>
  <div ref="rootRef" class="global-search-root">
    <button
      ref="triggerRef"
      type="button"
      class="global-search-trigger"
      :class="{ 'is-collapsed': collapsed }"
      aria-label="全局搜索，快捷键 Ctrl K"
      aria-haspopup="dialog"
      :aria-expanded="open"
      @click="openPanel"
    >
      <el-icon class="global-search-trigger-icon"><Search /></el-icon>
      <span v-show="!collapsed" class="global-search-trigger-text">搜索</span>
      <kbd v-show="!collapsed" class="global-search-kbd">Ctrl K</kbd>
    </button>

    <Teleport to="body">
      <div v-if="open" class="global-search-overlay" @click.self="closePanel(false)">
        <section
          ref="panelRef"
          class="global-search-panel"
          role="dialog"
          aria-modal="true"
          aria-label="全局搜索"
        >
          <header class="global-search-head">
            <el-icon class="global-search-input-icon" aria-hidden="true"><Search /></el-icon>
            <input
              ref="inputRef"
              v-model="keyword"
              class="global-search-input"
              type="text"
              role="combobox"
              placeholder="搜索 Agent、知识库、RAG 评估、模型、数据集…"
              aria-label="搜索关键词"
              aria-autocomplete="list"
              aria-controls="global-search-listbox"
              :aria-expanded="open"
              :aria-activedescendant="activeIndex >= 0 ? optionId(activeIndex) : undefined"
              maxlength="50"
              autocomplete="off"
            />
            <el-icon v-if="loading" class="global-search-spinner" aria-label="搜索中">
              <Loading />
            </el-icon>
            <kbd v-else class="global-search-kbd global-search-kbd--esc">Esc</kbd>
          </header>

          <div class="global-search-body">
            <p v-if="!hasQuery" class="global-search-hint" role="status">
              输入关键词，一次搜索你的 Agent、知识库、RAG 评估、模型与数据集。
            </p>

            <div v-else-if="error" class="global-search-state global-search-state--error" role="alert">
              <el-icon><Warning /></el-icon>
              <span>{{ error }}</span>
              <button type="button" class="global-search-retry" @click="onRetry">重试</button>
            </div>

            <div
              v-else-if="loading && !searched"
              class="global-search-skeleton"
              aria-busy="true"
              aria-label="正在搜索"
            >
              <span v-for="i in 4" :key="i" class="global-search-skeleton-row" />
            </div>

            <p
              v-else-if="searched && total === 0"
              class="global-search-state global-search-state--empty"
              role="status"
            >
              没有找到与「{{ result?.keyword || keyword.trim() }}」匹配的内容，换个关键词试试。
            </p>

            <div
              v-else-if="searched"
              id="global-search-listbox"
              class="global-search-results"
              role="listbox"
              aria-label="搜索结果"
            >
              <div
                v-for="group in groups"
                :key="group.category"
                class="global-search-group"
                role="group"
                :aria-label="group.label"
              >
                <div class="global-search-group-label">{{ group.label }}</div>
                <div
                  v-for="item in group.items"
                  :id="optionId(item.index)"
                  :key="`${item.category}-${item.index}`"
                  class="global-search-option"
                  :class="{ 'is-active': item.index === activeIndex }"
                  role="option"
                  :aria-selected="item.index === activeIndex"
                  @mouseenter="activeIndex = item.index"
                  @click="choose(item)"
                >
                  <div class="global-search-option-main">
                    <span class="global-search-option-title">{{ item.title }}</span>
                    <span v-if="item.subtitle" class="global-search-option-subtitle">{{ item.subtitle }}</span>
                  </div>
                  <el-icon class="global-search-option-arrow" aria-hidden="true"><ArrowRight /></el-icon>
                </div>
              </div>
            </div>

            <p v-if="result?.degraded" class="global-search-degraded" role="status">
              部分数据源暂时不可用，当前结果可能不完整。
            </p>
          </div>

          <footer class="global-search-foot">
            <span><kbd class="global-search-kbd">↑</kbd><kbd class="global-search-kbd">↓</kbd> 选择</span>
            <span><kbd class="global-search-kbd">Enter</kbd> 打开</span>
            <span><kbd class="global-search-kbd">Esc</kbd> 关闭</span>
            <span v-if="searched && total > 0" class="global-search-count">{{ total }} 条结果</span>
          </footer>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.global-search-root {
  width: 100%;
}

.global-search-trigger {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 38px;
  padding: 0 10px;
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

.global-search-trigger:hover {
  background: var(--layout-menu-hover-bg);
  color: var(--layout-menu-text-hover);
}

.global-search-trigger:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

.global-search-trigger.is-collapsed {
  justify-content: center;
  padding: 0;
}

.global-search-trigger-icon {
  font-size: 18px;
  line-height: 1;
}

.global-search-trigger-text {
  flex: 1;
  text-align: left;
}

.global-search-kbd {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 20px;
  padding: 0 6px;
  border: 1px solid var(--border-color-soft);
  border-radius: 5px;
  background: var(--bg-tertiary);
  color: var(--text-tertiary);
  font-family: inherit;
  font-size: 11px;
  line-height: 1;
  white-space: nowrap;
}

/* 侧栏触发器里的快捷键徽标跟随导航配色，弹层里的保持面板配色 */
.global-search-trigger .global-search-kbd {
  border-color: var(--nav-kbd-border);
  background: var(--nav-kbd-bg);
  color: var(--nav-kbd-text);
}

.global-search-overlay {
  position: fixed;
  inset: 0;
  z-index: 1200;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding: 12vh 16px 16px;
  background: var(--surface-overlay);
}

.global-search-panel {
  display: flex;
  flex-direction: column;
  width: min(720px, 100%);
  max-height: 70vh;
  overflow: hidden;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  background: var(--surface-card-strong);
  box-shadow: var(--shadow-lg);
  backdrop-filter: var(--glass-blur);
}

.global-search-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--border-color-soft);
}

.global-search-input-icon {
  color: var(--text-tertiary);
  font-size: 18px;
  flex-shrink: 0;
}

.global-search-input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  color: var(--text-primary);
  font-size: 15px;
  line-height: 1.4;
}

.global-search-input::placeholder {
  color: var(--text-tertiary);
}

.global-search-spinner {
  color: var(--accent);
  animation: global-search-spin 0.8s linear infinite;
}

@keyframes global-search-spin {
  to {
    transform: rotate(360deg);
  }
}

.global-search-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
}

.global-search-hint,
.global-search-state {
  margin: 0;
  padding: 28px 16px;
  color: var(--text-tertiary);
  font-size: 13px;
  text-align: center;
}

.global-search-state--error {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--error);
}

.global-search-retry {
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: var(--surface-soft);
  color: var(--text-primary);
  font-size: 12px;
  padding: 4px 10px;
  cursor: pointer;
}

.global-search-retry:hover {
  background: var(--surface-hover);
}

.global-search-skeleton {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px;
}

.global-search-skeleton-row {
  height: 44px;
  border-radius: 8px;
  background: var(--surface-soft);
  animation: global-search-pulse 1.2s ease-in-out infinite;
}

@keyframes global-search-pulse {
  50% {
    opacity: 0.45;
  }
}

.global-search-group + .global-search-group {
  margin-top: 8px;
}

.global-search-group-label {
  padding: 6px 10px 4px;
  color: var(--text-tertiary);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.global-search-option {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
}

.global-search-option.is-active {
  background: var(--surface-hover);
}

.global-search-option-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.global-search-option-title {
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.global-search-option-subtitle {
  color: var(--text-tertiary);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.global-search-option-arrow {
  flex-shrink: 0;
  color: var(--text-tertiary);
  font-size: 14px;
  opacity: 0;
  transition: opacity var(--transition-fast);
}

.global-search-option.is-active .global-search-option-arrow {
  opacity: 1;
}

.global-search-degraded {
  margin: 4px 8px 8px;
  padding: 8px 10px;
  border: 1px solid var(--warning-border);
  border-radius: 8px;
  background: var(--warning-soft);
  color: var(--warning);
  font-size: 12px;
}

.global-search-foot {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 16px;
  border-top: 1px solid var(--border-color-soft);
  color: var(--text-tertiary);
  font-size: 12px;
}

.global-search-foot span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.global-search-count {
  margin-left: auto;
}

.global-search-kbd--esc {
  margin-left: 4px;
}

@media (max-width: 640px) {
  .global-search-overlay {
    padding-top: 6vh;
  }

  .global-search-panel {
    max-height: 82vh;
  }

  .global-search-foot {
    gap: 10px;
  }

  .global-search-foot span:nth-child(1),
  .global-search-foot span:nth-child(2) {
    display: none;
  }
}
</style>
