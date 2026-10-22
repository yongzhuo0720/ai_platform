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
  Clock,
  Fold,
  MoreFilled,
  Menu,
  Grid,
  PriceTag,
  Check,
} from '@element-plus/icons-vue'
import { ref, computed, nextTick, watch, onMounted, onUnmounted } from 'vue'
import {
  knowledgeChatStreaming,
  retrieveFromDataset,
  getAppParameters,
  getConversations,
  getConversationHistory,
  deleteConversation,
  getSuggestedMessages,
} from '@/api/knowledgeChat'
import type { RetrievalResult, KnowledgeChatFile } from '@/api/knowledgeChat'
import { modelsConfigApi } from '@/api/modelsConfig'
import { uploadFileToDify } from '@/api/request'
import { getCurrentUserId } from '@/utils/user'
import { markKbConversation, unmarkKbConversation, isKbConversation } from '@/utils/conversationSource'
import { parseChatError } from '@/utils/chatError'
import { safeJsonParse } from '@/utils/json'
import type {
  AppParametersResponse,
  NormalizedUserInputField,
  UserInputFormItem,
} from '@/types/chat'
import { ElMessage, ElMessageBox, ElPopover } from 'element-plus'
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
  selectedCategories,
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
  loadConversations()
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
   * ChatView(1776-1779) 和 AgentAssistantView(1342-1344) 都做了这一步，
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
    return { label: key.toUpperCase(), color: '#4da6ff', bg: 'rgba(77, 166, 255, 0.12)' }
  }
  return { label: 'FILE', color: '#4da6ff', bg: 'rgba(77, 166, 255, 0.12)' }
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
    fetchKbRetrieveRecords(newId)
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
    fetchKbRetrieveRecords(newId)
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
  chatMessages.value = []
  chatInput.value = ''
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
  // 加载文档列表和检索结果
  loadSquareDocuments(kb.id)
  fetchKbRetrieveRecords(kb.id)
  // 获取成员列表和标签
  fetchMembers(kb.id)
  refreshTagBind(kb.id)
  // Reset chat when switching KB
  chatMessages.value = []
  chatInput.value = ''
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

const chatDialogVisible = ref(false)
const chatInput = ref('')
const chatMessages = ref<ChatMessage[]>([])
const chatLoading = ref(false)
let chatStopFn: (() => void) | null = null

// 知识库检索结果（选中知识库时预加载）
const kbRetrieveRecords = ref('')
/** 消息回答完成后推荐的后续问题 */
const postAnswerSuggestions = ref<string[]>([])
/** 当前消息的 message_id，用于调用推荐消息接口 */
const lastMessageId = ref('')

