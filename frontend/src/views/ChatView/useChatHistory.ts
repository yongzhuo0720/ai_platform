import { nextTick } from "vue";
import { ElMessage } from "element-plus";
import { chatApi } from "@/api/chat";
import { splitThinking } from "@/api/request";
import type { ChatMessage } from "@/types/chat";
import type { MessageItem } from "./chatTypes";
import {
  CONVERSATION_PAGE_SIZE,
  MESSAGE_PAGE_SIZE,
  activeConversationId,
  cancelCurrentRequest,
  chatMessagesRef,
  conversations,
  conversationsCursor,
  conversationsHasMore,
  conversationsLoadingMore,
  isDirectMode,
  messages,
  messagesCursor,
  messagesHasMore,
  messagesLoadingMore,
  showScrollToBottom,
} from "./chatState";
import { getCurrentUser } from "./chatStorage";
import { chatRuntime } from "./chatRuntime";

/** 消息区是否接近底部（用于判断流式输出是否继续跟随） */
export const isMessageListNearBottom = (threshold = 120): boolean => {
  const el = chatMessagesRef.value;
  if (!el) return true;
  return el.scrollHeight - el.scrollTop - el.clientHeight <= threshold;
};

export const handleMessagesScroll = () => {
  showScrollToBottom.value = !isMessageListNearBottom(120);
};

/** 滚动到消息区底部；双 rAF 等待 Markdown 完成布局 */
export const scrollToBottom = (smooth = false) => {
  showScrollToBottom.value = false;
  nextTick(() => {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        const el = chatMessagesRef.value;
        if (!el) return;
        if (smooth && typeof el.scrollTo === "function") {
          el.scrollTo({ top: el.scrollHeight, behavior: "smooth" });
        } else {
          el.scrollTop = el.scrollHeight;
        }
      });
    });
  });
};

export const loadConversations = async () => {
  const token = ++chatRuntime.conversationsRequestToken;
  // 刷新会整体替换列表，进行中的「加载更多」作废，避免按钮卡在加载中
  conversationsLoadingMore.value = false;
  try {
    const response = await chatApi.getConversations({
      user: getCurrentUser(),
      limit: CONVERSATION_PAGE_SIZE,
    });
    if (token !== chatRuntime.conversationsRequestToken) return;
    const nextConversations = response.data || [];
    conversations.value = nextConversations;
    conversationsHasMore.value = !!response.has_more;
    conversationsCursor.value = nextConversations.length
      ? nextConversations[nextConversations.length - 1].id
      : "";
  } catch (error) {
    if (token !== chatRuntime.conversationsRequestToken) return;
    console.warn("获取会话列表失败:", error);
    conversations.value = [];
    conversationsHasMore.value = false;
    conversationsCursor.value = "";
  }
};

/** 加载更早的会话（Dify 游标分页；直连会话全量在本地，无需分页） */
export const loadMoreConversations = async () => {
  if (isDirectMode.value || conversationsLoadingMore.value || !conversationsHasMore.value) {
    return;
  }
  const cursor = conversationsCursor.value;
  if (!cursor) {
    conversationsHasMore.value = false;
    return;
  }
  const token = chatRuntime.conversationsRequestToken;
  conversationsLoadingMore.value = true;
  try {
    const response = await chatApi.getConversations({
      user: getCurrentUser(),
      last_id: cursor,
      limit: CONVERSATION_PAGE_SIZE,
    });
    if (token !== chatRuntime.conversationsRequestToken) return;
    const rows = response.data || [];
    const existingIds = new Set(conversations.value.map((c) => c.id));
    const fresh = rows.filter((c) => !existingIds.has(c.id));
    if (fresh.length) {
      conversations.value = [...conversations.value, ...fresh];
    }
    // 空页说明已经到底，避免出现「有更多但点了没反应」
    conversationsHasMore.value = !!response.has_more && rows.length > 0;
    if (rows.length) {
      conversationsCursor.value = rows[rows.length - 1].id;
    }
  } catch (error) {
    if (token !== chatRuntime.conversationsRequestToken) return;
    console.warn("加载更多会话失败:", error);
    ElMessage.error("加载更多会话失败");
  } finally {
    if (token === chatRuntime.conversationsRequestToken) {
      conversationsLoadingMore.value = false;
    }
  }
};

