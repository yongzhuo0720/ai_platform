<template>
  <Transition name="kb-preview-fade">
    <div v-if="visible" class="kb-preview-overlay" @click.self="handleClose">
      <div class="kb-preview-dialog" :class="{ 'kb-preview-loading': loading }">
        <!-- 顶部工具栏 -->
        <div class="kb-preview-header">
          <div class="kb-preview-title-wrap">
            <div class="kb-preview-icon" :style="{ background: typeInfo.bg, color: typeInfo.color, borderColor: typeInfo.color + '40' }">
              <span class="kb-preview-icon-label">{{ typeInfo.label }}</span>
            </div>
            <div class="kb-preview-title-text">
              <h3 class="kb-preview-title">{{ document?.name || '文档预览' }}</h3>
              <div class="kb-preview-subtitle">
                <span>{{ typeInfo.label }} 文档</span>
                <span v-if="document?.tokens" class="kb-preview-divider">|</span>
                <span v-if="document?.tokens">{{ formatSize(document.tokens) }} tokens</span>
              </div>
            </div>
          </div>
          <div class="kb-preview-header-actions">
            <button
              v-if="previewMode !== 'rendered'"
              class="kb-preview-action-btn"
              title="切换分段 / 全文视图"
              @click="togglePreviewMode"
            >
              <el-icon><Files /></el-icon>
              <span>{{ previewMode === 'raw' ? '查看分段' : '查看原文' }}</span>
            </button>
            <button class="kb-preview-action-btn kb-preview-close" title="关闭" @click="handleClose">
              <el-icon><Close /></el-icon>
            </button>
          </div>
        </div>

        <!-- 内容区 -->
        <div class="kb-preview-body" ref="bodyRef">
          <!-- 加载中 -->
          <div v-if="isLoading" class="kb-preview-state kb-preview-loading-state">
            <div class="kb-preview-spinner"></div>
            <p>正在加载文档内容…</p>
          </div>

          <!-- 错误 -->
          <div v-else-if="showError" class="kb-preview-state kb-preview-error-state">
            <el-icon class="kb-preview-state-icon"><WarningFilled /></el-icon>
            <h4>无法预览该文档</h4>
            <p class="kb-preview-error-text">{{ combinedError }}</p>
          </div>

          <!-- PDF：使用 iframe 直接嵌入 PDF.js / 原生预览 -->
          <template v-else-if="ext === 'pdf' && downloadUrl && previewMode === 'rendered'">
            <iframe
              :src="pdfPreviewUrl"
              class="kb-preview-iframe"
              :title="document?.name"
            ></iframe>
          </template>

          <!-- 图片：内嵌展示 -->
          <template v-else-if="isImage && downloadUrl && previewMode === 'rendered'">
            <div class="kb-preview-image-wrap">
              <img :src="downloadUrl" :alt="document?.name" class="kb-preview-image" />
            </div>
          </template>

          <!-- 纯文本 / Markdown：渲染 markdown -->
          <template v-else-if="isTextLike && rawText && previewMode === 'rendered'">
            <div class="kb-preview-text kb-markdown" v-html="renderedText"></div>
          </template>

          <!-- 分段渲染（适用于 docx / pptx 等无法直接解析格式的文档） -->
          <template v-else-if="chunks && chunks.length > 0 && previewMode === 'rendered'">
            <div class="kb-preview-chunks">
              <div
                v-for="(chunk, index) in chunks"
                :key="chunk.id"
                class="kb-preview-chunk"
              >
                <div class="kb-preview-chunk-header">
                  <span class="kb-preview-chunk-index">#{{ index + 1 }}</span>
                  <span v-if="chunk.hit_count" class="kb-preview-chunk-hits">被引用 {{ chunk.hit_count }} 次</span>
                </div>
                <div
                  class="kb-preview-chunk-content kb-markdown"
                  v-html="renderChunkContent(chunk.content)"
                ></div>
              </div>
            </div>
          </template>

          <!-- 二进制文档（docx/pptx/xlsx 等）：显示提示 -->
          <template v-else-if="isBinaryDoc && downloadUrl">
            <div class="kb-preview-state kb-preview-binary-state">
              <div class="kb-preview-binary-icon" :style="{ background: typeInfo.bg, color: typeInfo.color }">
                <span class="kb-preview-binary-label">{{ typeInfo.label }}</span>
              </div>
              <h4>该格式暂不支持在线预览</h4>
              <p>{{ typeInfo.label }} 为二进制文档格式，当前暂不支持在线预览。</p>
            </div>
          </template>

          <!-- 兜底：空状态 -->
          <div v-else class="kb-preview-state kb-preview-empty-state">
            <el-icon class="kb-preview-state-icon"><Document /></el-icon>
            <h4>暂无内容可预览</h4>
            <p v-if="isBinaryDoc">该文档为二进制格式，Dify 暂未生成可显示的分段内容。</p>
            <p v-else>该文档暂无可显示的文本或分段内容</p>
          </div>
        </div>
      </div>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import { Close, Document, Files, WarningFilled } from '@element-plus/icons-vue'
