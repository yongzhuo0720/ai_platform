<template>
  <div class="tree-node">
    <div
      class="tree-node-row"
      :style="{ paddingLeft: depth * 24 + 12 + 'px' }"
      :class="{ 'is-directory': node.type === 'directory' }"
      @click="toggleExpand"
    >
      <!-- Expand arrow for directories -->
      <span class="tree-node-arrow" v-if="node.type === 'directory'">
        <el-icon :class="{ expanded: expanded }">
          <ArrowRight />
        </el-icon>
      </span>
      <span class="tree-node-arrow tree-node-arrow-placeholder" v-else></span>

      <!-- Icon -->
      <span class="tree-node-icon">
        <el-icon v-if="node.type === 'directory'">
          <Folder v-if="!expanded" />
          <FolderOpened v-else />
        </el-icon>
        <el-icon v-else>
          <Document />
        </el-icon>
      </span>

      <!-- Name -->
      <span class="tree-node-name" :title="node.name">{{ node.name }}</span>

      <!-- File size -->
      <span v-if="node.type === 'file' && node.size != null" class="tree-node-size">{{ formatSize(node.size) }}</span>

      <!-- Download button for files -->
      <button
        v-if="node.type === 'file'"
        type="button"
        class="tree-node-download"
        :disabled="downloading"
        title="下载文件"
        @click.stop="handleDownload"
      >
        <el-icon v-if="!downloading"><Download /></el-icon>
        <el-icon v-else class="tree-node-spinner"><Loading /></el-icon>
      </button>
    </div>

    <!-- Children -->
    <div v-if="node.type === 'directory' && expanded" class="tree-node-children">
      <TreeNode
        v-for="(child, idx) in node.children"
        :key="idx"
        :node="child"
        :depth="depth + 1"
      />
      <div v-if="!node.children || node.children.length === 0" class="tree-node-empty" :style="{ paddingLeft: (depth + 1) * 24 + 12 + 'px' }">
        空文件夹
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ArrowRight, Folder, FolderOpened, Document, Download, Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import axios from '@/api/axios'

interface TreeNodeData {
  name: string
  type: 'file' | 'directory'
  path: string
  size?: number
  children: TreeNodeData[]
}

const props = defineProps<{
  node: TreeNodeData
  depth: number
}>()

const expanded = ref(false)
const downloading = ref(false)

function toggleExpand() {
  if (props.node.type === 'directory') {
    expanded.value = !expanded.value
  }
}

function formatSize(bytes: number): string {
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const k = 1024
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  const size = (bytes / Math.pow(k, i)).toFixed(i === 0 ? 0 : 1)
  return `${size} ${units[i]}`
}

async function handleDownload() {
  downloading.value = true
  try {
    const res = await axios.post('/ai/api/datasetsBean/downloadFile', { filePath: props.node.path }, { responseType: 'blob' })
    const blob = res.data
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = props.node.name
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    ElMessage.success('开始下载')
  } catch {
    ElMessage.error('下载请求失败')
  } finally {
    downloading.value = false
  }
}
</script>

<style scoped>
.tree-node-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 6px;
  cursor: default;
  transition: background 0.15s;
  user-select: none;
}

.tree-node-row:hover {
  background: rgba(13, 48, 100, 0.04);
}

.tree-node-row.is-directory {
  cursor: pointer;
}

.tree-node-arrow {
  width: 16px;
  height: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: rgba(13, 48, 100, 0.4);
}

.tree-node-arrow .el-icon {
  font-size: 12px;
  transition: transform 0.2s;
}

.tree-node-arrow .el-icon.expanded {
  transform: rotate(90deg);
}

.tree-node-arrow-placeholder {
  visibility: hidden;
}

.tree-node-icon {
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 18px;
}

.tree-node-icon .el-icon {
  color: rgba(13, 48, 100, 0.45);
}

.tree-node-row.is-directory .tree-node-icon .el-icon {
  color: #f0a020;
}

.tree-node-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: rgba(13, 48, 100, 0.8);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tree-node-size {
  font-size: 12px;
  color: rgba(13, 48, 100, 0.4);
  flex-shrink: 0;
  margin-right: 4px;
}

.tree-node-download {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: rgba(13, 48, 100, 0.3);
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.15s;
}

.tree-node-download:hover {
  background: rgba(13, 48, 100, 0.06);
  color: #2563eb;
}

.tree-node-download:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.tree-node-download:disabled:hover {
  background: transparent;
  color: rgba(13, 48, 100, 0.3);
}

.tree-node-download .el-icon {
  font-size: 15px;
}

.tree-node-spinner {
  animation: tree-node-spin 1s linear infinite;
}

@keyframes tree-node-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.tree-node-children {
  overflow: hidden;
}

.tree-node-empty {
  padding: 6px 12px;
  font-size: 12px;
  color: rgba(13, 48, 100, 0.3);
  font-style: italic;
}
</style>
