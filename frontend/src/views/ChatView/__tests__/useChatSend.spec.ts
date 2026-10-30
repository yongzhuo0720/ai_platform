import { beforeEach, describe, expect, it, vi } from "vitest";
import { ElMessage } from "element-plus";
import {
  activeConversationId,
  activeDirectConversationId,
  activeModelId,
  attachedFiles,
  baseInputFields,
  defaultModels,
  directConversations,
  inputValue,
  loading,
  messages,
  userInput,
} from "../chatState";
import {
  canRegenerateMessage,
  getLatestAssistantMessageId,
  getPreviousUserMessage,
  handleQuickAction,
  handleRegenerateMessage,
  submitMessage,
} from "../useChatSend";
import { runDirectStream, sendDirectMessage } from "../useChatDirect";

vi.mock("../useChatDirect", () => ({
  sendDirectMessage: vi.fn(),
  runDirectStream: vi.fn(),
}));

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

describe("useChatSend", () => {
  beforeEach(() => {
    localStorage.clear();
    messages.value = [];
    loading.value = false;
    inputValue.value = "";
    attachedFiles.value = [];
    userInput.value = {};
    baseInputFields.value = [];
    activeConversationId.value = "";
    activeDirectConversationId.value = "";
    directConversations.value = [];
    defaultModels.value = [];
    activeModelId.value = "";
  });

  describe("消息定位", () => {
    beforeEach(() => {
      messages.value = [
        { id: "u1", role: "user", content: "问题一" },
        { id: "a1", role: "assistant", content: "回答一" },
        { id: "u2", role: "user", content: "问题二" },
        { id: "a2", role: "assistant", content: "回答二" },
      ];
    });

    it("getPreviousUserMessage 找到紧随其前的一条用户消息", () => {
      expect(getPreviousUserMessage("a2")?.id).toBe("u2");
      expect(getPreviousUserMessage("a1")?.id).toBe("u1");
    });

    it("getPreviousUserMessage 找不到时返回 null", () => {
      expect(getPreviousUserMessage("u1")).toBeNull();
      expect(getPreviousUserMessage("missing")).toBeNull();
    });

    it("getLatestAssistantMessageId 返回最后一条助手消息", () => {
      expect(getLatestAssistantMessageId()).toBe("a2");
      messages.value = [{ id: "u1", role: "user", content: "问题" }];
      expect(getLatestAssistantMessageId()).toBe("");
    });
  });

  describe("canRegenerateMessage", () => {
    beforeEach(() => {
      enableDirectMode();
      messages.value = [
        {
          id: "u1",
          role: "user",
          content: "你好",
          requestQueryContent: "你好",
        },
        { id: "a1", role: "assistant", content: "回答" },
      ];
    });

    it("加载中不能重新生成", () => {
      loading.value = true;
      expect(canRegenerateMessage("a1")).toBe(false);
    });

    it("非直连且没有当前会话时不能重新生成", () => {
      defaultModels.value = [];
      activeModelId.value = "";
      expect(canRegenerateMessage("a1")).toBe(false);
    });

    it("只能重新生成最新一条助手回复", () => {
      messages.value.push({ id: "u2", role: "user", content: "再问" });
      messages.value.push({ id: "a2", role: "assistant", content: "再答" });
      expect(canRegenerateMessage("a1")).toBe(false);
      expect(canRegenerateMessage("a2")).toBe(true);
    });

    it("用户消息或未知消息不能重新生成", () => {
      expect(canRegenerateMessage("u1")).toBe(false);
      expect(canRegenerateMessage("missing")).toBe(false);
    });

    it("找不到上一条用户消息时不能重新生成", () => {
      messages.value = [{ id: "a1", role: "assistant", content: "回答" }];
      expect(canRegenerateMessage("a1")).toBe(false);
    });

    it("用户消息既无文本也无附件时不能重新生成", () => {
      messages.value = [
        { id: "u1", role: "user", content: "  " },
        { id: "a1", role: "assistant", content: "回答" },
      ];
      expect(canRegenerateMessage("a1")).toBe(false);
    });

    it("仅有附件也可以重新生成", () => {
      messages.value = [
        {
          id: "u1",
          role: "user",
          content: "",
          requestFiles: [
            { id: "f1", name: "a.pdf", type: "application/pdf", size: 10 },
          ],
        },
        { id: "a1", role: "assistant", content: "回答" },
      ];
      expect(canRegenerateMessage("a1")).toBe(true);
    });
  });

  describe("handleQuickAction", () => {
    it("把快捷操作拼成输入内容", () => {
      handleQuickAction("总结全文");
      expect(inputValue.value).toBe("请帮我总结全文");
    });
  });

  describe("submitMessage", () => {
    beforeEach(() => {
      enableDirectMode();
    });

    it("必填变量未填时提示且不发送", async () => {
      const warning = vi.spyOn(ElMessage, "warning").mockImplementation(() => ({ close: () => undefined }));
      baseInputFields.value = [
        {
          type: "text-input",
          variable: "topic",
          label: "主题",
          required: true,
          defaultValue: "",
        },
      ];
      inputValue.value = "你好";

      await submitMessage();

      expect(warning).toHaveBeenCalledWith("请先填写：主题");
      expect(vi.mocked(sendDirectMessage)).not.toHaveBeenCalled();
      expect(inputValue.value).toBe("你好");
    });

    it("没有文本也没有附件时什么都不做", async () => {
      inputValue.value = "   ";
      await submitMessage();
      expect(vi.mocked(sendDirectMessage)).not.toHaveBeenCalled();
    });

    it("直连模式发送文本并清空输入", async () => {
      inputValue.value = "  你好  ";

      await submitMessage();

      expect(vi.mocked(sendDirectMessage)).toHaveBeenCalledWith("你好", []);
      expect(inputValue.value).toBe("");
    });

    it("仅有附件时使用默认提问并带上附件", async () => {
      const file = {
        id: "f1",
        name: "报告.pdf",
        type: "application/pdf",
        size: 10,
      };
      attachedFiles.value = [file];

      await submitMessage();

      expect(vi.mocked(sendDirectMessage)).toHaveBeenCalledWith(
        "请分析上传的文件",
        [file]
      );
      expect(attachedFiles.value).toEqual([]);
    });
  });

  describe("handleRegenerateMessage", () => {
    beforeEach(() => {
      enableDirectMode();
      const conversation = {
        id: "d1",
        name: "会话",
        modelIid: "5",
        modelName: DIRECT_MODEL.name,
        messages: [
          { role: "user" as const, content: "旧问题" },
          { role: "assistant" as const, content: "旧回答" },
        ],
        updatedAt: Date.now(),
      };
      directConversations.value = [conversation];
      activeDirectConversationId.value = "d1";
      messages.value = [
        { id: "u1", role: "user", content: "旧问题" },
        { id: "a1", role: "assistant", content: "旧回答" },
      ];
    });

    it("重新生成时移除最后一条助手轮次并重发用户消息", async () => {
      await handleRegenerateMessage("a1");

      const conversation = directConversations.value[0];
      expect(conversation.messages).toEqual([
        { role: "user", content: "旧问题" },
      ]);
      expect(vi.mocked(runDirectStream)).toHaveBeenCalledWith(
        conversation,
        "a1",
        "u1",
        "旧问题"
      );
    });

    it("不允许重新生成时只给出提示", async () => {
      const info = vi.spyOn(ElMessage, "info").mockImplementation(() => ({ close: () => undefined }));
      messages.value.push({ id: "a2", role: "assistant", content: "新回答" });

      await handleRegenerateMessage("a1");

      expect(info).toHaveBeenCalled();
      expect(vi.mocked(runDirectStream)).not.toHaveBeenCalled();
    });

    it("直连会话丢失时给出错误提示", async () => {
      const error = vi.spyOn(ElMessage, "error").mockImplementation(() => ({ close: () => undefined }));
      directConversations.value = [];

      await handleRegenerateMessage("a1");

      expect(error).toHaveBeenCalledWith("会话不存在，无法重新生成");
      expect(vi.mocked(runDirectStream)).not.toHaveBeenCalled();
    });
  });
});
