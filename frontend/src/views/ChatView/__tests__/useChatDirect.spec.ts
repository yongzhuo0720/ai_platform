import { beforeEach, describe, expect, it, vi } from "vitest";
import { ElMessage } from "element-plus";
import { streamDirectChat, type DirectStreamOptions } from "@/api/directModel";
import type { KbSearchItemDto } from "@/api/kbRag";
import {
  activeDirectConversationId,
  activeModelId,
  cancelCurrentRequest,
  defaultModels,
  directConversations,
  directConversationsKey,
  directKbName,
  loading,
  messages,
  thinking,
} from "../chatState";
import {
  loadDirectConversation,
  sendDirectMessage,
} from "../useChatDirect";
import type { DirectChatTurn } from "../chatTypes";
import { chatRuntime } from "../chatRuntime";

vi.mock("@/api/directModel", () => ({
  streamDirectChat: vi.fn(() => () => undefined),
}));

vi.mock("../useChatHistory", () => ({
  scrollToBottom: vi.fn(),
}));

const DIRECT_MODEL = {
  id: "direct-5",
  name: "DeepSeek Chat",
  provider: "直连",
  iid: 5,
  protocol: "openai",
  upstreamModel: "deepseek-chat",
};

const sources: KbSearchItemDto[] = [
  { docIid: 1, docName: "手册.md", kbName: "kb", chunkIndex: 0, content: "片段", score: 0.9 },
];

const enableDirectMode = () => {
  defaultModels.value = [{ ...DIRECT_MODEL }];
  activeModelId.value = "direct-5";
};

/** 最近一次发起请求时注册的回调 */
const streamOptions = (): DirectStreamOptions => {
  const calls = vi.mocked(streamDirectChat).mock.calls;
  const call = calls[calls.length - 1];
  if (!call) {
    throw new Error("streamDirectChat 未被调用");
  }
  return call[2];
};

const lastTurn = (conversation: { messages: DirectChatTurn[] }) =>
  conversation.messages[conversation.messages.length - 1];

/** 读回当前用户写入 localStorage 的直连会话 */
const persisted = () =>
  JSON.parse(localStorage.getItem(directConversationsKey("guest")) || "[]");

