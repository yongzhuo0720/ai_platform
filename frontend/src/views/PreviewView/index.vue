<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { modelsMngApi } from '@/api/modelsMng'
import { datasetsMngApi } from '@/api/datasetsMng'

const route = useRoute()
const router = useRouter()
const iframeSrc = ref('about:blank')
const loading = ref(true)
const error = ref('')

const type = computed(() => route.query.type as string)
const title = computed(() => (type.value === 'datasets' ? '数据集下载预览' : '模型下载预览'))

onMounted(async () => {
  const iid = route.query.iid as string

  if (!iid || !type.value) {
    error.value = '参数错误'
    loading.value = false
    return
  }

  try {
    const api = type.value === 'datasets' ? datasetsMngApi : modelsMngApi
    const { data: res } = await api.getDownloadUrl({ data: { iid } })
    if (res.resp_code === 0 && res.datas) {
      iframeSrc.value = res.datas
    } else {
      error.value = res.resp_msg || '获取链接失败'
    }
  } catch (e) {
    error.value = '请求失败'
  }
  loading.value = false
})

const goBack = () => {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.replace(type.value === 'datasets' ? '/datasets' : '/models')
  }
}
</script>

<template>
  <div class="preview-container">
    <div class="preview-header">
      <button type="button" class="preview-back" @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
        <span>返回</span>
      </button>
      <h3 class="preview-title">{{ title }}</h3>
      <div class="preview-spacer"></div>
    </div>
    <div class="preview-body">
      <div v-if="loading" class="preview-overlay">
        <p>正在加载...</p>
      </div>
      <div v-else-if="error" class="preview-overlay preview-error">
        <p>{{ error }}</p>
      </div>
      <iframe
        v-else
        :src="iframeSrc"
        class="preview-frame"
        title="预览页面"
        sandbox="allow-scripts allow-same-origin allow-forms allow-popups"
      />
    </div>
  </div>
</template>

<style scoped>
.preview-container {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background-color: #ffffff;
  display: flex;
  flex-direction: column;
  z-index: 100;
}

.preview-header {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
  height: 56px;
  padding: 0 16px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.08);
  background: #ffffff;
}

.preview-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border-radius: 8px;
  background: transparent;
  border: 1px solid rgba(0, 0, 0, 0.12);
  color: #1f2937;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.preview-back:hover {
  border-color: #3d5bf5;
  color: #3d5bf5;
  background: rgba(0, 102, 255, 0.06);
}

.preview-back .el-icon {
  font-size: 16px;
}

.preview-title {
  flex: 1;
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #111827;
  text-align: center;
  font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
}

.preview-spacer {
  width: 80px;
}

.preview-body {
  flex: 1;
  min-height: 0;
  position: relative;
}

.preview-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  color: #666;
}

.preview-error {
  color: #f56c6c;
}

.preview-frame {
  width: 100%;
  height: 100%;
  border: none;
  display: block;
}
</style>