import { renderMarkdown } from '@/utils/markdown'
import { getKnowledgeFileConfig, gatewayAuthHeaders } from '@/stores/difyConfig'
import type { KnowledgeBaseDocument, KnowledgeBaseChunk } from '@/types/knowledge'

const props = defineProps<{
  visible: boolean
  document: KnowledgeBaseDocument | null
  datasetId: string
  /**
   * 文档分段（可选）。提供后将用于 docx / pptx 等无法直接解析格式的文档分段渲染。
   */
  chunks?: KnowledgeBaseChunk[] | null
  /**
   * 是否正在加载外部资源
   */
  loading?: boolean
  /**
   * 错误信息（来自父组件，已格式化好）
   */
  errorMessage?: string | null
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'load-text', doc: KnowledgeBaseDocument): void
}>()

const previewMode = ref<'rendered' | 'raw'>('rendered')
const rawText = ref<string>('')
const bodyRef = ref<HTMLElement | null>(null)
// ============== 内部加载/错误状态 ==============
// Dialog 自身的异步加载（fetchDownloadUrl / loadTextContent）期间用此状态展示 loading，
// 与父组件传入的 loading 合并后统一控制 UI。
const internalLoading = ref(false)
const internalError = ref<string | null>(null)

// ============== 文件类型识别 ==============
const ext = computed(() => {
  const name = props.document?.name || ''
  return name.split('.').pop()?.toLowerCase() || ''
})