async function fetchKbRetrieveRecords(kbId: string) {
  try {
    const records = await retrieveFromDataset(kbId)
    kbRetrieveRecords.value = JSON.stringify(records)
  } catch (e) {
    console.warn('知识库检索失败:', e)
    kbRetrieveRecords.value = ''
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

// ============== 历史会话 ==============
interface Conversation {
  id: string
  name: string
  inputs: Record<string, unknown>
  status: string
  created_at: number
  updated_at: number
}

const conversations = ref<Conversation[]>([])
const activeConversationId = ref('')
const historyVisible = ref(false)
const historyLoading = ref(false)
const historySelectMode = ref(false)
const selectedConversationIds = ref<Set<string>>(new Set())

async function loadConversations() {
  historyLoading.value = true
  try {
    const response = (await getConversations({
      user: getCurrentUserId(),
      limit: 100,
    })) as { data: Conversation[] }
    // 按 localStorage 追踪记录过滤，只显示知识库中心创建的会话
    conversations.value = (response.data || []).filter(
      (c: any) => isKbConversation(c.id)
    )
  } catch (e) {
    console.warn('获取会话列表失败:', e)
    conversations.value = []
  } finally {
    historyLoading.value = false
  }
}

async function loadConversationMessages(conversationId: string) {
  historyLoading.value = true
  try {
    const response = (await getConversationHistory(
      conversationId,
      getCurrentUserId(),
      100
    )) as { data: any[] }
    const messageList: ChatMessage[] = []
    ;(response.data || []).forEach((msg: any) => {
      if (msg.query) {
        messageList.push({ id: `${msg.id}-user`, role: 'user', content: msg.query })
      }
      if (msg.answer) {
        messageList.push({ id: `${msg.id}-assistant`, role: 'assistant', content: msg.answer })
      }
    })
    chatMessages.value = messageList
    activeConversationId.value = conversationId
  } catch (e) {
    console.warn('获取会话历史失败:', e)
    ElMessage.error('获取会话历史失败')
  } finally {
    historyLoading.value = false
  }
}

function handleSelectConversation(conversationId: string) {
  loadConversationMessages(conversationId)
  historyVisible.value = false
}

async function handleDeleteHistory(conversationId: string) {
  try {
    await ElMessageBox.confirm('确定要删除此对话吗？', '删除对话', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await deleteConversation(conversationId, getCurrentUserId())
    conversations.value = conversations.value.filter(c => c.id !== conversationId)
    unmarkKbConversation(conversationId)
    if (activeConversationId.value === conversationId) {
      activeConversationId.value = ''
      chatMessages.value = []
    }
    ElMessage.success('对话已删除')
  } catch (e: any) {
    if (!isConfirmCancelled(e)) {
      console.error('删除对话失败:', e)
    }
  }
}

// ============== 历史会话多选 ==============
function toggleHistorySelectMode() {
  historySelectMode.value = !historySelectMode.value
  if (!historySelectMode.value) {
    selectedConversationIds.value = new Set()
  }
}

function toggleConversationSelection(id: string) {
  const next = new Set(selectedConversationIds.value)
  if (next.has(id)) {
    next.delete(id)
  } else {
    next.add(id)
  }
  selectedConversationIds.value = next
}

function toggleSelectAll() {
  if (selectedConversationIds.value.size === conversations.value.length) {
    selectedConversationIds.value = new Set()
  } else {
    selectedConversationIds.value = new Set(conversations.value.map(c => c.id))
  }
}

async function handleBatchDelete() {
  const ids = [...selectedConversationIds.value]
  if (ids.length === 0) return
  try {
    await ElMessageBox.confirm(`确定要删除选中的 ${ids.length} 个会话吗？`, '批量删除', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
    let deleted = 0
    for (const id of ids) {
      try {
        await deleteConversation(id, getCurrentUserId())
        unmarkKbConversation(id)
        if (activeConversationId.value === id) {
          activeConversationId.value = ''
          chatMessages.value = []
        }
        deleted++
      } catch (e) {
        console.error(`删除会话 ${id} 失败:`, e)
      }
    }
    conversations.value = conversations.value.filter(c => !selectedConversationIds.value.has(c.id))
    selectedConversationIds.value = new Set()
    historySelectMode.value = false
    if (deleted > 0) {
      ElMessage.success(`已删除 ${deleted} 个会话`)
    }
  } catch (e: any) {
    if (!isConfirmCancelled(e)) {
      console.error('批量删除失败:', e)
    }
  }
}

function startNewConversation() {
  activeConversationId.value = ''
  chatMessages.value = []
  historyVisible.value = false
}

function toggleHistoryPanel() {
  if (!historyVisible.value) {
    loadConversations()
  }
  historyVisible.value = !historyVisible.value
  if (!historyVisible.value) {
    historySelectMode.value = false
    selectedConversationIds.value = new Set()
  }
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

function closeChatDialog() {
  chatDialogVisible.value = false
  if (chatStopFn) {
    chatStopFn()
    chatStopFn = null
  }
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
  if (promptInputVariable.value) {
    currentInputs[promptInputVariable.value] = kbRetrieveRecords.value || ''
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

  // 使用选中知识库时预加载的检索结果展示
  // （safeJsonParse：解析失败按无结果处理，但会打印错误，不再静默吞掉）
  const receivedResources = safeJsonParse<RetrievalResult[]>(kbRetrieveRecords.value, [])
  chatMessages.value[assistantIdx].retrieverResources = receivedResources

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
          await loadConversations()
        }
        // 消息完成后获取推荐问题
        try {
          let msgId = lastMessageId.value
          // 兜底：如果 SSE 事件没有 message_id，从会话历史获取最新消息
          if (!msgId && activeConversationId.value) {
            debug.log('[推荐消息] SSE 未捕获 message_id，从会话历史兜底获取')
            const historyRes = await getConversationHistory(activeConversationId.value, getCurrentUserId(), 1)
            const msgs = (historyRes as any)?.data || []
            if (msgs.length > 0) {
              msgId = msgs[0].id
            }
          }
          debug.log('[推荐消息] message_id:', msgId, 'conversation_id:', activeConversationId.value)
          if (msgId) {
            const suggestions = await getSuggestedMessages(msgId, getCurrentUserId())
            debug.log('[推荐消息] 获取成功:', suggestions)
            postAnswerSuggestions.value = suggestions
          } else {
            console.warn('[推荐消息] 无法获取 message_id，跳过推荐问题')
          }
        } catch (e: any) {
          console.warn('[推荐消息] 获取失败:', e?.message || e)
        }
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

  const receivedResources = safeJsonParse<RetrievalResult[]>(kbRetrieveRecords.value, [])
  chatMessages.value[newAssistantIdx].retrieverResources = receivedResources

  const currentInputs: Record<string, unknown> = { ...userInput.value }
  currentInputs.source = 'knowledge_base'
  if (modelNameVariable.value) {
    currentInputs[modelNameVariable.value] = activeChatModelId.value
  }
  if (promptInputVariable.value) {
    currentInputs[promptInputVariable.value] = kbRetrieveRecords.value || ''
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
          await loadConversations()
        }
        // 消息完成后获取推荐问题
        try {
          let msgId = lastMessageId.value
          if (!msgId && activeConversationId.value) {
            debug.log('[推荐消息] SSE 未捕获 message_id，从会话历史兜底获取')
            const historyRes = await getConversationHistory(activeConversationId.value, getCurrentUserId(), 1)
            const msgs = (historyRes as any)?.data || []
            if (msgs.length > 0) {
              msgId = msgs[0].id
            }
          }
          debug.log('[推荐消息] message_id:', msgId, 'conversation_id:', activeConversationId.value)
          if (msgId) {
            const suggestions = await getSuggestedMessages(msgId, getCurrentUserId())
            debug.log('[推荐消息] 获取成功:', suggestions)
            postAnswerSuggestions.value = suggestions
          } else {
            console.warn('[推荐消息] 无法获取 message_id，跳过推荐问题')
          }
        } catch (e: any) {
          console.warn('[推荐消息] 获取失败:', e?.message || e)
        }
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

function clearChatMessages() {
  if (chatMessages.value.length === 0) return
  ElMessageBox.confirm(
    '确定要清空当前知识库的所有对话内容吗？此操作不可恢复。',
    '清除会话',
    {
      confirmButtonText: '清除',
      cancelButtonText: '取消',
      type: 'warning',
    }
  ).then(() => {
    chatMessages.value = []
    ElMessage.success('会话已清空')
  }).catch(() => {
    // 用户取消，不做任何操作
  })
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
          <button class="kb-add-btn" @click.stop="openMngCreateDialog" title="新建知识库">
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
            {{ selectedCategories.length > 0 ? '没有符合标签的共享知识库' : '暂无共享知识库' }}
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
            {{ selectedCategories.length > 0 ? '没有符合标签的订阅知识库' : '暂无加入的知识库' }}
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

      <div v-else-if="activeMenu === 'personal'" class="kb-empty">
        <p>请选择一个知识库查看内容</p>
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
      <el-form :model="mngFormData" :rules="mngFormRules" label-width="100px">
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
        <el-button type="primary" @click="handleMngSubmit">确定</el-button>
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
      title="创建知识库"
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

    <!-- Chat Dialog -->
    <Transition name="chat-fade">
      <div v-if="chatDialogVisible" class="chat-overlay">
        <div class="chat-dialog">
          <div class="chat-header">
            <div class="chat-header-left">
              <h3 class="chat-title">问询知识库</h3>
            </div>
            <div class="chat-header-right">
              <button class="chat-header-btn chat-clear-btn" @click="clearChatMessages" title="清除会话">
                <el-icon><Delete /></el-icon>
              </button>
              <button class="chat-header-btn chat-close-btn" @click="closeChatDialog" title="关闭">
                <el-icon><Close /></el-icon>
              </button>
            </div>
          </div>
          <KbChatPanel
            v-model="chatInput"
            :messages="chatMessages"
            :loading="chatLoading"
            placeholder="请输入您的问题..."
            :model-options="chatModelOptions"
            :active-model-id="activeChatModelId"
            :attached-files="chatAttachedFiles"
            :kb-name="getCurrentChatKb()?.name || ''"
            :suggested-questions="postAnswerSuggestions"
            @submit="sendChatMessage"
            @copy="copyMessageContent"
            @regenerate="regenerateMessage"
            @select-model="selectChatModel"
            @file-selected="handleChatFileSelected"
            @remove-file="removeChatAttachedFile"
            @suggest-click="handleSuggestedClick"
          />
        </div>
      </div>
    </Transition>

    <!-- ========== 历史会话悬浮按钮和面板 ========== -->
    <button
      v-if="!showSquareView && !squareDetailKbId"
      class="kb-history-fab"
      :class="{ 'is-active': historyVisible }"
      title="历史会话"
      @click="toggleHistoryPanel"
    >
      <el-icon :size="20"><Clock /></el-icon>
    </button>

    <Transition name="kb-history-slide">
      <div v-if="historyVisible" class="kb-history-panel">
        <div class="kb-history-panel-header">
          <span>历史会话</span>
          <div class="kb-history-panel-header-actions">
            <button
              v-if="conversations.length > 0 && !historySelectMode"
              class="kb-history-manage-btn"
              @click="toggleHistorySelectMode"
            >
              管理
            </button>
            <button
              v-if="historySelectMode"
              class="kb-history-manage-btn"
              @click="toggleHistorySelectMode"
            >
              完成
            </button>
            <button class="kb-history-panel-close" @click="historyVisible = false">
              <el-icon><Close /></el-icon>
            </button>
          </div>
        </div>

        <button v-if="!historySelectMode" class="kb-history-new-btn" @click="startNewConversation">
          <el-icon><Plus /></el-icon>
          <span>新建会话</span>
        </button>

        <div v-if="historyLoading" class="kb-history-loading">加载中...</div>

        <div v-else-if="conversations.length === 0" class="kb-history-empty">
          暂无历史会话
        </div>

        <ul v-else class="kb-history-list">
          <li
            v-for="item in conversations"
            :key="item.id"
            class="kb-history-item"
            :class="{ active: !historySelectMode && activeConversationId === item.id }"
            @click="historySelectMode ? toggleConversationSelection(item.id) : handleSelectConversation(item.id)"
          >
            <span v-if="historySelectMode" class="kb-history-checkbox" @click.stop>
              <span
                class="kb-history-checkbox-box"
                :class="{ checked: selectedConversationIds.has(item.id) }"
                @click.stop="toggleConversationSelection(item.id)"
              >
                <el-icon v-if="selectedConversationIds.has(item.id)" :size="12"><Check /></el-icon>
              </span>
            </span>
            <span class="kb-history-item-name">{{ item.name }}</span>
            <button
              v-if="!historySelectMode"
              class="kb-history-item-delete"
              @click.stop="handleDeleteHistory(item.id)"
            >
              <el-icon :size="14"><Delete /></el-icon>
            </button>
          </li>
        </ul>

        <!-- 批量操作栏 -->
        <div v-if="historySelectMode && conversations.length > 0" class="kb-history-batch-bar">
          <button class="kb-history-batch-btn" @click="toggleSelectAll">
            {{ selectedConversationIds.size === conversations.length ? '取消全选' : '全选' }}
          </button>
          <button
            class="kb-history-batch-btn danger"
            :disabled="selectedConversationIds.size === 0"
            @click="handleBatchDelete"
          >
            删除{{ selectedConversationIds.size > 0 ? ` (${selectedConversationIds.size})` : '' }}
          </button>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped lang="less">
@import './index.less';

// ============== Chat Bar (Bottom Input) ==============
.kb-chat-bar {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  background: rgba(16, 30, 80, 0.55);
  backdrop-filter: blur(16px) saturate(160%);
  border-top: 1px solid rgba(100, 150, 255, 0.18);
  padding: 16px 24px;
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    background: rgba(20, 40, 95, 0.65);
  }
}

.kb-chat-bar-input {
  max-width: 600px;
  margin: 0 auto;
}

.kb-chat-bar-input-field {
  width: 100%;
  padding: 10px 16px;
  background: rgba(0, 10, 40, 0.35);
  border: 1px solid rgba(100, 150, 255, 0.22);
  border-radius: 6px;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.5);
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    background: rgba(0, 10, 40, 0.45);
    border-color: #4da6ff;
    color: rgba(255, 255, 255, 0.75);
  }
}

.kb-chat-bar-footer {
  text-align: center;
  margin-top: 8px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.45);
}

// ============== Chat Overlay ==============
.chat-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 5, 25, 0.65);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 20px;
}

.chat-fade-enter-active,
.chat-fade-leave-active {
  transition: opacity 0.3s ease;
}

.chat-fade-enter-from,
.chat-fade-leave-to {
  opacity: 0;
}

// ============== Chat Dialog ==============
.chat-dialog {
  width: 100%;
  max-width: 800px;
  height: 80vh;
  max-height: 700px;
  background: var(--qwen-bg);
  border: 1px solid var(--qwen-border);
  border-radius: var(--qwen-radius-lg);
  box-shadow: var(--qwen-shadow);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  animation: chat-slide-up 0.3s ease;
}

@keyframes chat-slide-up {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

// ============== Chat Header ==============
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--qwen-border);
  background: var(--qwen-surface);
  flex-shrink: 0;
}

