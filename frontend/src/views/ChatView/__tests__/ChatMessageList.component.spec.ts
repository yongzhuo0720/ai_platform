import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { enableAutoUnmount, mount } from "@vue/test-utils";
import { nextTick } from "vue";

vi.mock("../useChatHistory", () => ({ loadEarlierMessages: vi.fn() }));
vi.mock("../useChatConversations", () => ({ startEditUserMessage: vi.fn() }));
vi.mock("../useChatFiles", () => ({
  copyMessage: vi.fn(),
  exportMessageMarkdown: vi.fn(),
}));
vi.mock("../useChatSend", () => ({
  canRegenerateMessage: vi.fn(() => false),
  handleRegenerateMessage: vi.fn(),
}));
vi.mock("@/api/chat", () => ({ chatApi: { submitFeedback: vi.fn() } }));
vi.mock("@/utils/user", () => ({ getCurrentUserId: () => "u1" }));

import ChatMessageList from "../ChatMessageList.vue";
import {
  activeModelId,
  defaultModels,
  loading,
  messages,
  messagesHasMore,
  messagesLoadingMore,
} from "../chatState";
import type { MessageItem } from "../chatTypes";
import { loadEarlierMessages } from "../useChatHistory";
import { startEditUserMessage } from "../useChatConversations";
import { copyMessage, exportMessageMarkdown } from "../useChatFiles";
import { canRegenerateMessage, handleRegenerateMessage } from "../useChatSend";
import { chatApi } from "@/api/chat";

/**
 * ChatMessageList 组件级测试：验证模板接线（v-if/v-show、按钮回调、折叠默认态）。
 * 纯逻辑已在逻辑层用例覆盖，这里专门锁「模板有没有接对」。
 */

enableAutoUnmount(afterEach);

const userMessage = (over: Partial<MessageItem> = {}): MessageItem => ({
  id: "u0",
  role: "user",
  content: "问题",
  ...over,
});

const assistantMessage = (over: Partial<MessageItem> = {}): MessageItem => ({
  id: "a1",
  role: "assistant",
  content: "回答",
  ...over,
});

const mountList = () => mount(ChatMessageList);

/**
 * v-show 的可见性断言。
 * <p>JSdom 下 VTU 的 isVisible() 会返回上一次的结果（首次渲染后不再更新），
 * 直接看 Vue 写入的 inline display 才能反映真实状态。</p>
 */
const hasHiddenStyle = (wrapper: ReturnType<typeof mountList>, selector: string) =>
  (wrapper.find(selector).attributes("style") || "").includes("display: none");

/** 把 activeModelId 切到直连模型，isDirectMode 走真实计算属性 */
const enterDirectMode = () => {
  defaultModels.value = [
    {
      id: "direct-1",
      name: "直连模型",
      provider: "openai",
      protocol: "openai",
      iid: 1,
      upstreamModel: "gpt-4o-mini",
    },
  ];
  activeModelId.value = "direct-1";
};

beforeEach(() => {
  messages.value = [];
  loading.value = false;
  messagesHasMore.value = false;
  messagesLoadingMore.value = false;
  defaultModels.value = [];
  activeModelId.value = "";
  vi.mocked(canRegenerateMessage).mockImplementation(() => false);
  vi.mocked(chatApi.submitFeedback).mockResolvedValue(undefined);
});