const isTextLike = computed(() => ['md', 'markdown', 'txt', 'log', 'json', 'csv', 'xml', 'html', 'htm'].includes(ext.value))
const isImage = computed(() => ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp'].includes(ext.value))
const isPdf = computed(() => ext.value === 'pdf')
const isBinaryDoc = computed(() => ['doc', 'docx', 'ppt', 'pptx', 'xls', 'xlsx'].includes(ext.value))

const typeInfo = computed(() => {
  const map: Record<string, { label: string; color: string; bg: string }> = {
    pdf: { label: 'PDF', color: '#ff4d4f', bg: 'rgba(255, 77, 79, 0.12)' },
    doc: { label: 'DOC', color: '#2f6bff', bg: 'rgba(47, 107, 255, 0.12)' },
    docx: { label: 'DOCX', color: '#2f6bff', bg: 'rgba(47, 107, 255, 0.12)' },
    ppt: { label: 'PPT', color: '#e66e00', bg: 'rgba(230, 110, 0, 0.12)' },
    pptx: { label: 'PPTX', color: '#e66e00', bg: 'rgba(230, 110, 0, 0.12)' },
    xls: { label: 'XLS', color: '#1a9f5b', bg: 'rgba(26, 159, 91, 0.12)' },
    xlsx: { label: 'XLSX', color: '#1a9f5b', bg: 'rgba(26, 159, 91, 0.12)' },
    md: { label: 'MD', color: '#8b5cf6', bg: 'rgba(139, 92, 246, 0.12)' },
    txt: { label: 'TXT', color: '#6b7280', bg: 'rgba(107, 114, 128, 0.12)' },
  }
  return map[ext.value] || { label: (ext.value || 'FILE').toUpperCase(), color: '#7c93ff', bg: 'rgba(77, 166, 255, 0.12)' }
})

// ============== 预览 URL ==============
/**
 * 文档下载 / 预览走 Dify 的 datasets 接口，因此用「知识库文件」模块的配置。
 *
 * 原先这里硬编码了 Dify 地址与 dataset token，属于凭据泄露，已改为配置下发。
 *
 * 注意要在「调用时」取而不是在 setup 阶段固化成常量 ——
 * Dify 配置是异步加载的，组件挂载时可能还没就绪。
 */
const getDifyFileConfig = () => getKnowledgeFileConfig()

/** 直接访问 Dify 签名下载链接（缓存到本地） */
const downloadUrl = ref<string>('')

async function fetchDownloadUrl() {
  if (!props.document || !props.datasetId) return
  try {
    const { apiUrl, apiKey } = getDifyFileConfig()
    if (!apiUrl || !apiKey) {
      throw new Error('未配置「知识库文件」模块的 Dify 地址或密钥')
    }
    const res = await fetch(
      `${apiUrl}/datasets/${props.datasetId}/documents/${props.document.id}/download`,
      {
        method: 'GET',
        headers: {
          Authorization: `Bearer ${apiKey}`,
          ...gatewayAuthHeaders(),
        },
      }
    )
    if (!res.ok) {
      throw new Error(`获取下载链接失败 (${res.status})`)
    }
    const data = await res.json() as { url?: string }
    if (!data.url) {
      throw new Error('未返回下载链接')
    }
    downloadUrl.value = data.url
  } catch (err) {
    downloadUrl.value = ''
    throw err
  }
}

// ============== 组合加载/错误/内容状态 ==============
// 合并父组件 loading 与 Dialog 内部 loading，确保任何异步阶段都显示加载态
const isLoading = computed(() => props.loading || internalLoading.value)
// 判断当前是否已有可渲染的内容（用于决定是否显示错误/空状态）
const hasContent = computed(() => Boolean(
  (ext.value === 'pdf' && downloadUrl.value) ||
  (isImage.value && downloadUrl.value) ||
  (isTextLike.value && rawText.value) ||
  (Array.isArray(props.chunks) && props.chunks.length > 0) ||
  (isBinaryDoc.value && downloadUrl.value)
))
// 合并父组件错误信息与 Dialog 内部错误
const combinedError = computed(() => props.errorMessage || internalError.value || null)
// 仅在加载完成、确实无内容、且存在错误信息时才展示错误态
const showError = computed(() => !isLoading.value && Boolean(combinedError.value) && !hasContent.value)

// PDF：使用 #toolbar=0 隐藏 Dify 自带的下载按钮，添加沉浸式预览参数
const pdfPreviewUrl = computed(() => {
  if (!downloadUrl.value) return ''
  return `${downloadUrl.value}#toolbar=1&navpanes=0&scrollbar=1`
})

// ============== 渲染逻辑 ==============
/** 将 Markdown 中的相对路径图片 URL 尝试解析为绝对路径 */
function resolveImageUrls(html: string): string {
  if (!html) return html
  const { apiUrl } = getDifyFileConfig()
  // 匹配 src="..." 或 src='...' 中不以 http/https/data 开头的相对路径
  return html.replace(
    /(<img[^>]*\ssrc\s*=\s*["'])(?!https?:\/\/|data:)([^"']+)(["'])/gi,
    (match, prefix, path, suffix) => {
      // 尝试用 Dify API 的 file 接口解析
      if (path.startsWith('/')) {
        // 绝对路径：拼接 Dify 基础地址
        return `${prefix}${apiUrl}${path}${suffix}`
      }
      // 相对路径：尝试拼接
      if (path.startsWith('files/') || path.startsWith('uploads/')) {
        return `${prefix}${apiUrl}/${path}${suffix}`
      }
      return match
    }
  )
}

const renderedText = computed(() => resolveImageUrls(renderMarkdown(rawText.value || '')))

function renderChunkContent(content?: string | null) {
  if (!content) return '<p class="kb-chunk-empty">(该分段暂无内容)</p>'
  return resolveImageUrls(renderMarkdown(content))
}

function formatSize(tokens: number) {
  if (tokens > 10000) return `${(tokens / 1000).toFixed(1)}K`
  return String(tokens)
}

// ============== 切换模式 ==============
function togglePreviewMode() {
  previewMode.value = previewMode.value === 'rendered' ? 'raw' : 'rendered'
}

function handleClose() {
  emit('update:visible', false)
}

// 加载纯文本（md / txt）供 markdown 渲染
// 注意：此处 fetch 签名 URL 属跨域请求，可能被 CORS 拦截；
// 失败时不抛出，仅记录内部错误，由父组件加载的 chunks 作为 fallback。
async function loadTextContent() {
  if (!props.document || !props.datasetId) return
  if (!isTextLike.value) return
  if (rawText.value) return
  try {
    if (!downloadUrl.value) {
      await fetchDownloadUrl()
    }
    if (!downloadUrl.value) return
    const res = await fetch(downloadUrl.value)
    if (!res.ok) throw new Error(`下载文件失败 (${res.status})`)
    rawText.value = await res.text()
  } catch (err) {
    console.warn('[DocumentPreviewDialog] 加载文本失败:', err)
    rawText.value = ''
    internalError.value = '无法加载文档原文内容'
  }
}

// ============== 监听 ==============
watch(
  () => [props.visible, props.document?.id, props.datasetId],
  async ([vis, docId, dsId]) => {
    if (vis && docId && dsId) {
      previewMode.value = 'rendered'
      rawText.value = ''
      downloadUrl.value = ''
      internalLoading.value = true
      internalError.value = null
      try {
        // 先获取签名 URL（PDF / 图片 / 二进制文档都需要它）
        if (isPdf.value || isImage.value || isBinaryDoc.value) {
          await fetchDownloadUrl()
        } else if (isTextLike.value) {
          await loadTextContent()
        }
      } catch (err) {
        console.warn('[DocumentPreviewDialog] 加载失败:', err)
        internalError.value = err instanceof Error ? err.message : '加载文档失败'
      } finally {
        internalLoading.value = false
      }
      // 触发父组件的加载回调（用于按需加载分段，作为文本预览的 fallback）
      if (props.document) {
        emit('load-text', props.document)
      }
      await nextTick()
      if (bodyRef.value) {
        bodyRef.value.scrollTop = 0
      }
    } else if (!vis) {
      // 关闭时清空缓存
      downloadUrl.value = ''
      rawText.value = ''
      internalLoading.value = false
      internalError.value = null
    }
  },
  { immediate: true }
)
</script>

<style scoped lang="less">
.kb-preview-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 5, 25, 0.7);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2500;
  padding: 32px;
}