.chat-header-left {
  flex: 1;
}

.chat-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--qwen-text-primary);
  margin: 0;
}

.chat-header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.chat-header-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: transparent;
  border: 1px solid var(--qwen-border);
  border-radius: var(--radius-sm);
  color: var(--qwen-text-secondary);
  cursor: pointer;
  transition: all 0.2s;

  .el-icon {
    font-size: 16px;
  }

  &:hover {
    background: var(--qwen-surface);
    color: var(--qwen-brand-blue);
    border-color: var(--qwen-border-strong);
  }
}

.chat-clear-btn {
  &:hover {
    background: rgba(245, 158, 11, 0.15);
    color: #f59e0b;
    border-color: rgba(245, 158, 11, 0.4);
  }
}

.chat-close-btn {
  &:hover {
    background: var(--qwen-danger-soft);
    color: var(--error);
    border-color: var(--error);
  }
}

// ============== Chat Messages ==============
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  background: var(--qwen-bg);
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.chat-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--qwen-text-tertiary);
  font-size: 13px;
}

.kb-chat-message-item {
  display: flex;
  gap: 12px;
  width: 100%;
  animation: message-slide-up 0.30s cubic-bezier(0.16, 1, 0.3, 1);

  &.user {
    flex-direction: row-reverse;

    .kb-chat-message-content {
      align-items: flex-end;
    }
  }

  &.assistant {
    .kb-chat-message-content {
      align-items: flex-start;
    }
  }
}

