<template>
  <div class="tag-filter-dropdown" ref="dropdownRef">
    <!-- 默认触发按钮 -->
    <button
      v-if="!inline"
      type="button"
      class="tag-filter-trigger"
      @click.stop="toggleDropdown"
    >
      <PriceTag class="tag-filter-icon" />
      <span class="tag-filter-text">{{ triggerText }}</span>
      <ArrowDown class="tag-filter-arrow" :class="{ 'is-open': isOpen }" />
    </button>

    <!-- inline 触发器：一直显示，类似 select 输入框 -->
    <div
      v-else
      class="tag-filter-inline-trigger"
      :class="{ 'is-open': isOpen }"
      @click.stop="toggleDropdown"
    >
      <PriceTag class="tag-filter-inline-icon" />
      <span class="tag-filter-inline-text">{{ triggerText }}</span>
      <ArrowDown class="tag-filter-inline-arrow" :class="{ 'is-open': isOpen }" />
    </div>

    <!-- 下拉面板：支持 teleport 到 body 以逃出父级 overflow:hidden -->
    <Teleport to="body" :disabled="!teleport">
      <Transition name="tag-filter-fade">
        <div
          v-show="isOpen"
          ref="panelRef"
          class="tag-filter-panel"
          :class="{ 'is-inline': inline, 'is-teleported': teleport }"
          :style="teleport ? panelStyle : undefined"
          @click.stop
        >
          <div v-if="!inline && !teleport" class="tag-filter-arrow-shape"></div>
          <!-- 搜索框 -->
          <div class="tag-filter-search">
            <Search class="tag-filter-search-icon" />
            <input
              v-model="searchQuery"
              type="text"
              placeholder="搜索标签..."
              class="tag-filter-search-input"
            />
          </div>

          <!-- 标签列表 -->
          <div class="tag-filter-list">
            <div v-if="filteredTags.length === 0" class="tag-filter-empty">
              暂无标签
            </div>
            <label
              v-for="tag in filteredTags"
              :key="tag.iid"
              class="tag-filter-item"
              :class="{ 'is-checked': isChecked(tag.iid) }"
            >
              <input
                type="checkbox"
                :value="tag.iid"
                :checked="isChecked(tag.iid)"
                @change="toggleTag(tag)"
                class="tag-filter-checkbox"
              />
              <span class="tag-filter-name" :title="tag.name">{{ tag.name }}</span>
            </label>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { PriceTag, ArrowDown, Search } from '@element-plus/icons-vue'
import { dropdownState } from '../TagDropdown/dropdownState'

const props = defineProps<{
  allTags: any[]
  modelValue: string[]
  type?: string
  inline?: boolean
  teleport?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string[]]
  'visible-change': [visible: boolean]
}>()

const isOpen = ref(false)
const dropdownRef = ref<HTMLElement>()
const panelRef = ref<HTMLElement>()
const searchQuery = ref('')
const panelStyle = ref<Record<string, string>>({})

const dropdownId = Math.random().toString(36).substr(2, 9)
let unsubscribe: (() => void) | null = null
let rafId: number | null = null

const triggerText = computed(() => {
  if (props.modelValue.length === 0) return '全部标签'
  if (props.modelValue.length === 1) {
    const tag = props.allTags.find(t => t.iid === props.modelValue[0])
    return tag?.name || '全部标签'
  }
  return `已选 ${props.modelValue.length} 项`
})

const filteredTags = computed(() => {
  if (!searchQuery.value) return props.allTags
  const query = searchQuery.value.toLowerCase()
  return props.allTags.filter((tag: any) =>
    tag.name.toLowerCase().includes(query)
  )
})

const isChecked = (tagId: any) => {
  return props.modelValue.includes(String(tagId))
}

const toggleTag = (tag: any) => {
  const tagId = String(tag.iid)
  const ids = [...props.modelValue]
  const index = ids.indexOf(tagId)
  if (index > -1) {
    ids.splice(index, 1)
  } else {
    ids.push(tagId)
  }
  emit('update:modelValue', ids)
}

const updatePanelPosition = () => {
  if (!props.teleport || !dropdownRef.value) return
  const rect = dropdownRef.value.getBoundingClientRect()
  const spaceBelow = window.innerHeight - rect.bottom - 16

  panelStyle.value = {
    position: 'fixed',
    top: `${rect.bottom + 4}px`,
    left: `${rect.left}px`,
    width: `${Math.max(rect.width, 200)}px`,
    minWidth: '200px',
    maxHeight: `${Math.max(spaceBelow, 120)}px`,
    overflowY: 'auto',
  }
}

const schedulePositionUpdate = () => {
  if (rafId !== null) return
  rafId = requestAnimationFrame(() => {
    rafId = null
    updatePanelPosition()
  })
}

const toggleDropdown = () => {
  if (!isOpen.value) {
    // 先计算位置再显示面板，避免闪烁
    updatePanelPosition()
    dropdownState.open(dropdownId)
    searchQuery.value = ''
  } else {
    dropdownState.close()
  }
  isOpen.value = !isOpen.value
}

const handleClickOutside = (event: MouseEvent) => {
  const target = event.target as Node
  const insideTrigger = dropdownRef.value?.contains(target) ?? false
  const insidePanel = panelRef.value?.contains(target) ?? false
  if (!insideTrigger && !insidePanel) {
    isOpen.value = false
    if (dropdownState.getCurrentOpenId() === dropdownId) {
      dropdownState.close()
    }
  }
}

