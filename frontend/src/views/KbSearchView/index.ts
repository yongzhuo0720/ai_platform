import { onBeforeUnmount, onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { kbRagApi, type KbDoc, type KbSearchResultDto } from '@/api/kbRag'
import { debug } from '@/utils/debug'
import { isConfirmCancelled } from '@/utils/confirm'

/**
 * 按扩展名提取文档文本：txt/md 直接读；pdf 用 pdfjs；docx 用 mammoth。
 * 解析器全部动态 import——只有真的上传对应格式才加载（pdfjs ~350KB）。
 */
async function extractTextFromFile(file: File): Promise<string> {
  const lower = file.name.toLowerCase()
  if (lower.endsWith('.pdf')) {
    const pdfjs = await import('pdfjs-dist/legacy/build/pdf.min.js')
    pdfjs.GlobalWorkerOptions.workerSrc = new URL(
      'pdfjs-dist/legacy/build/pdf.worker.js',
      import.meta.url,
    ).toString()
    const buf = await file.arrayBuffer()
    const doc = await pdfjs.getDocument({ data: buf }).promise
    const parts: string[] = []
    for (let i = 1; i <= doc.numPages; i++) {
      const page = await doc.getPage(i)
      const content = await page.getTextContent()
      parts.push(content.items.map((it: { str?: string }) => it.str || '').join(' '))
    }
    return parts.join('\n')
  }
  if (lower.endsWith('.docx')) {
    const mammoth = await import('mammoth/mammoth.browser')
    const buf = await file.arrayBuffer()
    const result = await mammoth.extractRawText({ arrayBuffer: buf })
    return result.value || ''
  }
  // txt / md / markdown 及其他文本类：直接读
  return await new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(new Error('读取文件失败'))
    reader.readAsText(file, 'utf-8')
  })
}

const MAX_FILE_BYTES = 20 * 1024 * 1024
const MAX_DOC_CHARS = 200000
const POLL_INTERVAL_MS = 2000
/** 服务端可直接解析的文本类文件：原样上传，避免浏览器与后端两次解码结果不一致 */
const SERVER_PARSE_TYPES = new Set(['txt', 'md', 'markdown', 'csv', 'json', 'log'])
/** 浏览器解析、只提交提取后正文的类型 */
const CLIENT_PARSE_TYPES = new Set(['pdf', 'docx'])
const ACTIVE_STATUSES = new Set(['queued', 'chunking', 'embedding'])

function extensionOf(fileName: string): string {
  const dot = fileName.lastIndexOf('.')
  return dot < 0 ? '' : fileName.slice(dot + 1).toLowerCase()
}

