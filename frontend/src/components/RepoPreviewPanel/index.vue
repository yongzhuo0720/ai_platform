<template>
  <div class="repo-preview-panel">
    <div class="repo-preview-header">
      <div class="repo-preview-title">
        <h3>{{ panelTitle }}</h3>
        <p v-if="downloadUrl" class="repo-preview-url" :title="downloadUrl">{{ downloadUrl }}</p>
      </div>
      <button type="button" class="repo-preview-back" @click="handleBack">
        <el-icon><ArrowLeft /></el-icon>
        <span>{{ backText || '返回' }}</span>
      </button>
    </div>

    <div class="repo-preview-body">
      <!-- Loading -->
      <div v-if="loading" class="repo-preview-state">
        <el-icon class="repo-preview-spinner"><Loading /></el-icon>
        <span>正在加载资源列表...</span>
      </div>

      <!-- Error -->
      <div v-else-if="error" class="repo-preview-state repo-preview-error">
        <el-icon><WarningFilled /></el-icon>
        <span>{{ error }}</span>
        <div class="repo-preview-error-actions">
          <el-button type="primary" size="small" @click="fetchUrl">重试</el-button>
        </div>
      </div>

      <!-- Tree view -->
      <div v-else-if="treeData.length > 0" class="repo-tree-wrap">
        <TreeNode
          v-for="(node, idx) in treeData"
          :key="idx"
          :node="node"
          :depth="0"
        />
      </div>

      <!-- Empty -->
      <div v-else class="repo-preview-state">
        <el-icon><FolderOpened /></el-icon>
        <span>暂无文件数据</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { datasetsMngApi } from '@/api/datasetsMng'
import { modelsMngApi } from '@/api/modelsMng'
import {
  ArrowLeft,
  Loading,
  WarningFilled,
  FolderOpened,
} from '@element-plus/icons-vue'
import TreeNode from './TreeNode.vue'

export interface TreeNodeData {
  name: string
  type: 'file' | 'directory'
  path: string
  size?: number
  children: TreeNodeData[]
}

const props = defineProps<{
  type: 'datasets' | 'models'
  iid: string | number
  name?: string
  backText?: string
}>()

const emit = defineEmits<{
  (e: 'back'): void
}>()

const loading = ref(true)
const error = ref('')
const downloadUrl = ref('')
const treeData = ref<TreeNodeData[]>([])

const apiMap = {
  datasets: datasetsMngApi,
  models: modelsMngApi,
}

const panelTitle = computed(() => {
  const prefix = props.type === 'datasets' ? '数据集' : '模型'
  return props.name ? `${prefix}：${props.name}` : `${prefix}下载预览`
})

const isTreeData = (data: any): data is TreeNodeData[] => {
  if (!Array.isArray(data)) return false
  if (data.length === 0) return true
  return data[0] && typeof data[0] === 'object' && 'name' in data[0] && 'type' in data[0]
}

const fetchUrl = async () => {
  if (!props.iid) {
    error.value = '缺少资源标识'
    loading.value = false
    return
  }

  loading.value = true
  error.value = ''
  downloadUrl.value = ''
  treeData.value = []

  try {
    const api = apiMap[props.type]
    const { data: res } = await api.getDownloadUrl({ data: { iid: props.iid } })
    if (res.resp_code === 0 && res.datas) {
      if (isTreeData(res.datas)) {
        treeData.value = res.datas
      } else if (typeof res.datas === 'string') {
        downloadUrl.value = res.datas
        error.value = '该资源暂不支持图形列表展示'
      } else {
        error.value = '数据格式不正确'
      }
    } else {
      error.value = res.resp_msg || '获取下载地址失败'
    }
  } catch (e) {
    error.value = '请求失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

const handleBack = () => {
  emit('back')
}

watch(() => props.iid, fetchUrl, { immediate: true })
</script>

<style scoped>
.repo-preview-panel {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  min-height: 0;
  background: var(--surface-card, var(--glass-bg));
  border: 1px solid var(--glass-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--glass-shadow);
  overflow: hidden;
}

.repo-preview-header {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
  padding: 16px 20px;
  border-bottom: 1px solid var(--glass-border);
  background: var(--gradient-glass);
}

.repo-preview-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border-radius: var(--radius-sm);
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all var(--transition-fast);
  flex-shrink: 0;
}

.repo-preview-back:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
}

.repo-preview-back .el-icon {
  font-size: 16px;
}

.repo-preview-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.repo-preview-title h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.repo-preview-url {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.repo-preview-body {
  flex: 1;
  min-height: 0;
  position: relative;
  background: var(--bg-primary, #ffffff);
  overflow: auto;
}

.repo-tree-wrap {
  padding: 12px 20px;
}

.repo-preview-state {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  font-size: 14px;
  color: var(--text-secondary);
}

.repo-preview-state .el-icon {
  font-size: 32px;
  color: var(--accent);
}

.repo-preview-error {
  color: var(--error, #ff3b30);
}

.repo-preview-error .el-icon {
  color: var(--error, #ff3b30);
}

.repo-preview-error-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.repo-preview-spinner {
  animation: repo-preview-spin 1s linear infinite;
}

@keyframes repo-preview-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
