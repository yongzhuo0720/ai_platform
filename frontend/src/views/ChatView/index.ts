import { nextTick, onMounted, onUnmounted, watch } from "vue";
import type { DirectConversation } from "./chatTypes";
import {
  activeAssistantId,
  builtinAssistants,
  closeAssistantMenu,
  customAssistants,
  hiddenAssistantIds,
  syncAssistantList,
  syncAssistantPromptInput,
} from "./chatAssistantState";
import {
  activeConversationId,
  activeDirectConversationId,
  activeDirectKey,
  activeModelId,
  attachedFiles,
  cancelCurrentRequest,
  chatMessagesRef,
  cloudSyncTimers,
  composerRef,
  conversations,
  currentUserId,
  defaultModels,
  directConversations,
  directConversationsKey,
  directKbName,
  directKbOptions,
  historyCollapsed,
  inputValue,
  isDirectMode,
  isDragging,
  loadDirectKbOptions,
  loading,
  mergeCloudConversations,
  messages,
  parametersLoading,
  postAnswerSuggestions,
  showScrollToBottom,
  thinking,
  userInput,
} from "./chatState";
import {
  allowFileUpload,
  composerInputFields,
  composerSelectOptionsMap,
  displaySuggestedQuestions,
  modelOptions,
  syncModelBoundInput,
} from "./chatComposerState";
import { activeAssistant, assistantGreetingName } from "./useChatSelectors";
import {
  handleMessagesScroll,
  isMessageListNearBottom,
  loadConversations,
  scrollToBottom,
} from "./useChatHistory";
import { handleQuickAction, handleSuggestedClick, submitMessage } from "./useChatSend";
import { startNewConversation, toggleHistorySidebar } from "./useChatConversations";
import {
  handleDragLeave,
  handleDragOver,
  handleDrop,
  handleFileSelected,
  removeAttachedFile,
  selectModel,
  stopGenerating,
  updateInputField,
} from "./useChatFiles";
import { loadAppParameters, loadModels } from "./useChatModels";
import {
  getActiveAssistantStorageKey,
  getActiveModelStorageKey,
  getCurrentUser,
  getHiddenAssistantStorageKey,
  readStorage,
  writeStorage,
} from "./chatStorage";
import { chatRuntime } from "./chatRuntime";

export interface UseChatReturn {
  activeModelId: typeof activeModelId;
  historyCollapsed: typeof historyCollapsed;
  inputValue: typeof inputValue;
  messages: typeof messages;
  chatMessagesRef: typeof chatMessagesRef;
  composerRef: typeof composerRef;
  /** 是否显示「回到底部」悬浮按钮 */
  showScrollToBottom: typeof showScrollToBottom;
  /** 消息区滚动事件：更新「回到底部」按钮显隐 */
  handleMessagesScroll: typeof handleMessagesScroll;
  /** 滚动消息区到底部 */
  scrollToBottom: typeof scrollToBottom;
  loading: typeof loading;
  parametersLoading: typeof parametersLoading;
  chatInputFields: typeof composerInputFields;
  chatSelectOptionsMap: typeof composerSelectOptionsMap;
  modelOptions: typeof modelOptions;
  suggestedQuestions: typeof displaySuggestedQuestions;
  postAnswerSuggestions: typeof postAnswerSuggestions;
  handleSuggestedClick: typeof handleSuggestedClick;
  userInput: typeof userInput;
  activeAssistant: typeof activeAssistant;
  /** 助手问候语中显示的助手名称 */
  assistantGreetingName: typeof assistantGreetingName;
  handleQuickAction: typeof handleQuickAction;
  submitMessage: typeof submitMessage;
  toggleHistorySidebar: typeof toggleHistorySidebar;
  /** 已上传的文件列表，包含文件ID、名称、类型和大小 */
  attachedFiles: typeof attachedFiles;
  /** 是否正在拖拽文件，用于显示拖拽覆盖层 */
  isDragging: typeof isDragging;
  /** 处理拖拽悬停事件 */
  handleDragOver: typeof handleDragOver;
  /** 处理拖拽离开事件 */
  handleDragLeave: typeof handleDragLeave;
  /** 处理文件放下事件，上传文件到 Dify */
  handleDrop: typeof handleDrop;
  /** 移除已上传的文件 */
  removeAttachedFile: typeof removeAttachedFile;
  /** 处理点击附件按钮选择的文件 */
  handleFileSelected: typeof handleFileSelected;
  /** 停止当前正在进行的流式生成 */
  stopGenerating: typeof stopGenerating;
  allowFileUpload: typeof allowFileUpload;
  /** 当前是否处于直连模型模式（OpenAI 兼容协议，经网关路由） */
  isDirectMode: typeof isDirectMode;
  /** 直连模式选中的知识库名（空 = 不引用知识库，服务端 RAG） */
  directKbName: typeof directKbName;
  /** 直连模式可选的知识库名列表 */
  directKbOptions: typeof directKbOptions;
  updateInputField: typeof updateInputField;
  selectModel: typeof selectModel;
}

