/**
 * 知识库对话 API
 * 调用 /v1/chat-messages 进行流式对话
 */

import { streamingRequest, type StreamingOptions, get, del } from './request'
import { getChatConfig, getKnowledgeFileConfig, gatewayAuthHeaders } from '@/stores/difyConfig'

/** 会话类接口（chat-messages/parameters/conversations 等）使用问答聊天对话配置 */
const getBase = () => {
  const { apiUrl } = getChatConfig()
  return apiUrl
}

const getKey = () => {
  const { apiKey } = getChatConfig()
  return apiKey
}

/** 会话类接口专用请求头 */
const getAuthHeaders = () => ({
  'Authorization': `Bearer ${getKey()}`,
})

/**
 * 检索接口使用「知识库文件」模块的密钥（dataset 级），
 * 与会话类接口的 app 级密钥不同，不能混用。
 * 密钥统一由后端配置下发，源码中不得硬编码。
 */
const getRetrieveKey = () => getKnowledgeFileConfig().apiKey

/**
 * 知识库检索结果
 */
export interface RetrievalResult {
  dataset_id: string
  dataset_name: string
  document_id: string
  document_name: string
  segment_id: string
  score: number
  content: string
}

/**
 * 聊天消息附件
 */
export interface KnowledgeChatFile {
  type: string
  transfer_method: 'local_file' | 'remote_url'
  url?: string
  upload_file_id?: string
}

/**
 * 知识库对话参数
 */
export interface KnowledgeChatParams {
  query: string
  inputs?: Record<string, unknown>
  user: string
  conversation_id?: string
  files?: KnowledgeChatFile[]
}

/**
 * 调用知识库检索接口
 * @param datasetId 知识库 ID
 * @param query 检索查询
 * @returns 检索结果数组
 */
export const retrieveFromDataset = async (
  datasetId: string,
  query: string = ''
): Promise<RetrievalResult[]> => {
  const { apiUrl } = getKnowledgeFileConfig()
  if (!apiUrl) {
    console.warn('[retrieveFromDataset] 未配置知识库文件的 Dify 地址，跳过检索')
    return []
  }
  const url = `${apiUrl}/datasets/${datasetId}/retrieve`
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${getRetrieveKey()}`,
      'Content-Type': 'application/json',
      ...gatewayAuthHeaders(),
    },
    // 原先这里写死 query: '海光服务器'（调试残留），导致选中知识库时预取的片段
    // 永远只跟这一个词相关，与用户实际提问无关。现改为由调用方传入查询词。
    body: JSON.stringify({ query }),
  })

  if (!response.ok) {
    const errorText = await response.text()
    console.warn('[retrieveFromDataset] 请求失败:', response.status, errorText)
    return []
  }

  const data = await response.json()
  return data.records || ''
}

/**
 * 知识库对话（流式）
 * 使用 /v1/chat-messages 接口
 */
export const knowledgeChatStreaming = (
  params: KnowledgeChatParams,
  options: StreamingOptions
): (() => void) => {
  const body: Record<string, unknown> = {
    inputs: params.inputs || {},
    query: params.query,
    response_mode: 'streaming',
    user: params.user,
  }

  // conversation_id 只在有值且需要时才传递
  if (params.conversation_id) {
    body.conversation_id = params.conversation_id
  }

  // 有文件时才传递 files
  if (params.files && params.files.length > 0) {
    body.files = params.files
  }

  return streamingRequest(`${getBase()}/chat-messages`, body, {
    ...options,
    headers: {
      'Authorization': `Bearer ${getKey()}`,
      'Content-Type': 'application/json',
    },
  })
}

/**
 * 获取应用参数（/parameters）
 */
export const getAppParameters = async () => {
  return get(`${getBase()}/parameters`, undefined, getAuthHeaders())
}

/**
 * 获取会话列表（/conversations）
 */
export const getConversations = async (params: { user: string; limit?: number; last_id?: string }) => {
  return get(`${getBase()}/conversations`, params, getAuthHeaders())
}

/**
 * 获取会话历史（/messages）
 */
export const getConversationHistory = async (
  conversation_id: string,
  user: string,
  limit?: number,
  first_id?: string
) => {
  return get(`${getBase()}/messages`, { conversation_id, user, limit, first_id }, getAuthHeaders())
}

/**
 * 删除会话（/conversations/{id}）
 */
export const deleteConversation = async (conversation_id: string, user: string) => {
  return del(`${getBase()}/conversations/${conversation_id}`, { user }, getAuthHeaders())
}

/**
 * 获取消息的推荐问题
 * @param messageId - 消息 ID
 * @param user - 用户标识
 * @returns 推荐问题列表
 */
export const getSuggestedMessages = async (messageId: string, user: string): Promise<string[]> => {
  const res = await get<{ result: string; data: string[] }>(`${getBase()}/messages/${messageId}/suggested`, { user }, getAuthHeaders());
  return res.data || [];
}

export const knowledgeChatApi = {
  knowledgeChatStreaming,
  retrieveFromDataset,
  getAppParameters,
  getConversations,
  getConversationHistory,
  deleteConversation,
  getSuggestedMessages,
}

export default knowledgeChatApi
