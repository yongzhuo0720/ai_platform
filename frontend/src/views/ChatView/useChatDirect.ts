import { ElMessage } from "element-plus";
import { streamDirectChat } from "@/api/directModel";
import type { KbSearchItemDto } from "@/api/kbRag";
import type { DirectConversation, UploadedChatFile } from "./chatTypes";
import {
  activeDirectConversationId,
  activeDirectModel,
  cancelCurrentRequest,
  directConversations,
  directKbName,
  lastMessageId,
  loading,
  messages,
  messagesCursor,
  messagesHasMore,
  messagesLoadingMore,
  persistDirectConversations,
  postAnswerSuggestions,
  syncConversationToCloud,
  thinking,
} from "./chatState";
import { activeAssistant } from "./useChatSelectors";
import { chatRuntime } from "./chatRuntime";
import { scrollToBottom } from "./useChatHistory";

export const getOrCreateDirectConversation = (firstQuery: string): DirectConversation => {
  const existing = directConversations.value.find(
    (c) => c.id === activeDirectConversationId.value
  );
  if (existing) {
    return existing;
  }
  const model = activeDirectModel.value;
  const conversation: DirectConversation = {
    id: `direct-${Date.now()}`,
    name: firstQuery.slice(0, 30) || "新对话",
    modelIid: model?.iid != null ? String(model.iid) : "",
    modelName: model?.name || "",
    messages: [],
    updatedAt: Date.now(),
  };
  directConversations.value = [conversation, ...directConversations.value];
  activeDirectConversationId.value = conversation.id;
  persistDirectConversations();
  syncConversationToCloud(conversation);
  return conversation;
};

export const persistPartialDirectReply = (
  conversation: DirectConversation,
  content: string,
  thinkingText?: string,
  kbSources?: KbSearchItemDto[]
) => {
  if (!content.trim()) {
    return;
  }
  conversation.messages.push({
    role: "assistant",
    content,
    thinking: thinkingText?.trim() ? thinkingText : undefined,
    kbSources: kbSources?.length ? kbSources : undefined,
  });
  conversation.updatedAt = Date.now();
  persistDirectConversations();
  syncConversationToCloud(conversation);
};

/** 发起直连流式请求并把分片写进消息列表与本地会话 */
export const runDirectStream = (
  conversation: DirectConversation,
  assistantMessageId: string,
  userMessageId: string,
  queryContent: string,
  images: { url: string; name: string }[] = []
) => {
  const model = activeDirectModel.value;
  if (!model || model.iid == null) {
    ElMessage.error("直连模型不可用，请检查模型直连配置或重新选择模型");
    return;
  }

  cancelCurrentRequest();
  const requestToken = chatRuntime.currentRequestToken;
  thinking.value = "";
  postAnswerSuggestions.value = [];
  lastMessageId.value = "";
  loading.value = true;

  let assistantContent = "";
  const owner = chatRuntime.ownerUserId;
  chatRuntime.currentPartialSaveFn = () => {
    const msg = messages.value.find((item) => item.id === assistantMessageId);
    if (owner === chatRuntime.ownerUserId) {
      persistPartialDirectReply(conversation, assistantContent, msg?.thinking, msg?.kbSources);
    }
  };
  const existingAssistantMessage = messages.value.find((m) => m.id === assistantMessageId);
  if (existingAssistantMessage) {
    existingAssistantMessage.content = "";
    delete existingAssistantMessage.thinking;
  } else {
    messages.value.push({
      id: assistantMessageId,
      role: "assistant",
      content: "",
      requestQueryContent: queryContent,
      requestDisplayContent: queryContent,
      sourceUserMessageId: userMessageId,
    });
  }

  /**
   * OpenAI 协议无状态：把历史轮次一并带给网关才能多轮对话。
   * 只取最近 40 条，防止上下文超长。
   */
  const context: {
    role: "system" | "user" | "assistant";
    content: string | Array<{ type: "text"; text: string } | { type: "image_url"; image_url: { url: string } }>;
  }[] = conversation.messages.slice(-40).map((turn) => ({
    role: turn.role,
    content: turn.content,
  }));
  // 携带图片时：最后一条 user 消息升级为 OpenAI 多模态数组（text + image_url）
  if (images.length > 0) {
    const lastUser = [...context].reverse().find((m) => m.role === "user");
    if (lastUser && typeof lastUser.content === "string") {
      lastUser.content = [
        { type: "text", text: lastUser.content },
        ...images.map((im) => ({ type: "image_url" as const, image_url: { url: im.url } })),
      ];
    }
  }

  // 自定义/内置助手的提示词注入为 system 首消息——
  // 直连模型此前完全不感知助手角色，提示词只在 Dify 模式生效。
  // 助手的 prompt 在加载时存进 description 字段
  const assistantPrompt = activeAssistant.value?.description?.trim();
  if (assistantPrompt) {
    context.unshift({ role: "system", content: assistantPrompt });
  }

  chatRuntime.currentStopFn = streamDirectChat(String(model.iid), context, {
    // 来源由网关随流下发（真正注入上下文的片段），前端不再重复检索一次
    onKbSources: (items) => {
      if (requestToken !== chatRuntime.currentRequestToken) {
        return;
      }
      const msg = messages.value.find((m) => m.id === assistantMessageId);
      if (msg) {
        msg.kbSources = items;
      }
    },
    onChunk: (text) => {
      if (requestToken !== chatRuntime.currentRequestToken) {
        return;
      }
      assistantContent += text;
      thinking.value = "";
      const msg = messages.value.find((m) => m.id === assistantMessageId);
      if (msg) {
        msg.content = assistantContent;
      }
    },
    onThinking: (thought) => {
      if (requestToken !== chatRuntime.currentRequestToken) {
        return;
      }
      thinking.value = thought;
      const msg = messages.value.find((m) => m.id === assistantMessageId);
      if (msg) {
        msg.thinking = thought;
      }
    },
    onComplete: () => {
      if (requestToken !== chatRuntime.currentRequestToken) {
        return;
      }
      loading.value = false;
      thinking.value = "";
      const msg = messages.value.find((m) => m.id === assistantMessageId);
      if (msg) {
        // 思考过程保留在消息上，折叠面板可回看；仅重新生成时才清空
        if (!msg.content || !msg.content.trim()) {
          msg.content = "模型未返回有效内容，请重试或尝试切换其他模型。";
        }
      }
      if (assistantContent.trim()) {
        conversation.messages.push({
          role: "assistant",
          content: assistantContent,
          thinking: msg?.thinking || undefined,
          kbSources: msg?.kbSources,
        });
      }
      conversation.updatedAt = Date.now();
      persistDirectConversations();
      syncConversationToCloud(conversation);
      chatRuntime.currentPartialSaveFn = null;
      chatRuntime.currentStopFn = null;
    },
    onError: (error) => {
      if (requestToken !== chatRuntime.currentRequestToken) {
        return;
      }
      console.error("直连模型发送失败:", error);
      thinking.value = "";
      const msg = messages.value.find((m) => m.id === assistantMessageId);
      if (msg) {
        msg.content = error.message || "直连模型调用失败，请稍后重试";
      }
      // 已生成的部分内容保留到本地会话，切换/刷新后不丢
      persistPartialDirectReply(conversation, assistantContent, msg?.thinking, msg?.kbSources);
      chatRuntime.currentPartialSaveFn = null;
      loading.value = false;
      chatRuntime.currentStopFn = null;
    },
  }, directKbName.value ? { kbName: directKbName.value } : undefined);
};

