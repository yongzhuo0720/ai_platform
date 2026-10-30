import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ElMessage } from "element-plus";
import { chatApi } from "@/api/chat";
import type { ChatMessage } from "@/types/chat";
import {
  activeConversationId,
  activeModelId,
  chatMessagesRef,
  conversations,
  conversationsCursor,
  conversationsHasMore,
  conversationsLoadingMore,
  defaultModels,
  messages,
  messagesCursor,
  messagesHasMore,
  messagesLoadingMore,
  showScrollToBottom,
} from "../chatState";
import { chatRuntime } from "../chatRuntime";
import {
  isMessageListNearBottom,
  loadConversationMessages,
  loadConversations,
  loadEarlierMessages,
  loadMoreConversations,
  mapHistoryMessages,
} from "../useChatHistory";

vi.mock("@/api/chat", () => ({
  chatApi: {
    getConversations: vi.fn(),
    getConversationHistory: vi.fn(),
    sendMessageStreaming: vi.fn(),
    renameConversation: vi.fn(),
    getSuggestedMessages: vi.fn(),
    submitFeedback: vi.fn(),
  },
}));

const DIRECT_MODEL = {
  id: "direct-5",
  name: "DeepSeek Chat",
  provider: "直连",
  iid: 5,
  protocol: "openai",
  upstreamModel: "deepseek-chat",
};

const makeRow = (
  id: string,
  query: string,
  answer: string,
  createdAt = 1
): ChatMessage => ({
  id,
  conversation_id: "c1",
  inputs: {},
  query,
  answer,
  created_at: createdAt,
});

const makeConversation = (id: string, updatedAt: number) => ({
  id,
  name: id,
  inputs: {},
  status: "",
  introduction: "",
  created_at: updatedAt,
  updated_at: updatedAt,
});

