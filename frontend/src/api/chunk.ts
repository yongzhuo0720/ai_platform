import type {
  KnowledgeBaseChunk,
  AddChunkParams,
  UpdateChunkParams,
  DifyChunksResponse,
  DifyChildChunksResponse,
} from '@/types/knowledge';
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

export interface ChunkListParams {
  page?: number;
  limit?: number;
}

export const chunkApi = {
  getChunks: async (
    datasetId: string,
    documentId: string,
    page = 1,
    limit = 20
  ): Promise<{ data: KnowledgeBaseChunk[]; doc_form: string; has_more: boolean; limit: number; total: number; page: number }> => {
    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/documents/${documentId}/segments?page=${page}&limit=${limit}`,
      {
        method: 'GET',
        headers: getHeaders(),
      }
    );
    if (!response.ok) {
      throw new Error(`获取分段列表失败: ${response.statusText}`);
    }
    const result: DifyChunksResponse = await response.json();
    return {
      data: result.data.map(chunk => transformChunk(chunk)),
      doc_form: result.doc_form,
      has_more: result.has_more,
      limit: result.limit,
      total: result.total,
      page: result.page,
    };
  },

  getChunk: async (
    datasetId: string,
    documentId: string,
    segmentId: string
  ): Promise<{ data: KnowledgeBaseChunk; doc_form: string }> => {
    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/documents/${documentId}/segments/${segmentId}`,
      {
        method: 'GET',
        headers: getHeaders(),
      }
    );
    if (!response.ok) {
      throw new Error(`获取分段详情失败: ${response.statusText}`);
    }
    const result = await response.json();
    return {
      data: transformChunk(result.data),
      doc_form: result.doc_form,
    };
  },

  addChunks: async (
    datasetId: string,
    documentId: string,
    params: AddChunkParams
  ): Promise<{ data: KnowledgeBaseChunk[]; doc_form: string }> => {
    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/documents/${documentId}/segments`,
      {
        method: 'POST',
        headers: getHeaders(),
        body: JSON.stringify(params),
      }
    );
    if (!response.ok) {
      throw new Error(`添加分段失败: ${response.statusText}`);
    }
    const result: DifyChunksResponse = await response.json();
    return {
      data: result.data.map(chunk => transformChunk(chunk)),
      doc_form: result.doc_form,
    };
  },

  updateChunk: async (
    datasetId: string,
    documentId: string,
    segmentId: string,
    params: UpdateChunkParams
  ): Promise<{ data: KnowledgeBaseChunk; doc_form: string }> => {
    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/documents/${documentId}/segments/${segmentId}`,
      {
        method: 'POST',
        headers: getHeaders(),
        body: JSON.stringify({ segment: params }),
      }
    );
    if (!response.ok) {
      throw new Error(`更新分段失败: ${response.statusText}`);
    }
    const result = await response.json();
    return {
      data: transformChunk(result.data),
      doc_form: result.doc_form,
    };
  },

  deleteChunk: async (
    datasetId: string,
    documentId: string,
    segmentId: string
  ): Promise<void> => {
    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/documents/${documentId}/segments/${segmentId}`,
      {
        method: 'DELETE',
        headers: getHeaders(),
      }
    );
    if (!response.ok) {
      throw new Error(`删除分段失败: ${response.statusText}`);
    }
  },

  getChildChunks: async (
    datasetId: string,
    documentId: string,
    segmentId: string,
    page = 1,
    limit = 20
  ): Promise<DifyChildChunksResponse> => {
    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/documents/${documentId}/segments/${segmentId}/child_chunks?page=${page}&limit=${limit}`,
      {
        method: 'GET',
        headers: getHeaders(),
      }
    );
    if (!response.ok) {
      throw new Error(`获取子分段列表失败: ${response.statusText}`);
    }
    const result = await response.json();
    return {
      data: result.data.map((chunk: any) => transformChunk(chunk)),
      total: result.total,
      total_pages: result.total_pages,
      page: result.page,
      limit: result.limit,
    };
  },

  addChildChunk: async (
    datasetId: string,
    documentId: string,
    segmentId: string,
    content: string
  ): Promise<{ data: KnowledgeBaseChunk }> => {
    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/documents/${documentId}/segments/${segmentId}/child_chunks`,
      {
        method: 'POST',
        headers: getHeaders(),
        body: JSON.stringify({ content }),
      }
    );
    if (!response.ok) {
      throw new Error(`添加子分段失败: ${response.statusText}`);
    }
    const result = await response.json();
    return {
      data: transformChunk(result.data),
    };
  },

  updateChildChunk: async (
    datasetId: string,
    documentId: string,
    segmentId: string,
    childChunkId: string,
    content: string
  ): Promise<{ data: KnowledgeBaseChunk }> => {
    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/documents/${documentId}/segments/${segmentId}/child_chunks/${childChunkId}`,
      {
        method: 'PATCH',
        headers: getHeaders(),
        body: JSON.stringify({ content }),
      }
    );
    if (!response.ok) {
      throw new Error(`更新子分段失败: ${response.statusText}`);
    }
    const result = await response.json();
    return {
      data: transformChunk(result.data),
    };
  },

  deleteChildChunk: async (
    datasetId: string,
    documentId: string,
    segmentId: string,
    childChunkId: string
  ): Promise<void> => {
    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/documents/${documentId}/segments/${segmentId}/child_chunks/${childChunkId}`,
      {
        method: 'DELETE',
        headers: getHeaders(),
      }
    );
    if (!response.ok) {
      throw new Error(`删除子分段失败: ${response.statusText}`);
    }
  },
};

function transformChunk(data: any): KnowledgeBaseChunk {
  return {
    id: data.id,
    content: data.content,
    answer: data.answer,
    keywords: data.keywords,
    position: data.position,
    document_id: data.document_id,
    word_count: data.word_count,
    tokens: data.tokens,
    index_node_id: data.index_node_id,
    index_node_hash: data.index_node_hash,
    hit_count: data.hit_count,
    enabled: data.enabled,
    disabled_at: data.disabled_at,
    disabled_by: data.disabled_by,
    status: data.status,
    created_by: data.created_by,
    created_at: data.created_at,
    indexing_at: data.indexing_at,
    completed_at: data.completed_at,
    error: data.error,
    stopped_at: data.stopped_at,
    summary: data.summary,
    document: data.document,
    segment_id: data.segment_id,
  };
}

export default chunkApi;