describe("useChatDirect", () => {
  beforeEach(() => {
    localStorage.clear();
    messages.value = [];
    loading.value = false;
    thinking.value = "";
    directConversations.value = [];
    activeDirectConversationId.value = "";
    activeModelId.value = "";
    defaultModels.value = [];
    directKbName.value = "";
    chatRuntime.ownerUserId = "guest";
    chatRuntime.currentRequestToken = 0;
    chatRuntime.historyRequestToken = 0;
    chatRuntime.currentStopFn = null;
    chatRuntime.currentPartialSaveFn = null;
    vi.mocked(streamDirectChat).mockClear();
  });

  describe("sendDirectMessage", () => {
    it("创建直连会话，用户消息立即落盘", () => {
      enableDirectMode();

      sendDirectMessage("你好", []);

      expect(vi.mocked(streamDirectChat)).toHaveBeenCalledTimes(1);
      const [iid, context] = vi.mocked(streamDirectChat).mock.calls[0];
      expect(iid).toBe("5");
      expect(context).toEqual([{ role: "user", content: "你好" }]);

      const conversation = directConversations.value[0];
      expect(conversation.name).toBe("你好");
      expect(conversation.messages).toEqual([{ role: "user", content: "你好" }]);
      expect(persisted()[0].messages).toEqual([{ role: "user", content: "你好" }]);

      expect(messages.value.map((item) => item.role)).toEqual(["user", "assistant"]);
      expect(loading.value).toBe(true);
    });

    it("用户消息记录本轮附件，供重新生成时重发图片", () => {
      enableDirectMode();
      const image = {
        id: "local-1",
        name: "a.png",
        type: "image/png",
        size: 10,
        dataUrl: "data:image/jpeg;base64,xxx",
      };

      sendDirectMessage("看图", [image]);

      const userMessage = messages.value.find((item) => item.role === "user");
      expect(userMessage?.requestFiles).toEqual([image]);
    });

    it("没有可用的直连模型时不发起请求", () => {
      const error = vi.spyOn(ElMessage, "error");

      sendDirectMessage("你好", []);

      expect(error).toHaveBeenCalledWith(
        "直连模型不可用，请检查模型直连配置或重新选择模型"
      );
      expect(vi.mocked(streamDirectChat)).not.toHaveBeenCalled();
      expect(directConversations.value).toHaveLength(0);
    });
  });

  describe("runDirectStream", () => {
    it("流式分片累加到助手消息，完成后整轮落盘", () => {
      enableDirectMode();
      sendDirectMessage("你好", []);
      const options = streamOptions();

      options.onChunk("你");
      options.onChunk("好");
      expect(messages.value[messages.value.length - 1].content).toBe("你好");

      options.onComplete?.();

      expect(loading.value).toBe(false);
      expect(directConversations.value[0].messages).toEqual([
        { role: "user", content: "你好" },
        { role: "assistant", content: "你好" },
      ]);
      expect(persisted()[0].messages).toHaveLength(2);
    });

    it("思考过程与知识库来源随回答一起保存，检索参数只随请求发送", () => {
      enableDirectMode();
      directKbName.value = "kb";
      sendDirectMessage("查一下", []);
      const options = streamOptions();

      options.onThinking?.("先想一下");
      options.onKbSources?.(sources);
      options.onChunk("答案");
      options.onComplete?.();

      expect(lastTurn(directConversations.value[0])).toEqual({
        role: "assistant",
        content: "答案",
        thinking: "先想一下",
        kbSources: sources,
      });
      expect(vi.mocked(streamDirectChat).mock.calls[0][3]).toEqual({ kbName: "kb" });
    });

    it("出错时展示错误信息，并保留已生成的部分回答", () => {
      enableDirectMode();
      sendDirectMessage("你好", []);
      const options = streamOptions();

      options.onChunk("半句");
      options.onError?.(new Error("上游服务异常"));

      expect(messages.value[messages.value.length - 1].content).toBe("上游服务异常");
      expect(loading.value).toBe(false);
      expect(lastTurn(directConversations.value[0])).toEqual({
        role: "assistant",
        content: "半句",
      });
      expect(lastTurn({ messages: persisted()[0].messages })).toEqual({
        role: "assistant",
        content: "半句",
      });
    });

    it("停止生成把已生成内容写入本地会话", () => {
      enableDirectMode();
      sendDirectMessage("你好", []);
      streamOptions().onChunk("说到一半");

      cancelCurrentRequest();

      expect(loading.value).toBe(false);
      expect(lastTurn(directConversations.value[0])).toEqual({
        role: "assistant",
        content: "说到一半",
      });
      expect(lastTurn({ messages: persisted()[0].messages })).toEqual({
        role: "assistant",
        content: "说到一半",
      });
      expect(chatRuntime.currentPartialSaveFn).toBeNull();
    });

    it("过期请求的分片与完成回调被丢弃", () => {
      enableDirectMode();
      sendDirectMessage("你好", []);
      const options = streamOptions();
      const conversation = directConversations.value[0];
      const assistantId = messages.value[messages.value.length - 1].id;

      // 模拟切换会话或发起新请求：旧请求令牌作废
      chatRuntime.currentRequestToken += 1;
      options.onChunk("迟到的回答");
      options.onComplete?.();

      expect(messages.value.find((item) => item.id === assistantId)?.content).toBe("");
      expect(conversation.messages).toHaveLength(1);
    });
  });

  describe("loadDirectConversation", () => {
    it("还原消息、思考过程与引用来源", () => {
      directConversations.value = [
        {
          id: "d1",
          name: "旧会话",
          modelIid: "5",
          modelName: DIRECT_MODEL.name,
          messages: [
            { role: "user", content: "问题" },
            { role: "assistant", content: "回答", thinking: "思路", kbSources: sources },
          ],
          updatedAt: 1,
        },
      ];

      loadDirectConversation("d1");

      expect(activeDirectConversationId.value).toBe("d1");
      expect(messages.value).toEqual([
        {
          id: "d1-0",
          role: "user",
          content: "问题",
          requestQueryContent: "问题",
          requestDisplayContent: "问题",
        },
        {
          id: "d1-1",
          role: "assistant",
          content: "回答",
          thinking: "思路",
          kbSources: sources,
        },
      ]);
    });

    it("会话不存在时不做任何切换", () => {
      loadDirectConversation("missing");

      expect(activeDirectConversationId.value).toBe("");
      expect(messages.value).toEqual([]);
    });
  });
});