describe("ChatMessageList", () => {
  it("助手消息渲染 Markdown，用户消息转义 HTML 且保留换行", () => {
    messages.value = [
      userMessage({ content: "<b>加粗</b>\n第二行" }),
      assistantMessage({ content: "**重点**" }),
    ];
    const wrapper = mountList();

    const userContent = wrapper.find(".user-content");
    expect(userContent.text()).toContain("<b>加粗</b>");
    expect(userContent.html()).toContain("<br>");
    expect(userContent.find("b").exists()).toBe(false);

    expect(wrapper.find(".markdown-content").html()).toContain("<strong>重点</strong>");
  });

  it("思考块：流式中默认展开，正文到达后默认收起，可手动展开", async () => {
    messages.value = [assistantMessage({ content: "", thinking: "先分析问题" })];
    const wrapper = mountList();

    expect(wrapper.find(".thinking-toggle").text()).toContain("思考中…");
    expect(hasHiddenStyle(wrapper, ".thinking-body")).toBe(false);

    messages.value = [assistantMessage({ content: "答案", thinking: "先分析问题" })];
    await nextTick();

    expect(wrapper.find(".thinking-toggle").text()).toContain("已深度思考");
    expect(hasHiddenStyle(wrapper, ".thinking-body")).toBe(true);

    await wrapper.find(".thinking-toggle").trigger("click");
    expect(hasHiddenStyle(wrapper, ".thinking-body")).toBe(false);
    expect(wrapper.find(".thinking-body").text()).toContain("先分析问题");
  });

  it("知识库引用默认收起，展开后显示文档名、段号与相关度", async () => {
    messages.value = [
      assistantMessage({
        kbSources: [
          {
            docIid: 1,
            docName: "手册.pdf",
            kbName: "默认知识库",
            chunkIndex: 2,
            content: "片段内容",
            score: 0.876,
          },
          {
            docIid: 2,
            docName: null,
            kbName: "默认知识库",
            chunkIndex: 0,
            content: "另一片段",
            score: 0.5,
          },
        ],
      }),
    ];
    const wrapper = mountList();

    expect(wrapper.find(".kb-sources-toggle").text()).toContain("引用来源 · 2 个片段");
    expect(hasHiddenStyle(wrapper, ".kb-sources-list")).toBe(true);

    await wrapper.find(".kb-sources-toggle").trigger("click");
    const items = wrapper.findAll(".kb-source-item");
    expect(items).toHaveLength(2);
    expect(hasHiddenStyle(wrapper, ".kb-sources-list")).toBe(false);
    expect(items[0].text()).toContain("手册.pdf · 第 3 段");
    expect(items[0].text()).toContain("相关度 88%");
    expect(items[1].text()).toContain("未命名文档 · 第 1 段");
    expect(items[1].text()).toContain("另一片段");
  });

  it("历史消息分页：有更早消息时显示并触发加载，加载中按钮禁用", async () => {
    messagesHasMore.value = true;
    const wrapper = mountList();

    const button = wrapper.find(".chat-load-older button");
    expect(button.exists()).toBe(true);
    await button.trigger("click");
    expect(loadEarlierMessages).toHaveBeenCalledTimes(1);

    messagesLoadingMore.value = true;
    await nextTick();
    const loadingButton = wrapper.find(".chat-load-older button");
    expect(loadingButton.text()).toContain("加载中…");
    expect(loadingButton.attributes("disabled")).toBeDefined();
  });

  it("直连模式没有服务端分页，不显示「加载更早消息」", () => {
    enterDirectMode();
    messagesHasMore.value = true;
    messages.value = [assistantMessage({ content: "回答" })];
    const wrapper = mountList();

    expect(wrapper.find(".chat-load-older").exists()).toBe(false);
    // 直连模式没有 Dify message_id，不显示反馈按钮
    expect(wrapper.find('[aria-label="赞同"]').exists()).toBe(false);
  });

  it("重新生成按钮按 canRegenerateMessage 禁用，点击带上消息 id", async () => {
    messages.value = [assistantMessage({ id: "a1" })];
    const wrapper = mountList();
    const rerender = async () => {
      messages.value = [...messages.value];
      await nextTick();
    };

    expect(wrapper.find('[aria-label="重新生成"]').attributes("disabled")).toBeDefined();

    vi.mocked(canRegenerateMessage).mockImplementation(() => true);
    await rerender();
    expect(wrapper.find('[aria-label="重新生成"]').attributes("disabled")).toBeUndefined();

    await wrapper.find('[aria-label="重新生成"]').trigger("click");
    expect(handleRegenerateMessage).toHaveBeenCalledWith("a1");
  });

  it("Dify 模式点赞提交反馈，再点一次取消", async () => {
    messages.value = [assistantMessage({ id: "a1", content: "回答" })];
    const wrapper = mountList();

    const like = wrapper.find('[aria-label="赞同"]');
    await like.trigger("click");
    expect(chatApi.submitFeedback).toHaveBeenCalledWith("a1", {
      rating: "like",
      user: "u1",
    });
    expect(like.classes()).toContain("is-active");

    await like.trigger("click");
    expect(chatApi.submitFeedback).toHaveBeenCalledTimes(1);
    expect(like.classes()).not.toContain("is-active");
  });

  it("用户消息：生成中禁用编辑，复制携带原文，附件显示类型与大小", async () => {
    messages.value = [
      userMessage({
        content: "问题",
        requestFiles: [
          { id: "f1", name: "报告.pdf", type: "application/pdf", size: 2048 },
        ],
      }),
    ];
    loading.value = true;
    const wrapper = mountList();

    const edit = wrapper.find('[aria-label="编辑并重发"]');
    expect(edit.attributes("disabled")).toBeDefined();
    await edit.trigger("click");
    expect(startEditUserMessage).not.toHaveBeenCalled();

    const files = wrapper.find(".user-files");
    expect(files.text()).toContain("📕");
    expect(files.text()).toContain("报告.pdf");
    expect(files.text()).toContain("2.0 KB");

    await wrapper.find('[aria-label="复制"]').trigger("click");
    expect(copyMessage).toHaveBeenCalledWith("问题");
  });

  it("助手消息可导出 Markdown", async () => {
    messages.value = [assistantMessage({ id: "a1", content: "回答" })];
    const wrapper = mountList();

    await wrapper.find('[aria-label="导出 Markdown"]').trigger("click");
    expect(exportMessageMarkdown).toHaveBeenCalledWith("a1");
  });
});
