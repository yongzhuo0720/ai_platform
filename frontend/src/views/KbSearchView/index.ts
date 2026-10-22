import { onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { kbRagApi, type KbDoc, type KbSearchResultDto } from '@/api/kbRag'
import { debug } from '@/utils/debug'
import { isConfirmCancelled } from '@/utils/confirm'

const MAX_DOC_CHARS = 200000

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
    },
    searching: false,
    searchForm: {
      query: '',
      topK: 5,
    },
    searchResult: null as KbSearchResultDto | null,
  })

  onMounted(() => {
    loadDocs()
  })

  async function loadDocs() {
    state.docsLoading = true
    try {
      const { data: res } = await kbRagApi.listDocs()
      if (res.resp_code === 0) {
        state.docs = res.datas ?? []
      } else {
        ElMessage.error(res.resp_msg || '获取文档列表失败')
      }
    } catch (err) {
      debug.error('获取知识库文档列表失败', err)
      ElMessage.error('获取文档列表失败，请稍后重试')
    } finally {
      state.docsLoading = false
    }
  }

  function openUploadDialog() {
    state.uploadForm = { name: '', kbName: '默认知识库', content: '' }
    state.uploadDialogVisible = true
  }

  /** 读取本地 txt/md 文本，填入上传弹窗 */
  function handleFileChange(event: Event) {
    const input = event.target as HTMLInputElement
    const file = input.files?.[0]
    if (!file) return
    if (file.size > MAX_DOC_CHARS) {
      ElMessage.warning('文件过大，单篇最多 20 万字符')
      input.value = ''
      return
    }
    const reader = new FileReader()
    reader.onload = () => {
      state.uploadForm.content = String(reader.result ?? '')
      if (!state.uploadForm.name) {
        state.uploadForm.name = file.name
      }
    }
    reader.onerror = () => {
      ElMessage.error('读取文件失败，请重试')
    }
    reader.readAsText(file, 'utf-8')
    input.value = ''
  }

  async function submitUpload() {
    if (!state.uploadForm.name.trim()) {
      ElMessage.warning('请填写文档名称')
      return
    }
    if (!state.uploadForm.content.trim()) {
      ElMessage.warning('请选择文件或粘贴文档内容')
      return
    }
    state.uploading = true
    try {
      const { data: res } = await kbRagApi.uploadDoc({
        name: state.uploadForm.name.trim(),
        kbName: state.uploadForm.kbName.trim() || '默认知识库',
        content: state.uploadForm.content,
      })
      if (res.resp_code === 0) {
        ElMessage.success(res.resp_msg || '文档已入库')
        state.uploadDialogVisible = false
        loadDocs()
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
    submitUpload,
    handleDelete,
    submitSearch,
    scoreTagType,
    formatTime,
  }
}
