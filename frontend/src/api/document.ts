import type {
  KnowledgeBaseDocument,
  DifyDocumentResponse,
  DifyDocumentListResponse,
  DifyIndexingStatusResponse,
} from '@/types/knowledge';
import { ElMessage } from 'element-plus';
import { getKnowledgeFileConfig, gatewayAuthHeaders } from '@/stores/difyConfig'

const getBase = () => {
  const { apiUrl } = getKnowledgeFileConfig()
  return apiUrl
}

const getKey = () => {
  const { apiKey } = getKnowledgeFileConfig()
  return apiKey
}

const getHeaders = (isJson = true) => {
  const headers: Record<string, string> = {
    'Authorization': `Bearer ${getKey()}`,
    ...gatewayAuthHeaders(),
  };
  if (isJson) {
    headers['Content-Type'] = 'application/json';
  }
  return headers;
};

const isApiEnabled = () => Boolean(getKey())

export interface DocumentListParams {
  page?: number;
  limit?: number;
}

export interface CreateDocumentByTextParams {
  name: string;
  text: string;
  indexing_technique?: 'high_quality' | 'economy';
  doc_form?: string;
  doc_language?: string;
  process_rule?: {
    mode: 'automatic' | 'custom';
    rules?: {
      pre_processing_rules?: Array<{ id: string; enabled: boolean }>;
      segmentation?: { separator: string; max_tokens: number };
      parent_mode?: 'full-doc' | 'parent_doc';
      subchunk_segmentation?: {
        separator: string;
        max_tokens: number;
        chunk_overlap: number;
      };
    };
  };
  retrieval_model?: any;
  embedding_model?: string;
  embedding_model_provider?: string;
  summary_index_setting?: any;
}

export interface UpdateDocumentParams {
  name?: string;
  text?: string;
  process_rule?: {
    mode: 'automatic' | 'custom';
    rules?: {
      pre_processing_rules?: Array<{ id: string; enabled: boolean }>;
      segmentation?: { separator: string; max_tokens: number };
      parent_mode?: 'full-doc' | 'parent_doc';
      subchunk_segmentation?: {
        separator: string;
        max_tokens: number;
        chunk_overlap: number;
      };
    };
  };
}

export interface UpdateDocumentByFileParams {
  name?: string;
  process_rule?: {
    mode: 'automatic' | 'custom';
  };
}

