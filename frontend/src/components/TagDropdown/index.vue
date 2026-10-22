<template>
  <div class="tag-dropdown" ref="dropdownRef">
    <!-- 触发按钮 -->
    <div class="tag-dropdown-trigger" @click.stop="toggleDropdown" ref="triggerRef">
      <slot name="trigger">
        <button v-if="showAddButton" type="button" class="action-btn">
          <Plus class="tag-add-icon" />
          <span>添加标签</span>
        </button>
      </slot>
    </div>

    <!-- 下拉面板 - 使用 Teleport 传送到 body -->
    <Teleport to="body">
      <Transition name="tag-dropdown-fade">
        <div v-show="isOpen" class="tag-dropdown-panel" :style="fixedPanelStyle" @click.stop>
          <!-- 倒三角箭头 -->
          <div class="tag-dropdown-arrow"></div>
          <!-- 顶部：搜索框 -->
          <div class="tag-dropdown-search">
            <Search class="tag-search-icon" />
            <input
              v-model="searchQuery"
              type="text"
              placeholder="搜索标签..."
              class="tag-search-input"
            />
          </div>

          <!-- 中部：标签列表 -->
          <div class="tag-dropdown-list">
            <div v-if="filteredTags.length === 0" class="tag-dropdown-empty">
              暂无标签
            </div>
            <label
              v-for="tag in filteredTags"
              :key="tag.iid"
              class="tag-dropdown-item"
              :class="{ 'is-selected': isSelected(tag.iid) }"
              @click.stop
            >
              <input
                type="checkbox"
                :value="tag.iid"
                :checked="isSelected(tag.iid)"
                @change.stop="toggleTag(tag)"
                class="tag-checkbox"
              />
              <span class="tag-name">{{ tag.name }}</span>
              <!-- <span v-if="tag.binding_count" class="tag-count">{{ tag.binding_count }}</span> -->
            </label>
          </div>

          <!-- 底部：管理标签按钮 -->
          <div class="tag-dropdown-footer" v-if="rid===SUPER_ADMIN_RID">
            <button type="button" class="tag-manage-btn" @click.stop="openManageDialog">
              <Setting class="tag-manage-icon" />
              <span>管理标签</span>
            </button>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- 标签管理弹窗 -->
    <TagManageDialog
      v-model:visible="manageDialogVisible"
      :tags="props.allTags"
      :type="props.type"
      @refresh="getAllTags"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch} from 'vue'
import { Plus, Search, Setting } from '@element-plus/icons-vue'
import TagManageDialog from '../TagManageDialog/index.vue'
import tagsBindApi from '@/api/tagsbind'
import Cookies from 'js-cookie'
import { dropdownState } from './dropdownState'
import { SUPER_ADMIN_RID } from '@/config/constants'

const props=defineProps(['type','parent','showAddButton','placement','allTags'])

const emit=defineEmits(['refreshTagBind','getAllTags'])

const isOpen = ref(false)
const dropdownRef = ref<HTMLElement>()
const triggerRef = ref<HTMLElement>()
const panelPosition = ref({ top: 0, left: 0 })

// 生成唯一ID
const dropdownId = Math.random().toString(36).substr(2, 9)

// 取消订阅函数
let unsubscribe: (() => void) | null = null
const searchQuery = ref('')
const manageDialogVisible = ref(false)
// 当前选中的标签ID
const currentSelected = ref<string[]>([])

const rid = parseInt(Cookies.get('rid') || '0')

/** 保存 body 原始 overflow 值，下拉框打开期间临时改为 auto 以允许页面滚动 */
let savedBodyOverflow = ''
let savedHtmlOverflow = ''

const enableBodyScroll = () => {
  savedBodyOverflow = document.body.style.overflow
  savedHtmlOverflow = document.documentElement.style.overflow
  document.body.style.overflow = 'auto'
  document.documentElement.style.overflow = 'auto'
}

const restoreBodyScroll = () => {
  document.body.style.overflow = savedBodyOverflow
  document.documentElement.style.overflow = savedHtmlOverflow
}




// 监听外部selectedTags变化
watch(() => props.parent.tagList, (newVal) => {
  // 处理 tagList 可能是字符串数组或对象数组的情况
  currentSelected.value = [...(newVal || []).map((item: any) => {
    if (typeof item === 'string') return item
    return item.iid || item.id || item
  })]
}, { deep: true })

// 过滤后的标签列表
const filteredTags = computed(() => {
  if (!searchQuery.value) return props.allTags
  const query = searchQuery.value.toLowerCase()
  return props.allTags.filter((tag: any) =>
    tag.name.toLowerCase().includes(query)
  )
})