.kb-preview-fade-enter-active,
.kb-preview-fade-leave-active {
  transition: opacity 0.25s ease;
}

.kb-preview-fade-enter-from,
.kb-preview-fade-leave-to {
  opacity: 0;
}

.kb-preview-dialog {
  width: 100%;
  max-width: 1100px;
  height: 80vh;
  max-height: 820px;
  background: var(--kb-preview-bg, rgba(16, 30, 80, 0.92));
  backdrop-filter: blur(24px) saturate(180%);
  -webkit-backdrop-filter: blur(24px) saturate(180%);
  border: 1px solid var(--kb-preview-border, rgba(100, 150, 255, 0.25));
  border-radius: var(--radius-lg, 20px);
  box-shadow: 0 20px 60px rgba(0, 20, 80, 0.6);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  animation: kb-preview-slide-up 0.3s ease;
}

@keyframes kb-preview-slide-up {
  from {
    opacity: 0;
    transform: translateY(20px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.kb-preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  background: var(--kb-preview-header-bg, rgba(16, 30, 80, 0.65));
  border-bottom: 1px solid var(--kb-preview-divider, rgba(100, 150, 255, 0.15));
  flex-shrink: 0;
}

.kb-preview-title-wrap {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1;
}

.kb-preview-icon {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid;
  flex-shrink: 0;
}

.kb-preview-icon-label {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.05em;
}

.kb-preview-title-text {
  min-width: 0;
  flex: 1;
}

.kb-preview-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--kb-preview-title-color, rgba(255, 255, 255, 0.95));
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.kb-preview-subtitle {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
  font-size: 12px;
  color: var(--kb-preview-subtitle-color, rgba(255, 255, 255, 0.55));
}

.kb-preview-divider {
  color: var(--kb-preview-divider-color, rgba(255, 255, 255, 0.25));
}

.kb-preview-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.kb-preview-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 32px;
  padding: 0 12px;
  background: var(--kb-preview-action-bg, rgba(255, 255, 255, 0.06));
  border: 1px solid var(--kb-preview-action-border, rgba(100, 150, 255, 0.2));
  border-radius: 6px;
  color: var(--kb-preview-action-color, rgba(255, 255, 255, 0.8));
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;

  .el-icon {
    font-size: 14px;
  }

  &:hover {
    background: rgba(77, 166, 255, 0.18);
    border-color: rgba(77, 166, 255, 0.4);
    color: #7c93ff;
  }
}