export const documentApi = {
  getDocuments: async (
    datasetId: string,
    page = 1,
    limit = 20
  ): Promise<{ data: KnowledgeBaseDocument[]; has_more: boolean; limit: number; total: number; page: number }> => {
    if (!isApiEnabled()) {
      return { data: [], has_more: false, limit, total: 0, page };
    }
    try {
      const response = await fetch(
        `${getBase()}/datasets/${datasetId}/documents?page=${page}&limit=${limit}`,
        {
          method: 'GET',
          headers: getHeaders(),
        }
      );
      if (!response.ok) {
        console.warn(`获取文档列表失败: ${response.statusText}`);
        return { data: [], has_more: false, limit, total: 0, page };
      }
      const result: DifyDocumentListResponse = await response.json();
      return {
        data: result.data.map(doc => transformDocument(doc)),
        has_more: result.has_more,
        limit: result.limit,
        total: result.total,
        page: result.page,
      };
    } catch (err) {
      console.warn('Dify API不可用');
      return { data: [], has_more: false, limit, total: 0, page };
    }
  },

  getDocument: async (
    datasetId: string,
    documentId: string
  ): Promise<KnowledgeBaseDocument> => {
    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/documents/${documentId}?metadata=all`,
      {
        method: 'GET',
        headers: getHeaders(),
      }
    );
    if (!response.ok) {
      throw new Error(`获取文档详情失败: ${response.statusText}`);
    }
    const result = await response.json();
    return transformDocument(result);
  },

  createByText: async (
    datasetId: string,
    params: CreateDocumentByTextParams
  ): Promise<{ document: KnowledgeBaseDocument; batch: string }> => {
    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/document/create-by-text`,
      {
        method: 'POST',
        headers: getHeaders(),
        body: JSON.stringify(params),
      }
    );
    if (!response.ok) {
      throw new Error(`通过文本创建文档失败: ${response.statusText}`);
    }
    const result: DifyDocumentResponse = await response.json();
    return {
      document: transformDocument(result.document),
      batch: result.batch,
    };
  },

  createByFile: async (
    datasetId: string,
    file: File,
    additionalData?: Record<string, any>
  ): Promise<{ document: KnowledgeBaseDocument; batch: string }> => {
    const formData = new FormData();
    const dataObj = {
      indexing_technique: additionalData?.indexing_technique || 'high_quality',
      process_rule: additionalData?.process_rule || { mode: 'automatic' },
      summary_index_setting: additionalData?.summary_index_setting || {
        enable: true,
        model_name: 'qwen-vl-plus',
        model_provider_name: 'langgenius/tongyi/tongyi',
        summary_prompt: 'summarize the text',
      },
    };
    formData.append('file', file);
    formData.append('data', JSON.stringify(dataObj));

    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/document/create-by-file`,
      {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${getKey()}`,
        },
        body: formData,
      }
    );
    if (!response.ok) {
      throw new Error(`通过文件创建文档失败: ${response.statusText}`);
    }
    const result: DifyDocumentResponse = await response.json();
    return {
      document: transformDocument(result.document),
      batch: result.batch,
    };
  },

  updateByText: async (
    datasetId: string,
    documentId: string,
    params: UpdateDocumentParams
  ): Promise<{ document: KnowledgeBaseDocument; batch: string }> => {
    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/documents/${documentId}/update-by-text`,
      {
        method: 'POST',
        headers: getHeaders(),
        body: JSON.stringify(params),
      }
    );
    if (!response.ok) {
      throw new Error(`通过文本更新文档失败: ${response.statusText}`);
    }
    const result: DifyDocumentResponse = await response.json();
    return {
      document: transformDocument(result.document),
      batch: result.batch,
    };
  },

  updateByFile: async (
    datasetId: string,
    documentId: string,
    file: File,
    additionalData?: Record<string, any>
  ): Promise<{ document: KnowledgeBaseDocument; batch: string }> => {
    const formData = new FormData();
    const dataObj = {
      name: additionalData?.name || file.name,
      process_rule: additionalData?.process_rule || { mode: 'automatic' },
    };
    formData.append('file', file);
    formData.append('data', JSON.stringify(dataObj));

    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/documents/${documentId}/update-by-file`,
      {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${getKey()}`,
        },
        body: formData,
      }
    );
    if (!response.ok) {
      throw new Error(`通过文件更新文档失败: ${response.statusText}`);
    }
    const result: DifyDocumentResponse = await response.json();
    return {
      document: transformDocument(result.document),
      batch: result.batch,
    };
  },

  getIndexingStatus: async (
    datasetId: string,
    batch: string
  ): Promise<DifyIndexingStatusResponse> => {
    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/documents/${batch}/indexing-status`,
      {
        method: 'GET',
        headers: getHeaders(),
      }
    );
    if (!response.ok) {
      throw new Error(`获取索引状态失败: ${response.statusText}`);
    }
    return response.json();
  },

  deleteDocument: async (datasetId: string, documentId: string): Promise<void> => {
    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/documents/${documentId}`,
      {
        method: 'DELETE',
        headers: getHeaders(),
      }
    );
    if (!response.ok) {
      throw new Error(`删除文档失败: ${response.statusText}`);
    }
  },

  updateStatus: async (
    datasetId: string,
    action: 'enable' | 'disable' | 'archive',
    documentIds: string[]
  ): Promise<{ result: string }> => {
    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/documents/status/${action}`,
      {
        method: 'PATCH',
        headers: getHeaders(),
        body: JSON.stringify({ document_ids: documentIds }),
      }
    );
    if (!response.ok) {
      throw new Error(`更新文档状态失败: ${response.statusText}`);
    }
    return response.json();
  },

  /**
   * 获取文档的签名下载链接（不直接触发下载）
   * @param datasetId 知识库 ID
   * @param documentId 文档 ID
   * @returns 包含 url 字段的对象（url 为短期签名链接）
   */
  getDownloadInfo: async (datasetId: string, documentId: string): Promise<{ url?: string }> => {
    if (!isApiEnabled()) {
      throw new Error('Dify API 未启用');
    }
    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/documents/${documentId}/download`,
      {
        method: 'GET',
        headers: {
          'Authorization': `Bearer ${getKey()}`,
        },
      }
    );
    if (!response.ok) {
      throw new Error(`获取文档下载链接失败: ${response.statusText}`);
    }
    return response.json();
  },
  downloadDocumentInfo: async (datasetId: string, documentId: string): Promise<{ url?: string }> => {
    return documentApi.getDownloadInfo(datasetId, documentId);
  },

  downloadDocument: async (datasetId: string, documentId: string, fileName: string): Promise<void> => {
    if (!isApiEnabled()) {
      ElMessage.warning('Dify API未启用，无法下载文件');
      return;
    }
    try {
      const response = await fetch(
        `${getBase()}/datasets/${datasetId}/documents/${documentId}/download`,
        {
          method: 'GET',
          headers: {
            'Authorization': `Bearer ${getKey()}`,
          },
        }
      );
      if (!response.ok) {
        throw new Error(`下载文档失败: ${response.statusText}`);
      }
      const result = await response.json() as { url?: string };
      if (!result.url) {
        throw new Error('未获取到文件下载链接');
      }
      const link = document.createElement('a');
      link.href = result.url;
      link.download = fileName;
      link.target = '_blank';
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      ElMessage.success('文件下载成功');
    } catch (error) {
      console.error('下载文档失败:', error);
      ElMessage.error('下载文档失败');
    }
  },
};

/**
 * 与 documentApi.downloadDocument 行为一致，但仅返回签名 URL 不触发下载。
 * 用于在 Vue 组件中按需获取下载链接进行预览。
 */
function transformDocument(data: any): KnowledgeBaseDocument {
  return {
    id: data.id,
    name: data.name,
    type: data.data_source_type || 'text',
    size: data.tokens ? `${(data.tokens / 1000).toFixed(1)}K` : '未知',
    updateTime: data.created_at
      ? new Date(data.created_at * 1000).toISOString().split('T')[0]
      : new Date().toISOString().split('T')[0],
    content: data.content,
    position: data.position,
    data_source_type: data.data_source_type,
    data_source_info: data.data_source_info,
    dataset_process_rule_id: data.dataset_process_rule_id,
    created_from: data.created_from,
    created_by: data.created_by,
    created_at: data.created_at,
    tokens: data.tokens,
    indexing_status: data.indexing_status,
    error: data.error,
    enabled: data.enabled,
    disabled_at: data.disabled_at,
    disabled_by: data.disabled_by,
    archived: data.archived,
    display_status: data.display_status,
    hit_count: data.hit_count,
    doc_form: data.doc_form,
    dataset_process_rule: data.dataset_process_rule,
    document_process_rule: data.document_process_rule,
    indexing_latency: data.indexing_latency,
    segment_count: data.segment_count,
    average_segment_length: data.average_segment_length,
    doc_language: data.doc_language,
  };
}

export default documentApi;
