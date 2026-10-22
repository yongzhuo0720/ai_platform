export type KnowledgeBaseVisibility = 'only_me' | 'partial_share' | 'public' | 'partial_invisible';
export type IndexingTechnique = 'high_quality' | 'economy';
export type IndexingStatus = 'pending' | 'processing' | 'completed' | 'failed';
export type SearchMethod = 'hybrid_search' | 'semantic_search' | 'full_text_search';

export interface RetrievalModel {
  search_method: SearchMethod;
  reranking_enable: boolean;
  reranking_mode?: 'reranking_model' | 'weighted_rank';
  reranking_model?: {
    reranking_provider_name: string;
    reranking_model_name: string;
  };
  top_k: number;
  score_threshold_enabled: boolean;
  score_threshold?: number;
  weights?: number;
  metadata_filtering_conditions?: {
    logical_operator: 'and' | 'or';
    conditions: Array<{
      name: string;
      comparison_operator: string;
      value: string;
    }>;
  };
}

export interface EmbeddingModel {
  embedding_model: string;
  embedding_model_provider: string;
}

export interface SummaryIndexSetting {
  enable: boolean;
  model_name: string;
  model_provider_name: string;
  summary_prompt?: string;
}

export interface KnowledgeBaseTag {
  id: number;
  name: string;
  type?: string;
  binding_count?: number;
}

export interface KnowledgeBase {
  id: string;
  name: string;
  description: string;
  coverImage?: string;
  category?: string;
  size?: string;
  updateTime?: string;
  isFeatured?: boolean;
  isRecommended?: boolean;
  provider?: string;
  permission?: KnowledgeBaseVisibility;
  indexing_technique?: IndexingTechnique;
  indexing_status?: IndexingStatus;
  document_count?: number;
  word_count?: number;
  app_count?: number;
  created_by?: string;
  created_at?: number;
  updated_by?: string;
  updated_at?: number;
  embedding_model?: string;
  embedding_model_provider?: string;
  embedding_available?: boolean;
  retrieval_model?: RetrievalModel;
  retrieval_model_dict?: RetrievalModel;
  tags?: string[];
  doc_form?: string;
  /**
   * 标签列表。
   * 注意不能写成 `[]`：那是「空元组」类型，元素会被推导成 never，
   * 导致模板里 `v-for="t in kb.tagList"` 拿到的 t 是 never，
   * 访问 t.name / t.iid 全部报 TS2339（本项目曾因此产生 25 条类型错误）。
   * 元素形态不固定（标签绑定接口返回的字段可能有增减），故用 any[]。
   */
  tagList?: any[];
  documents?: KnowledgeBaseDocument[];
  // 共享知识库 / 广场扩展字段
  creator?: string;
  member_count?: number;
  icon?: string;
  is_shared?: boolean;
  // 广场统计扩展字段（图1：订阅 / 浏览 / 喜欢 / 官方认证）
  subscribe_count?: number;
  view_count?: number;
  like_count?: number;
  is_official?: boolean;
}

export interface KnowledgeBaseDocument {
  id: string;
  name: string;
  type?: string;
  size?: string;
  updateTime?: string;
  content?: string;
  position?: number;
  data_source_type?: string;
  data_source_info?: Record<string, unknown>;
  dataset_process_rule_id?: string;
  created_from?: string;
  created_by?: string;
  created_at?: number;
  tokens?: number;
  indexing_status?: IndexingStatus;
  error?: string;
  enabled?: boolean;
  disabled_at?: number;
  disabled_by?: string;
  archived?: boolean;
  display_status?: string;
  hit_count?: number;
  doc_form?: string;
  dataset_process_rule?: {
    mode: 'automatic' | 'custom';
    rules?: {
      pre_processing_rules?: Array<{ id: string; enabled: boolean }>;
      segmentation?: { separator: string; max_tokens: number };
      parent_mode?: string;
      subchunk_segmentation?: {
        separator: string;
        max_tokens: number;
        chunk_overlap: number;
      };
    };
  };
  document_process_rule?: {
    mode: 'automatic' | 'custom';
    rules?: {
      pre_processing_rules?: Array<{ id: string; enabled: boolean }>;
      segmentation?: { separator: string; max_tokens: number };
      parent_mode?: string;
      subchunk_segmentation?: {
        separator: string;
        max_tokens: number;
        chunk_overlap: number;
      };
    };
    id?: string;
    dataset_id?: string;
  };
  indexing_latency?: number;
  segment_count?: number;
  average_segment_length?: number;
  doc_language?: string;
}