/** Dify 历史消息 → 消息列表项（一条记录拆成用户问 + 助手答） */
export const mapHistoryMessages = (rows: ChatMessage[]): MessageItem[] => {
  const messageList: MessageItem[] = [];
  rows.forEach((msg) => {
    if (msg.query) {
      messageList.push({
        id: `${msg.id}-user`,
        role: "user",
        content: msg.query,
      });
    }
    if (msg.answer) {
      // 与流式展示保持一致：历史回答同样剥掉 <think> 思考块，
      // 否则重新打开会话会看到一大段推理原文
      const parsed = splitThinking(msg.answer);
      messageList.push({
        id: `${msg.id}-assistant`,
        role: "assistant",
        content: parsed.closed ? parsed.answer : msg.answer,
      });
    }
  });
  return messageList;
};

export const loadConversationMessages = async (conversationId: string) => {
  cancelCurrentRequest();
  const token = ++chatRuntime.historyRequestToken;
  messagesCursor.value = "";
  messagesHasMore.value = false;
  messagesLoadingMore.value = false;
  chatRuntime.suppressAutoScroll = false;
  try {
    const response = await chatApi.getConversationHistory(
      conversationId,
      getCurrentUser(),
      undefined,
      MESSAGE_PAGE_SIZE
    );
    if (token !== chatRuntime.historyRequestToken) return;
    const rows = response.data || [];
    messages.value = mapHistoryMessages(rows);
    messagesHasMore.value = !!response.has_more;
    messagesCursor.value = rows.length ? rows[0].id : "";
    activeConversationId.value = conversationId;
    scrollToBottom(false);
  } catch (error) {
    if (token !== chatRuntime.historyRequestToken) return;
    console.warn("获取会话历史失败:", error);
    ElMessage.error("获取会话历史失败");
  }
};

/** 加载更早的历史消息：前置插入并保持当前滚动位置 */
export const loadEarlierMessages = async () => {
  if (
    isDirectMode.value ||
    messagesLoadingMore.value ||
    !messagesHasMore.value ||
    !activeConversationId.value
  ) {
    return;
  }
  const cursor = messagesCursor.value;
  if (!cursor) {
    messagesHasMore.value = false;
    return;
  }
  const container = chatMessagesRef.value;
  const prevHeight = container?.scrollHeight ?? 0;
  const prevTop = container?.scrollTop ?? 0;
  const token = chatRuntime.historyRequestToken;
  messagesLoadingMore.value = true;
  chatRuntime.suppressAutoScroll = true;
  try {
    const response = await chatApi.getConversationHistory(
      activeConversationId.value,
      getCurrentUser(),
      cursor,
      MESSAGE_PAGE_SIZE
    );
    // 加载期间切换了会话：丢弃这一页，避免把旧会话的消息插到新会话顶部
    if (token !== chatRuntime.historyRequestToken) {
      return;
    }
    const rows = response.data || [];
    const existingIds = new Set(messages.value.map((m) => m.id));
    const older = mapHistoryMessages(rows).filter((m) => !existingIds.has(m.id));
    messagesHasMore.value = !!response.has_more && rows.length > 0;
    if (rows.length) {
      messagesCursor.value = rows[0].id;
    }
    if (older.length) {
      messages.value = [...older, ...messages.value];
    }
    await nextTick();
    requestAnimationFrame(() => {
      const el = chatMessagesRef.value;
      if (el) {
        el.scrollTop = el.scrollHeight - prevHeight + prevTop;
      }
      chatRuntime.suppressAutoScroll = false;
    });
  } catch (error) {
    if (token !== chatRuntime.historyRequestToken) return;
    chatRuntime.suppressAutoScroll = false;
    console.warn("加载更早消息失败:", error);
    ElMessage.error("加载更早消息失败");
  } finally {
    if (token === chatRuntime.historyRequestToken) {
      messagesLoadingMore.value = false;
    }
  }
};