export function useChat(): UseChatReturn {
  const userId = getCurrentUser();
  if (chatRuntime.ownerUserId !== userId) {
    chatRuntime.currentRequestToken += 1;
    chatRuntime.currentPartialSaveFn?.();
    chatRuntime.currentPartialSaveFn = null;
    chatRuntime.currentStopFn?.();
    chatRuntime.currentStopFn = null;
    cloudSyncTimers.forEach((timer) => window.clearTimeout(timer));
    cloudSyncTimers.clear();
    chatRuntime.ownerUserId = userId;
    currentUserId.value = userId;
    directConversations.value = readStorage<DirectConversation[]>(
      directConversationsKey(userId),
      []
    );
    activeDirectConversationId.value = "";
    activeConversationId.value = "";
    messages.value = [];
    conversations.value = [];
    inputValue.value = "";
    attachedFiles.value = [];
    loading.value = false;
    thinking.value = "";
    directKbName.value = "";
    directKbOptions.value = [];
    defaultModels.value = [];
    customAssistants.value = [];
    builtinAssistants.value = [];
    hiddenAssistantIds.value = readStorage<string[]>(getHiddenAssistantStorageKey(), []);
    activeAssistantId.value = readStorage<string>(getActiveAssistantStorageKey(), "");
    activeModelId.value = readStorage<string>(getActiveModelStorageKey(), "");
    syncAssistantList();
  }

  const focusEmptyTextarea = () => {
    nextTick(() => {
      if (messages.value.length > 0 || parametersLoading.value || loading.value) {
        return;
      }
      composerRef.value?.focus({ preventScroll: true });
    });
  };

  // 直连模型切换/进入/离开直连模式时开新对话，避免上下文跨模型混用
  watch(activeDirectKey, (next, prev) => {
    if (next !== prev && (next || prev)) {
      startNewConversation();
    }
  });

  onMounted(() => {
    loadModels();
    loadAppParameters();
    loadConversations();
    loadDirectKbOptions();
    mergeCloudConversations();
    if (typeof document !== "undefined") {
      document.addEventListener("click", closeAssistantMenu);
    }
  });

  watch(
    [() => messages.value.length, parametersLoading, loading],
    ([messageLength, appParametersLoading, isChatLoading]) => {
      if (messageLength === 0 && !appParametersLoading && !isChatLoading) {
        focusEmptyTextarea();
      }
    },
    { immediate: true }
  );

  // 流式输出时自动跟随到底部，但用户上翻查看历史后不再抢滚动；
  // 双 rAF 确保 Markdown 内容完成布局后再计算 scrollHeight
  watch(
    messages,
    () => {
      if (chatRuntime.suppressAutoScroll) {
        return;
      }
      if (!isMessageListNearBottom(160)) {
        if (loading.value) {
          showScrollToBottom.value = true;
        }
        return;
      }
      nextTick(() => {
        requestAnimationFrame(() => {
          requestAnimationFrame(() => {
            if (chatRuntime.suppressAutoScroll || !chatMessagesRef.value) {
              return;
            }
            chatMessagesRef.value.scrollTop = chatMessagesRef.value.scrollHeight;
            showScrollToBottom.value = false;
          });
        });
      });
    },
    { deep: true }
  );

  onUnmounted(() => {
    cancelCurrentRequest();
    if (typeof document !== "undefined") {
      document.removeEventListener("click", closeAssistantMenu);
    }
  });

  watch(
    activeModelId,
    (value) => {
      writeStorage(getActiveModelStorageKey(), value);
      syncModelBoundInput(value);
    },
    { immediate: true }
  );

  watch(
    activeAssistantId,
    (value) => {
      writeStorage(getActiveAssistantStorageKey(), value);
      syncAssistantPromptInput(value);
    },
    { immediate: true }
  );

  return {
    activeModelId,
    historyCollapsed,
    inputValue,
    messages,
    chatMessagesRef,
    composerRef,
    showScrollToBottom,
    handleMessagesScroll,
    scrollToBottom,
    loading,
    parametersLoading,
    chatInputFields: composerInputFields,
    chatSelectOptionsMap: composerSelectOptionsMap,
    modelOptions,
    suggestedQuestions: displaySuggestedQuestions,
    postAnswerSuggestions,
    handleSuggestedClick,
    userInput,
    activeAssistant,
    assistantGreetingName,
    handleQuickAction,
    submitMessage,
    toggleHistorySidebar,
    attachedFiles,
    isDragging,
    handleDragOver,
    handleDragLeave,
    handleDrop,
    removeAttachedFile,
    handleFileSelected,
    stopGenerating,
    allowFileUpload,
    isDirectMode,
    directKbName,
    directKbOptions,
    updateInputField,
    selectModel,
  };
}