@keyframes message-slide-up {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.kb-chat-message-avatar {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--qwen-surface);
  border: 1px solid var(--qwen-border);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;

  img {
    width: 22px;
    height: 22px;
    object-fit: contain;
  }
}

.kb-chat-message-content {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-width: min(82%, 680px);
  min-width: 0;
}

.kb-chat-message-bubble {
  padding: 12px 18px;
  border-radius: var(--qwen-radius-lg);
  font-size: 15px;
  line-height: 1.65;
  word-break: break-word;
  width: fit-content;
  max-width: 100%;
  transition: box-shadow 150ms ease;

  .markdown-content,
  .user-content {
    font-size: 15px;
    line-height: 1.65;
  }
}

.kb-chat-message-item.user .kb-chat-message-bubble {
  background: var(--qwen-brand-blue);
  color: #ffffff;
  border-bottom-right-radius: 6px;
  box-shadow: 0 4px 12px rgba(26, 74, 140, 0.18);

  &:hover {
    box-shadow: 0 6px 16px rgba(26, 74, 140, 0.24);
  }

  .markdown-content a {
    color: #ffffff;
    border-bottom-color: rgba(255, 255, 255, 0.6);

    &:hover {
      border-bottom-color: #ffffff;
    }
  }

  .markdown-content code {
    background: rgba(255, 255, 255, 0.2);
    color: #ffffff;
  }
}

