import { ElMessage, ElMessageBox } from "element-plus";
import { chatApi } from "@/api/chat";
import { assistantApi } from "@/api/assistant";
import { chatConversationApi } from "@/api/chatConversation";
import {
  activeConversationId,
  activeDirectConversationId,
  attachedFiles,
  cancelConversationSync,
  cancelCurrentRequest,
  cloudSyncRequests,
  conversations,
  directConversations,
  historyCollapsed,
  inputValue,
  isDirectMode,
  lastMessageId,
  messages,
  messagesCursor,
  messagesHasMore,
  messagesLoadingMore,
  persistDirectConversations,
  postAnswerSuggestions,
  showScrollToBottom,
  sideTab,
  syncConversationToCloud,
  thinking,
} from "./chatState";
import {
  activeAssistantId,
  assistantDialogVisible,
  assistantEditingId,
  assistantForm,
  canConfirmAssistant,
  closeAssistantMenu,
  customAssistants,
  difyModels,
  extractAssistantIid,
  getResponseMessage,
  isSuccessResponse,
  loadCustomAssistants,
  syncAssistantList,
  syncAssistantPromptInput,
} from "./chatAssistantState";
import { readDifyPinned, writeDifyPinned } from "./useChatSelectors";
import {
  addDeletedConversationId,
  getCurrentUser,
  removeDeletedConversationId,
} from "./chatStorage";
import { chatRuntime, setStartNewConversationHandler } from "./chatRuntime";
import { loadConversationMessages } from "./useChatHistory";
import { loadDirectConversation } from "./useChatDirect";

export const toggleHistorySidebar = () => {
  historyCollapsed.value = !historyCollapsed.value;
};

/** 置顶 / 取消置顶会话（直连改本地会话字段，Dify 存本地 id 列表） */
export const togglePinConversation = (conversationId: string) => {
  if (isDirectMode.value) {
    const target = directConversations.value.find((c) => c.id === conversationId);
    if (!target) return;
    target.pinned = !target.pinned;
    target.updatedAt = Date.now();
    persistDirectConversations();
    syncConversationToCloud(target);
    return;
  }
  const pinnedIds = readDifyPinned();
  if (pinnedIds.has(conversationId)) {
    pinnedIds.delete(conversationId);
  } else {
    pinnedIds.add(conversationId);
  }
  writeDifyPinned(pinnedIds);
};

/** 重命名会话：直连改本地；Dify 走 renameConversation 接口 */
export const renameConversationById = async (conversationId: string, rawName: string) => {
  const name = rawName.trim();
  if (!name) return;
  if (isDirectMode.value) {
    const target = directConversations.value.find((c) => c.id === conversationId);
    if (!target) return;
    target.name = name;
    target.updatedAt = Date.now();
    persistDirectConversations();
    syncConversationToCloud(target);
    return;
  }
  try {
    await chatApi.renameConversation(conversationId, {
      name,
      auto_generate: false,
      user: getCurrentUser(),
    });
    const target = conversations.value.find((c: any) => c.id === conversationId);
    if (target) target.name = name;
  } catch (error) {
    console.error("重命名会话失败:", error);
    ElMessage.error("重命名失败，请重试");
  }
};

/**
 * 编辑用户消息：把内容填回输入框。
 * 直连会话无状态，直接截断该消息之后的历史，重发即覆盖；
 * Dify 会话有状态（服务端保留历史），只回填输入框、由用户重新发送。
 */
export const startEditUserMessage = (messageId: string) => {
  const index = messages.value.findIndex((m) => m.id === messageId);
  if (index < 0) return;
  const message = messages.value[index];
  if (message.role !== "user") return;
  inputValue.value = message.content;
  if (isDirectMode.value && activeDirectConversationId.value) {
    const conversation = directConversations.value.find(
      (c) => c.id === activeDirectConversationId.value
    );
    // 本地镜像与服务端无关，直接截断：该消息之前的保留，其本身及之后丢弃
    const keepCount = messages.value.slice(0, index).filter((m) => m.role === "user" || m.role === "assistant").length;
    if (conversation) {
      conversation.messages = conversation.messages.slice(0, keepCount);
      conversation.updatedAt = Date.now();
      persistDirectConversations();
      // 截断结果也要同步云端，否则其它设备会继续显示被丢弃的轮次
      syncConversationToCloud(conversation);
    }
    messages.value = messages.value.slice(0, index);
  }
  closeAssistantMenu();
};

/** 新建对话：清空当前消息与分页状态，保留侧栏与输入配置 */
export function startNewConversation() {
  cancelCurrentRequest();
  chatRuntime.historyRequestToken += 1;
  activeConversationId.value = "";
  activeDirectConversationId.value = "";
  messages.value = [];
  messagesHasMore.value = false;
  messagesLoadingMore.value = false;
  messagesCursor.value = "";
  showScrollToBottom.value = false;
  chatRuntime.suppressAutoScroll = false;
  inputValue.value = "";
  thinking.value = "";
  postAnswerSuggestions.value = [];
  lastMessageId.value = "";
  closeAssistantMenu();
  /**
   * 新建对话时清空附件列表
   */
  attachedFiles.value = [];
}

