<script setup lang="ts">
import { useIndex } from './index'
import { isConfirmCancelled } from '@/utils/confirm'
import { debug } from '@/utils/debug'
import {
  Share,
  Plus,
  ArrowDown,
  ArrowRight,
  Search,
  Sort,
  DocumentAdd,
  User,
  EditPen,
  Delete,
  Histogram,
  Document,
  Close,
  Fold,
  MoreFilled,
  Menu,
  Grid,
  PriceTag,
} from '@element-plus/icons-vue'
import { ref, computed, nextTick, watch, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import {
  knowledgeChatStreaming,
  retrieveFromDataset,
  getAppParameters,
  getConversationHistory,
  getSuggestedMessages,
} from '@/api/knowledgeChat'
import type { RetrievalResult, KnowledgeChatFile } from '@/api/knowledgeChat'
import { modelsConfigApi } from '@/api/modelsConfig'
import { uploadFileToDify } from '@/api/request'
import { getCurrentUserId } from '@/utils/user'
import { markKbConversation } from '@/utils/conversationSource'
import { parseChatError } from '@/utils/chatError'
import type {
  AppParametersResponse,
  NormalizedUserInputField,
  UserInputFormItem,
} from '@/types/chat'
import { ElMessage, ElMessageBox, ElPopover } from 'element-plus'
import type { FormInstance } from 'element-plus'
import { documentApi } from '@/api/document'
import { chunkApi } from '@/api/chunk'
import type { KnowledgeBaseDocument } from '@/types/knowledge'
import TagManageDialog from '@/components/TagManageDialog/index.vue'
import TagDropdown from '@/components/TagDropdown/index.vue'
import TagFilterDropdown from '@/components/TagFilterDropdown/index.vue'
import DocumentPreviewDialog from '@/components/DocumentPreviewDialog/index.vue'
import KnowledgeBaseSquareView from '@/views/KnowledgeBaseSquareView/index.vue'
import KbChatPanel from '@/components/KbChatPanel/index.vue'

const {
  isLoading,
  error,
  fetchKnowledgeBases,
  knowledgeFileUnavailable,
  allTags,
  filteredKnowledgeBases,
  activeMenu,
  sharedKnowledgeBases,
  selectedSharedKb,
  goToSquare,
  switchMenu,
  handleSelectSharedKb,
  refreshTagBind,
  getAllTags,
  mngDialogVisible,
  mngDialogTitle,
  isEditingKb,
  mngFormData,
  mngFormRules,
  isAdmin,
  treeRef,
  deptTree,
  roleList,
  defaultProps,
  embeddingModelOptions,
  openMngCreateDialog,
  openMngEditDialog,
  handleMngSubmit,
  handleMngDelete,
  handleDeptCheckChange,
  joinedKnowledgeBases,
  fetchJoinedKnowledgeBases,
  handleSelectJoinedKb,
  memberList,
  memberLoading,
  currentMemberDialogKbId,
  fetchMembers,
  fetchJoinedKbIds,
  joinKb,
  leaveKb,
  removeMember,
  selectedKbId,
  showSquareView,
  hideSquareView,
  squareDetailKbId,
  myKbIds,
} = useIndex()

const handleEmbeddingModelChange = (model: string) => {
  const selected = embeddingModelOptions.value.find((item) => item.model === model)
  mngFormData.value.embedding_model_provider = selected ? selected.provider : ''
}

// 弹窗里的名称/向量模型规则此前只挂载未校验：确定按钮直接调用 handleMngSubmit，
// 空名称也会真正创建知识库。这里补上表单校验再提交。
const mngFormRef = ref<FormInstance>()
const submitMngForm = () => {
  mngFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) {
      ElMessage.error('请填写完整的信息')
      return
    }
    await handleMngSubmit()
  })
}

// Sidebar state
const createdExpanded = ref(true)
const joinedExpanded = ref(false)
const sharedExpanded = ref(true)
const sidebarCollapsed = ref(false)

const tagManageVisible = ref(false)

// ============== 知识库成员弹窗 ==============
const showMemberDialog = ref(false)
const showJoinedMemberDialog = ref(false)
const memberSearchQuery = ref('')

const filteredMembers = computed(() => {
  if (!memberSearchQuery.value) return memberList.value
  const query = memberSearchQuery.value.toLowerCase()
  return memberList.value.filter(
    m => m.userName.toLowerCase().includes(query) || m.userId.toLowerCase().includes(query)
  )
})

function openMemberDialog(kbId: string) {
  memberSearchQuery.value = ''
  currentMemberDialogKbId.value = kbId
  showMemberDialog.value = true
}

function openJoinedMemberDialog(kbId: string) {
  memberSearchQuery.value = ''
  currentMemberDialogKbId.value = kbId
  showJoinedMemberDialog.value = true
}


// 监听详情页加入知识库事件，刷新订阅列表
function onKbJoined() {
  fetchJoinedKnowledgeBases()
  fetchJoinedKbIds()
}

onMounted(() => {
  window.addEventListener('kb-joined', onKbJoined)
  loadChatModels()
  loadAppParameters()
})

onUnmounted(() => {
  window.removeEventListener('kb-joined', onKbJoined)
  if (sharedMoreMenuOutsideHandler) {
    document.removeEventListener('click', sharedMoreMenuOutsideHandler)
    sharedMoreMenuOutsideHandler = null
  }
  /**
   * 中止在途的 SSE 流式请求。
   *
   * 之前这里只移除事件监听，没有中止请求：从知识库中心切走后
   * 流仍在后台跑，响应还会继续写 chatMessages，既浪费连接也有并发问题。
   * ChatView 也做了这一步，
   * 这里是全项目唯一漏掉的。
   */
  if (chatStopFn) {
    chatStopFn()
    chatStopFn = null
  }
})

// 选中共享知识库并切到共享视图
function selectSharedKb(kb: any) {
  handleSelectSharedKb(kb)
  squareDetailKbId.value = null
  squareDetailKbData.value = null
  hideSquareView()
  switchMenu('shared')
  // 选中时获取成员列表并同步加入人数
  fetchMembers(kb.id)
  // 选中时刷新该知识库的最新标签
  refreshTagBind(kb.id)
  // 切换知识库时重置会话上下文
  chatMessages.value = []
  chatInput.value = ''
}

function selectJoinedKb(kb: any) {
  handleSelectJoinedKb(kb)
  squareDetailKbId.value = null
  squareDetailKbData.value = null
  hideSquareView()
  switchMenu('personal')
  joinedDocViewMode.value = 'list'
  chatMessages.value = []
  chatInput.value = ''
}

// 共享知识库内容区搜索
const sharedSearchQuery = ref('')
const sharedSearchInputRef = ref<HTMLInputElement | null>(null)

function closeSharedSearch() {
  sharedSearchQuery.value = ''
}

function clearSharedSearch() {
  sharedSearchQuery.value = ''
  nextTick(() => {
    sharedSearchInputRef.value?.focus()
  })
}

// 侧边栏知识库搜索
const sidebarSearchQuery = ref('')
const sidebarSearchInputRef = ref<HTMLInputElement | null>(null)

// 全局搜索跳转带 ?keyword=：index.ts 负责筛选与选中，这里同步可见的搜索框
const route = useRoute()
const applyRouteKeyword = (value: unknown) => {
  if (typeof value !== 'string') return
  const keyword = value.trim()
  if (keyword) sidebarSearchQuery.value = keyword
}
applyRouteKeyword(route.query.keyword)
watch(() => route.query.keyword, applyRouteKeyword)

// 侧边栏标签筛选
const sidebarSelectedTag = ref<string[]>([])

const sidebarFilteredKnowledgeBases = computed(() => {
  let result = filteredKnowledgeBases.value
  if (sidebarSelectedTag.value.length > 0) {
    const selected = new Set(sidebarSelectedTag.value)
    result = result.filter((kb: any) =>
      kb.tagList?.some((tag: any) => selected.has(String(tag.iid)))
    )
  }
  if (!sidebarSearchQuery.value) return result
  const query = sidebarSearchQuery.value.toLowerCase()
  return result.filter((kb: any) =>
    kb.name.toLowerCase().includes(query) ||
    (kb.description && kb.description.toLowerCase().includes(query))
  )
})

const sidebarFilteredSharedKnowledgeBases = computed(() => {
  if (sidebarSelectedTag.value.length === 0) return sharedKnowledgeBases.value
  const selected = new Set(sidebarSelectedTag.value)
  return sharedKnowledgeBases.value.filter((kb: any) =>
    kb.tagList?.some((tag: any) => selected.has(String(tag.iid)))
  )
})

const sidebarFilteredJoinedKnowledgeBases = computed(() => {
  if (sidebarSelectedTag.value.length === 0) return joinedKnowledgeBases.value
  const selected = new Set(sidebarSelectedTag.value)
  return joinedKnowledgeBases.value.filter((kb: any) =>
    kb.tagList?.some((tag: any) => selected.has(String(tag.iid)))
  )
})

// 个人知识库内容区搜索
const personalSearchQuery = ref('')
const personalSearchInputRef = ref<HTMLInputElement | null>(null)

function closePersonalSearch() {
  personalSearchQuery.value = ''
}

function clearPersonalSearch() {
  personalSearchQuery.value = ''
  nextTick(() => {
    personalSearchInputRef.value?.focus()
  })
}

// 个人知识库文档视图：grid / list
const docViewMode = ref<'grid' | 'list'>('list')

function toggleDocViewMode() {
  docViewMode.value = docViewMode.value === 'grid' ? 'list' : 'grid'
}

// 订阅知识库文档视图：grid / list（独立状态，默认列表）
const joinedDocViewMode = ref<'grid' | 'list'>('list')

function toggleJoinedDocViewMode() {
  joinedDocViewMode.value = joinedDocViewMode.value === 'grid' ? 'list' : 'grid'
}

// 当前实际使用的文档视图模式（订阅知识库使用独立状态）
const currentDocViewMode = computed(() => {
  return isJoinedSelectedKb.value ? joinedDocViewMode.value : docViewMode.value
})

// 共享知识库文档视图：grid / list
const sharedDocViewMode = ref<'grid' | 'list'>('list')

function toggleSharedDocViewMode() {
  sharedDocViewMode.value = sharedDocViewMode.value === 'grid' ? 'list' : 'grid'
}

// 文档排序方式
type SortOrder = 'default' | 'name-asc' | 'name-desc' | 'date-asc' | 'date-desc'
const sortOrder = ref<SortOrder>('default')
const sharedSortOrder = ref<SortOrder>('default')

function cycleSortOrder(current: SortOrder): SortOrder {
  const order: SortOrder[] = ['default', 'name-asc', 'name-desc', 'date-asc', 'date-desc']
  const idx = order.indexOf(current)
  return order[(idx + 1) % order.length]
}

function cyclePersonalSort() {
  sortOrder.value = cycleSortOrder(sortOrder.value)
  ElMessage.info(`排序方式：${getSortLabel(sortOrder.value)}`)
}

function cycleSharedSort() {
  sharedSortOrder.value = cycleSortOrder(sharedSortOrder.value)
  ElMessage.info(`排序方式：${getSortLabel(sharedSortOrder.value)}`)
}

function getSortLabel(order: SortOrder): string {
  const labels: Record<SortOrder, string> = {
    'default': '默认',
    'name-asc': '名称升序',
    'name-desc': '名称降序',
    'date-asc': '日期升序',
    'date-desc': '日期降序',
  }
  return labels[order]
}

