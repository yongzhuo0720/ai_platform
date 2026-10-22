import type {
  KnowledgeBase,
  CreateDatasetParams,
  UpdateDatasetParams,
  RetrievalModel,
  RetrievalResult,
  DifyDatasetListResponse,
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

const isApiEnabled = () => Boolean(getKey())

export interface DatasetListParams {
  page?: number;
  limit?: number;
}

export interface DatasetListResponse {
  data: KnowledgeBase[];
  has_more: boolean;
  limit: number;
  total: number;
  page: number;
}

export interface RetrieveParams {
  query: string;
  retrieval_model?: RetrievalModel;
}

export interface EmbeddingModelOption {
  provider: string;
  model: string;
  label: string;
}

export interface EmbeddingModelsResponse {
  data: Array<{
    provider: string;
    label: Record<string, string>;
    icon_small?: Record<string, string>;
    icon_small_dark?: Record<string, string> | null;
    status?: string;
    models: Array<{
      model: string;
      label: Record<string, string>;
      model_type: string;
      features?: any;
      fetch_from?: string;
      model_properties?: Record<string, unknown>;
      status?: string;
      deprecated?: boolean;
      load_balancing_enabled?: boolean;
      has_invalid_load_balancing_configs?: boolean;
    }>;
    tenant_id?: string;
  }>;
}

export const datasetApi = {
  getDatasets: async (page = 1, limit = 20, throwOnError = false): Promise<DatasetListResponse> => {
    if (!isApiEnabled()) {
      if (throwOnError) throw new Error('Dify API 未配置');
      return { data: [], has_more: false, limit, total: 0, page };
    }
    try {
      const response = await fetch(
        `${getBase()}/datasets?page=${page}&limit=${limit}`,
        {
          method: 'GET',
          headers: getHeaders(),
        }
      );
      if (!response.ok) {
        const msg = `获取知识库列表失败: ${response.statusText}`;
        if (throwOnError) throw new Error(msg);
        console.warn(msg);
        return { data: [], has_more: false, limit, total: 0, page };
      }
      const result: DifyDatasetListResponse = await response.json();
      return {
        data: result.data.map(item => transformDataset(item)),
        has_more: result.has_more,
        limit: result.limit,
        total: result.total,
        page: result.page,
      };
    } catch (err) {
      if (throwOnError) throw err;
      console.warn('Dify API不可用');
      return { data: [], has_more: false, limit, total: 0, page };
    }
  },

  getDataset: async (datasetId: string): Promise<KnowledgeBase | null> => {
    if (!isApiEnabled()) {
      return null;
    }
    try {
      const response = await fetch(
        `${getBase()}/datasets/${datasetId}`,
        {
          method: 'GET',
          headers: getHeaders(),
        }
      );
      if (!response.ok) {
        console.warn(`获取知识库详情失败: ${response.statusText}`);
        return null;
      }
      const result = await response.json();
      return transformDataset(result);
    } catch (err) {
      console.warn('Dify API不可用');
      return null;
    }
  },

  createDataset: async (params: CreateDatasetParams): Promise<KnowledgeBase> => {
    const response = await fetch(`${getBase()}/datasets`, {
      method: 'POST',
      headers: getHeaders(),
      body: JSON.stringify(params),
    });
    if (!response.ok) {
      throw new Error(`创建知识库失败: ${response.statusText}`);
    }
    const result = await response.json();
    return transformDataset(result);
  },

  updateDataset: async (
    datasetId: string,
    params: UpdateDatasetParams
  ): Promise<KnowledgeBase> => {
    const response = await fetch(`${getBase()}/datasets/${datasetId}`, {
      method: 'PATCH',
      headers: getHeaders(),
      body: JSON.stringify(params),
    });
    if (!response.ok) {
      throw new Error(`更新知识库失败: ${response.statusText}`);
    }
    const result = await response.json();
    return transformDataset(result);
  },

  deleteDataset: async (datasetId: string): Promise<void> => {
    const response = await fetch(`${getBase()}/datasets/${datasetId}`, {
      method: 'DELETE',
      headers: getHeaders(),
    });
    if (!response.ok) {
      throw new Error(`删除知识库失败: ${response.statusText}`);
    }
  },

  getEmbeddingModels: async (): Promise<EmbeddingModelOption[]> => {
    if (!isApiEnabled()) {
      return [];
    }
    try {
      const response = await fetch(
        `${getBase()}/workspaces/current/models/model-types/text-embedding`,
        {
          method: 'GET',
          headers: getHeaders(),
        }
      );
      if (!response.ok) {
        console.warn(`获取Embedding模型失败: ${response.statusText}`);
        return [];
      }
      const result: EmbeddingModelsResponse = await response.json();
      const options: EmbeddingModelOption[] = [];
      (result.data || []).forEach((provider) => {
        (provider.models || []).forEach((model) => {
          options.push({
            provider: provider.provider,
            model: model.model,
            label: model.label?.zh_Hans || model.label?.en_US || model.model,
          });
        });
      });
      return options;
    } catch (err) {
      console.warn('获取Embedding模型失败:', err);
      return [];
    }
  },

  retrieve: async (
    datasetId: string,
    params: RetrieveParams
  ): Promise<RetrievalResult> => {
    const response = await fetch(
      `${getBase()}/datasets/${datasetId}/retrieve`,
      {
        method: 'POST',
        headers: getHeaders(),
        body: JSON.stringify({
          query: params.query,
          retrieval_model: params.retrieval_model || {
            search_method: 'hybrid_search',
            reranking_enable: true,
            top_k: 5,
            score_threshold_enabled: true,
            score_threshold: 0.5,
          },
        }),
      }
    );
    if (!response.ok) {
      throw new Error(`检索失败: ${response.statusText}`);
    }
    return response.json();
  },

  testRetrieve: async (
    datasetId: string,
    query: string,
    retrievalModel?: RetrievalModel
  ): Promise<RetrievalResult> => {
    return datasetApi.retrieve(datasetId, { query, retrieval_model: retrievalModel });
  },
};

function transformDataset(data: any): KnowledgeBase {
  return {
    id: data.id,
    name: data.name,
    description: data.description || '',
    coverImage: data.cover_image || data.icon || undefined,
    category: data.tags?.[0] || '未分类',
    size: data.word_count ? `${(data.word_count / 1024).toFixed(1)}K字` : '未知',
    updateTime: data.updated_at
      ? new Date(data.updated_at * 1000).toISOString().split('T')[0]
      : new Date().toISOString().split('T')[0],
    isFeatured: data.is_featured || false,
    isRecommended: data.is_recommended || false,
    provider: data.provider,
    permission: data.permission,
    indexing_technique: data.indexing_technique,
    indexing_status: data.indexing_status,
    document_count: data.document_count,
    word_count: data.word_count,
    app_count: data.app_count,
    created_by: data.created_by,
    created_at: data.created_at,
    updated_by: data.updated_by,
    updated_at: data.updated_at,
    embedding_model: data.embedding_model,
    embedding_model_provider: data.embedding_model_provider,
    embedding_available: data.embedding_available,
    retrieval_model: data.retrieval_model,
    retrieval_model_dict: data.retrieval_model_dict,
    tags: data.tags,
    doc_form: data.doc_form,
  };
}

export default datasetApi;