export function useIndex() {
  const state = reactive({
    docs: [] as KbDoc[],
    docsLoading: false,
    uploadDialogVisible: false,
    uploading: false,
    uploadForm: {
      name: '',
      kbName: '默认知识库',
      content: '',
      fileName: '',
      fileType: '',
      sizeBytes: 0,
    },
    searching: false,
    searchForm: {
      query: '',
      topK: 5,
    },
    searchResult: null as KbSearchResultDto | null,
  })

  // 原始 File 不放进 reactive：Vue 的 Proxy 会包裹原生对象，个别浏览器下读文件会失效
  let pendingFile: File | null = null
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let disposed = false
  // 连续选择文件时，只接受最后一次解析结果，避免慢解析覆盖新选择
  let parseToken = 0

  onMounted(() => {
    loadDocs()
  })
  onBeforeUnmount(() => {
    disposed = true
    stopPolling()
  })

  function hasActiveDocs(): boolean {
    return state.docs.some((doc) => ACTIVE_STATUSES.has(doc.status))
  }

  function stopPolling() {
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  /** 只在还有处理中的文档时轮询；文档全部就绪/失败后自动停止 */
  function schedulePoll() {
    if (disposed || pollTimer || !hasActiveDocs()) {
      return
    }
    pollTimer = setTimeout(async () => {
      pollTimer = null
      await loadDocs({ silent: true })
      // 请求期间组件可能已卸载：不能再安排下一轮
      if (disposed) return
      schedulePoll()
    }, POLL_INTERVAL_MS)
  }

  async function loadDocs(options: { silent?: boolean } = {}) {
    if (!options.silent) state.docsLoading = true
    try {
      const { data: res } = await kbRagApi.listDocs()
      if (res.resp_code === 0) {
        state.docs = res.datas ?? []
        if (hasActiveDocs()) {
          schedulePoll()
        } else {
          stopPolling()
        }
      } else if (!options.silent) {
        ElMessage.error(res.resp_msg || '获取文档列表失败')
      }
    } catch (err) {
      debug.error('获取知识库文档列表失败', err)
      if (!options.silent) {
        ElMessage.error('获取文档列表失败，请稍后重试')
      }
    } finally {
      if (!options.silent) state.docsLoading = false
    }
  }

  function resetUploadForm() {
    parseToken++
    pendingFile = null
    state.uploadForm = {
      name: '',
      kbName: '默认知识库',
      content: '',
      fileName: '',
      fileType: '',
      sizeBytes: 0,
    }
  }

  function openUploadDialog() {
    resetUploadForm()
    state.uploadDialogVisible = true
  }

  function clearSelectedFile(clearAutoName = false) {
    parseToken++
    pendingFile = null
    state.uploadForm.fileName = ''
    state.uploadForm.fileType = ''
    state.uploadForm.sizeBytes = 0
    if (clearAutoName) state.uploadForm.name = ''
  }

  /** 用户手动粘贴正文：放弃已选文件，避免「文件优先」导致粘贴内容被忽略 */
  function handleContentInput() {
    clearSelectedFile()
  }

  async function handleFileChange(event: Event) {
    const input = event.target as HTMLInputElement
    const file = input.files?.[0]
    if (!file) return
    input.value = ''
    if (file.size > MAX_FILE_BYTES) {
      ElMessage.warning('文件过大，单篇最多 20MB')
      return
    }
    const extension = extensionOf(file.name)
    if (!SERVER_PARSE_TYPES.has(extension) && !CLIENT_PARSE_TYPES.has(extension)) {
      ElMessage.warning('仅支持 txt / md / csv / json / log / pdf / docx 文档')
      return
    }

    const token = ++parseToken
    const autoName = !state.uploadForm.name.trim()
    state.uploadForm.fileName = file.name
    state.uploadForm.fileType = extension
    state.uploadForm.sizeBytes = file.size
    if (autoName) {
      state.uploadForm.name = file.name
    }
    // 换了文件就必须丢掉上一份正文：否则新文件解析失败时，旧正文会被当成本次输入提交
    state.uploadForm.content = ''

    if (SERVER_PARSE_TYPES.has(extension)) {
      // 原文上传：后端统一做编码探测（UTF-8 / GBK）与分块
      pendingFile = file
      return
    }

    pendingFile = null
    ElMessage.info('正在解析文档…')
    try {
      const text = await extractTextFromFile(file)
      // 解析期间用户又选了别的文件：丢弃本次结果
      if (token !== parseToken) return
      if (!text.trim()) {
        ElMessage.warning('未能从文档中提取到文本（可能是扫描件/纯图片 PDF）')
        clearSelectedFile(autoName)
        return
      }
      if (text.length > MAX_DOC_CHARS) {
        ElMessage.warning(
          `提取文本 ${text.length} 字符，超过单篇 20 万上限，请拆分后再上传`
        )
        clearSelectedFile(autoName)
        return
      }
      state.uploadForm.content = text
    } catch (e) {
      if (token !== parseToken) return
      debug.error('文档解析失败', e)
      ElMessage.error('文档解析失败，请确认文件未损坏')
      clearSelectedFile(autoName)
    }
  }

  async function submitUpload() {
    const form = state.uploadForm
    const name = form.name.trim()
    if (!name) {
      ElMessage.warning('请填写文档名称')
      return
    }
    const kbName = form.kbName.trim() || '默认知识库'
    if (!pendingFile && !form.content.trim()) {
      ElMessage.warning('请选择文件或粘贴文档内容')
      return
    }

    state.uploading = true
    try {
      const { data: res } = pendingFile
        ? await kbRagApi.uploadFile(pendingFile, { name, kbName })
        : await kbRagApi.uploadContent({
            name,
            kbName,
            content: form.content,
            fileType: form.fileType || 'txt',
            fileName: form.fileName || undefined,
            sizeBytes: form.sizeBytes || undefined,
          })
      if (res.resp_code === 0) {
        ElMessage.success(res.resp_msg || '文档已受理，正在后台处理')
        state.uploadDialogVisible = false
        resetUploadForm()
        await loadDocs()
        schedulePoll()
      } else {
        ElMessage.error(res.resp_msg || '上传失败')
      }
    } catch (err) {
      debug.error('上传知识库文档失败', err)
      ElMessage.error('上传失败，请稍后重试')
    } finally {
      state.uploading = false
    }
  }

  async function handleRetry(row: KbDoc) {
    if (!row.iid) return
    try {
      const { data: res } = await kbRagApi.retryDoc(row.iid)
      if (res.resp_code === 0) {
        ElMessage.success(res.resp_msg || '文档已重新排队')
        await loadDocs()
        schedulePoll()
      } else {
        ElMessage.error(res.resp_msg || '重试失败')
      }
    } catch (err) {
      debug.error('重试知识库文档失败', err)
      ElMessage.error('重试失败，请稍后重试')
    }
  }

  async function handleDelete(row: KbDoc) {
    try {
      await ElMessageBox.confirm(
        `确定删除文档「${row.name}」吗？其分块与向量将一并删除。`,
        '删除确认',
        { type: 'warning' }
      )
    } catch {
      return
    }
    try {
      const { data: res } = await kbRagApi.deleteDoc(row.iid!)
      if (res.resp_code === 0) {
        ElMessage.success('删除成功')
        loadDocs()
      } else {
        ElMessage.error(res.resp_msg || '删除失败')
      }
    } catch (err) {
      if (!isConfirmCancelled(err)) {
        debug.error('删除知识库文档失败', err)
        ElMessage.error('删除失败，请稍后重试')
      }
    }
  }

  async function submitSearch() {
    if (!state.searchForm.query.trim()) {
      ElMessage.warning('请输入检索内容')
      return
    }
    state.searching = true
    state.searchResult = null
    try {
      const { data: res } = await kbRagApi.search({
        query: state.searchForm.query.trim(),
        topK: state.searchForm.topK,
      })
      if (res.resp_code === 0) {
        state.searchResult = res.datas
      } else {
        ElMessage.error(res.resp_msg || '检索失败')
      }
    } catch (err) {
      debug.error('知识库检索失败', err)
      ElMessage.error('检索失败，请稍后重试')
    } finally {
      state.searching = false
    }
  }

  function scoreTagType(score: number): 'success' | 'warning' | 'info' {
    if (score >= 0.5) return 'success'
    if (score >= 0.3) return 'warning'
    return 'info'
  }

  function isActiveDoc(row: KbDoc): boolean {
    return ACTIVE_STATUSES.has(row.status)
  }

  function statusLabel(status: string | null | undefined): string {
    switch (status) {
      case 'queued':
        return '排队中'
      case 'chunking':
        return '分块中'
      case 'embedding':
        return '向量化中'
      case 'ready':
        return '就绪'
      case 'failed':
        return '失败'
      default:
        return '未知'
    }
  }

  function statusTagType(
    status: string | null | undefined
  ): 'success' | 'warning' | 'danger' | 'info' {
    switch (status) {
      case 'ready':
        return 'success'
      case 'failed':
        return 'danger'
      case 'queued':
      case 'chunking':
      case 'embedding':
        return 'warning'
      default:
        return 'info'
    }
  }

  function progressOf(row: KbDoc): number {
    if (row.status === 'ready') return 100
    const progress = row.progress ?? 0
    return Math.max(0, Math.min(100, progress))
  }

  function formatSize(bytes: number | null | undefined): string {
    if (!bytes || bytes <= 0) return '-'
    if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
    return `${Math.max(1, Math.round(bytes / 1024))} KB`
  }

  function formatTime(value: string | number | null | undefined): string {
    if (value === null || value === undefined || value === '') return '-'
    const parsed = new Date(String(value).replace(/\//g, '-').replace(' ', 'T'))
    if (Number.isNaN(parsed.getTime())) return String(value)
    const pad = (num: number) => String(num).padStart(2, '0')
    return `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`
  }

  return {
    state,
    loadDocs,
    openUploadDialog,
    handleFileChange,
    handleContentInput,
    submitUpload,
    handleRetry,
    handleDelete,
    submitSearch,
    scoreTagType,
    isActiveDoc,
    statusLabel,
    statusTagType,
    progressOf,
    formatSize,
    formatTime,
  }
}