describe("useChatHistory", () => {
  beforeEach(() => {
    conversations.value = [];
    conversationsHasMore.value = false;
    conversationsCursor.value = "";
    conversationsLoadingMore.value = false;
    messages.value = [];
    messagesHasMore.value = false;
    messagesCursor.value = "";
    messagesLoadingMore.value = false;
    activeConversationId.value = "";
    activeModelId.value = "";
    defaultModels.value = [];
    showScrollToBottom.value = false;
    chatRuntime.conversationsRequestToken = 0;
    chatRuntime.historyRequestToken = 0;
    chatRuntime.suppressAutoScroll = false;
    chatMessagesRef.value = null;
    vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => {
      cb(0);
      return 1;
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  describe("mapHistoryMessages", () => {
    it("一条记录拆成用户问与助手答", () => {
      const items = mapHistoryMessages([makeRow("m1", "问题一", "回答一")]);
      expect(items).toEqual([
        { id: "m1-user", role: "user", content: "问题一" },
        { id: "m1-assistant", role: "assistant", content: "回答一" },
      ]);
    });

    it("历史回答同样剥离已闭合的思考块", () => {
      const items = mapHistoryMessages([
        makeRow("m2", "", "<think>推理过程</think>最终回答"),
      ]);
      expect(items).toEqual([
        { id: "m2-assistant", role: "assistant", content: "最终回答" },
      ]);
    });

    it("未闭合的思考块保留原文，避免内容丢失", () => {
      const items = mapHistoryMessages([makeRow("m3", "", "<think>只写了一半")]);
      expect(items[0].content).toBe("<think>只写了一半");
    });

    it("空问题或空回答不生成对应消息", () => {
      const items = mapHistoryMessages([
        makeRow("m4", "", ""),
        makeRow("m5", "只有问题", ""),
      ]);
      expect(items).toEqual([
        { id: "m5-user", role: "user", content: "只有问题" },
      ]);
    });
  });

  describe("isMessageListNearBottom / handleMessagesScroll", () => {
    it("没有滚动容器时视为在底部", () => {
      expect(isMessageListNearBottom()).toBe(true);
    });

    it("距底部小于阈值视为在底部", () => {
      chatMessagesRef.value = {
        scrollHeight: 1000,
        scrollTop: 800,
        clientHeight: 100,
      } as unknown as HTMLDivElement;
      expect(isMessageListNearBottom(120)).toBe(true);
      expect(showScrollToBottom.value).toBe(false);
    });

    it("距底部超过阈值时不在底部", () => {
      chatMessagesRef.value = {
        scrollHeight: 1000,
        scrollTop: 700,
        clientHeight: 100,
      } as unknown as HTMLDivElement;
      expect(isMessageListNearBottom(120)).toBe(false);
    });
  });

  describe("loadConversations", () => {
    it("加载成功后写入列表与游标", async () => {
      vi.mocked(chatApi.getConversations).mockResolvedValue({
        data: [makeConversation("c1", 100), makeConversation("c2", 200)],
        has_more: true,
        limit: 30,
      });

      await loadConversations();

      expect(conversations.value.map((c) => c.id)).toEqual(["c1", "c2"]);
      expect(conversationsHasMore.value).toBe(true);
      expect(conversationsCursor.value).toBe("c2");
    });

    it("加载失败时清空列表并告警", async () => {
      const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
      conversations.value = [makeConversation("old", 1)];
      vi.mocked(chatApi.getConversations).mockRejectedValue(new Error("boom"));

      await loadConversations();

      expect(warn).toHaveBeenCalled();
      expect(conversations.value).toEqual([]);
      expect(conversationsHasMore.value).toBe(false);
      expect(conversationsCursor.value).toBe("");
    });

    it("旧请求晚返回时丢弃，避免覆盖新列表", async () => {
      let resolveFirst!: (value: unknown) => void;
      vi.mocked(chatApi.getConversations)
        .mockImplementationOnce(
          () => new Promise((resolve) => {
            resolveFirst = resolve;
          }) as never
        )
        .mockResolvedValueOnce({
          data: [makeConversation("new", 200)],
          has_more: false,
          limit: 30,
        } as never);

      const first = loadConversations();
      await loadConversations();
      resolveFirst({
        data: [makeConversation("stale", 100)],
        has_more: true,
        limit: 30,
      });
      await first;

      expect(conversations.value.map((c) => c.id)).toEqual(["new"]);
      expect(conversationsHasMore.value).toBe(false);
    });
  });

  describe("loadMoreConversations", () => {
    it("直连模式不需要分页", async () => {
      defaultModels.value = [{ ...DIRECT_MODEL }];
      activeModelId.value = "direct-5";
      conversationsHasMore.value = true;
      conversationsCursor.value = "c1";

      await loadMoreConversations();

      expect(vi.mocked(chatApi.getConversations)).not.toHaveBeenCalled();
    });

    it("没有游标时直接标记结束", async () => {
      conversationsHasMore.value = true;
      conversationsCursor.value = "";

      await loadMoreConversations();

      expect(conversationsHasMore.value).toBe(false);
      expect(vi.mocked(chatApi.getConversations)).not.toHaveBeenCalled();
    });

    it("追加新会话并跳过已存在的 id", async () => {
      conversations.value = [makeConversation("c1", 100)];
      conversationsHasMore.value = true;
      conversationsCursor.value = "c1";
      vi.mocked(chatApi.getConversations).mockResolvedValue({
        data: [makeConversation("c1", 100), makeConversation("c2", 90)],
        has_more: true,
        limit: 30,
      });

      await loadMoreConversations();

      expect(conversations.value.map((c) => c.id)).toEqual(["c1", "c2"]);
      expect(conversationsCursor.value).toBe("c2");
      expect(conversationsHasMore.value).toBe(true);
      expect(conversationsLoadingMore.value).toBe(false);
    });

    it("空页说明到底，不再显示加载更多", async () => {
      conversations.value = [makeConversation("c1", 100)];
      conversationsHasMore.value = true;
      conversationsCursor.value = "c1";
      vi.mocked(chatApi.getConversations).mockResolvedValue({
        data: [],
        has_more: true,
        limit: 30,
      });

      await loadMoreConversations();

      expect(conversationsHasMore.value).toBe(false);
      expect(conversationsCursor.value).toBe("c1");
    });

    it("加载失败时提示并复位加载态", async () => {
      const error = vi.spyOn(ElMessage, "error").mockImplementation(() => ({ close: () => undefined }));
      conversationsHasMore.value = true;
      conversationsCursor.value = "c1";
      vi.mocked(chatApi.getConversations).mockRejectedValue(new Error("boom"));

      await loadMoreConversations();

      expect(error).toHaveBeenCalled();
      expect(conversationsLoadingMore.value).toBe(false);
    });
  });

  describe("loadConversationMessages", () => {
    it("加载成功后替换消息列表并记录游标", async () => {
      vi.mocked(chatApi.getConversationHistory).mockResolvedValue({
        data: [makeRow("m1", "问题", "回答", 1), makeRow("m2", "问题2", "回答2", 2)],
        has_more: true,
        limit: 30,
      });

      await loadConversationMessages("c1");

      expect(activeConversationId.value).toBe("c1");
      expect(messages.value.map((m) => m.id)).toEqual([
        "m1-user",
        "m1-assistant",
        "m2-user",
        "m2-assistant",
      ]);
      expect(messagesHasMore.value).toBe(true);
      expect(messagesCursor.value).toBe("m1");
    });

    it("连续切换会话时旧响应被丢弃", async () => {
      let resolveFirst!: (value: unknown) => void;
      vi.mocked(chatApi.getConversationHistory)
        .mockImplementationOnce(
          () => new Promise((resolve) => {
            resolveFirst = resolve;
          }) as never
        )
        .mockResolvedValueOnce({
          data: [makeRow("new", "新问题", "新回答")],
          has_more: false,
          limit: 30,
        } as never);

      const first = loadConversationMessages("old-conv");
      await loadConversationMessages("new-conv");
      resolveFirst({
        data: [makeRow("stale", "旧问题", "旧回答")],
        has_more: true,
        limit: 30,
      });
      await first;

      expect(activeConversationId.value).toBe("new-conv");
      expect(messages.value.map((m) => m.id)).toEqual([
        "new-user",
        "new-assistant",
      ]);
      expect(messagesHasMore.value).toBe(false);
    });
  });

  describe("loadEarlierMessages", () => {
    it("直连模式不需要分页", async () => {
      defaultModels.value = [{ ...DIRECT_MODEL }];
      activeModelId.value = "direct-5";
      messagesHasMore.value = true;
      messagesCursor.value = "m2";
      activeConversationId.value = "c1";

      await loadEarlierMessages();

      expect(vi.mocked(chatApi.getConversationHistory)).not.toHaveBeenCalled();
    });

    it("前置插入更早的消息并去重", async () => {
      messages.value = [
        { id: "m2-user", role: "user", content: "问题2" },
        { id: "m2-assistant", role: "assistant", content: "回答2" },
      ];
      messagesHasMore.value = true;
      messagesCursor.value = "m2";
      activeConversationId.value = "c1";
      vi.mocked(chatApi.getConversationHistory).mockResolvedValue({
        data: [makeRow("m1", "问题1", "回答1")],
        has_more: true,
        limit: 30,
      });

      await loadEarlierMessages();

      expect(messages.value.map((m) => m.id)).toEqual([
        "m1-user",
        "m1-assistant",
        "m2-user",
        "m2-assistant",
      ]);
      expect(messagesCursor.value).toBe("m1");
      expect(messagesHasMore.value).toBe(true);
      expect(messagesLoadingMore.value).toBe(false);
      expect(chatRuntime.suppressAutoScroll).toBe(false);
    });

    it("空页说明到底", async () => {
      messages.value = [{ id: "m2-user", role: "user", content: "问题2" }];
      messagesHasMore.value = true;
      messagesCursor.value = "m2";
      activeConversationId.value = "c1";
      vi.mocked(chatApi.getConversationHistory).mockResolvedValue({
        data: [],
        has_more: true,
        limit: 30,
      });

      await loadEarlierMessages();

      expect(messages.value).toHaveLength(1);
      expect(messagesHasMore.value).toBe(false);
    });
  });
});