export const sendDirectMessage = (queryContent: string, files: UploadedChatFile[]) => {
  const model = activeDirectModel.value;
  if (!model || model.iid == null) {
    ElMessage.error("直连模型不可用，请检查模型直连配置或重新选择模型");
    return;
  }
  const conversation = getOrCreateDirectConversation(queryContent);
  const userMessageId = `user-${Date.now()}`;
  messages.value.push({
    id: userMessageId,
    role: "user",
    content: queryContent,
    requestQueryContent: queryContent,
    requestDisplayContent: queryContent,
  });
  // 直连图片：取本地 dataUrl 附件随本次请求发送（多模态数组仅在请求体中构造，
  // 本地历史存纯文本——旧图在后续轮次没有再利用的价值，还占存储）
  const images = files
    .filter((f) => f.dataUrl)
    .map((f) => ({ url: f.dataUrl as string, name: f.name }));
  conversation.messages.push({ role: "user", content: queryContent });
  conversation.updatedAt = Date.now();
  persistDirectConversations();
  syncConversationToCloud(conversation);
  scrollToBottom(false);
  runDirectStream(conversation, `assistant-${Date.now()}`, userMessageId, queryContent, images);
};

/** 进入直连会话时清空 Dify 历史分页游标（分页状态只有一份） */
const messagesCursorReset = () => {
  messagesCursor.value = "";
  messagesHasMore.value = false;
  messagesLoadingMore.value = false;
  chatRuntime.suppressAutoScroll = false;
};

export const loadDirectConversation = (conversationId: string) => {
  cancelCurrentRequest();
  chatRuntime.historyRequestToken += 1;
  messagesCursorReset();
  const conversation = directConversations.value.find((c) => c.id === conversationId);
  if (!conversation) {
    return;
  }
  activeDirectConversationId.value = conversationId;
  messages.value = conversation.messages.map((turn, index) => ({
    id: `${conversationId}-${index}`,
    role: turn.role,
    content: turn.content,
    ...(turn.role === "user"
      ? { requestQueryContent: turn.content, requestDisplayContent: turn.content }
      : {
          ...(turn.thinking ? { thinking: turn.thinking } : {}),
          ...(turn.kbSources?.length ? { kbSources: turn.kbSources } : {}),
        }),
  }));
  scrollToBottom(false);
};