html[data-theme="light"] .kb-chat-message-item.user .kb-chat-message-bubble {
  background: #2563eb;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.18);

  &:hover {
    box-shadow: 0 6px 16px rgba(37, 99, 235, 0.24);
  }
}

.kb-chat-message-item.assistant .kb-chat-message-bubble {
  background: var(--qwen-surface);
  color: var(--qwen-text-primary);
  border: 1px solid var(--qwen-border);
  border-bottom-left-radius: 6px;
}

.kb-chat-message-bubble.has-thinking {
  background: var(--qwen-surface);
  border: 1px dashed var(--qwen-border);
  color: var(--qwen-text-secondary);
}

.kb-chat-thinking-text {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--qwen-text-secondary);

  &::before {
    content: "";
    width: 14px;
    height: 14px;
    border: 2px solid var(--qwen-border);
    border-top-color: var(--qwen-brand-blue);
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
  }
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

// ============== Source Reference ==============
.kb-chat-source {
  background: var(--qwen-surface);
  border: 1px solid var(--qwen-border);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  max-width: 100%;
}

.kb-chat-source-header {
  margin-bottom: 8px;
}

.kb-chat-source-label {
  font-size: 12px;
  color: var(--qwen-text-secondary);
}

.kb-chat-source-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  background: var(--qwen-bg);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    background: var(--qwen-border);
  }
}

.kb-chat-source-icon {
  font-size: 14px;
  color: var(--qwen-brand-blue);
}

.kb-chat-source-name {
  font-size: 12px;
  color: var(--qwen-brand-blue);
  font-weight: 500;
}

// ============== Message Actions ==============
.kb-chat-message-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 0 2px;
  opacity: 0.7;
  transition: opacity 150ms ease;

  .kb-chat-message-item:hover & {
    opacity: 1;
  }
}

.kb-chat-message-action {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--qwen-text-tertiary);
  cursor: pointer;
  transition: background 150ms, color 150ms, transform 120ms;

  .el-icon {
    font-size: 14px;
  }

  &:hover {
    background: var(--qwen-surface);
    color: var(--qwen-brand-blue);
  }

  &:active {
    transform: scale(0.92);
  }
}