.kb-preview-close:hover {
  background: rgba(255, 90, 90, 0.15);
  border-color: rgba(255, 90, 90, 0.35);
  color: #ff7a7a;
}

.kb-preview-body {
  flex: 1;
  overflow: auto;
  position: relative;
  background: var(--kb-preview-body-bg, rgba(10, 20, 55, 0.3));
}

.kb-preview-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  height: 100%;
  padding: 40px 20px;
  text-align: center;
  color: var(--kb-preview-state-color, rgba(255, 255, 255, 0.7));
}

.kb-preview-state-icon {
  font-size: 48px;
  color: rgba(100, 150, 255, 0.5);
}

.kb-preview-state h4 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--kb-preview-title-color, rgba(255, 255, 255, 0.9));
}

.kb-preview-state p {
  margin: 0;
  font-size: 13px;
  color: var(--kb-preview-subtitle-color, rgba(255, 255, 255, 0.6));
}

.kb-preview-error-text {
  max-width: 480px;
  word-break: break-word;
}

.kb-preview-binary-icon {
  width: 64px;
  height: 64px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid;
  margin-bottom: 8px;
}

.kb-preview-binary-label {
  font-size: 16px;
  font-weight: 800;
  letter-spacing: 0.06em;
}

.kb-preview-spinner {
  width: 36px;
  height: 36px;
  border: 3px solid rgba(100, 150, 255, 0.2);
  border-top-color: #7c93ff;
  border-radius: 50%;
  animation: kb-preview-spin 0.8s linear infinite;
}

@keyframes kb-preview-spin {
  to {
    transform: rotate(360deg);
  }
}

.kb-preview-iframe {
  width: 100%;
  height: 100%;
  border: 0;
  background: #ffffff;
}

.kb-preview-image-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: 24px;
}

.kb-preview-image {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
  background: #ffffff;
}

.kb-preview-text {
  padding: 28px 36px;
  font-size: 14px;
  line-height: 1.75;
  color: var(--kb-preview-text-color, rgba(255, 255, 255, 0.92));
  max-width: 920px;
  margin: 0 auto;
}

.kb-preview-chunks {
  padding: 24px 28px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 980px;
  margin: 0 auto;
}

.kb-preview-chunk {
  background: var(--kb-preview-chunk-bg, rgba(16, 30, 80, 0.4));
  border: 1px solid var(--kb-preview-chunk-border, rgba(100, 150, 255, 0.15));
  border-radius: 10px;
  padding: 16px 18px;
}

.kb-preview-chunk-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--kb-preview-subtitle-color, rgba(255, 255, 255, 0.6));
}

.kb-preview-chunk-index {
  background: rgba(77, 166, 255, 0.18);
  color: #7c93ff;
  padding: 2px 8px;
  border-radius: 999px;
  font-weight: 600;
  font-size: 11px;
}

.kb-preview-chunk-hits {
  color: var(--accent, #7c93ff);
}

.kb-preview-chunk-content {
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--kb-preview-text-color, rgba(255, 255, 255, 0.9));
}

