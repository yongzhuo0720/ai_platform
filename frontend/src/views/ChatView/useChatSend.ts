import { nextTick } from "vue";
import { ElMessage } from "element-plus";
import { debug } from "@/utils/debug";
import { chatApi } from "@/api/chat";
import { parseChatError } from "@/utils/chatError";
import type { MessageItem, UploadedChatFile } from "./chatTypes";
import {
  activeConversationId,
  activeDirectConversationId,
  attachedFiles,
  cancelCurrentRequest,
  directConversations,
  inputValue,
  isDirectMode,
  lastMessageId,
  loading,
  messages,
  persistDirectConversations,
  postAnswerSuggestions,
  sideTab,
  suggestedAfterAnswerEnabled,
  thinking,
  userInput,
} from "./chatState";
import { activeAssistant } from "./useChatSelectors";
import {
  composerInputFields,
  fileInputVariables,
  getFileInputTargets,
  requiredFileInputFields,
} from "./chatComposerState";
import { getCurrentUser } from "./chatStorage";
import { chatRuntime } from "./chatRuntime";
import { loadConversations, scrollToBottom } from "./useChatHistory";
import { runDirectStream, sendDirectMessage } from "./useChatDirect";

export const sendMessageInternal = async (options: {
  queryContent: string;
  displayContent: string;
  files?: UploadedChatFile[];
  switchToTopic?: boolean;
  renameConversationTo?: string;
  appendUserMessage?: boolean;
  resetComposerState?: boolean;
  targetAssistantMessageId?: string;
  sourceUserMessageId?: string;
}) => {
  const {
    queryContent,
    displayContent,
    files = [],
    switchToTopic = false,
    renameConversationTo,
    appendUserMessage = true,
    resetComposerState = true,
    targetAssistantMessageId,
    sourceUserMessageId,
  } = options;

  if (!queryContent.trim() && files.length === 0) {
    return;
  }

  cancelCurrentRequest();
  const requestToken = chatRuntime.currentRequestToken;

  thinking.value = "";
  if (switchToTopic) {
    sideTab.value = "topic";
  }

  const userMessageId = sourceUserMessageId ?? `user-${Date.now()}`;

  /**
   * 将附件文件转换为 Dify 消息格式
   * @description
   * - type: 根据文件 MIME 类型判断是 'image' 还是 'document'
   * - transfer_method: 使用 'local_file' 表示本地上传方式
   * - upload_file_id: 上传文件后获得的 Dify 文件 ID
   */
  const fileList = files.map((file) => ({
    type: file.type.startsWith("image/") ? "image" : "document",
    transfer_method: "local_file" as const,
    upload_file_id: file.id,
  }));

  // 发送新消息时清空上一次的推荐问题
  postAnswerSuggestions.value = [];
  lastMessageId.value = '';

  if (appendUserMessage) {
    messages.value.push({
      id: userMessageId,
      role: "user",
      content: displayContent,
      requestQueryContent: queryContent,
      requestDisplayContent: displayContent,
      requestFiles: [...files],
    });
  }

  if (resetComposerState) {
    inputValue.value = "";
    /**
     * 发送消息后清空附件列表
     */
    attachedFiles.value = [];
  }
  loading.value = true;

  const assistantMessageId = targetAssistantMessageId ?? `assistant-${Date.now()}`;
  let assistantContent = "";
  let receivedConversationId = activeConversationId.value || undefined;
  const existingAssistantMessage = messages.value.find((item) => item.id === assistantMessageId);

  if (existingAssistantMessage) {
    existingAssistantMessage.content = "";
    delete existingAssistantMessage.thinking;
    existingAssistantMessage.requestQueryContent = queryContent;
    existingAssistantMessage.requestDisplayContent = displayContent;
    existingAssistantMessage.requestFiles = [...files];
    existingAssistantMessage.sourceUserMessageId = userMessageId;
  } else {
    messages.value.push({
      id: assistantMessageId,
      role: "assistant",
      content: assistantContent,
      requestQueryContent: queryContent,
      requestDisplayContent: displayContent,
      requestFiles: [...files],
      sourceUserMessageId: userMessageId,
    });
  }

  // 发送/重新生成后强制跟随到最新消息，避免用户此前上翻时看不到新回复
  scrollToBottom(false);

  /**
   * 发送流式消息到 Dify
   * @param queryContent - 使用处理后的查询内容（确保不为空）
   * @param files - 附件文件列表，如果有文件则附加到消息中
   * Dify 会自动处理文件并将其用于增强回答（如 RAG 检索）
   *
   * 文件传递策略：
   * 1. files 参数：始终传递，文件可通过 sys.files 访问
   * 2. inputs[fileVariable]：当 Chatflow 有 file-upload 类型输入变量时，
   *    将文件信息也注入到 inputs 中，确保 LLM 节点能通过 {{#START.input_file#}} 引用
   */
  const requestInputs: Record<string, unknown> = { ...userInput.value, source: 'chat' };

  // 将文件信息注入到对应输入变量中
  if (fileList.length > 0) {
    const targetVars = getFileInputTargets(requestInputs);
    debug.log('[文件注入] 目标变量:', targetVars, 'fileList:', fileList);
    targetVars.forEach((variable) => {
      requestInputs[variable] = fileList;
    });
  }

  debug.log("[发送消息] 准备发送消息:", {
    query: queryContent,
    user: getCurrentUser(),
    conversation_id: receivedConversationId,
    inputsKeys: Object.keys(requestInputs),
    files: fileList.length > 0 ? fileList : undefined,
    detectedFileVars: fileInputVariables.value,
  });

  chatRuntime.currentStopFn = chatApi.sendMessageStreaming(
    {
      query: queryContent,
      user: getCurrentUser(),
      conversation_id: receivedConversationId,
      inputs: requestInputs,
      files: fileList.length > 0 ? fileList : undefined,
    },
    {
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
      onMessageId: (messageId) => {
        lastMessageId.value = messageId;
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
      onComplete: async (conversationId) => {
        if (requestToken !== chatRuntime.currentRequestToken) {
          return;
        }
        loading.value = false;
        thinking.value = "";
        const msg = messages.value.find((m) => m.id === assistantMessageId);
        if (msg) {
          // 思考过程保留在消息上，折叠面板可回看；仅重新生成时才清空
          // 兜底：流结束后内容仍为空（模型未返回有效内容），给出提示而非空白
          if (!msg.content || !msg.content.trim()) {
            msg.content = "模型未返回有效内容，请重试或尝试切换其他模型。";
          }
        }
        chatRuntime.currentStopFn = null;
        if (conversationId) {
          receivedConversationId = conversationId;
          activeConversationId.value = conversationId;
          if (renameConversationTo) {
            try {
              await chatApi.renameConversation(conversationId, {
                name: renameConversationTo,
                user: getCurrentUser(),
              });
            } catch (error) {
              console.warn("重命名会话失败:", error);
            }
          }
          await loadConversations();
          if (switchToTopic) {
            sideTab.value = "topic";
          }
        }
        // 消息完成后获取推荐问题（应用未开启该功能时不请求，Dify 会固定返回 400）
        if (suggestedAfterAnswerEnabled.value) {
          try {
            let msgId = lastMessageId.value;
            // 兜底：如果 SSE 事件没有 message_id，从会话历史获取最新消息
            if (!msgId && receivedConversationId) {
              debug.log('[推荐消息] SSE 未捕获 message_id，从会话历史兜底获取');
              const historyRes = await chatApi.getConversationHistory(receivedConversationId, getCurrentUser(), undefined, 1);
              const msgs = historyRes?.data || [];
              if (msgs.length > 0) {
                msgId = msgs[0].id;
              }
            }
            debug.log('[推荐消息] message_id:', msgId, 'conversation_id:', receivedConversationId);
            if (msgId) {
              const suggestions = await chatApi.getSuggestedMessages(msgId, getCurrentUser());
              debug.log('[推荐消息] 获取成功:', suggestions);
              postAnswerSuggestions.value = suggestions;
            } else {
              console.warn('[推荐消息] 无法获取 message_id，跳过推荐问题');
            }
          } catch (e: any) {
            console.warn('[推荐消息] 获取失败:', e?.message || e);
          }
        } else {
          postAnswerSuggestions.value = [];
        }
      },
      onError: (error) => {
        if (requestToken !== chatRuntime.currentRequestToken) {
          return;
        }
        console.error("发送消息失败:", error);
        const parsed = parseChatError(error);
        thinking.value = "";
        const msg = messages.value.find((m) => m.id === assistantMessageId);
        if (msg) {
          msg.content = parsed.message;
        }
        loading.value = false;
        chatRuntime.currentStopFn = null;
      },
    }
  );
};

export const submitMessage = async () => {
  debug.log("[提交消息] 输入值:", inputValue.value);
  const content = inputValue.value.trim();

  const missingFields = composerInputFields.value.filter((field) => {
    if (!field.required) {
      return false;
    }
    return String(userInput.value[field.variable] ?? "").trim().length === 0;
  });

  if (missingFields.length > 0) {
    ElMessage.warning(`请先填写：${missingFields.map((field) => field.label).join("、")}`);
    return;
  }

  const missingFiles = requiredFileInputFields.value;
  if (missingFiles.length > 0 && attachedFiles.value.length === 0) {
    ElMessage.warning(`请先上传：${missingFiles.map((field) => field.label).join("、")}`);
    return;
  }

  /**
   * 检查是否可以发送消息：有文本内容或有附件文件时才能发送
   * 注意：当只有附件文件没有文字时，需要设置默认 query
   */
  if (!content && attachedFiles.value.length === 0) {
    return;
  }

  /**
   * 如果只有附件没有文字，设置默认 query
   * Dify API 要求 query 字段不能为空
   */
  const queryContent = content || "请分析上传的文件";
  const files = [...attachedFiles.value];
  inputValue.value = "";
  /**
   * 发送消息后清空附件列表
   */
  attachedFiles.value = [];

  // 构建用户消息的显示内容：有文字则显示文字，否则显示文件名列表
  const displayContent = content || (files.length > 0
    ? files.map((f) => f.name).join('、')
    : '[文件]');

  if (isDirectMode.value) {
    sendDirectMessage(queryContent, files);
    return;
  }

  await sendMessageInternal({
    queryContent,
    displayContent,
    files,
    renameConversationTo:
      !activeConversationId.value && activeAssistant.value.id ? activeAssistant.value.name : undefined,
  });
};

/** 点击建议/推荐问题：原文填入并立即发送 */
export const handleSuggestedClick = async (question: string) => {
  inputValue.value = question;
  await nextTick();
  await submitMessage();
};

export const getPreviousUserMessage = (assistantMessageId: string): MessageItem | null => {
  const assistantIndex = messages.value.findIndex((item) => item.id === assistantMessageId);
  if (assistantIndex <= 0) {
    return null;
  }

  for (let index = assistantIndex - 1; index >= 0; index -= 1) {
    const item = messages.value[index];
    if (item.role === "user") {
      return item;
    }
  }

  return null;
};

export const getLatestAssistantMessageId = (): string => {
  for (let index = messages.value.length - 1; index >= 0; index -= 1) {
    if (messages.value[index].role === "assistant") {
      return messages.value[index].id;
    }
  }
  return "";
};

export const canRegenerateMessage = (messageId: string): boolean => {
  if (loading.value) {
    return false;
  }
  if (!isDirectMode.value && !activeConversationId.value) {
    return false;
  }

  const targetMessage = messages.value.find((item) => item.id === messageId);
  if (!targetMessage || targetMessage.role !== "assistant") {
    return false;
  }

  if (getLatestAssistantMessageId() !== messageId) {
    return false;
  }

  const sourceUserMessage = getPreviousUserMessage(messageId);
  if (!sourceUserMessage) {
    return false;
  }

  const queryContent = sourceUserMessage.requestQueryContent || sourceUserMessage.content;
  const requestFiles = sourceUserMessage.requestFiles || [];
  return queryContent.trim().length > 0 || requestFiles.length > 0;
};

export const handleRegenerateMessage = async (messageId: string) => {
  if (!canRegenerateMessage(messageId)) {
    ElMessage.info("仅支持重新生成当前最新一条助手回复");
    return;
  }

  const sourceUserMessage = getPreviousUserMessage(messageId);
  if (!sourceUserMessage) {
    ElMessage.error("未找到可重新生成的用户消息");
    return;
  }

  const queryContent = sourceUserMessage.requestQueryContent || sourceUserMessage.content;
  const displayContent = sourceUserMessage.requestDisplayContent || sourceUserMessage.content;
  const requestFiles = sourceUserMessage.requestFiles || [];

  if (!queryContent.trim() && requestFiles.length === 0) {
    ElMessage.warning("当前消息缺少可重新生成的请求内容");
    return;
  }

  if (isDirectMode.value) {
    const conversation = directConversations.value.find(
      (c) => c.id === activeDirectConversationId.value
    );
    if (!conversation) {
      ElMessage.error("会话不存在，无法重新生成");
      return;
    }
    // 移除被重新生成的 assistant 轮次（最后一条），保留用户消息重发
    for (let i = conversation.messages.length - 1; i >= 0; i--) {
      if (conversation.messages[i].role === "assistant") {
        conversation.messages.splice(i, 1);
        break;
      }
    }
    persistDirectConversations();
    // 原消息带的图片要一并重发，否则第二次回答会悄悄少了图片上下文
    const images = requestFiles
      .filter((file) => file.dataUrl)
      .map((file) => ({ url: file.dataUrl as string, name: file.name }));
    runDirectStream(
      conversation,
      messageId,
      sourceUserMessage.id,
      queryContent,
      images
    );
    return;
  }

  await sendMessageInternal({
    queryContent,
    displayContent,
    files: requestFiles,
    appendUserMessage: false,
    resetComposerState: false,
    targetAssistantMessageId: messageId,
    sourceUserMessageId: sourceUserMessage.id,
  });
};