// 文档排序函数
function sortDocuments(docs: any[], order: SortOrder) {
  if (order === 'default') return docs
  const sorted = [...docs]
  switch (order) {
    case 'name-asc':
      return sorted.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'))
    case 'name-desc':
      return sorted.sort((a, b) => b.name.localeCompare(a.name, 'zh-CN'))
    case 'date-asc':
      return sorted.sort((a, b) => (a.updateTime || '').localeCompare(b.updateTime || ''))
    case 'date-desc':
      return sorted.sort((a, b) => (b.updateTime || '').localeCompare(a.updateTime || ''))
    default:
      return sorted
  }
}

// Create dialog state
const createDialogVisible = ref(false)
const createForm = ref({
  name: '',
  description: '',
  cover_image: '',
  scope: 'global',
  permissions: 'view_download' as 'view_download' | 'view_only',
})

// Selected KB for main content display
const documents = ref<KnowledgeBaseDocument[]>([])
const docsLoading = ref(false)

const selectedKb = computed(() => {
  if (!selectedKbId.value) return filteredKnowledgeBases.value[0] || null
  return filteredKnowledgeBases.value.find((kb: any) => kb.id === selectedKbId.value)
    || joinedKnowledgeBases.value.find((kb: any) => kb.id === selectedKbId.value)
    || null
})

const isJoinedSelectedKb = computed(() => {
  if (!selectedKbId.value) return false
  return !filteredKnowledgeBases.value.some((kb: any) => kb.id === selectedKbId.value)
    && joinedKnowledgeBases.value.some((kb: any) => kb.id === selectedKbId.value)
})

// 根据搜索关键字过滤并排序当前知识库文档
const displayedDocuments = computed(() => {
  let list = documents.value
  if (personalSearchQuery.value) {
    const query = personalSearchQuery.value.toLowerCase()
    list = list.filter(doc =>
      doc.name.toLowerCase().includes(query) ||
      (doc.type && doc.type.toLowerCase().includes(query))
    )
  }
  return sortDocuments(list, sortOrder.value)
})

const displayedSharedDocuments = computed(() => {
  let list = sharedDocuments.value
  if (sharedSearchQuery.value) {
    const query = sharedSearchQuery.value.toLowerCase()
    list = list.filter(doc =>
      doc.name.toLowerCase().includes(query) ||
      (doc.type && doc.type.toLowerCase().includes(query))
    )
  }
  return sortDocuments(list, sharedSortOrder.value)
})

// 共享知识库：更多下拉菜单
const sharedMoreMenuVisible = ref(false)
const sharedMoreMenuPos = ref({ top: 0, left: 0 })

let sharedMoreMenuOutsideHandler: ((e: MouseEvent) => void) | null = null

function openSharedMoreMenu(event: MouseEvent) {
  event.stopPropagation()
  const btn = event.currentTarget as HTMLElement
  const rect = btn.getBoundingClientRect()
  sharedMoreMenuPos.value = {
    top: rect.bottom + 8,
    left: rect.right - 160,
  }
  sharedMoreMenuVisible.value = true
  // 下一帧再绑定外部点击关闭，避免当前点击冒泡时立刻关闭
  nextTick(() => {
    if (sharedMoreMenuOutsideHandler) {
      document.removeEventListener('click', sharedMoreMenuOutsideHandler)
    }
    sharedMoreMenuOutsideHandler = (e: MouseEvent) => {
      const target = e.target as HTMLElement
      if (target.closest('.kb-shared-more-menu')) return
      closeSharedMoreMenu()
    }
    setTimeout(() => {
      document.addEventListener('click', sharedMoreMenuOutsideHandler!)
    }, 0)
  })
}

function closeSharedMoreMenu() {
  sharedMoreMenuVisible.value = false
  if (sharedMoreMenuOutsideHandler) {
    document.removeEventListener('click', sharedMoreMenuOutsideHandler)
    sharedMoreMenuOutsideHandler = null
  }
}

// 知识库更多菜单动作（个人/共享通用）
const sharedMoreActions = {
  editKb() {
    closeSharedMoreMenu()
    const kb = activeMenu.value === 'shared' ? selectedSharedKb.value : selectedKb.value
    if (!kb) return
    openMngEditDialog(kb)
  },
  deleteKb() {
    closeSharedMoreMenu()
    const kb = activeMenu.value === 'shared' ? selectedSharedKb.value : selectedKb.value
    if (!kb) return
    handleMngDelete(kb)
  },
}

function handleSharedMoreAction(action: keyof typeof sharedMoreActions) {
  sharedMoreActions[action]()
}

// 文件类型信息（图标颜色、背景色、扩展名标签）
function getFileTypeInfo(doc: KnowledgeBaseDocument) {
  const name = doc.name || ''
  const type = String(doc.type || '').toLowerCase()
  const ext = name.split('.').pop()?.toLowerCase() || ''
  // 当文件名没有扩展名时，ext === 整个 name（小写），此时优先用 type 字段
  const hasRealExt = ext && ext !== name.toLowerCase()
  const key = (hasRealExt ? ext : type) || ''
  const map: Record<string, { label: string; color: string; bg: string }> = {
    pdf: { label: 'PDF', color: '#ff4d4f', bg: 'rgba(255, 77, 79, 0.12)' },
    doc: { label: 'DOC', color: '#2f6bff', bg: 'rgba(47, 107, 255, 0.12)' },
    docx: { label: 'DOCX', color: '#2f6bff', bg: 'rgba(47, 107, 255, 0.12)' },
    word: { label: 'DOCX', color: '#2f6bff', bg: 'rgba(47, 107, 255, 0.12)' },
    xls: { label: 'XLS', color: '#1a9f5b', bg: 'rgba(26, 159, 91, 0.12)' },
    xlsx: { label: 'XLSX', color: '#1a9f5b', bg: 'rgba(26, 159, 91, 0.12)' },
    excel: { label: 'XLSX', color: '#1a9f5b', bg: 'rgba(26, 159, 91, 0.12)' },
    ppt: { label: 'PPT', color: '#e66e00', bg: 'rgba(230, 110, 0, 0.12)' },
    pptx: { label: 'PPTX', color: '#e66e00', bg: 'rgba(230, 110, 0, 0.12)' },
    txt: { label: 'TXT', color: '#6b7280', bg: 'rgba(107, 114, 128, 0.12)' },
    md: { label: 'MD', color: '#8b5cf6', bg: 'rgba(139, 92, 246, 0.12)' },
    markdown: { label: 'MD', color: '#8b5cf6', bg: 'rgba(139, 92, 246, 0.12)' },
    jpg: { label: 'JPG', color: '#f59e0b', bg: 'rgba(245, 158, 11, 0.12)' },
    jpeg: { label: 'JPEG', color: '#f59e0b', bg: 'rgba(245, 158, 11, 0.12)' },
    png: { label: 'PNG', color: '#06b6d4', bg: 'rgba(6, 182, 212, 0.12)' },
    gif: { label: 'GIF', color: '#8b5cf6', bg: 'rgba(139, 92, 246, 0.12)' },
  }
  if (map[key]) return map[key]
  // key 太长说明是整个文件名，兜底为 FILE
  if (key && key.length <= 6) {
    return { label: key.toUpperCase(), color: '#7c93ff', bg: 'rgba(77, 166, 255, 0.12)' }
  }
  return { label: 'FILE', color: '#7c93ff', bg: 'rgba(77, 166, 255, 0.12)' }
}

// ============== 文档预览对话框状态 ==============
const previewVisible = ref(false)
const previewDocumentInfo = ref<KnowledgeBaseDocument | null>(null)
const previewDatasetId = ref<string>('')
const deletingDocumentId = ref('')

// 预览时按需加载分段（用于 docx / pptx 等无法直接解析的文档）
const previewChunks = ref<any[]>([])
const previewLoading = ref(false)
const previewError = ref<string | null>(null)

async function handlePreviewLoadText(doc: KnowledgeBaseDocument) {
  const datasetId = previewDatasetId.value
  if (!datasetId) return
  const ext = (doc.name || '').split('.').pop()?.toLowerCase() || ''
  // 仅对 PDF 和图片格式跳过分段加载（它们使用 iframe / 原生 <img> 渲染，无需分段）
  const skipChunksExts = ['pdf', 'png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp']
  if (skipChunksExts.includes(ext)) {
    return
  }

  previewLoading.value = true
  previewError.value = null
  previewChunks.value = []
  try {
    const result = await chunkApi.getChunks(datasetId, doc.id, 1, 100)
    previewChunks.value = (result.data || []).filter((c: any) => c.enabled !== false)
  } catch (err) {
    console.warn('[KnowledgeBaseView] 加载分段失败:', err)
    previewError.value = '无法加载该文档的解析内容，请尝试下载或在新窗口中打开。'
  } finally {
    previewLoading.value = false
  }
}

/**
 * 触发文档预览：弹出统一预览对话框
 * 支持 PDF / 图片 / Markdown / 文本 / 其它（通过分段或下载方式兜底）
 */
async function previewDocument(doc: KnowledgeBaseDocument) {
  const currentKb = getCurrentChatKb()
  if (!currentKb) {
    ElMessage.warning('请先选择知识库')
    return
  }
  previewDocumentInfo.value = doc
  previewDatasetId.value = currentKb.id
  previewChunks.value = []
  previewError.value = null
  previewVisible.value = true
  // 立即按需加载分段（由父组件统一控制，避免 Dialog 内部重复加载）
  await handlePreviewLoadText(doc)
}

