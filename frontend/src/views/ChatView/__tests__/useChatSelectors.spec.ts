import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Conversation } from "@/types/chat";
import {
  activeModelId,
  attachedFiles,
  baseInputFields,
  conversations,
  currentUserId,
  defaultModels,
  directConversations,
  inputValue,
  loading,
  userInput,
} from "../chatState";
import {
  activeAssistantId,
  builtinAssistants,
  customAssistants,
  difyModels,
} from "../chatAssistantState";
import {
  assistantSearch,
  assistantGroups,
  assistantIntro,
  assistantGreetingName,
  canSubmit,
  conversationSearch,
  filteredConversations,
  formatTopicTime,
  hasRequiredInputs,
  topicGroups,
  topicPreview,
  writeDifyPinned,
} from "../useChatSelectors";

const DIRECT_MODEL = {
  id: "direct-5",
  name: "DeepSeek Chat",
  provider: "直连",
  iid: 5,
  protocol: "openai",
  upstreamModel: "deepseek-chat",
};

const makeConversation = (
  id: string,
  name: string,
  updatedAtSeconds: number
): Conversation => ({
  id,
  name,
  inputs: {},
  status: "",
  introduction: "",
  created_at: updatedAtSeconds,
  updated_at: updatedAtSeconds,
});

describe("useChatSelectors", () => {
  beforeEach(() => {
    localStorage.clear();
    conversationSearch.value = "";
    assistantSearch.value = "";
    currentUserId.value = "u1";
    defaultModels.value = [];
    activeModelId.value = "";
    conversations.value = [];
    directConversations.value = [];
    difyModels.value = [];
    builtinAssistants.value = [];
    customAssistants.value = [];
    activeAssistantId.value = "";
    inputValue.value = "";
    attachedFiles.value = [];
    loading.value = false;
    baseInputFields.value = [];
    userInput.value = {};
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  describe("canSubmit / hasRequiredInputs", () => {
    it("无文本且无附件时不可提交", () => {
      expect(canSubmit.value).toBe(false);
    });

    it("有文本即可提交", () => {
      inputValue.value = "你好";
      expect(canSubmit.value).toBe(true);
    });

    it("仅附件也可提交", () => {
      attachedFiles.value = [
        { id: "f1", name: "a.pdf", type: "application/pdf", size: 1 },
      ];
      expect(canSubmit.value).toBe(true);
    });

    it("加载中不可提交", () => {
      inputValue.value = "你好";
      loading.value = true;
      expect(canSubmit.value).toBe(false);
    });

    it("必填变量未填时不可提交，空白字符视为未填", () => {
      inputValue.value = "你好";
      baseInputFields.value = [
        {
          type: "text-input",
          variable: "topic",
          label: "主题",
          required: true,
          defaultValue: "",
        },
      ];
      expect(hasRequiredInputs.value).toBe(false);
      expect(canSubmit.value).toBe(false);

      userInput.value = { topic: "   " };
      expect(canSubmit.value).toBe(false);

      userInput.value = { topic: "季度总结" };
      expect(hasRequiredInputs.value).toBe(true);
      expect(canSubmit.value).toBe(true);
    });

    it("非必填变量留空不影响提交", () => {
      inputValue.value = "你好";
      baseInputFields.value = [
        {
          type: "text-input",
          variable: "note",
          label: "备注",
          required: false,
          defaultValue: "",
        },
      ];
      expect(canSubmit.value).toBe(true);
    });
  });

  describe("assistantGreetingName", () => {
    it("已以「助手」结尾的名称不会重复拼接", () => {
      difyModels.value = [
        { id: "a1", name: "公文写作助手", provider: "" },
      ];
      activeAssistantId.value = "a1";
      expect(assistantGreetingName.value).toBe("公文写作助手");
    });

    it("普通名称补上「助手」后缀", () => {
      difyModels.value = [{ id: "a1", name: "商业数据分析", provider: "" }];
      activeAssistantId.value = "a1";
      expect(assistantGreetingName.value).toBe("商业数据分析助手");
    });

    it("未选择助手时回落为 AI 助手", () => {
      expect(assistantGreetingName.value).toBe("AI 助手");
    });

    it("直连模式下问候语使用当前模型名", () => {
      defaultModels.value = [{ ...DIRECT_MODEL }];
      activeModelId.value = "direct-5";
      expect(assistantGreetingName.value).toBe("DeepSeek Chat");
    });
  });

  describe("filteredConversations", () => {
    it("排除知识库中心创建的会话", () => {
      localStorage.setItem("kb-conversation-ids", JSON.stringify(["kb-1"]));
      conversations.value = [
        makeConversation("kb-1", "知识库会话", 100),
        makeConversation("c1", "普通会话", 200),
      ];
      expect(filteredConversations.value.map((c) => c.id)).toEqual(["c1"]);
    });

    it("按关键词过滤且不区分大小写", () => {
      conversations.value = [
        makeConversation("c1", "SQL 调优记录", 100),
        makeConversation("c2", "周报整理", 200),
      ];
      conversationSearch.value = "sql";
      expect(filteredConversations.value.map((c) => c.id)).toEqual(["c1"]);
    });

    it("置顶会话排在最前，其余按更新时间倒序", () => {
      conversations.value = [
        makeConversation("old", "旧会话", 100),
        makeConversation("new", "新会话", 300),
        makeConversation("mid", "中间会话", 200),
      ];
      writeDifyPinned(new Set(["old"]));

      expect(filteredConversations.value.map((c) => c.id)).toEqual([
        "old",
        "new",
        "mid",
      ]);
      expect(filteredConversations.value[0].pinned).toBe(true);
    });

    it("直连模式只展示当前模型的会话，并换算成秒级时间戳", () => {
      defaultModels.value = [{ ...DIRECT_MODEL }];
      activeModelId.value = "direct-5";
      directConversations.value = [
        {
          id: "d1",
          name: "当前模型会话",
          modelIid: "5",
          modelName: DIRECT_MODEL.name,
          messages: [],
          updatedAt: 1_700_000_123_456,
        },
        {
          id: "d2",
          name: "其他模型会话",
          modelIid: "7",
          modelName: "别的模型",
          messages: [],
          updatedAt: 1_700_000_000_000,
        },
      ];

      const result = filteredConversations.value;
      expect(result.map((c) => c.id)).toEqual(["d1"]);
      expect(result[0].updated_at).toBe(1_700_000_123);
    });
  });

  describe("assistantIntro", () => {
    it("优先使用定制简介", () => {
      expect(
        assistantIntro({
          id: "a",
          name: "A",
          provider: "",
          intro: "  一行简介  ",
          description: "提示词",
        })
      ).toBe("一行简介");
    });

    it("没有简介时取提示词首句", () => {
      expect(
        assistantIntro({
          id: "a",
          name: "A",
          provider: "",
          description: "第一句说明。第二句说明。",
        })
      ).toBe("第一句说明");
    });

    it("首句过长时截断并加省略号", () => {
      const long = "啊".repeat(50);
      const intro = assistantIntro({
        id: "a",
        name: "A",
        provider: "",
        description: long,
      });
      expect(intro).toBe(`${"啊".repeat(42)}…`);
    });

    it("没有提示词时简介为空", () => {
      expect(
        assistantIntro({ id: "a", name: "A", provider: "", description: "  " })
      ).toBe("");
    });

    it("首句为空时给出兜底文案", () => {
      expect(
        assistantIntro({
          id: "a",
          name: "A",
          provider: "",
          description: "。只有标点开头的提示词",
        })
      ).toBe("自定义提示词助手");
    });
  });

  describe("assistantGroups", () => {
    beforeEach(() => {
      builtinAssistants.value = [
        {
          id: "b1",
          name: "翻译助理",
          provider: "内置",
          description: "中英互译",
          builtin: true,
        },
      ];
      customAssistants.value = [
        {
          id: "custom-assistant-1",
          name: "SQL 助手",
          provider: "自定义助手",
          description: "帮你写 SQL",
          userId: "u1",
        },
      ];
    });

    it("默认返回内置推荐与我的助手两个分组", () => {
      expect(assistantGroups.value.map((g) => g.key)).toEqual([
        "builtin",
        "custom",
      ]);
    });

    it("按名称或提示词搜索，空分组被移除", () => {
      assistantSearch.value = "sql";
      const groups = assistantGroups.value;
      expect(groups).toHaveLength(1);
      expect(groups[0].key).toBe("custom");
      expect(groups[0].items.map((m) => m.id)).toEqual([
        "custom-assistant-1",
      ]);
    });
  });

  describe("topicGroups / topicPreview / formatTopicTime", () => {
    it("会话按置顶、今天、近 7 天、更早分组", () => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date(2026, 9, 30, 12, 0, 0));
      const nowSeconds = Math.floor(Date.now() / 1000);
      const day = 86_400;

      conversations.value = [
        makeConversation("pinned", "置顶会话", nowSeconds - 15 * day),
        makeConversation("today", "今天会话", nowSeconds - 3600),
        makeConversation("week", "近七天会话", nowSeconds - 3 * day),
        makeConversation("earlier", "更早会话", nowSeconds - 10 * day),
      ];
      writeDifyPinned(new Set(["pinned"]));

      const groups = topicGroups.value;
      expect(groups.map((g) => g.key)).toEqual([
        "pinned",
        "today",
        "week",
        "earlier",
      ]);
      expect(groups.map((g) => g.items[0].id)).toEqual([
        "pinned",
        "today",
        "week",
        "earlier",
      ]);
      expect(groups.map((g) => g.label)).toEqual([
        "置顶",
        "今天",
        "近 7 天",
        "更早",
      ]);
    });

    it("空分组不展示", () => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date(2026, 9, 30, 12, 0, 0));
      const nowSeconds = Math.floor(Date.now() / 1000);
      conversations.value = [makeConversation("today", "今天会话", nowSeconds)];
      expect(topicGroups.value.map((g) => g.key)).toEqual(["today"]);
    });

    it("会话摘要取直连会话最后一条消息并截断", () => {
      directConversations.value = [
        {
          id: "d1",
          name: "会话",
          modelIid: "5",
          modelName: "模型",
          messages: [
            { role: "user", content: "问题" },
            { role: "assistant", content: "a".repeat(50) },
          ],
          updatedAt: Date.now(),
        },
      ];
      expect(topicPreview("d1")).toBe(`${"a".repeat(40)}…`);
      expect(topicPreview("missing")).toBe("");
    });

    it("会话摘要折叠多余空白", () => {
      directConversations.value = [
        {
          id: "d1",
          name: "会话",
          modelIid: "5",
          modelName: "模型",
          messages: [{ role: "assistant", content: "  hello   world\nnext  " }],
          updatedAt: Date.now(),
        },
      ];
      expect(topicPreview("d1")).toBe("hello world next");
    });

    it("时间显示：刚刚 / 分钟 / 小时 / 日期", () => {
      vi.useFakeTimers();
      vi.setSystemTime(new Date(2026, 9, 30, 12, 0, 0));
      const now = Date.now();

      expect(formatTopicTime(0)).toBe("");
      expect(formatTopicTime(Math.floor((now - 30_000) / 1000))).toBe("刚刚");
      expect(formatTopicTime(Math.floor((now - 5 * 60_000) / 1000))).toBe(
        "5 分钟前"
      );
      expect(formatTopicTime(Math.floor((now - 2 * 3_600_000) / 1000))).toBe(
        "2 小时前"
      );
      expect(
        formatTopicTime(Math.floor(new Date(2026, 2, 5, 10, 0, 0).getTime() / 1000))
      ).toBe("3-5");
      expect(
        formatTopicTime(
          Math.floor(new Date(2025, 11, 31, 10, 0, 0).getTime() / 1000)
        )
      ).toBe("2025-12-31");
    });
  });
});