// 注入给 confirmDeleteAssistant：删除「当前正在使用」的助手后清空对话
setStartNewConversationHandler(startNewConversation);

export const activateAssistant = async (assistantId: string) => {
  const targetAssistant = difyModels.value.find((item) => item.id === assistantId);
  if (!targetAssistant) {
    return;
  }

  const shouldRestartConversation =
    assistantId !== activeAssistantId.value ||
    activeConversationId.value.length > 0 ||
    messages.value.length > 0;

  if (!shouldRestartConversation) {
    closeAssistantMenu();
    return;
  }

  activeAssistantId.value = assistantId;
  startNewConversation();
  sideTab.value = "assistant";
};

export const confirmAssistantDialog = async () => {
  if (!canConfirmAssistant.value) return;

  const payload = {
    name: assistantForm.value.name.trim(),
    prompt: assistantForm.value.description.trim(),
  };
  const editingId = assistantEditingId.value;
  if (editingId) {
    const iid = extractAssistantIid(editingId);
    if (iid === null) {
      ElMessage.error("助手数据异常，无法编辑");
      return;
    }

    try {
      const res = await assistantApi.update(payload, { iid });
      if (!isSuccessResponse(res)) {
        ElMessage.error(getResponseMessage(res, "更新助手失败"));
        return;
      }
      await loadCustomAssistants();
      syncAssistantList();
      assistantForm.value = { name: "", description: "" };
      assistantDialogVisible.value = false;
      assistantEditingId.value = null;
      if (activeAssistantId.value === editingId) {
        syncAssistantPromptInput(editingId);
      }
      ElMessage.success("助手已更新");
    } catch (error) {
      console.warn("更新助手失败:", error);
      ElMessage.error("更新助手失败，请稍后重试");
    }
    return;
  }

  try {
    const res = await assistantApi.create(payload);
    if (!isSuccessResponse(res)) {
      ElMessage.error(getResponseMessage(res, "新建助手失败"));
      return;
    }
  } catch (error) {
    console.warn("新建助手失败:", error);
    ElMessage.error("新建助手失败，请稍后重试");
    return;
  }

  await loadCustomAssistants();
  syncAssistantList();
  const createdAssistant = customAssistants.value.find(
    (item) => item.name === payload.name && item.description === payload.prompt
  );
  assistantForm.value = { name: "", description: "" };
  assistantDialogVisible.value = false;
  assistantEditingId.value = null;
  if (createdAssistant) {
    await activateAssistant(createdAssistant.id);
  }
  ElMessage.success("助手已添加并应用");
};

export const selectAssistant = async (assistantId: string) => {
  const targetAssistant = difyModels.value.find((item) => item.id === assistantId);
  if (!targetAssistant) {
    return;
  }

  activeAssistantId.value = assistantId;
  startNewConversation();
  sideTab.value = "topic";
};

export const handleSelectConversation = (conversationId: string) => {
  if (isDirectMode.value) {
    loadDirectConversation(conversationId);
    return;
  }
  loadConversationMessages(conversationId);
};

export const handleDeleteHistory = async (itemId: string) => {
  if (isDirectMode.value) {
    try {
      await ElMessageBox.confirm("确定要删除此对话吗？", "删除对话", {
        confirmButtonText: "确定",
        cancelButtonText: "取消",
        type: "warning",
      });
      if (activeDirectConversationId.value === itemId) cancelCurrentRequest();
      cancelConversationSync(itemId);
      directConversations.value = directConversations.value.filter((c) => c.id !== itemId);
      persistDirectConversations();
      const owner = chatRuntime.ownerUserId;
      if (owner !== "guest") {
        // 先立墓碑：即使云端删除失败，下次登录合并时也不会把会话合并回来
        addDeletedConversationId(owner, itemId);
        await cloudSyncRequests.get(itemId);
        try {
          await chatConversationApi.delete(itemId);
          removeDeletedConversationId(owner, itemId);
        } catch (error) {
          console.warn("云端会话删除失败，已记录待下次合并重试:", error);
        }
      }
      if (activeDirectConversationId.value === itemId) {
        activeDirectConversationId.value = "";
        messages.value = [];
      }
      ElMessage.success("删除成功");
    } catch (error) {
      if (error !== "cancel") {
        console.error("删除直连会话失败:", error);
        ElMessage.error("删除会话失败");
      }
    }
    return;
  }
  try {
    await ElMessageBox.confirm(
      "确定要删除此对话吗？",
      "删除对话",
      {
        confirmButtonText: "确定",
        cancelButtonText: "取消",
        type: "warning",
      }
    );
    await chatApi.deleteConversation(itemId, getCurrentUser());
    chatRuntime.conversationsRequestToken += 1;
    conversations.value = conversations.value.filter((c) => c.id !== itemId);
    // 清掉本地置顶记录，避免已删会话的 id 长期残留
    const pinnedIds = readDifyPinned();
    if (pinnedIds.delete(itemId)) {
      writeDifyPinned(pinnedIds);
    }
    if (activeConversationId.value === itemId) {
      activeConversationId.value = "";
      messages.value = [];
    }
    ElMessage.success("删除成功");
  } catch (error) {
    if (error !== "cancel") {
      console.error("删除会话失败:", error);
      ElMessage.error("删除会话失败");
    }
  }
};