.kb-markdown {
  word-break: break-word;
}

.kb-markdown :deep(p) {
  margin: 0 0 12px;
}

.kb-markdown :deep(h1),
.kb-markdown :deep(h2),
.kb-markdown :deep(h3),
.kb-markdown :deep(h4) {
  margin: 18px 0 10px;
  font-weight: 600;
  color: var(--kb-preview-text-color, rgba(255, 255, 255, 0.95));
}

.kb-markdown :deep(pre) {
  background: rgba(0, 10, 40, 0.55);
  border: 1px solid rgba(100, 150, 255, 0.18);
  border-radius: 8px;
  padding: 12px 14px;
  overflow-x: auto;
  font-size: 12.5px;
  line-height: 1.6;
  margin: 12px 0;
}

.kb-markdown :deep(code) {
  font-family: 'Fira Code', 'Cascadia Code', Consolas, Menlo, monospace;
  font-size: 0.92em;
}

.kb-markdown :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 12px 0;
  font-size: 12.5px;
}

.kb-markdown :deep(table) th,
.kb-markdown :deep(table) td {
  border: 1px solid rgba(100, 150, 255, 0.18);
  padding: 6px 10px;
}

.kb-markdown :deep(table) th {
  background: rgba(77, 166, 255, 0.12);
  font-weight: 600;
}

.kb-markdown :deep(img) {
  max-width: 100%;
  border-radius: 6px;
  margin: 8px 0;
}

.kb-markdown :deep(blockquote) {
  border-left: 3px solid rgba(77, 166, 255, 0.5);
  margin: 12px 0;
  padding: 6px 14px;
  background: rgba(77, 166, 255, 0.08);
  color: var(--kb-preview-subtitle-color, rgba(255, 255, 255, 0.75));
  border-radius: 0 6px 6px 0;
}

/* ============== 浅色主题适配 ============== */
html[data-theme="light"] {
  .kb-preview-overlay {
    background: rgba(13, 48, 100, 0.25);
  }

  .kb-preview-dialog {
    --kb-preview-bg: rgba(255, 255, 255, 0.95);
    --kb-preview-border: rgba(13, 48, 100, 0.18);
    --kb-preview-header-bg: rgba(255, 255, 255, 0.85);
    --kb-preview-divider: rgba(13, 48, 100, 0.1);
    --kb-preview-title-color: #0d3064;
    --kb-preview-subtitle-color: #4a6ba8;
    --kb-preview-divider-color: rgba(13, 48, 100, 0.2);
    --kb-preview-action-bg: rgba(13, 48, 100, 0.05);
    --kb-preview-action-border: rgba(13, 48, 100, 0.12);
    --kb-preview-action-color: #1f4b8a;
    --kb-preview-body-bg: #ffffff;
    --kb-preview-state-color: #1f4b8a;
    --kb-preview-text-color: #0d3064;
    --kb-preview-chunk-bg: #f7faff;
    --kb-preview-chunk-border: rgba(13, 48, 100, 0.1);
    box-shadow: 0 20px 60px rgba(13, 48, 100, 0.2);
  }

  .kb-preview-action-btn:hover {
    background: rgba(0, 102, 255, 0.1);
    border-color: #3d5bf5;
    color: #3d5bf5;
  }

  .kb-preview-close:hover {
    background: rgba(220, 38, 38, 0.1);
    border-color: rgba(220, 38, 38, 0.35);
    color: #dc2626;
  }

  .kb-markdown :deep(pre) {
    background: rgba(13, 48, 100, 0.04);
    border-color: rgba(13, 48, 100, 0.12);
  }

  .kb-markdown :deep(table) th,
  .kb-markdown :deep(table) td {
    border-color: rgba(13, 48, 100, 0.15);
  }

  .kb-markdown :deep(table) th {
    background: rgba(0, 102, 255, 0.08);
  }

  .kb-markdown :deep(blockquote) {
    border-left-color: rgba(0, 102, 255, 0.5);
    background: rgba(0, 102, 255, 0.06);
    color: #1f4b8a;
  }
}
</style>
