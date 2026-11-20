import axios from './axios'
import type { KnowledgeBase, KnowledgeBaseDocument } from '@/types/knowledge'

/**
 * 本地知识库数据源（2026-11-20）。
 *
 * 项目真实数据在本地 RAG（/api/kbRag/*）：文档带 kbName 字段，没有独立的知识库实体表。
 * 知识库中心页原走 Dify file 模块（未配置），本模块把本地文档按 kbName 分组，
 * 映射成页面期望的 KnowledgeBase / KnowledgeBaseDocument 结构，让页面显示真实数据。
 *
 * 约定：本地知识库的 id 就是 kbName（字符串），文档 id 就是 KbDocBean.iid。
 */

interface LocalKbDoc {
  iid: number | null
  kbName: string
  name: string
  charCount?: number
  chunkCount?: number
  status?: string
  progress?: number | null
  errorMsg?: string | null
  fileType?: string | null
  sizeBytes?: number | null
  createtime?: string | number | null
  embeddingModel?: string | null
}

const fetchDocs = async (): Promise<LocalKbDoc[]> => {
  const res = await axios.post('/ai/api/kbRag/listDocs', {})
  const list = res?.data?.datas
  return Array.isArray(list) ? list : []
}

const DEFAULT_KB = '默认知识库'

/** 文档 → 页面期望的知识库文档结构 */
/** 兼容后端返回的时间戳数字 与 "2026/11/20 19:53:20" 字符串 两种格式 */
const parseCreatedAt = (value: string | number | null | undefined): number => {
  if (value == null || value === '') return 0
  if (typeof value === 'number') return value
  // "2026/11/20 19:53:20" → "2026-11-20T19:53:20"
  const normalized = String(value).replace(/\//g, '-').replace(' ', 'T')
  const ts = Date.parse(normalized)
  return Number.isNaN(ts) ? 0 : ts
}

const toDocument = (doc: LocalKbDoc): KnowledgeBaseDocument => {
  const createdMs = parseCreatedAt(doc.createtime)
  return {
    id: String(doc.iid),
    name: doc.name,
    type: doc.fileType || (doc.name.includes('.') ? doc.name.split('.').pop() : 'text'),
    data_source_type: doc.fileType || 'text',
    size: doc.sizeBytes ? `${(doc.sizeBytes / 1024).toFixed(1)}KB` : undefined,
    tokens: doc.charCount || 0,
    word_count: doc.charCount || 0,
    indexing_status: doc.status === 'ready' ? 'completed' : (doc.status as any) || 'indexing',
    error: doc.errorMsg || undefined,
    created_at: createdMs || undefined,
    updateTime: createdMs
      ? new Date(createdMs > 1e12 ? createdMs : createdMs * 1000).toISOString().split('T')[0]
      : undefined,
    enabled: true,
    // 扩展字段：本地 IID，供删除/预览用
    ...({ iid: doc.iid, status: doc.status, chunkCount: doc.chunkCount } as any),
  }
}

export const localKnowledgeBaseApi = {
  /** 是否使用本地数据源（项目当前固定走本地 RAG） */
  isEnabled: () => true,

  /** 知识库列表：按 kbName 分组 */
  getDatasets: async (): Promise<{ data: KnowledgeBase[]; has_more: boolean; limit: number; total: number; page: number }> => {
    const docs = await fetchDocs()
    const groups = new Map<string, LocalKbDoc[]>()
    for (const doc of docs) {
      const kbName = (doc.kbName || DEFAULT_KB).trim() || DEFAULT_KB
      const bucket = groups.get(kbName) || []
      bucket.push(doc)
      groups.set(kbName, bucket)
    }
    const data: KnowledgeBase[] = []
    for (const [name, group] of groups) {
      const wordCount = group.reduce((s, d) => s + Number(d.charCount || 0), 0)
      const newest = group.reduce((max, d) => {
        const t = Number(d.createtime || 0)
        return t > max ? t : max
      }, 0)
      const allReady = group.every(d => d.status === 'ready')
      data.push({
        id: name,
        name,
        description: `本地知识库 · ${group.length} 篇文档 · ${group.reduce((s, d) => s + Number(d.chunkCount || 0), 0)} 个分块`,
        word_count: wordCount,
        document_count: group.length,
        documents: group.map(toDocument),
        created_at: newest || undefined,
        updated_at: newest || undefined,
        embedding_model: group.find(d => d.embeddingModel)?.embeddingModel || undefined,
        indexing_status: allReady ? 'completed' : 'indexing',
        permission: 'only_me',
        // 标记为本地知识库：无 knowledgebasebean 权限记录，成员/权限类接口会拒绝
        isLocal: true,
      } as KnowledgeBase)
    }
    return { data, has_more: false, limit: 100, total: data.length, page: 1 }
  },

  /** 某知识库下的文档列表：kbId 即 kbName */
  getDocuments: async (kbName: string): Promise<{ data: KnowledgeBaseDocument[]; has_more: boolean; limit: number; total: number; page: number }> => {
    const docs = await fetchDocs()
    const scoped = docs.filter(d => ((d.kbName || DEFAULT_KB).trim() || DEFAULT_KB) === kbName)
    const data = scoped.map(toDocument)
    return { data, has_more: false, limit: 100, total: data.length, page: 1 }
  },

  /** 上传文档到指定知识库 */
  uploadDocument: async (kbName: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    form.append('name', file.name)
    form.append('kbName', kbName)
    return axios.post('/ai/api/kbRag/uploadFile', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },

  /** 删除文档：iid 来自 KnowledgeBaseDocument 扩展字段 */
  deleteDocument: async (iid: number | string) =>
    axios.post('/ai/api/kbRag/deleteDoc', { data: { iid: Number(iid) } }),

  /** 检索测试 */
  search: async (kbName: string, query: string, topK = 5) =>
    axios.post('/ai/api/kbRag/search', { data: { query, kbName, topK } }),
}

export default localKnowledgeBaseApi