// 计算面板绝对定位位置（文档流坐标，超出视口时页面自动出现滚动条）
const updatePanelPosition = () => {
  if (!triggerRef.value) return
  const rect = triggerRef.value.getBoundingClientRect()

  panelPosition.value = {
    top: rect.bottom + window.scrollY + 8,
    left: rect.left + window.scrollX,
  }
}

// 面板样式（absolute 定位，超出视口时页面自动出现滚动条）
const fixedPanelStyle = computed(() => {
  return {
    position: 'absolute' as const,
    top: `${panelPosition.value.top}px`,
    left: `${panelPosition.value.left}px`,
    zIndex: 99999,
  }
})

const getAllTags = async () => {
  emit('getAllTags')
}

// 切换下拉框显示
const toggleDropdown = () => {
  if (!isOpen.value) {
    // 先计算位置再显示面板，避免位置错误
    updatePanelPosition()
    // 要打开时，先通知关闭其他所有下拉框
    dropdownState.open(dropdownId)
    searchQuery.value = ''
    // 处理 tagList 可能是字符串数组或对象数组的情况
    currentSelected.value = [...(props.parent.tagList || []).map((item: any) => {
      return item.iid || item.id || item
    })]
    enableBodyScroll()
  } else {
    dropdownState.close()
    restoreBodyScroll()
  }
  isOpen.value = !isOpen.value
}

const closeDropdown = () => {
  if (!isOpen.value) return
  isOpen.value = false
  if (dropdownState.getCurrentOpenId() === dropdownId) {
    dropdownState.close()
  }
  restoreBodyScroll()
}

// 点击外部关闭
const handleClickOutside = (event: MouseEvent) => {
  if (dropdownRef.value && !dropdownRef.value.contains(event.target as Node)) {
    closeDropdown()
  }
}

// 窗口变化时更新位置
const handleWindowChange = () => {
  if (isOpen.value) {
    updatePanelPosition()
  }
}

// 注册和注销监听器
onMounted(() => {
  // 订阅状态变化
  unsubscribe = dropdownState.subscribe((id) => {
    // 如果当前打开的不是自己，则关闭
    if (id !== dropdownId && isOpen.value) {
      closeDropdown()
    }
  })
  document.addEventListener('click', handleClickOutside)
  window.addEventListener('scroll', handleWindowChange, true)
  window.addEventListener('resize', handleWindowChange)
})

onUnmounted(() => {
  // 取消订阅
  if (unsubscribe) {
    unsubscribe()
  }
  document.removeEventListener('click', handleClickOutside)
  window.removeEventListener('scroll', handleWindowChange, true)
  window.removeEventListener('resize', handleWindowChange)
  // 如果当前打开的是这个下拉框，清理状态
  if (dropdownState.getCurrentOpenId() === dropdownId) {
    dropdownState.close()
  }
  restoreBodyScroll()
})

// 判断是否选中
const isSelected = (tagId: string) => {
  return currentSelected.value.includes(tagId)
}

// 切换标签选中状态
const toggleTag = async (tag: any) => {
  const index = currentSelected.value.indexOf(tag.iid)
  const hash = props.parent.id || props.parent.iid
  const reqdata = {
    data: {
      hash,
      lableiid: tag.iid,
      type: props.type,
    }
  }

  try {
    if (index > -1) {
      // 当前已选中 → 删除绑定
      const { data: res } = await tagsBindApi.delete(reqdata)
      if (res.resp_code == 0) {
        currentSelected.value.splice(index, 1)
        // 只有成功了才通知父组件刷新，否则刷新会把用户刚点的选中态又覆盖回去
        emit('refreshTagBind', hash)
      } else {
        ElMessage.error(res.resp_msg || '取消标签绑定失败')
      }
    } else {
      // 未选中 → 创建绑定
      const { data: res } = await tagsBindApi.create(reqdata)
      if (res.resp_code == 0) {
        currentSelected.value.push(tag.iid)
        emit('refreshTagBind', hash)
      } else {
        ElMessage.error(res.resp_msg || '绑定标签失败')
      }
    }
  } catch (err) {
    /*
     * 修复前这里没有任何错误处理：
     *   1) await 的请求失败会产生 unhandled promise rejection；
     *   2) 用户点了复选框没反应、没报错、没提示；
     *   3) 函数末尾那句 emit('refreshTagBind') 无论成败都会执行，
     *      父组件刷新后选中态又被接口数据覆盖，表现为「点了没效果」。
     */
    console.error('标签绑定操作失败:', err)
    ElMessage.error('标签操作失败，请重试')
  }
}

// 打开管理弹窗
const openManageDialog = () => {
  manageDialogVisible.value = true
  isOpen.value = false
}