// ============== Chat Input ==============
.chat-input-area {
  padding: 16px 20px;
  background: var(--qwen-surface);
  border-top: 1px solid var(--qwen-border);
  flex-shrink: 0;
}

.chat-input-wrapper {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  background: var(--qwen-bg);
  border: 1px solid var(--qwen-border);
  border-radius: var(--qwen-radius-md);
  padding: 8px 12px;
  transition: all 0.2s;

  &:focus-within {
    border-color: var(--qwen-brand-blue);
    box-shadow: var(--qwen-shadow-focus);
  }
}

.chat-input {
  flex: 1;

  :deep(.el-textarea__inner) {
    border: none;
    border-radius: 0;
    outline: none;
    background: transparent;
    font-size: 15px;
    color: var(--qwen-text-primary);
    font-family: inherit;
    resize: none;
    min-height: 24px;
    line-height: 1.5;
    box-shadow: none;

    &::placeholder {
      color: var(--qwen-text-tertiary);
    }

    &:disabled {
      background: transparent;
      cursor: not-allowed;
      opacity: 0.6;
    }
  }
}

.chat-send-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: var(--qwen-brand-blue);
  color: #ffffff;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.2s;
  flex-shrink: 0;
  box-shadow: 0 4px 12px rgba(26, 74, 140, 0.18);

  .el-icon {
    font-size: 16px;
  }

  &:hover {
    background: var(--qwen-brand-blue-hover);
    transform: translateY(-1px);
    box-shadow: 0 6px 16px rgba(26, 74, 140, 0.24);
  }

  &:active {
    transform: translateY(0);
  }

  &:disabled {
    opacity: 0.35;
    cursor: not-allowed;
  }
}

.chat-input-footer {
  text-align: center;
  margin-top: 8px;
  font-size: 11px;
  color: var(--qwen-text-tertiary);
}

/* ============================================
   浅色模式适配
   ============================================ */
html[data-theme="light"] {
  .kb-chat-bar {
    background: rgba(255, 255, 255, 0.7);
    backdrop-filter: blur(18px) saturate(160%);
    -webkit-backdrop-filter: blur(18px) saturate(160%);
    border-top: 1px solid rgba(13, 48, 100, 0.1);
    box-shadow: 0 -4px 16px rgba(13, 48, 100, 0.08);

    &:hover {
      background: rgba(255, 255, 255, 0.85);
    }
  }

  .kb-chat-bar-input-field {
    background: rgba(255, 255, 255, 0.7);
    border: 1px solid rgba(13, 48, 100, 0.1);
    color: rgb(13, 48, 100);
    font-weight: 500;

    &::placeholder {
      color: #4a6ba8;
    }

    &:hover {
      background: rgba(255, 255, 255, 0.9);
      border-color: #0066ff;
      color: rgb(13, 48, 100);
    }
  }

  .kb-chat-bar-footer {
    color: #4a6ba8;
  }

  .chat-overlay {
    background: rgba(13, 48, 100, 0.25);
    backdrop-filter: blur(4px);
    -webkit-backdrop-filter: blur(4px);
  }

  .kb-history-fab {
    top: 0px;
    right: 0px;
    background: rgba(255, 255, 255, 0.85);
    border: 1px solid rgba(13, 48, 100, 0.15);
    color: rgb(13, 48, 100);
    backdrop-filter: blur(10px);

    &:hover {
      background: rgba(255, 255, 255, 1);
      box-shadow: 0 6px 24px rgba(13, 48, 100, 0.18);
    }

    &.is-active {
      background: rgba(13, 48, 100, 0.08);
      color: rgb(13, 48, 100);
    }
  }

  .kb-history-panel {
    background: rgba(255, 255, 255, 0.96);
    backdrop-filter: blur(20px) saturate(160%);
    border-left: 1px solid rgba(13, 48, 100, 0.1);
    box-shadow: -4px 0 24px rgba(13, 48, 100, 0.1);
  }

  .kb-history-panel-header {
    color: rgb(13, 48, 100);
    border-bottom-color: rgba(13, 48, 100, 0.08);
  }

  .kb-history-panel-close {
    background: rgba(13, 48, 100, 0.06);
    color: rgba(13, 48, 100, 0.5);

    &:hover {
      background: rgba(13, 48, 100, 0.1);
      color: rgb(13, 48, 100);
    }
  }

  .kb-history-new-btn {
    background: rgba(13, 48, 100, 0.05);
    border-color: rgba(13, 48, 100, 0.15);
    color: rgb(13, 48, 100);

    &:hover {
      background: rgba(13, 48, 100, 0.1);
      border-color: rgba(13, 48, 100, 0.3);
    }
  }

  .kb-history-loading,
  .kb-history-empty {
    color: rgba(13, 48, 100, 0.4);
  }

  .kb-history-item {
    &:hover {
      background: rgba(13, 48, 100, 0.06);
    }

    &.active {
      background: rgba(13, 48, 100, 0.1);
    }
  }

  .kb-history-item-name {
    color: rgba(13, 48, 100, 0.85);
  }

  .kb-history-item-delete {
    color: rgba(13, 48, 100, 0.3);

    &:hover {
      background: rgba(220, 50, 50, 0.1);
      color: rgb(220, 50, 50);
    }
  }

  .kb-history-list {
    &::-webkit-scrollbar-thumb {
      background: rgba(13, 48, 100, 0.15);
    }
  }

  // 管理按钮
  .kb-history-manage-btn {
    background: rgba(13, 48, 100, 0.06);
    border-color: rgba(13, 48, 100, 0.2);
    color: rgb(13, 48, 100);

    &:hover {
      background: rgba(13, 48, 100, 0.12);
      border-color: rgba(13, 48, 100, 0.35);
    }
  }

  // 复选框
  .kb-history-checkbox-box {
    border-color: rgba(13, 48, 100, 0.35);
    background: rgba(13, 48, 100, 0.04);

    &.checked {
      background: #0066ff;
      border-color: #0066ff;
      color: #fff;
    }

    &:hover {
      border-color: rgba(0, 102, 255, 0.6);
      background: rgba(0, 102, 255, 0.08);
    }
  }

  // 批量操作栏
  .kb-history-batch-bar {
    background: rgba(248, 250, 255, 0.92);
    border-top-color: rgba(13, 48, 100, 0.1);
  }

  .kb-history-batch-btn {
    color: rgba(13, 48, 100, 0.75);
    border-color: rgba(13, 48, 100, 0.15);
    background: rgba(13, 48, 100, 0.04);

    &:hover:not(:disabled) {
      background: rgba(13, 48, 100, 0.1);
      border-color: rgba(13, 48, 100, 0.3);
    }

    &:disabled {
      opacity: 0.3;
    }

    &.danger {
      color: rgb(220, 50, 50);
      border-color: rgba(220, 50, 50, 0.25);

      &:hover:not(:disabled) {
        background: rgba(220, 50, 50, 0.1);
        border-color: rgba(220, 50, 50, 0.45);
        color: rgb(220, 50, 50);
      }
    }
  }
}

