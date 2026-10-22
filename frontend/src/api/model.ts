import type { EmbeddingModelInfo, LLMModelInfo } from '@/types/knowledge';
import { get } from './request';
import { getChatConfig } from '@/stores/difyConfig'

const getBase = () => {
  const { apiUrl } = getChatConfig()
  return apiUrl
}

export const modelApi = {
  getEmbeddingModels: async (): Promise<EmbeddingModelInfo[]> => {
    const result = await get<{ data: EmbeddingModelInfo[] }>(
      `${getBase()}/workspaces/current/models/model-types/text-embedding`
    );
    return result.data || [];
  },

  getLLMModels: async (): Promise<LLMModelInfo[]> => {
    const result = await get<{ data: LLMModelInfo[] }>(
      `${getBase()}/workspaces/current/models/model-types/llm`
    );
    return result.data || [];
  },
};

export default modelApi;
