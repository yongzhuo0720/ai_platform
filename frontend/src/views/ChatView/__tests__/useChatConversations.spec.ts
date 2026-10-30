import { beforeEach, describe, expect, it, vi } from "vitest";
import { ElMessage, ElMessageBox } from "element-plus";
import { chatApi } from "@/api/chat";
import { chatConversationApi } from "@/api/chatConversation";
import type { Conversation } from "@/types/chat";
import type { DirectChatTurn, DirectConversation } from "../chatTypes";
import {
  activeConversationId,
  activeDirectConversationId,
  activeModelId,
  attachedFiles,
  cloudSyncRequests,
  cloudSyncTimers,
  conversations,
  currentUserId,
  defaultModels,
  directConversations,
  directConversationsKey,
  inputValue,
  lastMessageId,
  mergeCloudConversations,
  messages,
  messagesCursor,
  messagesHasMore,
  messagesLoadingMore,
  postAnswerSuggestions,
  sideTab,
  thinking,
} from "../chatState";
import { chatRuntime } from "../chatRuntime";
import {
  addDeletedConversationId,
  readDeletedConversationIds,
} from "../chatStorage";
import {
  handleDeleteHistory,
  handleSelectConversation,
  renameConversationById,
  startEditUserMessage,
  startNewConversation,
  togglePinConversation,
} from "../useChatConversations";
import { loadConversationMessages } from "../useChatHistory";
import { loadDirectConversation } from "../useChatDirect";

vi.mock("@/api/chat", () => ({
  chatApi: {
    renameConversation: vi.fn(),
    deleteConversation: vi.fn(),
    getConversationHistory: vi.fn(),
  },
}));

vi.mock("@/api/assistant", () => ({
  assistantApi: {
    create: vi.fn(),
    update: vi.fn(),
    getList: vi.fn(),
    delete: vi.fn(),
  },
}));

vi.mock("@/api/chatConversation", () => ({
  chatConversationApi: {
    list: vi.fn(),
    save: vi.fn(),
    delete: vi.fn(),
  },
}));

vi.mock("../useChatHistory", () => ({
  loadConversationMessages: vi.fn(),
}));

vi.mock("../useChatDirect", () => ({
  loadDirectConversation: vi.fn(),
}));

vi.mock("../chatStorage", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../chatStorage")>();
  // 固定登录用户，避免测试依赖 jsdom Cookie 的写入时序
  return { ...actual, getCurrentUser: () => "u1" };
});

const DIRECT_MODEL = {
  id: "direct-5",
  name: "DeepSeek Chat",
  provider: "直连",
  iid: 5,
  protocol: "openai",
  upstreamModel: "deepseek-chat",
};

const enableDirectMode = () => {
  defaultModels.value = [{ ...DIRECT_MODEL }];
  activeModelId.value = "direct-5";
};

const makeConversation = (
  id = "d1",
  turns: DirectChatTurn[] = []
): DirectConversation => ({
  id,
  name: "旧会话",
  modelIid: "5",
  modelName: DIRECT_MODEL.name,
  messages: turns,
  updatedAt: 1,
});

const persistedDirect = () =>
  JSON.parse(localStorage.getItem(directConversationsKey("u1")) || "[]");