// ============== 历史会话悬浮按钮和面板 ==============
.kb-history-fab {
  position: fixed;
  top: 14px;
  right: 14px;
  z-index: 9990;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, rgba(77, 166, 255, 0.2) 0%, rgba(100, 150, 255, 0.3) 100%);
  border: 1px solid rgba(100, 150, 255, 0.28);
  color: rgba(255, 255, 255, 0.9);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow:
    0 2px 6px rgba(0, 20, 80, 0.18),
    inset 0 1px 0 rgba(255, 255, 255, 0.06);
  padding: 0;
  margin: 0;
  line-height: 1;

  :deep(.el-icon) {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    color: inherit;
  }

  :deep(svg) {
    fill: currentColor;
    width: 18px;
    height: 18px;
    display: block;
  }

  &:hover {
    background: linear-gradient(135deg, rgba(77, 166, 255, 0.38) 0%, rgba(100, 150, 255, 0.28) 100%);
    border-color: rgba(77, 166, 255, 0.55);
    color: #ffffff;
    transform: scale(1.08);
    box-shadow:
      0 6px 16px rgba(26, 74, 140, 0.45),
      0 0 0 1px rgba(77, 166, 255, 0.25),
      inset 0 1px 0 rgba(255, 255, 255, 0.12);
  }

  &.is-active {
    background: linear-gradient(135deg, rgba(77, 166, 255, 0.55) 0%, rgba(100, 150, 255, 0.45) 100%);
    border-color: rgba(77, 166, 255, 0.6);
    color: #ffffff;
  }
}

.kb-history-panel {
  position: fixed;
  top: 0;
  right: 0;
  width: 320px;
  height: 100vh;
  z-index: 9995;
  background: rgba(10, 22, 60, 0.95);
  backdrop-filter: blur(20px) saturate(160%);
  border-left: 1px solid rgba(77, 166, 255, 0.2);
  box-shadow: -4px 0 24px rgba(0, 0, 0, 0.4);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding-bottom: 12px;
  box-sizing: border-box;
}

