import type { KbSearchItemDto } from "@/api/kbRag";

export type MessageRole = "user" | "assistant";

export interface UploadedChatFile {
  id: string;
  name: string;
  type: string;
  size: number;
  /** 直连模式的本地图片（base64 dataURL，不经 Dify 上传） */
  dataUrl?: string;
}

export interface MessageItem {
  id: string;
  role: MessageRole;
  content: string;
  thinking?: string;
  /** 服务端 RAG 命中的知识库片段（直连+知识库模式） */
  kbSources?: KbSearchItemDto[];
  requestQueryContent?: string;
  requestDisplayContent?: string;
  requestFiles?: UploadedChatFile[];
  sourceUserMessageId?: string;
}

export interface ChatModel {
  id: string;
  name: string;
  provider: string;
  description?: string;
  /** 侧栏卡片上的一行简介；没有时从 description 截取首句 */
  intro?: string;
  builtin?: boolean;
  createdAt?: number;
  userId?: string;
  /** modelsconfigbean 主键；直连模型经网关按它路由 */
  iid?: number;
  /** 直连协议：openai = OpenAI 兼容直连，空 = 仅作为 Dify 应用变量 */
  protocol?: string;
  /**
   * 上游真实模型名。
   *
   * <p>有值 = 这行配置可以作为对话模型（网关按它转发）；为空 = 只配了向量模型，
   * 仅供本地 RAG 做 embedding，不能出现在对话模型下拉里。</p>
   */
  upstreamModel?: string;
  /** 配置是否启用；false 表示已停用，不应出现在对话模型下拉里 */
  enable?: boolean;
}

export interface ChatSelectOption {
  value: string;
  label: string;
  title?: string;
  subtitle?: string;
}

/**
 * 与 Dify 的有状态会话不同，OpenAI 协议无状态：多轮上下文由前端携带，
 * 因此直连会话保存在本地（刷新不丢，换设备不同步）。
 */
export interface DirectChatTurn {
  role: "user" | "assistant";
  content: string;
  /** 模型思考过程（reasoning_content / <think>），完成后保留供回看 */
  thinking?: string;
  /** 服务端 RAG 命中的知识库片段，供「引用来源」回看 */
  kbSources?: KbSearchItemDto[];
}

export interface DirectConversation {
  id: string;
  name: string;
  modelIid: string;
  modelName: string;
  messages: DirectChatTurn[];
  updatedAt: number;
  /** 置顶的会话排在列表最前（本地持久化） */
  pinned?: boolean;
}

export interface AssistantForm {
  name: string;
  description: string;
}