export interface KnowledgeBaseChunk {
  id: string;
  content: string;
  answer?: string;
  keywords?: string[];
  position?: number;
  document_id?: string;
  word_count?: number;
  tokens?: number;
  index_node_id?: string;
  index_node_hash?: string;
  hit_count?: number;
  enabled?: boolean;
  disabled_at?: number;
  disabled_by?: string;
  status?: string;
  created_by?: string;
  created_at?: number;
  indexing_at?: number;
  completed_at?: number;
  error?: string;
  stopped_at?: number;
  summary?: string;
  document?: {
    id: string;
    data_source_type: string;
    name: string;
  };
  segment_id?: string;
}

export interface RetrievalRecord {
  position?: number;
  dataset_id?: string;
  dataset_name?: string;
  document_id?: string;
  document_name?: string;
  segment_id?: string;
  score?: number;
  content?: string;
}

export interface RetrievalResult {
  query: {
    content: string;
  };
  records: RetrievalRecord[];
}

export interface DifyDatasetListResponse {
  data: KnowledgeBase[];
  has_more: boolean;
  limit: number;
  total: number;
  page: number;
}

export interface DifyDocumentResponse {
  document: KnowledgeBaseDocument;
  batch: string;
}

export interface DifyDocumentListResponse {
  data: KnowledgeBaseDocument[];
  has_more: boolean;
  limit: number;
  total: number;
  page: number;
}

export interface DifyChunksResponse {
  data: KnowledgeBaseChunk[];
  doc_form: string;
  has_more: boolean;
  limit: number;
  total: number;
  page: number;
}

export interface DifyIndexingStatusResponse {
  data: Array<{
    id: string;
    indexing_status: string;
    processing_started_at: number;
    parsing_completed_at: number;
    cleaning_completed_at: number;
    splitting_completed_at: number;
    completed_at: number;
    paused_at: number;
    error: string;
    stopped_at: number;
    completed_segments: number;
    total_segments: number;
  }>;
}

export interface DifyChildChunksResponse {
  data: KnowledgeBaseChunk[];
  total: number;
  total_pages: number;
  page: number;
  limit: number;
}

export interface CreateDatasetParams {
  name: string;
  description?: string;
  indexing_technique?: IndexingTechnique;
  permission?: KnowledgeBaseVisibility;
  provider?: string;
  external_knowledge_api_id?: string;
  external_knowledge_id?: string;
  embedding_model?: string;
  embedding_model_provider?: string;
  retrieval_model?: RetrievalModel;
  summary_index_setting?: SummaryIndexSetting;
}

export interface UpdateDatasetParams {
  name?: string;
  description?: string;
  indexing_technique?: IndexingTechnique;
  permission?: KnowledgeBaseVisibility;
  embedding_model_provider?: string;
  embedding_model?: string;
  retrieval_model?: RetrievalModel;
  summary_index_setting?: SummaryIndexSetting;
  partial_member_list?: Array<{ user_id: string }>;
}

export interface CreateDocumentParams {
  name: string;
  text?: string;
  indexing_technique?: IndexingTechnique;
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
  retrieval_model?: RetrievalModel;
  embedding_model?: string;
  embedding_model_provider?: string;
  summary_index_setting?: SummaryIndexSetting;
}

export interface AddChunkParams {
  segments: Array<{
    content: string;
    answer?: string;
    keywords?: string[];
  }>;
}

export interface UpdateChunkParams {
  content?: string;
  answer?: string;
  keywords?: string[];
  summary?: string;
  enabled?: boolean;
  regenerate_child_chunks?: boolean;
}

export interface EmbeddingModelInfo {
  provider: string;
  label: Record<string, unknown>;
  icon_small: Record<string, unknown>;
  icon_large: Record<string, unknown>;
  status: string;
  models: Array<{
    model: string;
    label: Record<string, unknown>;
    model_type: string;
    features: unknown[];
    fetch_from: string;
    model_properties: {
      context_size: number;
    };
    deprecated: boolean;
    status: string;
    load_balancing_enabled: boolean;
  }>;
}

export interface LLMModelInfo {
  provider: string;
  label: Record<string, unknown>;
  icon_small: Record<string, unknown>;
  icon_large: Record<string, unknown>;
  status: string;
  models: Array<{
    model: string;
    label: Record<string, unknown>;
    model_type: string;
    features: string[] | null;
    fetch_from: string;
    model_properties: Record<string, unknown>;
    deprecated: boolean;
    status: string;
    load_balancing_enabled: boolean;
  }>;
}

export interface TagBindingParams {
  target_id: string;
  tag_ids: string[];
}

export interface TagUnbindingParams {
  target_id: string;
  tag_id: string;
}