async function handleDeleteDocument(doc: KnowledgeBaseDocument) {
  const kb = selectedKb.value
  if (!kb?.id) {
    ElMessage.warning('请先选择知识库')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确认删除文档“${doc.name}”吗？删除后将无法恢复。`,
      '删除文档',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
      }
    )

    deletingDocumentId.value = doc.id
    await documentApi.deleteDocument(kb.id, doc.id)

    if (previewVisible.value && previewDocumentInfo.value?.id === doc.id) {
      previewVisible.value = false
      previewDocumentInfo.value = null
      previewChunks.value = []
      previewError.value = null
    }

    await loadDocuments(kb.id)
    ElMessage.success('文档已删除')
  } catch (err: any) {
    if (!isConfirmCancelled(err)) {
      console.error('删除文档失败:', err)
      ElMessage.error(`删除失败：${err instanceof Error ? err.message : '未知错误'}`)
    }
  } finally {
    deletingDocumentId.value = ''
  }
}

// 添加文档：隐藏文件输入
const fileInputRef = ref<HTMLInputElement | null>(null)
const uploadTargetKb = ref<any>(null)

function handleUploadClick(kb: any) {
  if (!kb) {
    ElMessage.warning('请先选择知识库')
    return
  }
  uploadTargetKb.value = kb
  nextTick(() => {
    fileInputRef.value?.click()
  })
}

async function handleFileUpload(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file || !uploadTargetKb.value) return

  const kb = uploadTargetKb.value
  try {
    ElMessage.info(`正在上传：${file.name}`)
    await documentApi.createByFile(kb.id, file)
    ElMessage.success('文档上传成功')
    // 刷新文档列表
    if (activeMenu.value === 'shared' && selectedSharedKb.value?.id === kb.id) {
      loadSharedDocuments(kb.id)
    } else if (selectedKb.value?.id === kb.id) {
      loadDocuments(kb.id)
    }
  } catch (err) {
    console.error('上传文档失败:', err)
    ElMessage.error(`上传失败：${err instanceof Error ? err.message : '未知错误'}`)
  } finally {
    // 清空 input，允许重复选择同一文件
    if (fileInputRef.value) {
      fileInputRef.value.value = ''
    }
    uploadTargetKb.value = null
  }
}

// 从 Dify API 加载指定知识库的文档列表
async function loadDocuments(kbId: string) {
  if (!kbId) {
    documents.value = []
    return
  }
  docsLoading.value = true
  try {
    const res = await documentApi.getDocuments(kbId, 1, 100)
    documents.value = res.data || []
    // 同步更新到 selectedKb.documents，保证其他引用处一致
    const kb = filteredKnowledgeBases.value.find((k: any) => k.id === kbId)
      || joinedKnowledgeBases.value.find((k: any) => k.id === kbId)
    if (kb) {
      kb.documents = documents.value
      kb.document_count = documents.value.length
    }
  } catch (err) {
    console.warn('获取文档列表失败，保留已加载的文档:', err)
    const kb = filteredKnowledgeBases.value.find((k: any) => k.id === kbId)
      || joinedKnowledgeBases.value.find((k: any) => k.id === kbId)
    documents.value = kb?.documents || []
  } finally {
    docsLoading.value = false
  }
}

// Auto-select first knowledge base when data loads
watch(filteredKnowledgeBases, (list) => {
  if (list.length > 0 && !selectedKbId.value) {
    selectedKbId.value = list[0].id
  }
}, { immediate: true })

// 监听选中知识库变化，重新加载文档和检索结果
watch(selectedKbId, (newId, oldId) => {
  if (newId && newId !== oldId) {
    loadDocuments(newId)
  }
})

// 共享知识库文档列表
const sharedDocuments = ref<KnowledgeBaseDocument[]>([])
const sharedDocsLoading = ref(false)

async function loadSharedDocuments(kbId: string) {
  if (!kbId) {
    sharedDocuments.value = []
    return
  }
  sharedDocsLoading.value = true
  try {
    const res = await documentApi.getDocuments(kbId, 1, 100)
    sharedDocuments.value = res.data || []
    const kb = sharedKnowledgeBases.value.find((k: any) => k.id === kbId)
    if (kb) {
      kb.documents = sharedDocuments.value
      kb.document_count = sharedDocuments.value.length
    }
  } catch (err) {
    console.warn('获取共享知识库文档失败，保留已加载的文档:', err)
    const kb = sharedKnowledgeBases.value.find((k: any) => k.id === kbId)
    sharedDocuments.value = kb?.documents || []
  } finally {
    sharedDocsLoading.value = false
  }
}

// 知识库广场详情：存储从广场传入的完整 KB 数据
const squareDetailKbData = ref<any>(null)

// 知识库广场详情：文档列表
const squareDocuments = ref<KnowledgeBaseDocument[]>([])
const squareDocsLoading = ref(false)

async function loadSquareDocuments(kbId: string) {
  if (!kbId) {
    squareDocuments.value = []
    return
  }
  squareDocsLoading.value = true
  try {
    const res = await documentApi.getDocuments(kbId, 1, 100)
    squareDocuments.value = res.data || []
  } catch (err) {
    console.warn('获取广场知识库文档失败:', err)
    squareDocuments.value = []
  } finally {
    squareDocsLoading.value = false
  }
}

// 知识库广场详情：搜索、视图、排序
const squareSearchQuery = ref('')
const squareSearchInputRef = ref<HTMLInputElement | null>(null)
const squareDocViewMode = ref<'grid' | 'list'>('list')
const squareSortOrder = ref<SortOrder>('default')

function closeSquareSearch() {
  squareSearchQuery.value = ''
}

function clearSquareSearch() {
  squareSearchQuery.value = ''
  nextTick(() => {
    squareSearchInputRef.value?.focus()
  })
}

function toggleSquareDocViewMode() {
  squareDocViewMode.value = squareDocViewMode.value === 'grid' ? 'list' : 'grid'
}

function cycleSquareSort() {
  squareSortOrder.value = cycleSortOrder(squareSortOrder.value)
  ElMessage.info(`排序方式：${getSortLabel(squareSortOrder.value)}`)
}

const displayedSquareDocuments = computed(() => {
  let list = squareDocuments.value
  if (squareSearchQuery.value) {
    const query = squareSearchQuery.value.toLowerCase()
    list = list.filter(doc =>
      doc.name.toLowerCase().includes(query) ||
      (doc.type && doc.type.toLowerCase().includes(query))
    )
  }
  return sortDocuments(list, squareSortOrder.value)
})

// 监听共享知识库选中变化，重新加载文档和检索结果
watch(() => selectedSharedKb.value?.id, (newId, oldId) => {
  if (newId && newId !== oldId) {
    loadSharedDocuments(newId)
  }
})

function handleSelectKb(kb: any) {
  if (!kb || !kb.id) {
    console.warn('[KnowledgeBaseView] 无效的知识库条目:', kb)
    return
  }
  selectedKbId.value = kb.id
  squareDetailKbId.value = null
  squareDetailKbData.value = null
  hideSquareView()
  // 切换回个人知识库菜单，确保主内容区正确渲染
  switchMenu('personal')
  // 选中时获取成员列表并同步加入人数
  fetchMembers(kb.id)
  // 选中时刷新该知识库的最新标签
  refreshTagBind(kb.id)
  // Reset chat when switching KB
  startNewConversation()
}

// 从知识库广场点击进入详情，保留侧边栏，使用与共享知识库相同的布局
function handleSelectKbFromSquare(kb: any) {
  if (!kb || !kb.id) {
    console.warn('[KnowledgeBaseView] 无效的知识库条目:', kb)
    return
  }
  squareDetailKbId.value = kb.id
  squareDetailKbData.value = kb
  hideSquareView()
  // 加载文档列表
  loadSquareDocuments(kb.id)
  // 获取成员列表和标签
  fetchMembers(kb.id)
  refreshTagBind(kb.id)
  // Reset chat when switching KB
  startNewConversation()
}

async function handleCreateConfirm() {
  if (!createForm.value.name.trim()) return
  createDialogVisible.value = false
}

const defaultCover = new URL('@/assets/images/brand-cover-default.svg', import.meta.url).href

// ============== Chat State ==============
interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  retrieverResources?: RetrievalResult[]
  query?: string
}

const chatInput = ref('')
const chatMessages = ref<ChatMessage[]>([])
const chatLoading = ref(false)
let chatStopFn: (() => void) | null = null

/** 消息回答完成后推荐的后续问题 */
const postAnswerSuggestions = ref<string[]>([])
/**
 * 应用是否开启「回答后推荐问题」。
 * <p>Dify 未开启时 /messages/{id}/suggested 固定返回 400，前端不再发起该请求。</p>
 */
const suggestedAfterAnswerEnabled = ref(false)
/** 当前消息的 message_id，用于调用推荐消息接口 */
const lastMessageId = ref('')

/**
 * 用本次真实提问检索知识库，作为工作流 user_prompt 输入。
 * 之前是在选中知识库时用空 query 预检索，结果与问题无关；改为发送时按问题检索。
 */
async function retrieveContext(kbId: string, query: string): Promise<RetrievalResult[]> {
  if (!kbId || !query.trim()) return []
  try {
    return await retrieveFromDataset(kbId, query)
  } catch (e) {
    console.warn('知识库检索失败，按无上下文继续:', e)
    return []
  }
}

// ============== 应用参数 / 输入变量 ==============
const appParameters = ref<AppParametersResponse | null>(null)
const parametersLoading = ref(false)
const userInput = ref<Record<string, unknown>>({})
const modelNameVariable = ref('')
const promptInputVariable = ref('')

const isPromptVariable = (variable: string) => {
  const normalized = variable.trim().toLowerCase()
  return (
    normalized === 'prompt' ||
    normalized.endsWith('_prompt') ||
    normalized.endsWith('.prompt')
  )
}

const isModelNameVariable = (variable: string) => {
  const normalized = variable.trim().toLowerCase()
  return (
    normalized === 'model_name' ||
    normalized.endsWith('_model_name') ||
    normalized.endsWith('.model_name')
  )
}

const normalizeUserInputFields = (
  formItems: UserInputFormItem[] = []
): NormalizedUserInputField[] => {
  const normalizedFields: NormalizedUserInputField[] = []

  formItems.forEach((item) => {
    const textInput = item['text-input']
    const paragraph = item.paragraph
    const select = item.select

    if (textInput?.variable) {
      normalizedFields.push({
        type: 'text-input',
        label: textInput.label || textInput.variable,
        variable: textInput.variable,
        required: Boolean(textInput.required),
        defaultValue: textInput.default || '',
      })
      return
    }

    if (paragraph?.variable) {
      normalizedFields.push({
        type: 'paragraph',
        label: paragraph.label || paragraph.variable,
        variable: paragraph.variable,
        required: Boolean(paragraph.required),
        defaultValue: paragraph.default || '',
      })
      return
    }

    if (select?.variable) {
      normalizedFields.push({
        type: 'select',
        label: select.label || select.variable,
        variable: select.variable,
        required: Boolean(select.required),
        defaultValue: select.default || '',
        options: select.options || [],
      })
    }
  })

  return normalizedFields
}

async function loadAppParameters() {
  parametersLoading.value = true
  try {
    const params = (await getAppParameters()) as AppParametersResponse
    appParameters.value = params
    suggestedAfterAnswerEnabled.value = params.suggested_questions_after_answer?.enabled === true
    const normalizedFields = normalizeUserInputFields(params.user_input_form || [])
    const nextUserInput: Record<string, unknown> = {}
    normalizedFields.forEach((field) => {
      nextUserInput[field.variable] = field.defaultValue || ''
    })

    const promptField =
      normalizedFields.find((field) => isPromptVariable(field.variable)) ||
      normalizedFields.find((field) => field.variable.toLowerCase() === 'user_prompt')
    const modelField = normalizedFields.find((field) => isModelNameVariable(field.variable))

    promptInputVariable.value = promptField?.variable || 'user_prompt'
    modelNameVariable.value = modelField?.variable || 'model_name'
    userInput.value = nextUserInput

    // 同步当前选中的模型到 inputs
    syncModelInput()
  } catch (e) {
    console.warn('获取应用参数失败:', e)
    appParameters.value = null
    suggestedAfterAnswerEnabled.value = false
    promptInputVariable.value = 'user_prompt'
    modelNameVariable.value = 'model_name'
    userInput.value = {}
  } finally {
    parametersLoading.value = false
  }
}

const syncModelInput = () => {
  if (!modelNameVariable.value) return
  userInput.value = {
    ...userInput.value,
    [modelNameVariable.value]: activeChatModelId.value,
  }
}

/** 回答完成后拉取推荐问题；应用未开启该功能时不请求（Dify 会固定返回 400） */
async function fetchPostAnswerSuggestions() {
  if (!suggestedAfterAnswerEnabled.value) {
    postAnswerSuggestions.value = []
    return
  }
  try {
    let msgId = lastMessageId.value
    // 兜底：如果 SSE 事件没有 message_id，从会话历史获取最新消息
    if (!msgId && activeConversationId.value) {
      const historyRes = await getConversationHistory(activeConversationId.value, getCurrentUserId(), 1)
      const msgs = (historyRes as any)?.data || []
      if (msgs.length > 0) {
        msgId = msgs[0].id
      }
    }
    if (!msgId) {
      console.warn('[推荐消息] 无法获取 message_id，跳过推荐问题')
      return
    }
    postAnswerSuggestions.value = await getSuggestedMessages(msgId, getCurrentUserId())
  } catch (e: any) {
    console.warn('[推荐消息] 获取失败:', e?.message || e)
  }
}

// ============== 会话上下文 ==============
const activeConversationId = ref('')

/** 开启新会话：清空消息并重置 Dify 会话上下文 */
function startNewConversation() {
  if (chatStopFn) {
    chatStopFn()
    chatStopFn = null
  }
  chatMessages.value = []
  chatInput.value = ''
  activeConversationId.value = ''
  postAnswerSuggestions.value = []
  lastMessageId.value = ''
}

// ============== 模型选择 ==============
interface ModelOption {
  value: string
  label: string
  title?: string
  subtitle?: string
}

const chatModelOptions = ref<ModelOption[]>([])
const activeChatModelId = ref('')
const chatModelsLoading = ref(false)

async function loadChatModels() {
  chatModelsLoading.value = true
  try {
    const { data: res } = await modelsConfigApi.getList({
      data: {},
      pageRequest: { pageSize: 100, pageNum: 1 },
    })
    if (res?.datas?.length) {
      const seen = new Set<string>()
      chatModelOptions.value = res.datas
        .filter((item: { id: string; name: string; provider: string }) => {
          const key = item.id
          if (seen.has(key)) return false
          seen.add(key)
          return true
        })
        .map((item: { id: string; name: string; provider: string }) => ({
          value: item.id,
          label: item.provider ? `${item.name} · ${item.provider}` : item.name,
          title: item.name,
          subtitle: item.provider || '系统模型',
        }))
    }
    if (!activeChatModelId.value && chatModelOptions.value.length > 0) {
      activeChatModelId.value = chatModelOptions.value[0].value
    }
  } catch (err) {
    console.warn('获取模型列表失败:', err)
    chatModelOptions.value = []
  } finally {
    chatModelsLoading.value = false
  }
}

function selectChatModel(value: string) {
  activeChatModelId.value = value
  syncModelInput()
}

// ============== 文件上传 ==============
interface AttachedFile {
  id: string
  name: string
  type: string
  size: number
}

const chatAttachedFiles = ref<AttachedFile[]>([])

async function handleChatFileSelected(file: File) {
  try {
    const result = await uploadFileToDify(file)
    chatAttachedFiles.value.push({
      id: result.id,
      name: file.name,
      type: file.type,
      size: file.size,
    })
  } catch (e) {
    console.error('文件上传失败:', e)
    ElMessage.error('文件上传失败')
  }
}

function removeChatAttachedFile(fileId: string) {
  chatAttachedFiles.value = chatAttachedFiles.value.filter(f => f.id !== fileId)
}

/** 获取当前活跃的知识库（广场详情 > 共享 > 个人） */
function getCurrentChatKb() {
  if (squareDetailKbId.value && squareDetailKbData.value) return squareDetailKbData.value
  if (activeMenu.value === 'shared') return selectedSharedKb.value
  return selectedKb.value
}

async function sendChatMessage() {
  const content = chatInput.value.trim()
  const currentKb = getCurrentChatKb()
  const files = [...chatAttachedFiles.value]

  if ((!content && files.length === 0) || chatLoading.value || !currentKb) return

  // 发送新消息时清空上一次的推荐问题
  postAnswerSuggestions.value = []
  lastMessageId.value = ''

  if (chatStopFn) {
    chatStopFn()
    chatStopFn = null
  }

  // 只有附件没有文字时，使用默认 query
  const queryContent = content || '请分析这个文件'
  const displayContent = content || '[文件]'

  // 构建 inputs：model_name 和 user_prompt 必须放在 inputs 对象内
  const currentInputs: Record<string, unknown> = { ...userInput.value }
  currentInputs.source = 'knowledge_base'
  if (modelNameVariable.value) {
    currentInputs[modelNameVariable.value] = activeChatModelId.value
  }

  // 构建 Dify 文件列表
  const fileList: KnowledgeChatFile[] = files.map((file) => ({
    type: file.type.startsWith('image/') ? 'image' : 'document',
    transfer_method: 'local_file',
    upload_file_id: file.id,
  }))

  const userMsg: ChatMessage = {
    id: `user-${Date.now()}`,
    role: 'user',
    content: displayContent,
    query: queryContent,
  }
  chatMessages.value.push(userMsg)
  chatInput.value = ''
  chatAttachedFiles.value = []
  chatLoading.value = true

  const assistantMsg: ChatMessage = {
    id: `assistant-${Date.now()}`,
    role: 'assistant',
    content: '',
    retrieverResources: [],
  }
  chatMessages.value.push(assistantMsg)
  const assistantIdx = chatMessages.value.length - 1

  let assistantContent = ''

  const retrieved = await retrieveContext(currentKb.id, queryContent)
  if (retrieved.length) {
    chatMessages.value[assistantIdx].retrieverResources = retrieved
  }
  if (promptInputVariable.value) {
    currentInputs[promptInputVariable.value] = retrieved.length ? JSON.stringify(retrieved) : ''
  }

  // 与问答交互引擎保持一致：conversation_id 在有值时传递，files 有文件时传递
  const payload: any = {
    query: queryContent,
    user: getCurrentUserId(),
    conversation_id: activeConversationId.value || undefined,
    inputs: currentInputs,
    files: fileList.length > 0 ? fileList : undefined,
  }

  debug.log('[知识库中心发送消息]', payload)

  chatStopFn = knowledgeChatStreaming(
    payload,
    {
      onChunk: (text) => {
        assistantContent += text
        chatMessages.value[assistantIdx].content = assistantContent
      },
      onMessageId: (messageId) => {
        lastMessageId.value = messageId
      },
      onRetrieverResources: (resources) => {
        chatMessages.value[assistantIdx].retrieverResources = resources
      },
      onComplete: async (conversationId) => {
        chatLoading.value = false
        chatStopFn = null
        // 兜底：流结束后内容仍为空（模型未返回有效内容），给出提示而非空白
        const doneMsg = chatMessages.value[assistantIdx]
        if (doneMsg && (!doneMsg.content || !doneMsg.content.trim())) {
          doneMsg.content = '模型未返回有效内容，请重试或尝试切换其他模型。'
        }
        if (conversationId) {
          markKbConversation(conversationId)
          activeConversationId.value = conversationId
        }
        // 消息完成后获取推荐问题
        await fetchPostAnswerSuggestions()
      },
      onError: (err) => {
        console.error('Chat error:', err)
        const parsed = parseChatError(err)
        chatMessages.value[assistantIdx].content = parsed.message
        chatLoading.value = false
        chatStopFn = null
      },
    }
  )
}

function copyMessageContent(content: string) {
  navigator.clipboard.writeText(content).then(() => {
    ElMessage.success('已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

/** 点击推荐问题：设置输入内容并自动发送 */
function handleSuggestedClick(question: string) {
  chatInput.value = question
  nextTick(() => {
    sendChatMessage()
  })
}

async function regenerateMessage(assistantMsgId: string) {
  if (chatLoading.value) return

  const assistantIdx = chatMessages.value.findIndex(m => m.id === assistantMsgId)
  if (assistantIdx < 1) return

  // 找到前一条用户消息
  const userIdx = assistantIdx - 1
  const userMsg = chatMessages.value[userIdx]
  if (userMsg.role !== 'user') return

  const queryContent = userMsg.query || userMsg.content
  if (!queryContent.trim()) return

  const currentKb = getCurrentChatKb()
  if (!currentKb) return

  // 重新生成时清空推荐问题
  postAnswerSuggestions.value = []
  lastMessageId.value = ''

  if (chatStopFn) {
    chatStopFn()
    chatStopFn = null
  }

  // 移除旧的 assistant 消息，重置其内容
  chatMessages.value.splice(assistantIdx, 1)
  chatLoading.value = true

  const assistantMsg: ChatMessage = {
    id: `assistant-${Date.now()}`,
    role: 'assistant',
    content: '',
    retrieverResources: [],
  }
  chatMessages.value.push(assistantMsg)
  const newAssistantIdx = chatMessages.value.length - 1

  let assistantContent = ''

  const currentInputs: Record<string, unknown> = { ...userInput.value }
  currentInputs.source = 'knowledge_base'
  if (modelNameVariable.value) {
    currentInputs[modelNameVariable.value] = activeChatModelId.value
  }

  const retrieved = await retrieveContext(currentKb.id, queryContent)
  if (retrieved.length) {
    chatMessages.value[newAssistantIdx].retrieverResources = retrieved
  }
  if (promptInputVariable.value) {
    currentInputs[promptInputVariable.value] = retrieved.length ? JSON.stringify(retrieved) : ''
  }

  const payload: any = {
    query: queryContent,
    user: getCurrentUserId(),
    conversation_id: activeConversationId.value || undefined,
    inputs: currentInputs,
  }

  chatStopFn = knowledgeChatStreaming(
    payload,
    {
      onChunk: (text) => {
        assistantContent += text
        chatMessages.value[newAssistantIdx].content = assistantContent
      },
      onMessageId: (messageId) => {
        lastMessageId.value = messageId
      },
      onRetrieverResources: (resources) => {
        chatMessages.value[newAssistantIdx].retrieverResources = resources
      },
      onComplete: async (conversationId) => {
        chatLoading.value = false
        chatStopFn = null
        // 兜底：流结束后内容仍为空（模型未返回有效内容），给出提示而非空白
        const doneMsg = chatMessages.value[newAssistantIdx]
        if (doneMsg && (!doneMsg.content || !doneMsg.content.trim())) {
          doneMsg.content = '模型未返回有效内容，请重试或尝试切换其他模型。'
        }
        if (conversationId) {
          markKbConversation(conversationId)
          activeConversationId.value = conversationId
        }
        // 消息完成后获取推荐问题
        await fetchPostAnswerSuggestions()
      },
      onError: (err) => {
        console.error('Chat error:', err)
        const parsed = parseChatError(err)
        chatMessages.value[newAssistantIdx].content = parsed.message
        chatLoading.value = false
        chatStopFn = null
      },
    }
  )
}
</script>

<template>
  <div class="kb-page">
    <!-- 隐藏的文件上传输入框 -->
    <input
      ref="fileInputRef"
      type="file"
      style="display: none"
      accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.md,.json,.png,.jpg,.jpeg,.gif,.webp"
      @change="handleFileUpload"
    />

    <!-- Left Sidebar -->
    <aside class="kb-sidebar" :class="{ 'kb-sidebar-collapsed': sidebarCollapsed }">
      <!-- Sidebar Toolbar - 仿聊天助手 Logo 按钮 -->
      <div class="kb-sidebar-toolbar">
        <button
          class="kb-sidebar-logo"
          :aria-label="sidebarCollapsed ? '展开菜单' : '折叠菜单'"
          @click="sidebarCollapsed = !sidebarCollapsed"
        >
          <img src="@/assets/images/brand-logo.svg" alt="logo" class="kb-sidebar-logo-mark"/>
          <span v-show="!sidebarCollapsed" class="kb-sidebar-logo-name">知识库中心</span>
          <el-icon v-show="!sidebarCollapsed" class="kb-sidebar-logo-arrow"><Fold /></el-icon>
        </button>
      </div>

      <div class="kb-sidebar-top">
        <!-- 搜索和标签筛选同时显示 -->
        <div
          v-show="!sidebarCollapsed"
          class="kb-sidebar-header-actions"
        >
          <!-- 搜索输入框 -->
          <div class="kb-sidebar-search-input-wrap">
            <input
              ref="sidebarSearchInputRef"
              v-model="sidebarSearchQuery"
              type="text"
              placeholder="搜索"
              class="kb-sidebar-search-input"
            />
          </div>
          <!-- 标签筛选下拉框 -->
          <div class="kb-sidebar-tag-filter-wrap">
            <TagFilterDropdown
              v-model="sidebarSelectedTag"
              :allTags="allTags"
              type="knowledge_base"
              inline
              teleport
              class="kb-sidebar-tag-select"
              @visible-change="(visible: boolean) => { if (visible) getAllTags() }"
            />
          </div>
        </div>
      </div>

      <div class="kb-sidebar-group">
        <div
          v-show="!sidebarCollapsed"
          class="kb-sidebar-group-header"
          @click="createdExpanded = !createdExpanded"
        >
          <el-icon class="kb-expand-icon">
            <ArrowDown v-if="createdExpanded" />
            <ArrowRight v-else />
          </el-icon>
          <span>个人知识库</span>
          <button
            v-if="!knowledgeFileUnavailable"
            class="kb-add-btn"
            @click.stop="openMngCreateDialog"
            title="新建知识库"
          >
            <Plus />
          </button>
        </div>
        <div
          v-show="sidebarCollapsed"
          class="kb-sidebar-group-icon"
          :class="{ active: filteredKnowledgeBases.length > 0 }"
          title="个人知识库"
        >
          <el-icon><Histogram /></el-icon>
          <span v-if="filteredKnowledgeBases.length > 0" class="kb-sidebar-icon-badge">
            {{ filteredKnowledgeBases.length }}
          </span>
        </div>

        <div v-show="createdExpanded && !sidebarCollapsed" class="kb-sidebar-children">
          <div
            v-for="kb in sidebarFilteredKnowledgeBases"
            :key="kb.id"
            class="kb-sidebar-child-item"
            :class="{ active: selectedKbId === kb.id && activeMenu === 'personal' }"
            @click="handleSelectKb(kb)"
            :title="kb.name"
          >
            <el-icon><Histogram /></el-icon>
            <span class="kb-sidebar-child-name">{{ kb.name }}</span>
          </div>
          <div v-if="sidebarSearchQuery && sidebarFilteredKnowledgeBases.length === 0" class="kb-sidebar-empty">
            未找到匹配的知识库
          </div>
        </div>
      </div>

      

      <!-- 共享知识库分组 -->
      <div class="kb-sidebar-group">
        <div
          v-show="!sidebarCollapsed"
          class="kb-sidebar-group-header kb-sidebar-group-header-shared"
          :class="{ active: activeMenu === 'shared' }"
          @click="switchMenu('shared')"
        >
          <el-icon class="kb-expand-icon" @click.stop="sharedExpanded = !sharedExpanded">
            <ArrowDown v-if="sharedExpanded" />
            <ArrowRight v-else />
          </el-icon>
          <el-icon><Share /></el-icon>
          <span>共享知识库</span>
        </div>
        <div
          v-show="sidebarCollapsed"
          class="kb-sidebar-group-icon"
          :class="{ active: activeMenu === 'shared' }"
          title="共享知识库"
          @click="switchMenu('shared')"
        >
          <el-icon><Share /></el-icon>
        </div>

        <div v-show="sharedExpanded && !sidebarCollapsed" class="kb-sidebar-children">
          <div
            v-for="kb in sidebarFilteredSharedKnowledgeBases"
            :key="kb.id"
            class="kb-sidebar-child-item"
            :class="{ active: selectedSharedKb?.id === kb.id && activeMenu === 'shared' }"
            @click="selectSharedKb(kb)"
            :title="kb.name"
          >
            <el-icon><Histogram /></el-icon>
            <span class="kb-sidebar-child-name">{{ kb.name }}</span>
          </div>
          <div v-if="sidebarFilteredSharedKnowledgeBases.length === 0" class="kb-sidebar-empty">
            {{ sidebarSelectedTag.length > 0 ? '没有符合标签的共享知识库' : '暂无共享知识库' }}
          </div>
        </div>
      </div>

      <div class="kb-sidebar-group">
        <div
          v-show="!sidebarCollapsed"
          class="kb-sidebar-group-header"
          @click="joinedExpanded = !joinedExpanded"
        >
          <el-icon class="kb-expand-icon">
            <ArrowDown v-if="joinedExpanded" />
            <ArrowRight v-else />
          </el-icon>
          <span>订阅知识库</span>
        </div>
        <div
          v-show="sidebarCollapsed"
          class="kb-sidebar-group-icon"
          title="订阅知识库"
        >
          <el-icon><User /></el-icon>
        </div>

        <div v-show="joinedExpanded && !sidebarCollapsed" class="kb-sidebar-children">
          <div
            v-for="kb in sidebarFilteredJoinedKnowledgeBases"
            :key="kb.id"
            class="kb-sidebar-child-item"
            :class="{ active: selectedKbId === kb.id && activeMenu === 'personal' }"
            @click="selectJoinedKb(kb)"
            :title="kb.name"
          >
            <el-icon><Histogram /></el-icon>
            <span class="kb-sidebar-child-name">{{ kb.name }}</span>
          </div>
          <div v-if="sidebarFilteredJoinedKnowledgeBases.length === 0" class="kb-sidebar-empty">
            {{ sidebarSelectedTag.length > 0 ? '没有符合标签的订阅知识库' : '暂无加入的知识库' }}
          </div>
        </div>
      </div>

      <!-- 知识库广场入口 -->
      <div class="kb-sidebar-group">
        <div
          v-show="!sidebarCollapsed"
          class="kb-sidebar-group-header kb-sidebar-group-header-capabilities"
          :class="{ active: showSquareView || !!squareDetailKbId }"
          @click="goToSquare"
        >
          <el-icon><Grid /></el-icon>
          <span>知识库广场</span>
        </div>
        <div
          v-show="sidebarCollapsed"
          class="kb-sidebar-group-icon"
          :class="{ active: showSquareView || !!squareDetailKbId }"
          title="知识库广场"
          @click="goToSquare"
        >
          <el-icon><Grid /></el-icon>
        </div>
      </div>

    </aside>

    <!-- Main Content Area -->
    <main class="kb-main">
      <div v-if="error && !knowledgeFileUnavailable" class="kb-config-alert kb-error-alert">
        <div class="kb-config-alert-title">{{ error }}</div>
        <div class="kb-config-alert-hint">
          <button
            type="button"
            class="kb-error-retry"
            :disabled="isLoading"
            @click="fetchKnowledgeBases"
          >
            {{ isLoading ? '正在重试...' : '重试' }}
          </button>
        </div>
      </div>
      <div v-if="knowledgeFileUnavailable" class="kb-config-alert">
        <div class="kb-config-alert-title">知识库文件模块未配置</div>
        <div class="kb-config-alert-hint">
          管理员需在「后台管理 → Dify 配置」中为「知识库文件」模块填写地址与密钥，知识库列表与文档管理才可使用。
        </div>
      </div>
      <!-- ========== 知识库广场详情视图（与共享知识库相同的布局） ========== -->
      <div v-if="squareDetailKbId && squareDetailKbData" class="kb-content-shared">
        <!-- 左侧：知识库详情与内容 -->
        <div class="kb-shared-left">
          <!-- 顶部知识库信息卡片 -->
          <div class="kb-shared-info-card">
            <div class="kb-shared-info-left">
              <div class="kb-shared-info-cover">
                <img :src="squareDetailKbData.coverImage || defaultCover" :alt="squareDetailKbData.name" />
              </div>
              <div class="kb-shared-info-text">
                <div class="kb-shared-info-title">{{ squareDetailKbData.name }}</div>
                <div class="kb-shared-info-join">
                  <span>{{ squareDocuments.length }}个内容</span>
                  <span
                    class="kb-shared-info-join-count"
                    @click="openMemberDialog(squareDetailKbId)"
                  >
                    {{ squareDetailKbData.member_count || 0 }}人加入
                  </span>
                </div>
                <p class="kb-shared-info-desc">{{ squareDetailKbData.description || '暂无描述' }}</p>
                <!-- 标签区域（只读展示） -->
                <div class="kb-info-tags">
                  <el-popover
                    v-for="(tag, tagIndex) in squareDetailKbData.tagList || []"
                    :key="`${squareDetailKbData.id}-tag-${tagIndex}`"
                    placement="bottom"
                    trigger="hover"
                    :width="200"
                    :show-after="200"
                  >
                    <template #reference>
                      <span class="kb-info-tag">
                        <el-icon><PriceTag /></el-icon>
                        {{ tag.name }}
                      </span>
                    </template>
                    <div class="kb-tags-popover">
                      <div
                        v-for="t in squareDetailKbData.tagList"
                        :key="t.iid"
                        class="kb-tags-popover-item"
                      >
                        <el-icon><PriceTag /></el-icon>
                        {{ t.name }}
                      </div>
                    </div>
                  </el-popover>
                </div>
              </div>
            </div>
            <div class="kb-shared-info-actions">
              <button class="kb-icon-action" title="加入知识库" @click="joinKb(squareDetailKbId)">
                <Plus />
              </button>
            </div>
          </div>

          <!-- 内容区标题栏 -->
          <div class="kb-shared-content-header">
            <div class="kb-shared-search-bar">
              <div class="kb-shared-search-input-wrap">
                <el-icon class="kb-shared-search-icon"><Search /></el-icon>
                <input
                  ref="squareSearchInputRef"
                  v-model="squareSearchQuery"
                  type="text"
                  placeholder="在知识库中搜索"
                  class="kb-shared-search-input"
                  @keydown.esc="closeSquareSearch"
                />
                <button
                  v-if="squareSearchQuery"
                  class="kb-shared-search-clear"
                  title="清除"
                  @click="clearSquareSearch"
                >
                  <Close />
                </button>
              </div>
            </div>
            <div class="kb-shared-content-actions">
              <button
                class="kb-icon-action-sm"
                :title="squareDocViewMode === 'grid' ? '列表视图' : '宫格视图'"
                @click="toggleSquareDocViewMode"
              >
                <Menu v-if="squareDocViewMode === 'grid'" />
                <Grid v-else />
              </button>
              <button class="kb-icon-action-sm" :title="`排序：${getSortLabel(squareSortOrder)}`" @click="cycleSquareSort">
                <Sort />
              </button>
            </div>
          </div>

          <!-- 文档列表 -->
          <div v-if="squareDocViewMode === 'list'" class="kb-shared-doc-list">
            <!-- 加载中 -->
            <div v-if="squareDocsLoading" class="kb-doc-loading">
              <Document /> <span>正在加载文档...</span>
            </div>
            <!-- 文档列表 -->
            <div
              v-for="doc in displayedSquareDocuments"
              :key="doc.id"
              class="kb-doc-list-item"
              :title="doc.name"
              @click="previewDocument(doc)"
            >
              <div class="kb-doc-list-thumb" :style="{ background: getFileTypeInfo(doc).bg, color: getFileTypeInfo(doc).color, borderColor: getFileTypeInfo(doc).color + '30' }">
                <span class="kb-doc-list-thumb-ext">{{ getFileTypeInfo(doc).label }}</span>
              </div>
              <div class="kb-doc-list-info">
                <div class="kb-doc-list-name">{{ doc.name }}</div>
                <div class="kb-doc-list-meta">
                  <span class="kb-doc-type" :class="(doc.type || doc.data_source_type || 'file').toLowerCase()">
                    {{ getFileTypeInfo(doc).label }}
                  </span>
                  <span class="kb-doc-date">{{ doc.updateTime || '—' }}</span>
                </div>
              </div>
            </div>
            <!-- 空状态 -->
            <div v-if="!squareDocsLoading && displayedSquareDocuments.length === 0" class="kb-shared-empty-state">
              <div class="kb-shared-empty-illustration">
                <svg viewBox="0 0 200 120" class="kb-shared-empty-plane" xmlns="http://www.w3.org/2000/svg">
                  <path class="kb-shared-empty-trail" d="M20,90 Q60,30 100,70 T180,40" fill="none" stroke="currentColor" stroke-width="1.5" stroke-dasharray="4 4" />
                  <g class="kb-shared-empty-paper-plane" transform="translate(170, 28) rotate(-15)">
                    <path d="M0,0 L24,8 L0,16 L6,8 Z" fill="currentColor" />
                  </g>
                </svg>
              </div>
              <p class="kb-shared-empty-text">知识库什么也没有，去这里添加</p>
            </div>
          </div>

          <!-- 宫格视图 -->
          <div v-else class="kb-doc-grid kb-shared-doc-grid">
            <div v-if="squareDocsLoading" class="kb-doc-loading">
              <Document /> <span>正在加载文档...</span>
            </div>
            <div
              v-for="doc in displayedSquareDocuments"
              :key="doc.id"
              class="kb-doc-card"
              :title="doc.name"
              @click="previewDocument(doc)"
            >
              <div class="kb-doc-thumb" :style="{ background: getFileTypeInfo(doc).bg, color: getFileTypeInfo(doc).color, borderColor: getFileTypeInfo(doc).color + '40' }">
                <span class="kb-doc-thumb-ext">{{ getFileTypeInfo(doc).label }}</span>
              </div>
              <div class="kb-doc-name">{{ doc.name }}</div>
              <div class="kb-doc-footer">
                <span class="kb-doc-type" :class="(doc.type || doc.data_source_type || 'file').toLowerCase()">
                  {{ getFileTypeInfo(doc).label }}
                </span>
                <span class="kb-doc-date">{{ doc.updateTime || '—' }}</span>
              </div>
            </div>
            <div v-if="!squareDocsLoading && displayedSquareDocuments.length === 0" class="kb-shared-empty-state">
              <div class="kb-shared-empty-illustration">
                <svg viewBox="0 0 200 120" class="kb-shared-empty-plane" xmlns="http://www.w3.org/2000/svg">
                  <path class="kb-shared-empty-trail" d="M20,90 Q60,30 100,70 T180,40" fill="none" stroke="currentColor" stroke-width="1.5" stroke-dasharray="4 4" />
                  <g class="kb-shared-empty-paper-plane" transform="translate(170, 28) rotate(-15)">
                    <path d="M0,0 L24,8 L0,16 L6,8 Z" fill="currentColor" />
                  </g>
                </svg>
              </div>
              <p class="kb-shared-empty-text">知识库什么也没有，去这里添加</p>
            </div>
          </div>
        </div>

        <!-- 右侧：对话模块（与共享知识库完全一致的 KbChatPanel） -->
        <div class="kb-shared-right">
          <KbChatPanel
            v-model="chatInput"
            :messages="chatMessages"
            :loading="chatLoading"
            placeholder="基于知识库提问"
            :model-options="chatModelOptions"
            :active-model-id="activeChatModelId"
            :attached-files="chatAttachedFiles"
            :kb-name="squareDetailKbData?.name || ''"
            :suggested-questions="postAnswerSuggestions"
            @submit="sendChatMessage"
            @copy="copyMessageContent"
            @regenerate="regenerateMessage"
            @select-model="selectChatModel"
            @file-selected="handleChatFileSelected"
            @remove-file="removeChatAttachedFile"
            @suggest-click="handleSuggestedClick"
            @new-conversation="startNewConversation"
          />
        </div>
      </div>

      <!-- ========== 个人知识库视图 ========== -->
      <div v-else-if="activeMenu === 'personal' && selectedKb && !showSquareView" class="kb-content-shared">
        <!-- 左侧：知识库详情与内容 -->
        <div class="kb-shared-left">
          <!-- 顶部知识库信息卡片 -->
          <div class="kb-shared-info-card">
          <div class="kb-shared-info-left">
            <div class="kb-shared-info-cover">
              <img :src="selectedKb.coverImage || defaultCover" :alt="selectedKb.name" />
            </div>
            <div class="kb-shared-info-text">
              <div class="kb-shared-info-title">{{ selectedKb.name }}</div>
              <div class="kb-shared-info-join">
                <span>{{ documents.length }}个内容</span>
                <span
                  class="kb-shared-info-join-count"
                  @click="isJoinedSelectedKb ? openJoinedMemberDialog(selectedKb.id) : openMemberDialog(selectedKb.id)"
                >
                  {{ selectedKb.member_count || 0 }}人加入
                </span>
              </div>
              <p class="kb-shared-info-desc">{{ selectedKb.description || '暂无描述' }}</p>
              <!-- 标签区域 -->
              <div class="kb-info-tags">
                <template v-if="isAdmin || !isJoinedSelectedKb">
                  <TagDropdown
                    v-for="(tag, tagIndex) in selectedKb.tagList || []"
                    :key="`${selectedKb.id}-tag-${tagIndex}`"
                    :parent="selectedKb"
                    :allTags="allTags"
                    :show-add-button="false"
                    type="knowledge_base"
                    @refreshTagBind="(id: string) => refreshTagBind(id)"
                    @getAllTags="getAllTags"
                  >
                    <template #trigger>
                      <el-popover
                        placement="bottom"
                        trigger="hover"
                        :width="160"
                        :show-after="200"
                      >
                        <template #reference>
                          <span class="kb-info-tag">
                            <el-icon><PriceTag /></el-icon>
                            {{ tag.name }}
                          </span>
                        </template>
                        <div class="kb-tags-popover">
                          <div
                            v-for="t in selectedKb.tagList"
                            :key="t.iid"
                            class="kb-tags-popover-item"
                          >
                            <el-icon><PriceTag /></el-icon>
                            {{ t.name }}
                          </div>
                        </div>
                      </el-popover>
                    </template>
                  </TagDropdown>
                  <TagDropdown
                    v-if="!selectedKb.tagList || selectedKb.tagList.length === 0"
                    :parent="selectedKb"
                    :allTags="allTags"
                    :show-add-button="true"
                    type="knowledge_base"
                    @refreshTagBind="(id: string) => refreshTagBind(id)"
                    @getAllTags="getAllTags"
                  />
                </template>
                <template v-else>
                  <el-popover
                    v-for="(tag, tagIndex) in selectedKb.tagList || []"
                    :key="`${selectedKb.id}-tag-${tagIndex}`"
                    placement="bottom"
                    trigger="hover"
                    :width="200"
                    :show-after="200"
                  >
                    <template #reference>
                      <span class="kb-info-tag">
                        <el-icon><PriceTag /></el-icon>
                        {{ tag.name }}
                      </span>
                    </template>
                    <div class="kb-tags-popover">
                      <div
                        v-for="t in selectedKb.tagList"
                        :key="t.iid"
                        class="kb-tags-popover-item"
                      >
                        <el-icon><PriceTag /></el-icon>
                        {{ t.name }}
                      </div>
                    </div>
                  </el-popover>
                </template>
              </div>
            </div>
          </div>
          <div class="kb-shared-info-actions">
            <template v-if="!isJoinedSelectedKb || isAdmin">
              <button class="kb-icon-action" title="更多" @click="openSharedMoreMenu($event)">
                <MoreFilled />
              </button>
            </template>
            <button
              v-if="isJoinedSelectedKb && !isAdmin"
              class="kb-icon-action kb-icon-action-danger"
              title="退出知识库"
              @click="leaveKb(selectedKb.id)"
            >
              <Close />
            </button>
          </div>
        </div>

        <!-- Sub Header -->
        <div class="kb-shared-content-header">
          <div class="kb-shared-search-bar">
            <div class="kb-shared-search-input-wrap">
              <el-icon class="kb-shared-search-icon"><Search /></el-icon>
              <input
                ref="personalSearchInputRef"
                v-model="personalSearchQuery"
                type="text"
                placeholder="在知识库中搜索"
                class="kb-shared-search-input"
                @keydown.esc="closePersonalSearch"
              />
              <button
                v-if="personalSearchQuery"
                class="kb-shared-search-clear"
                title="清除"
                @click="clearPersonalSearch"
              >
                <Close />
              </button>
            </div>
          </div>
          <div class="kb-shared-content-actions">
            <button
              class="kb-icon-action-sm"
              :title="currentDocViewMode === 'grid' ? '列表视图' : '宫格视图'"
              @click="isJoinedSelectedKb ? toggleJoinedDocViewMode() : toggleDocViewMode()"
            >
              <Menu v-if="currentDocViewMode === 'grid'" />
              <Grid v-else />
            </button>
            <button class="kb-icon-action-sm" :title="`排序：${getSortLabel(sortOrder)}`" @click="cyclePersonalSort">
              <Sort />
            </button>
            <button v-if="!isJoinedSelectedKb" class="kb-icon-action-sm" title="添加文档" @click="handleUploadClick(selectedKb)">
              <DocumentAdd />
            </button>
          </div>
        </div>

        <!-- Document Grid -->
        <div v-if="currentDocViewMode === 'grid'" class="kb-doc-grid">
          <!-- 加载中 -->
          <div v-if="docsLoading" class="kb-doc-loading">
            <Document /> <span>正在加载文档...</span>
          </div>
          <!-- 文档列表 -->
          <div
            v-for="doc in displayedDocuments"
            :key="doc.id"
            class="kb-doc-card"
            :title="doc.name"
            @click="previewDocument(doc)"
          >
            <button
              v-if="!isJoinedSelectedKb"
              type="button"
              class="kb-doc-card-delete"
              :disabled="deletingDocumentId === doc.id"
              :aria-label="`删除文档：${doc.name}`"
              @click.stop="handleDeleteDocument(doc)"
            >
              <Delete />
            </button>
            <div class="kb-doc-thumb" :style="{ background: getFileTypeInfo(doc).bg, color: getFileTypeInfo(doc).color, borderColor: getFileTypeInfo(doc).color + '30' }">
              <span class="kb-doc-thumb-ext">{{ getFileTypeInfo(doc).label }}</span>
            </div>
            <div class="kb-doc-name">{{ doc.name }}</div>
            <div class="kb-doc-footer">
              <span class="kb-doc-type" :class="(doc.type || doc.data_source_type || 'file').toLowerCase()">
                {{ getFileTypeInfo(doc).label }}
              </span>
              <span class="kb-doc-date">{{ doc.updateTime || '—' }}</span>
            </div>
          </div>
          <!-- 空状态 -->
          <div v-if="!docsLoading && displayedDocuments.length === 0" class="kb-doc-empty">
            <Document />
            <p>{{ personalSearchQuery ? '未找到匹配的文档' : '暂无文档，点击上方 + 添加文档' }}</p>
          </div>
        </div>

        <!-- Document List -->
        <div v-else class="kb-doc-list">
          <div v-if="docsLoading" class="kb-doc-loading">
            <Document /> <span>正在加载文档...</span>
          </div>
          <div
            v-for="doc in displayedDocuments"
            :key="doc.id"
            class="kb-doc-list-item"
            :title="doc.name"
            @click="previewDocument(doc)"
          >
            <div class="kb-doc-list-thumb" :style="{ background: getFileTypeInfo(doc).bg, color: getFileTypeInfo(doc).color, borderColor: getFileTypeInfo(doc).color + '30' }">
              <span class="kb-doc-list-thumb-ext">{{ getFileTypeInfo(doc).label }}</span>
            </div>
            <div class="kb-doc-list-info">
              <div class="kb-doc-list-name">{{ doc.name }}</div>
              <div class="kb-doc-list-meta">
                <span class="kb-doc-type" :class="(doc.type || doc.data_source_type || 'file').toLowerCase()">
                  {{ getFileTypeInfo(doc).label }}
                </span>
                <span class="kb-doc-date">{{ doc.updateTime || '—' }}</span>
              </div>
            </div>
            <button
              v-if="!isJoinedSelectedKb"
              type="button"
              class="kb-doc-list-delete"
              :disabled="deletingDocumentId === doc.id"
              :aria-label="`删除文档：${doc.name}`"
              @click.stop="handleDeleteDocument(doc)"
            >
              <Delete />
              <span>{{ deletingDocumentId === doc.id ? '删除中' : '删除' }}</span>
            </button>
          </div>
          <div v-if="!docsLoading && displayedDocuments.length === 0" class="kb-doc-empty">
            <el-icon><Document /></el-icon>
            <p>{{ personalSearchQuery ? '未找到匹配的文档' : '暂无文档，点击上方 + 添加文档' }}</p>
          </div>
        </div>

        </div>

        <!-- 右侧：对话模块 -->
        <div class="kb-shared-right">
          <KbChatPanel
            v-model="chatInput"
            :messages="chatMessages"
            :loading="chatLoading"
            placeholder="基于知识库提问"
            :model-options="chatModelOptions"
            :active-model-id="activeChatModelId"
            :attached-files="chatAttachedFiles"
            :kb-name="selectedKb?.name || ''"
            :suggested-questions="postAnswerSuggestions"
            @submit="sendChatMessage"
            @copy="copyMessageContent"
            @regenerate="regenerateMessage"
            @select-model="selectChatModel"
            @file-selected="handleChatFileSelected"
            @remove-file="removeChatAttachedFile"
            @suggest-click="handleSuggestedClick"
            @new-conversation="startNewConversation"
          />
        </div>
      </div>

      <KnowledgeBaseSquareView
        v-else-if="activeMenu === 'personal' && showSquareView"
        class="kb-square-landing"
        :is-admin="isAdmin"
        :my-kb-ids="myKbIds"
        @select-kb="handleSelectKbFromSquare"
      />

      <div v-else-if="activeMenu === 'personal' && !knowledgeFileUnavailable" class="kb-empty">
        <img src="@/assets/images/brand-logo.svg" alt="" class="kb-empty-logo" />
        <p class="kb-empty-title">从左侧选择一个知识库，开始检索与问答</p>
        <p class="kb-empty-hint">
          还没有知识库？点击左侧「新建知识库」，上传文档后即可在聊天中引用。
        </p>
      </div>

      <!-- ========== 共享知识库视图：左侧详情 + 右侧对话 ========== -->
      <div v-if="activeMenu === 'shared' && selectedSharedKb" class="kb-content-shared">
        <!-- 左侧：知识库详情与内容 -->
        <div class="kb-shared-left">
          <!-- 顶部知识库信息卡片 -->
          <div class="kb-shared-info-card">
            <div class="kb-shared-info-left">
              <div class="kb-shared-info-cover">
                <img :src="selectedSharedKb.coverImage || defaultCover" :alt="selectedSharedKb.name" />
              </div>
              <div class="kb-shared-info-text">
                <div class="kb-shared-info-title">{{ selectedSharedKb.name }}</div>
                <div class="kb-shared-info-join">
                  <span>{{ displayedSharedDocuments.length }}个内容</span>
                  <span
                    class="kb-shared-info-join-count"
                    @click="openMemberDialog(selectedSharedKb.id)"
                  >
                    {{ selectedSharedKb.member_count || 0 }}人加入
                  </span>
                </div>
                <p class="kb-shared-info-desc">{{ selectedSharedKb.description || '暂无描述' }}</p>
                <!-- 标签区域 -->
                <div class="kb-info-tags">
                  <template v-if="isAdmin || (selectedSharedKb && myKbIds.has(selectedSharedKb.id))">
                    <TagDropdown
                      v-for="(tag, tagIndex) in selectedSharedKb.tagList || []"
                      :key="`${selectedSharedKb.id}-tag-${tagIndex}`"
                      :parent="selectedSharedKb"
                      :allTags="allTags"
                      :show-add-button="false"
                      type="knowledge_base"
                      @refreshTagBind="(id: string) => refreshTagBind(id)"
                      @getAllTags="getAllTags"
                    >
                      <template #trigger>
                        <el-popover
                          placement="bottom"
                          trigger="hover"
                          :width="160"
                          :show-after="200"
                        >
                          <template #reference>
                            <span class="kb-info-tag">
                              <el-icon><PriceTag /></el-icon>
                              {{ tag.name }}
                            </span>
                          </template>
                          <div class="kb-tags-popover">
                            <div
                              v-for="t in selectedSharedKb.tagList"
                              :key="t.iid"
                              class="kb-tags-popover-item"
                            >
                              <el-icon><PriceTag /></el-icon>
                              {{ t.name }}
                            </div>
                          </div>
                        </el-popover>
                      </template>
                    </TagDropdown>
                    <TagDropdown
                      v-if="!selectedSharedKb.tagList || selectedSharedKb.tagList.length === 0"
                      :parent="selectedSharedKb"
                      :allTags="allTags"
                      :show-add-button="true"
                      type="knowledge_base"
                      @refreshTagBind="(id: string) => refreshTagBind(id)"
                      @getAllTags="getAllTags"
                    />
                  </template>
                  <template v-else>
                    <el-popover
                      v-for="(tag, tagIndex) in selectedSharedKb.tagList || []"
                      :key="`${selectedSharedKb.id}-tag-${tagIndex}`"
                      placement="bottom"
                      trigger="hover"
                      :width="200"
                      :show-after="200"
                    >
                      <template #reference>
                        <span class="kb-info-tag">
                          <el-icon><PriceTag /></el-icon>
                          {{ tag.name }}
                        </span>
                      </template>
                      <div class="kb-tags-popover">
                        <div
                          v-for="t in selectedSharedKb.tagList"
                          :key="t.iid"
                          class="kb-tags-popover-item"
                        >
                          <el-icon><PriceTag /></el-icon>
                          {{ t.name }}
                        </div>
                      </div>
                    </el-popover>
                  </template>
                </div>
              </div>
            </div>
            <div class="kb-shared-info-actions">
              <!-- <button class="kb-icon-action" title="分享" @click="shareSharedKb">
                <Promotion />
              </button> -->
              <button
                v-if="isAdmin || (selectedSharedKb && myKbIds.has(selectedSharedKb.id))"
                class="kb-icon-action"
                title="更多"
                @click="openSharedMoreMenu($event)"
              >
                <MoreFilled />
              </button>
            </div>
          </div>

          <!-- 内容区标题栏 -->
          <div class="kb-shared-content-header">
            <div class="kb-shared-search-bar">
              <div class="kb-shared-search-input-wrap">
                <el-icon class="kb-shared-search-icon"><Search /></el-icon>
                <input
                  ref="sharedSearchInputRef"
                  v-model="sharedSearchQuery"
                  type="text"
                  placeholder="在知识库中搜索"
                  class="kb-shared-search-input"
                  @keydown.esc="closeSharedSearch"
                />
                <button
                  v-if="sharedSearchQuery"
                  class="kb-shared-search-clear"
                  title="清除"
                  @click="clearSharedSearch"
                >
                  <Close />
                </button>
              </div>
            </div>
            <div class="kb-shared-content-actions">
              <button
                class="kb-icon-action-sm"
                :title="sharedDocViewMode === 'grid' ? '列表视图' : '宫格视图'"
                @click="toggleSharedDocViewMode"
              >
                <Menu v-if="sharedDocViewMode === 'grid'" />
                <Grid v-else />
              </button>
              <button class="kb-icon-action-sm" :title="`排序：${getSortLabel(sharedSortOrder)}`" @click="cycleSharedSort">
                <Sort />
              </button>
              <button class="kb-icon-action-sm" title="添加文档" @click="handleUploadClick(selectedSharedKb)">
                <DocumentAdd />
              </button>
            </div>
          </div>

          <!-- 共享知识库文档列表 -->
          <div v-if="sharedDocViewMode === 'list'" class="kb-shared-doc-list">
            <!-- 加载中 -->
            <div v-if="sharedDocsLoading" class="kb-doc-loading">
              <Document /> <span>正在加载文档...</span>
            </div>
            <!-- 文档列表 -->
            <div
              v-for="doc in displayedSharedDocuments"
              :key="doc.id"
              class="kb-doc-list-item"
              :title="doc.name"
              @click="previewDocument(doc)"
            >
              <div class="kb-doc-list-thumb" :style="{ background: getFileTypeInfo(doc).bg, color: getFileTypeInfo(doc).color, borderColor: getFileTypeInfo(doc).color + '30' }">
                <span class="kb-doc-list-thumb-ext">{{ getFileTypeInfo(doc).label }}</span>
              </div>
              <div class="kb-doc-list-info">
                <div class="kb-doc-list-name">{{ doc.name }}</div>
                <div class="kb-doc-list-meta">
                  <span class="kb-doc-type" :class="(doc.type || doc.data_source_type || 'file').toLowerCase()">
                    {{ getFileTypeInfo(doc).label }}
                  </span>
                  <span class="kb-doc-date">{{ doc.updateTime || '—' }}</span>
                </div>
              </div>
            </div>
            <!-- 空状态 -->
            <div v-if="!sharedDocsLoading && displayedSharedDocuments.length === 0" class="kb-shared-empty-state">
              <div class="kb-shared-empty-illustration">
                <svg viewBox="0 0 200 120" class="kb-shared-empty-plane" xmlns="http://www.w3.org/2000/svg">
                  <path class="kb-shared-empty-trail" d="M20,90 Q60,30 100,70 T180,40" fill="none" stroke="currentColor" stroke-width="1.5" stroke-dasharray="4 4" />
                  <g class="kb-shared-empty-paper-plane" transform="translate(170, 28) rotate(-15)">
                    <path d="M0,0 L24,8 L0,16 L6,8 Z" fill="currentColor" />
                  </g>
                </svg>
              </div>
              <p class="kb-shared-empty-text">知识库什么也没有，去这里添加</p>
            </div>
          </div>

          <!-- 共享知识库宫格视图 -->
          <div v-else class="kb-doc-grid kb-shared-doc-grid">
            <div v-if="sharedDocsLoading" class="kb-doc-loading">
              <Document /> <span>正在加载文档...</span>
            </div>
            <div
              v-for="doc in displayedSharedDocuments"
              :key="doc.id"
              class="kb-doc-card"
              :title="doc.name"
              @click="previewDocument(doc)"
            >
              <div class="kb-doc-thumb" :style="{ background: getFileTypeInfo(doc).bg, color: getFileTypeInfo(doc).color, borderColor: getFileTypeInfo(doc).color + '40' }">
                <span class="kb-doc-thumb-ext">{{ getFileTypeInfo(doc).label }}</span>
              </div>
              <div class="kb-doc-name">{{ doc.name }}</div>
              <div class="kb-doc-footer">
                <span class="kb-doc-type" :class="(doc.type || doc.data_source_type || 'file').toLowerCase()">
                  {{ getFileTypeInfo(doc).label }}
                </span>
                <span class="kb-doc-date">{{ doc.updateTime || '—' }}</span>
              </div>
            </div>
            <div v-if="!sharedDocsLoading && displayedSharedDocuments.length === 0" class="kb-shared-empty-state">
              <div class="kb-shared-empty-illustration">
                <svg viewBox="0 0 200 120" class="kb-shared-empty-plane" xmlns="http://www.w3.org/2000/svg">
                  <path class="kb-shared-empty-trail" d="M20,90 Q60,30 100,70 T180,40" fill="none" stroke="currentColor" stroke-width="1.5" stroke-dasharray="4 4" />
                  <g class="kb-shared-empty-paper-plane" transform="translate(170, 28) rotate(-15)">
                    <path d="M0,0 L24,8 L0,16 L6,8 Z" fill="currentColor" />
                  </g>
                </svg>
              </div>
              <p class="kb-shared-empty-text">知识库什么也没有，去这里添加</p>
            </div>
          </div>
        </div>

        <!-- 右侧：对话模块 -->
        <div class="kb-shared-right">
          <KbChatPanel
            v-model="chatInput"
            :messages="chatMessages"
            :loading="chatLoading"
            placeholder="基于知识库提问"
            :model-options="chatModelOptions"
            :active-model-id="activeChatModelId"
            :attached-files="chatAttachedFiles"
            :kb-name="selectedSharedKb?.name || ''"
            :suggested-questions="postAnswerSuggestions"
            @submit="sendChatMessage"
            @copy="copyMessageContent"
            @regenerate="regenerateMessage"
            @select-model="selectChatModel"
            @file-selected="handleChatFileSelected"
            @remove-file="removeChatAttachedFile"
            @suggest-click="handleSuggestedClick"
            @new-conversation="startNewConversation"
          />
        </div>
      </div>

      <div v-else-if="activeMenu === 'shared'" class="kb-empty">
        <p>请选择一个共享知识库查看内容</p>
      </div>
    </main>

    <!-- 知识库更多下拉菜单（个人/共享通用，Teleport 到 body，避免被遮挡） -->
    <Teleport to="body">
      <div
        v-if="sharedMoreMenuVisible"
        class="kb-shared-more-menu"
        :style="{ top: sharedMoreMenuPos.top + 'px', left: sharedMoreMenuPos.left + 'px' }"
        @click.stop
      >

        <div class="kb-shared-more-item" @click="handleSharedMoreAction('editKb')">
          <el-icon><EditPen /></el-icon>
          <span>编辑知识库</span>
        </div>
        <div class="kb-shared-more-divider"></div>
        <div class="kb-shared-more-item kb-shared-more-item-danger" @click="handleSharedMoreAction('deleteKb')">
          <el-icon><Delete /></el-icon>
          <span>删除知识库</span>
        </div>
      </div>
    </Teleport>

    <!-- 知识库管理弹窗：新建 / 编辑 -->
    <el-dialog v-model="mngDialogVisible" :title="mngDialogTitle" width="650px" :close-on-click-modal="false">
      <el-form ref="mngFormRef" :model="mngFormData" :rules="mngFormRules" label-width="100px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="mngFormData.name" placeholder="请输入知识库名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="mngFormData.description"
            type="textarea"
            :rows="3"
            placeholder="请输入知识库描述"
          />
        </el-form-item>
        <el-form-item label="索引技术" v-if="!isEditingKb">
          <el-select v-model="mngFormData.indexing_technique" style="width: 100%">
            <el-option label="高质量（推荐）" value="high_quality" />
            <el-option label="经济模式" value="economy" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型" v-if="!isEditingKb" prop="embedding_model">
          <el-select
            v-model="mngFormData.embedding_model"
            style="width: 100%"
            placeholder="请选择Embedding模型"
            clearable
            @change="handleEmbeddingModelChange"
          >
            <el-option
              v-for="item in embeddingModelOptions"
              :key="`${item.provider}/${item.model}`"
              :label="item.label"
              :value="item.model"
            />
          </el-select>
        </el-form-item>

        <el-divider content-position="left">权限配置（应用层权限，非 Dify）</el-divider>

        <el-form-item label="权限类型">
          <el-radio-group v-model="mngFormData.permissionType">
            <el-radio value="public">所有人可见</el-radio>
            <el-radio value="only_me">仅个人可见</el-radio>
            <el-radio value="partial_invisible">部分不可见</el-radio>
          </el-radio-group>
        </el-form-item>

        <template v-if="mngFormData.permissionType === 'partial_invisible'">
          <el-form-item label="部门权限">
            <el-tag type="warning" size="small" style="margin-bottom: 8px">选中的部门将看不到该知识库</el-tag>
            <div class="mng-tree-container">
              <el-tree
                ref="treeRef"
                :data="deptTree"
                :props="defaultProps"
                node-key="unitsno"
                :default-checked-keys="mngFormData.selectedDepts"
                @check-change="handleDeptCheckChange"
                show-checkbox
                default-expand-all
              />
            </div>
          </el-form-item>
          <el-form-item label="角色权限">
            <el-tag type="warning" size="small" style="margin-bottom: 8px">选中的角色将看不到该知识库</el-tag>
            <div class="mng-role-container">
              <el-checkbox-group v-model="mngFormData.selectedRoles">
                <el-checkbox
                  v-for="role in roleList"
                  :key="role.iid"
                  :label="role.iid"
                >
                  {{ role.name }}
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="mngDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitMngForm">确定</el-button>
      </template>
    </el-dialog>

    <!-- Tag Manage Dialog -->
    <TagManageDialog
      v-model:visible="tagManageVisible"
      :tags="allTags"
      type="knowledge_base"
      @refresh="getAllTags"
    />

    <!-- 文档预览对话框 -->
    <DocumentPreviewDialog
      v-model:visible="previewVisible"
      :document="previewDocumentInfo"
      :dataset-id="previewDatasetId"
      :chunks="previewChunks"
      :loading="previewLoading"
      :error-message="previewError"
      @load-text="handlePreviewLoadText"
    />

    <!-- Create Knowledge Base Dialog -->
    <el-dialog
      v-model="createDialogVisible"
      title="新建知识库"
      width="520px"
      :close-on-click-modal="false"
      class="kb-create-dialog"
    >
      <div class="kb-create-form">
        <div class="kb-form-row">
          <label class="kb-form-label">
            <span class="kb-required">*</span>名称：
          </label>
          <div class="kb-form-input-wrap">
            <input
              v-model="createForm.name"
              type="text"
              placeholder="请输入知识库名称"
              maxlength="20"
              class="kb-form-input"
            />
            <span class="kb-form-count">{{ createForm.name.length }}/20</span>
          </div>
        </div>

        <div class="kb-form-row">
          <label class="kb-form-label">封面：</label>
          <div class="kb-cover-selector">
            <div class="kb-cover-preview">
              <img :src="defaultCover" alt="默认封面" />
            </div>
          </div>
        </div>

        <div class="kb-form-row">
          <label class="kb-form-label">描述：</label>
          <div class="kb-form-textarea-wrap">
            <textarea
              v-model="createForm.description"
              placeholder="请输入知识库描述"
              maxlength="100"
              rows="3"
              class="kb-form-textarea"
            ></textarea>
            <span class="kb-form-count">{{ createForm.description.length }}/100</span>
          </div>
        </div>

        <div class="kb-form-row">
          <label class="kb-form-label">共享范围：</label>
          <select v-model="createForm.scope" class="kb-form-select">
            <option value="global">全局</option>
            <option value="department">部门</option>
            <option value="personal">个人</option>
          </select>
        </div>

        <div class="kb-form-row">
          <label class="kb-form-label">权限设置：</label>
          <div class="kb-permission-options">
            <label class="kb-radio-label">
              <input
                v-model="createForm.permissions"
                type="radio"
                value="view_download"
                class="kb-radio"
              />
              <span class="kb-radio-dot"></span>
              可查看、可下载
            </label>
            <label class="kb-radio-label">
              <input
                v-model="createForm.permissions"
                type="radio"
                value="view_only"
                class="kb-radio"
              />
              <span class="kb-radio-dot"></span>
              可查看、不可下载
            </label>
          </div>
        </div>
      </div>

      <template #footer>
        <div class="kb-dialog-footer">
          <button class="kb-btn-confirm" @click="handleCreateConfirm">确 定</button>
        </div>
      </template>
    </el-dialog>

    <!-- Knowledge Base Member Dialog (我创建的 / 共享知识库) -->
    <el-dialog
      v-model="showMemberDialog"
      title="知识库成员"
      width="420px"
      :close-on-click-modal="false"
      class="kb-member-dialog"
      @open="memberSearchQuery = ''"
    >
      <div class="kb-member-search">
        <el-icon class="kb-member-search-icon"><Search /></el-icon>
        <input
          v-model="memberSearchQuery"
          type="text"
          placeholder="搜索成员"
          class="kb-member-search-input"
        />
        <button
          v-if="memberSearchQuery"
          class="kb-member-search-clear"
          title="清除"
          @click="memberSearchQuery = ''"
        >
          <Close />
        </button>
      </div>

      <div class="kb-member-list" v-loading="memberLoading">
        <div
          v-for="member in filteredMembers"
          :key="member.userId"
          class="kb-member-item"
        >
          <div class="kb-member-avatar">
            <span>{{ member.userName.charAt(0) }}</span>
          </div>
          <div class="kb-member-info">
            <div class="kb-member-name">{{ member.userName }}</div>
            <div class="kb-member-role">{{ member.isOwner === 'true' ? '所有者' : '成员' }}</div>
          </div>
          <button
            v-if="member.isOwner !== 'true'"
            class="kb-member-remove-btn"
            title="移除成员"
            @click="removeMember(currentMemberDialogKbId, member.userId)"
          >
            <Close />
          </button>
        </div>
        <div v-if="!memberLoading && filteredMembers.length === 0" class="kb-member-empty">
          {{ memberSearchQuery ? '未找到匹配的成员' : '暂无成员' }}
        </div>
      </div>

      <template #footer>
        <el-button @click="showMemberDialog = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- Joined Knowledge Base Member Dialog (我加入的) -->
    <el-dialog
      v-model="showJoinedMemberDialog"
      title="知识库成员"
      width="420px"
      :close-on-click-modal="false"
      class="kb-member-dialog"
      @open="memberSearchQuery = ''"
    >
      <div class="kb-member-search">
        <el-icon class="kb-member-search-icon"><Search /></el-icon>
        <input
          v-model="memberSearchQuery"
          type="text"
          placeholder="搜索成员"
          class="kb-member-search-input"
        />
        <button
          v-if="memberSearchQuery"
          class="kb-member-search-clear"
          title="清除"
          @click="memberSearchQuery = ''"
        >
          <Close />
        </button>
      </div>

      <div class="kb-member-list" v-loading="memberLoading">
        <div
          v-for="member in filteredMembers"
          :key="member.userId"
          class="kb-member-item"
        >
          <div class="kb-member-avatar">
            <span>{{ member.userName.charAt(0) }}</span>
          </div>
          <div class="kb-member-info">
            <div class="kb-member-name">{{ member.userName }}</div>
            <div class="kb-member-role">{{ member.isOwner === 'true' ? '所有者' : '成员' }}</div>
          </div>
        </div>
        <div v-if="!memberLoading && filteredMembers.length === 0" class="kb-member-empty">
          {{ memberSearchQuery ? '未找到匹配的成员' : '暂无成员' }}
        </div>
      </div>

      <template #footer>
        <div class="kb-member-actions">
          <el-button type="danger" @click="leaveKb(currentMemberDialogKbId)">退出知识库</el-button>
          <el-button @click="showJoinedMemberDialog = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="less">
@import './index.less';
</style>