onMounted(() => {
  unsubscribe = dropdownState.subscribe((id) => {
    if (id !== dropdownId && isOpen.value) {
      isOpen.value = false
    }
  })
  document.addEventListener('click', handleClickOutside)
  if (props.teleport) {
    window.addEventListener('resize', schedulePositionUpdate)
    window.addEventListener('scroll', schedulePositionUpdate, true)
  }
})

onUnmounted(() => {
  if (unsubscribe) unsubscribe()
  document.removeEventListener('click', handleClickOutside)
  if (props.teleport) {
    window.removeEventListener('resize', schedulePositionUpdate)
    window.removeEventListener('scroll', schedulePositionUpdate, true)
  }
  if (rafId !== null) {
    cancelAnimationFrame(rafId)
  }
  if (dropdownState.getCurrentOpenId() === dropdownId) {
    dropdownState.close()
  }
})

watch(isOpen, (val) => {
  emit('visible-change', val)
  if (val && props.teleport) {
    nextTick(updatePanelPosition)
  }
})
</script>

<style scoped>
.tag-filter-dropdown {
  position: relative;
  display: inline-block;
  width: 100%;
}

.tag-filter-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  height: 40px;
  padding: 0 12px;
  background: var(--bg-primary);
  border: 1px solid var(--glass-border);
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-primary);
  cursor: pointer;
  min-width: 120px;
  transition: all 0.2s;
  backdrop-filter: blur(10px);
  box-sizing: border-box;
}

.tag-filter-trigger:hover {
  border-color: var(--accent);
  color: var(--text-primary);
  background: var(--accent-soft);
}

.tag-filter-icon {
  width: 14px;
  height: 14px;
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.tag-filter-text {
  flex: 1;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tag-filter-arrow {
  width: 12px;
  height: 12px;
  color: var(--text-tertiary);
  transition: transform 0.2s;
  flex-shrink: 0;
}

.tag-filter-arrow.is-open {
  transform: rotate(180deg);
}

/* inline 模式触发器 */
.tag-filter-inline-trigger {
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  width: 100%;
  height: 100%;
  min-height: 32px;
  padding: 0 10px;
  background: transparent;
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-primary);
  cursor: pointer;
  box-sizing: border-box;
  transition: all 0.2s;
}

.tag-filter-inline-icon {
  width: 14px;
  height: 14px;
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.tag-filter-inline-text {
  flex: 1;
  min-width: 0;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tag-filter-inline-arrow {
  width: 12px;
  height: 12px;
  color: var(--text-tertiary);
  transition: transform 0.2s;
  flex-shrink: 0;
}

.tag-filter-inline-arrow.is-open {
  transform: rotate(180deg);
}

.tag-filter-panel {
  position: absolute;
  top: 100%;
  right: 0;
  width: 100%;
  min-width: 200px;
  max-height: 300px;
  overflow-y: auto;
  background: var(--glass-bg1);
  border: 1px solid var(--glass-border);
  border-radius: 8px;
  box-shadow: var(--glass-shadow);
  z-index: 9999;
  margin-top: 8px;
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
  box-sizing: border-box;
}

.tag-filter-panel.is-inline {
  margin-top: 4px;
}

.tag-filter-panel.is-teleported {
  position: fixed;
  margin-top: 0;
}

.tag-filter-arrow-shape {
  position: absolute;
  top: -6px;
  right: 20px;
  width: 12px;
  height: 12px;
  background: var(--glass-bg1);
  transform: rotate(45deg);
  border-left: 1px solid var(--glass-border);
  border-top: 1px solid var(--glass-border);
  z-index: 1;
}

.tag-filter-search {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border-color-soft);
}

.tag-filter-search-icon {
  width: 14px;
  height: 14px;
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.tag-filter-search-input {
  flex: 1;
  border: none;
  outline: none;
  font-size: 13px;
  background: transparent;
  color: var(--text-primary);
}

.tag-filter-search-input::placeholder {
  color: var(--text-tertiary);
}

.tag-filter-list {
  padding: 4px 0;
}

.tag-filter-panel::-webkit-scrollbar {
  width: 6px;
}

.tag-filter-panel::-webkit-scrollbar-track {
  background: var(--border-color-soft);
  border-radius: 3px;
}

.tag-filter-panel::-webkit-scrollbar-thumb {
  background: var(--glass-border);
  border-radius: 3px;
}

.tag-filter-panel::-webkit-scrollbar-thumb:hover {
  background: var(--border-color-strong);
}

.tag-filter-empty {
  padding: 20px;
  text-align: center;
  color: var(--text-tertiary);
  font-size: 13px;
}

.tag-filter-item {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 8px 12px;
  cursor: pointer;
  transition: background 0.2s;
  color: var(--text-primary);
}

.tag-filter-item:hover {
  background: var(--accent-soft);
}

.tag-filter-item.is-checked {
  background: var(--accent-soft);
}

.tag-filter-checkbox {
  width: 14px;
  height: 14px;
  cursor: pointer;
  accent-color: var(--accent);
  flex-shrink: 0;
}

.tag-filter-name {
  flex: 1;
  min-width: 0;
  display: block;
  font-size: 13px;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 动画 */
.tag-filter-fade-enter-active,
.tag-filter-fade-leave-active {
  transition: opacity 0.2s, transform 0.2s;
}

.tag-filter-fade-enter-from,
.tag-filter-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
