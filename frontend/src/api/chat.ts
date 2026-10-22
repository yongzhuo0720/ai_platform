import { get, post, del, streamingRequest, type StreamingOptions } from './request'
import type { AppParametersResponse, ConversationListResponse, ConversationMessagesResponse } from '@/types/chat'
import { getChatConfig } from '@/stores/difyConfig'

const getBase = () => {
  const { apiUrl } = getChatConfig()
  return apiUrl
}

export interface SendMessageParams {
  inputs?: Record<string, unknown>;
  query: string;
  response_mode: 'streaming' | 'blocking';
  conversation_id?: string;
  user: string;
  files?: Array<{
    type: string;
    transfer_method: 'local_file' | 'remote_url';
    url?: string;
    upload_file_id?: string;
  }>;
}

export interface GetConversationsParams {
  user: string;
  last_id?: string;
  limit?: number;
  pinned?: boolean;
}

/**
 * 发送消息（普通模式，非流式）
 * @param params - 发送消息参数
 * @returns 消息响应
 */
export const chatApi = {
  sendMessage: async (params: SendMessageParams) => {
    return post(`${getBase()}/chat-messages`, params);
  },

  /**
   * 发送消息（流式模式，SSE）
   * @param params - 发送消息参数（不包含 response_mode）
   * @param options - 流式回调配置
   * @returns 取消请求的函数
   */
  sendMessageStreaming: (params: Omit<SendMessageParams, 'response_mode'>, options: StreamingOptions) => {
    return streamingRequest(`${getBase()}/chat-messages`, {
      ...params,
      response_mode: 'streaming',
    }, options);
  },

  /**
   * 停止生成中的消息
   * @param taskId - 任务 ID
   * @param user - 用户标识
   */
  stopMessage: async (taskId: string, user: string) => {
    return post(`${getBase()}/chat-messages/${taskId}/stop`, { user });
  },

  getConversations: async (params: GetConversationsParams) => {
    const { user, last_id, limit, pinned } = params;
    return get<ConversationListResponse>(`${getBase()}/conversations`, {
      user,
      last_id,
      limit,
      pinned,
    });
  },

  getConversationHistory: async (
    conversation_id: string,
    user: string,
    first_id?: string,
    limit?: number
  ) => {
    return get<ConversationMessagesResponse>(
      `${getBase()}/messages`,
      { user, first_id, limit,conversation_id }
    );
  },

  renameConversation: async (conversationId: string, params: { name: string; auto_generate?: boolean; user: string }) => {
    return post(`${getBase()}/conversations/${conversationId}`, params);
  },

  deleteConversation: async (conversationId: string, user: string) => {
    return del(`${getBase()}/conversations/${conversationId}`, { user });
  },

  submitFeedback: async (messageId: string, params: { rating: 'like' | 'dislike'; user: string; content?: string }) => {
    return post(
      `${getBase()}/messages/${messageId}/feedbacks`,
      params
    );
  },

  getFeedback: async (messageId: string, user: string) => {
    return get(
      `${getBase()}/messages/${messageId}/feedbacks`,
      { user }
    );
  },

  getAppParameters: async () => {
    return get<AppParametersResponse>(`${getBase()}/parameters`);
  },

  /**
   * 获取消息的推荐问题
   * @param messageId - 消息 ID
   * @param user - 用户标识
   * @returns 推荐问题列表
   */
  getSuggestedMessages: async (messageId: string, user: string): Promise<string[]> => {
    const res = await get<{ result: string; data: string[] }>(`${getBase()}/messages/${messageId}/suggested`, { user });
    return res.data || [];
  },
};

export default chatApi;