describe("useChatConversations", () => {
  beforeEach(() => {
    localStorage.clear();
    directConversations.value = [];
    activeDirectConversationId.value = "";
    activeModelId.value = "";
    defaultModels.value = [];
    conversations.value = [];
    activeConversationId.value = "";
    messages.value = [];
    inputValue.value = "";
    attachedFiles.value = [];
    thinking.value = "";
    postAnswerSuggestions.value = [];
    lastMessageId.value = "";
    messagesCursor.value = "";
    messagesHasMore.value = false;
    messagesLoadingMore.value = false;
    sideTab.value = "assistant";
    currentUserId.value = "u1";
    chatRuntime.ownerUserId = "u1";
    chatRuntime.currentRequestToken = 0;
    chatRuntime.historyRequestToken = 0;
    chatRuntime.conversationsRequestToken = 0;
    chatRuntime.currentStopFn = null;
    chatRuntime.currentPartialSaveFn = null;
    cloudSyncTimers.forEach((timer) => window.clearTimeout(timer));
    cloudSyncTimers.clear();
    cloudSyncRequests.clear();
    vi.mocked(chatConversationApi.save).mockResolvedValue({} as never);
    vi.mocked(chatConversationApi.delete).mockResolvedValue({} as never);
  });

  const clearSyncTimers = () => {
    cloudSyncTimers.forEach((timer) => window.clearTimeout(timer));
    cloudSyncTimers.clear();
  };

  describe("置顶", () => {
    it("直连会话置顶写回本地并刷新更新时间", () => {
      enableDirectMode();
      directConversations.value = [makeConversation()];

      togglePinConversation("d1");

      expect(directConversations.value[0].pinned).toBe(true);
      expect(directConversations.value[0].updatedAt).toBeGreaterThan(1);
      expect(persistedDirect()[0].pinned).toBe(true);

      togglePinConversation("d1");
      expect(directConversations.value[0].pinned).toBe(false);
      clearSyncTimers();
    });

    it("Dify 会话置顶写入本地 id 列表，再次点击取消", () => {
      togglePinConversation("c1");
      expect(
        JSON.parse(localStorage.getItem("chat-pinned-conversations-u1") || "[]")
      ).toEqual(["c1"]);

      togglePinConversation("c1");
      expect(
        JSON.parse(localStorage.getItem("chat-pinned-conversations-u1") || "[]")
      ).toEqual([]);
    });
  });

  describe("重命名", () => {
    it("直连会话去空格后改名并落盘", async () => {
      enableDirectMode();
      directConversations.value = [makeConversation()];

      await renameConversationById("d1", "  新名字  ");

      expect(directConversations.value[0].name).toBe("新名字");
      expect(persistedDirect()[0].name).toBe("新名字");
      clearSyncTimers();
    });

    it("空名字不触发改名", async () => {
      enableDirectMode();
      directConversations.value = [makeConversation()];

      await renameConversationById("d1", "   ");

      expect(directConversations.value[0].name).toBe("旧会话");
    });

    it("Dify 会话改名调用接口并更新列表", async () => {
      conversations.value = [{ id: "c1", name: "旧会话" } as Conversation];
      vi.mocked(chatApi.renameConversation).mockResolvedValue({} as never);

      await renameConversationById("c1", "新名字");

      expect(chatApi.renameConversation).toHaveBeenCalledWith("c1", {
        name: "新名字",
        auto_generate: false,
        user: "u1",
      });
      expect(conversations.value[0].name).toBe("新名字");
    });

    it("Dify 改名失败时提示重试", async () => {
      const error = vi
        .spyOn(ElMessage, "error")
        .mockImplementation(() => ({ close: () => undefined }) as never);
      vi.mocked(chatApi.renameConversation).mockRejectedValue(new Error("500"));

      await renameConversationById("c1", "新名字");

      expect(error).toHaveBeenCalledWith("重命名失败，请重试");
    });
  });

  describe("编辑用户消息", () => {
    it("直连模式回填输入框并截断本地会话", () => {
      enableDirectMode();
      const turns: DirectChatTurn[] = [
        { role: "user", content: "问题一" },
        { role: "assistant", content: "回答一" },
        { role: "user", content: "问题二" },
        { role: "assistant", content: "回答二" },
      ];
      directConversations.value = [makeConversation("d1", turns)];
      activeDirectConversationId.value = "d1";
      messages.value = turns.map((turn, index) => ({
        id: `d1-${index}`,
        role: turn.role,
        content: turn.content,
      }));

      startEditUserMessage("d1-2");

      expect(inputValue.value).toBe("问题二");
      expect(messages.value.map((item) => item.content)).toEqual([
        "问题一",
        "回答一",
      ]);
      expect(directConversations.value[0].messages).toEqual(turns.slice(0, 2));
      expect(persistedDirect()[0].messages).toHaveLength(2);
    });

    it("Dify 模式只回填输入框，不改写消息列表", () => {
      messages.value = [
        { id: "m1-user", role: "user", content: "问题" },
        { id: "m1-assistant", role: "assistant", content: "回答" },
      ];

      startEditUserMessage("m1-user");
      expect(inputValue.value).toBe("问题");
      expect(messages.value).toHaveLength(2);

      startEditUserMessage("m1-assistant");
      expect(inputValue.value).toBe("问题");
    });
  });

  describe("新建对话", () => {
    it("清空消息、游标、输入与附件", () => {
      enableDirectMode();
      messages.value = [{ id: "m1", role: "user", content: "问题" }];
      activeDirectConversationId.value = "d1";
      activeConversationId.value = "c1";
      inputValue.value = "草稿";
      attachedFiles.value = [
        { id: "f1", name: "a.png", type: "image/png", size: 10 },
      ];
      messagesCursor.value = "cursor";
      messagesHasMore.value = true;
      messagesLoadingMore.value = true;

      startNewConversation();

      expect(messages.value).toEqual([]);
      expect(activeDirectConversationId.value).toBe("");
      expect(activeConversationId.value).toBe("");
      expect(inputValue.value).toBe("");
      expect(attachedFiles.value).toEqual([]);
      expect(messagesCursor.value).toBe("");
      expect(messagesHasMore.value).toBe(false);
      expect(messagesLoadingMore.value).toBe(false);
    });
  });

  describe("删除会话", () => {
    it("直连会话：本地与云端都删除，墓碑清理，当前会话清空", async () => {
      enableDirectMode();
      directConversations.value = [makeConversation("d1"), makeConversation("d2")];
      activeDirectConversationId.value = "d1";
      messages.value = [{ id: "d1-0", role: "user", content: "问题" }];
      const success = vi
        .spyOn(ElMessage, "success")
        .mockImplementation(() => ({ close: () => undefined }) as never);

      await handleDeleteHistory("d1");

      expect(directConversations.value.map((c) => c.id)).toEqual(["d2"]);
      expect(persistedDirect().map((c: { id: string }) => c.id)).toEqual(["d2"]);
      expect(chatConversationApi.delete).toHaveBeenCalledWith("d1");
      expect(readDeletedConversationIds("u1")).toEqual([]);
      expect(activeDirectConversationId.value).toBe("");
      expect(messages.value).toEqual([]);
      expect(success).toHaveBeenCalledWith("删除成功");
    });

    it("云端删除失败：保留本地删除结果并记墓碑，不再提示失败", async () => {
      enableDirectMode();
      directConversations.value = [makeConversation("d1")];
      vi.mocked(chatConversationApi.delete).mockRejectedValue(new Error("offline"));
      const error = vi.spyOn(ElMessage, "error");
      const success = vi
        .spyOn(ElMessage, "success")
        .mockImplementation(() => ({ close: () => undefined }) as never);

      await handleDeleteHistory("d1");

      expect(directConversations.value).toEqual([]);
      expect(readDeletedConversationIds("u1")).toEqual(["d1"]);
      expect(error).not.toHaveBeenCalled();
      expect(success).toHaveBeenCalledWith("删除成功");
    });

    it("取消确认时保留会话且不调用云端接口", async () => {
      enableDirectMode();
      directConversations.value = [makeConversation("d1")];
      vi.spyOn(ElMessageBox, "confirm").mockRejectedValueOnce("cancel");

      await handleDeleteHistory("d1");

      expect(directConversations.value.map((c) => c.id)).toEqual(["d1"]);
      expect(chatConversationApi.delete).not.toHaveBeenCalled();
    });

    it("Dify 会话删除调用接口并从列表移除", async () => {
      conversations.value = [{ id: "c1" }, { id: "c2" }] as Conversation[];
      activeConversationId.value = "c1";
      messages.value = [{ id: "x", role: "user", content: "问题" }];
      vi.mocked(chatApi.deleteConversation).mockResolvedValue({} as never);

      await handleDeleteHistory("c1");

      expect(chatApi.deleteConversation).toHaveBeenCalledWith("c1", "u1");
      expect(conversations.value.map((c) => c.id)).toEqual(["c2"]);
      expect(activeConversationId.value).toBe("");
      expect(messages.value).toEqual([]);
    });
  });

  describe("切换会话", () => {
    it("直连模式走本地会话，Dify 模式走历史接口", () => {
      enableDirectMode();
      handleSelectConversation("d1");
      expect(loadDirectConversation).toHaveBeenCalledWith("d1");
      expect(loadConversationMessages).not.toHaveBeenCalled();

      activeModelId.value = "";
      handleSelectConversation("c1");
      expect(loadConversationMessages).toHaveBeenCalledWith("c1");
    });
  });

  describe("云端合并", () => {
    it("云端较新的会话合并进本地并落盘", async () => {
      const cloud = {
        ...makeConversation("d-cloud", [{ role: "user", content: "云端问题" }]),
        updatedAt: 200,
      };
      vi.mocked(chatConversationApi.list).mockResolvedValue({
        data: {
          resp_code: 0,
          datas: [
            {
              convId: "d-cloud",
              payload: JSON.stringify(cloud),
              clientUpdatedAt: 200,
            },
          ],
        },
      } as never);

      await mergeCloudConversations();

      expect(directConversations.value.map((c) => c.id)).toEqual(["d-cloud"]);
      expect(persistedDirect()[0].messages).toEqual([
        { role: "user", content: "云端问题" },
      ]);
    });

    it("墓碑中的会话不合并回本地，并在合并时重试云端删除", async () => {
      addDeletedConversationId("u1", "d-gone");
      const cloud = {
        ...makeConversation("d-gone", []),
        updatedAt: 300,
      };
      vi.mocked(chatConversationApi.list).mockResolvedValue({
        data: {
          resp_code: 0,
          datas: [
            {
              convId: "d-gone",
              payload: JSON.stringify(cloud),
              clientUpdatedAt: 300,
            },
          ],
        },
      } as never);

      await mergeCloudConversations();

      expect(directConversations.value).toEqual([]);
      expect(chatConversationApi.delete).toHaveBeenCalledWith("d-gone");
      await vi.waitFor(() =>
        expect(readDeletedConversationIds("u1")).toEqual([])
      );
    });
  });
});