.kb-history-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 20px 16px;
  border-bottom: 1px solid rgba(77, 166, 255, 0.15);
  font-size: 16px;
  font-weight: 600;
  color: #fff;
  flex-shrink: 0;
}

.kb-history-panel-close {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: none;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    background: rgba(255, 255, 255, 0.15);
    color: #fff;
  }
}

.kb-history-new-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 16px;
  padding: 10px 16px;
  background: rgba(77, 166, 255, 0.15);
  border: 1px dashed rgba(77, 166, 255, 0.35);
  border-radius: 8px;
  font-size: 13px;
  color: rgba(77, 166, 255, 0.9);
  cursor: pointer;
  transition: all 0.2s;
  flex-shrink: 0;

  &:hover {
    background: rgba(77, 166, 255, 0.25);
    border-color: rgba(77, 166, 255, 0.5);
  }
}

.kb-history-loading,
.kb-history-empty {
  padding: 40px 20px;
  text-align: center;
  color: rgba(255, 255, 255, 0.4);
  font-size: 13px;
}

.kb-history-list {
  flex: 1;
  overflow-y: auto;
  list-style: none;
  padding: 4px 8px;
  margin: 0;

  &::-webkit-scrollbar {
    width: 4px;
  }

  &::-webkit-scrollbar-track {
    background: transparent;
  }

  &::-webkit-scrollbar-thumb {
    background: rgba(77, 166, 255, 0.2);
    border-radius: 2px;
  }
}

.kb-history-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
  margin-bottom: 2px;

  &:hover {
    background: rgba(77, 166, 255, 0.12);
  }

  &.active {
    background: rgba(77, 166, 255, 0.2);
  }
}

.kb-history-item-name {
  flex: 1;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.85);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.kb-history-item-delete {
  width: 28px;
  height: 28px;
  border-radius: 4px;
  border: none;
  background: transparent;
  color: rgba(255, 255, 255, 0.3);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
  opacity: 0;
  transition: all 0.15s;

  .kb-history-item:hover & {
    opacity: 1;
  }

  &:hover {
    background: rgba(255, 80, 80, 0.2);
    color: rgba(255, 80, 80, 0.8);
  }
}

// ============== 历史会话多选样式 ==============
.kb-history-panel-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.kb-history-manage-btn {
  padding: 4px 12px;
  border-radius: 4px;
  border: 1px solid rgba(77, 166, 255, 0.3);
  background: rgba(77, 166, 255, 0.12);
  color: rgba(77, 166, 255, 0.9);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;

  &:hover {
    background: rgba(77, 166, 255, 0.22);
    border-color: rgba(77, 166, 255, 0.5);
  }
}

.kb-history-checkbox {
  flex-shrink: 0;
  margin-right: 8px;
  display: flex;
  align-items: center;
}

.kb-history-checkbox-box {
  width: 18px;
  height: 18px;
  border-radius: 4px;
  border: 1.5px solid rgba(255, 255, 255, 0.45);
  background: rgba(255, 255, 255, 0.08);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s;
  color: transparent;

  &.checked {
    background: #4da6ff;
    border-color: #4da6ff;
    color: #fff;
  }

  &:hover {
    border-color: rgba(77, 166, 255, 0.7);
    background: rgba(77, 166, 255, 0.12);
  }
}

.kb-history-batch-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  padding-bottom: max(10px, env(safe-area-inset-bottom, 10px));
  border-top: 1px solid rgba(77, 166, 255, 0.12);
  background: rgba(10, 22, 60, 0.6);
  flex-shrink: 0;
  margin-top: auto;
}

.kb-history-batch-btn {
  padding: 6px 14px;
  border-radius: 4px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  background: rgba(255, 255, 255, 0.06);
  color: rgba(255, 255, 255, 0.75);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;

  &:hover:not(:disabled) {
    background: rgba(255, 255, 255, 0.12);
    border-color: rgba(255, 255, 255, 0.3);
  }

  &:disabled {
    opacity: 0.35;
    cursor: not-allowed;
  }

  &.danger {
    color: rgba(255, 80, 80, 0.85);
    border-color: rgba(255, 80, 80, 0.25);

    &:hover:not(:disabled) {
      background: rgba(255, 80, 80, 0.15);
      border-color: rgba(255, 80, 80, 0.45);
      color: rgba(255, 80, 80, 1);
    }
  }
}

// 面板滑入动画
.kb-history-slide-enter-active,
.kb-history-slide-leave-active {
  transition: transform 0.3s ease, opacity 0.3s ease;
}

.kb-history-slide-enter-from,
.kb-history-slide-leave-to {
  transform: translateX(100%);
  opacity: 0;
}
</style>