// 暴露方法
defineExpose({
  open: () => { isOpen.value = true },
  close: () => { isOpen.value = false },
})
</script>

<style scoped>
.tag-dropdown {
  position: relative;
  display: inline-block;
 
}

.tag-dropdown-trigger {
  cursor: pointer;
}

.tag-add-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  background: var(--accent-soft);
  border: 1px dashed var(--accent);
  border-radius: 4px;
  font-size: 12px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.3s ease;
  position: relative;
  overflow: hidden;
}

.tag-add-btn::before {
  content: '';
  position: absolute;
  top: 50%;
  left: 50%;
  width: 0;
  height: 0;
  border-radius: 50%;
  background: var(--accent-soft);
  transform: translate(-50%, -50%);
  transition: width 0.4s ease, height 0.4s ease;
}

.tag-add-btn:hover::before {
  width: 80px;
  height: 80px;
}

.tag-add-btn:hover {
  border-color: var(--accent);
  color: var(--text-primary);
  background: var(--accent-soft);
  box-shadow: 0 0 16px var(--accent-soft);
  transform: translateY(-1px);
}

.tag-add-btn:active {
  transform: translateY(0);
}

.tag-add-icon {
  width: 12px;
  height: 12px;
  position: relative;
  z-index: 1;
  transition: transform 0.3s ease;
}

.tag-add-btn:hover .tag-add-icon {
  transform: rotate(90deg);
}

.tag-dropdown-panel {
  width: 240px;
  max-height: 300px;
  overflow-y: auto;
  background: var(--glass-bg1);
  border: 1px solid var(--glass-border);
  border-radius: 8px;
  box-shadow: var(--glass-shadow);
  margin-top: 8px;
  backdrop-filter: var(--glass-blur);
  -webkit-backdrop-filter: var(--glass-blur);
}

.tag-dropdown-panel::-webkit-scrollbar {
  width: 6px;
}

.tag-dropdown-panel::-webkit-scrollbar-track {
  background: var(--border-color-soft);
  border-radius: 3px;
}

.tag-dropdown-panel::-webkit-scrollbar-thumb {
  background: var(--glass-border);
  border-radius: 3px;
}

.tag-dropdown-panel::-webkit-scrollbar-thumb:hover {
  background: var(--border-color-strong);
}

/* 倒三角箭头 */
.tag-dropdown-arrow {
  position: absolute;
  top: -6px;
  left: 20px;
  width: 12px;
  height: 12px;
  background: var(--glass-bg1);
  transform: rotate(45deg);
  border-left: 1px solid var(--glass-border);
  border-top: 1px solid var(--glass-border);
  z-index: 1;
}

.tag-dropdown-search {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border-color-soft);
}

.tag-search-icon {
  width: 14px;
  height: 14px;
  color: var(--text-tertiary);
  flex-shrink: 0;
}

.tag-search-input {
  flex: 1;
  border: none;
  outline: none;
  font-size: 13px;
  background: transparent;
  color: var(--text-primary);
}

.tag-search-input::placeholder {
  color: var(--text-tertiary);
}

.tag-dropdown-list {
  padding: 4px 0;
}

.tag-dropdown-empty {
  padding: 20px;
  text-align: center;
  color: var(--text-tertiary);
  font-size: 13px;
}

.tag-dropdown-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  cursor: pointer;
  transition: background 0.2s;
  color: var(--text-primary);
}

.tag-dropdown-item:hover {
  background: var(--accent-soft);
}

.tag-dropdown-item.is-selected {
  background: var(--accent-soft);
}

.tag-checkbox {
  width: 14px;
  height: 14px;
  cursor: pointer;
  accent-color: var(--accent);
}

.tag-name {
  flex: 1;
  font-size: 13px;
  color: var(--text-primary);
}

.tag-count {
  font-size: 11px;
  color: var(--text-tertiary);
  background: var(--accent-soft);
  padding: 1px 6px;
  border-radius: 10px;
}

.tag-dropdown-footer {
  padding: 6px 8px;
  border-top: 1px solid var(--border-color-soft);
}

.tag-manage-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 8px 12px;
  background: transparent;
  border: none;
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s;
}

.tag-manage-btn:hover {
  background: var(--accent-soft);
  color: var(--accent);
}

.tag-manage-btn:active {
  background: rgba(77, 166, 255, 0.18);
}

.tag-manage-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  color: var(--text-tertiary);
}

.tag-manage-btn:hover .tag-manage-icon {
  color: var(--accent);
}

/* 动画 */
.tag-dropdown-fade-enter-active,
.tag-dropdown-fade-leave-active {
  transition: opacity 0.2s, transform 0.2s;
}

.tag-dropdown-fade-enter-from,
.tag-dropdown-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
