import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";

vi.mock("../useChatConversations", () => ({
  toggleHistorySidebar: vi.fn(),
  togglePinConversation: vi.fn(),
  renameConversationById: vi.fn(),
  startNewConversation: vi.fn(),
  selectAssistant: vi.fn(),
  handleSelectConversation: vi.fn(),
  handleDeleteHistory: vi.fn(),
}));
vi.mock("../useChatHistory", () => ({ loadMoreConversations: vi.fn() }));
vi.mock("@/api/chat", () => ({ chatApi: { getConversationHistory: vi.fn() } }));
vi.mock("@/api/chatShare", () => ({ chatShareApi: { create: vi.fn() } }));
vi.mock("@/utils/user", () => ({ getCurrentUserId: () => "u1" }));

import ChatSidebar from "../ChatSidebar.vue";
import {
  activeConversationId,
  conversations,
  conversationsHasMore,
  conversationsLoadingMore,
  defaultModels,
  activeModelId,
  historyCollapsed,
  sideTab,
} from "../chatState";
import {
  assistantSearch,
  conversationSearch,
  writeDifyPinned,
} from "../useChatSelectors";
import {
  assistantDeleteConfirm,
  assistantDialogVisible,
  assistantEditingId,
  assistantMenuOpenId,
  customAssistants,
} from "../chatAssistantState";
import {
  handleDeleteHistory,
  handleSelectConversation,
  renameConversationById,
  toggleHistorySidebar,
  togglePinConversation,
} from "../useChatConversations";
import { loadMoreConversations } from "../useChatHistory";
import { chatApi } from "@/api/chat";
import { chatShareApi } from "@/api/chatShare";

/**
 * ChatSidebar 组件级测试：分组/过滤走真实 selectors，
 * 只把「点了之后会改会话或请求后端」的动作换成 mock，验证模板接线。
 */

enableAutoUnmount(afterEach);

const conversation = (over: Record<string, unknown> = {}) => {
  const now = Math.floor(Date.now() / 1000);
  return {
    id: "c1",
    name: "会话 A",
    inputs: {},
    status: "",
    introduction: "",
    created_at: now,
    updated_at: now,
    ...over,
  };
};

const mountSidebar = (isMobile = false) => mount(ChatSidebar, { props: { isMobile } });

const hasHiddenStyle = (
  wrapper: ReturnType<typeof mountSidebar>,
  selector: string
) => (wrapper.find(selector).attributes("style") || "").includes("display: none");

beforeEach(() => {
  localStorage.clear();
  historyCollapsed.value = false;
  sideTab.value = "assistant";
  conversations.value = [];
  activeConversationId.value = "";
  conversationsHasMore.value = false;
  conversationsLoadingMore.value = false;
  conversationSearch.value = "";
  assistantSearch.value = "";
  customAssistants.value = [];
  assistantMenuOpenId.value = null;
  assistantDialogVisible.value = false;
  assistantEditingId.value = null;
  assistantDeleteConfirm.value = null;
  defaultModels.value = [];
  activeModelId.value = "";
  vi.mocked(chatApi.getConversationHistory).mockResolvedValue({ data: [] } as never);
});

describe("ChatSidebar", () => {
  it("折叠按钮调用 toggleHistorySidebar；折叠后桌面侧栏隐藏", async () => {
    const wrapper = mountSidebar();
    await wrapper.find(".chat-side-logo").trigger("click");
    expect(toggleHistorySidebar).toHaveBeenCalledTimes(1);

    historyCollapsed.value = true;
    await nextTick();
    expect(hasHiddenStyle(wrapper, ".chat-sidebar")).toBe(true);
  });

  it("默认展示助手 Tab，切换会话 Tab 同步 aria-selected 与面板显隐", async () => {
    const wrapper = mountSidebar();
    const tabs = wrapper.findAll(".chat-side-tab");
    expect(tabs[0].attributes("aria-selected")).toBe("true");
    expect(hasHiddenStyle(wrapper, ".chat-side-pane-assistant")).toBe(false);
    expect(hasHiddenStyle(wrapper, ".chat-side-pane-topic")).toBe(true);

    await tabs[1].trigger("click");
    expect(sideTab.value).toBe("topic");
    expect(tabs[1].attributes("aria-selected")).toBe("true");
    expect(hasHiddenStyle(wrapper, ".chat-side-pane-assistant")).toBe(true);
    expect(hasHiddenStyle(wrapper, ".chat-side-pane-topic")).toBe(false);
  });

  it("会话按置顶/今天分组，点击标题选中会话", async () => {
    writeDifyPinned(new Set(["c1"]));
    conversations.value = [
      conversation({ id: "c1", name: "置顶会话" }),
      conversation({ id: "c2", name: "普通会话" }),
    ] as never;
    activeConversationId.value = "c2";
    const wrapper = mountSidebar();

    sideTab.value = "topic";
    await nextTick();

    const heads = wrapper.findAll(".chat-side-group-head").map((h) => h.text());
    expect(heads.some((text) => text.includes("置顶"))).toBe(true);
    expect(heads.some((text) => text.includes("今天"))).toBe(true);
    expect(wrapper.find(".chat-side-topic-pin").exists()).toBe(true);

    const items = wrapper.findAll(".chat-side-topic-item");
    const active = items.find((item) => item.classes().includes("active"));
    expect(active?.text()).toContain("普通会话");

    await wrapper.find(".chat-side-topic-main").trigger("click");
    expect(handleSelectConversation).toHaveBeenCalled();
  });

  it("置顶/重命名/删除按钮分别调用对应操作", async () => {
    conversations.value = [conversation({ id: "c2", name: "待操作会话" })] as never;
    const wrapper = mountSidebar();
    sideTab.value = "topic";
    await nextTick();

    await wrapper.find('[aria-label="置顶：待操作会话"]').trigger("click");
    expect(togglePinConversation).toHaveBeenCalledWith("c2");

    const promptSpy = vi
      .spyOn(ElMessageBox, "prompt")
      .mockResolvedValue({ value: "新名字" } as never);
    await wrapper.find('[aria-label="重命名会话：待操作会话"]').trigger("click");
    await flushPromises();
    expect(promptSpy).toHaveBeenCalled();
    expect(renameConversationById).toHaveBeenCalledWith("c2", "新名字");

    await wrapper.find('[aria-label="删除会话：待操作会话"]').trigger("click");
    expect(handleDeleteHistory).toHaveBeenCalledWith("c2");
    expect(handleSelectConversation).not.toHaveBeenCalled();
  });

  it("分享空会话给出提示且不创建分享", async () => {
    conversations.value = [conversation({ id: "c2", name: "空会话" })] as never;
    const warningSpy = vi
      .spyOn(ElMessage, "warning")
      .mockReturnValue({ close: () => {} } as never);
    const wrapper = mountSidebar();
    sideTab.value = "topic";
    await nextTick();

    await wrapper.find('[aria-label="分享会话：空会话"]').trigger("click");
    await flushPromises();

    expect(chatApi.getConversationHistory).toHaveBeenCalled();
    expect(warningSpy).toHaveBeenCalled();
    expect(chatShareApi.create).not.toHaveBeenCalled();
  });

  it("会话分页：非直连且有更多时显示并触发加载，直连模式不显示", async () => {
    conversationsHasMore.value = true;
    const wrapper = mountSidebar();
    sideTab.value = "topic";
    await nextTick();

    const button = wrapper.find(".chat-side-load-more button");
    expect(button.exists()).toBe(true);
    await button.trigger("click");
    expect(loadMoreConversations).toHaveBeenCalledTimes(1);

    conversationsLoadingMore.value = true;
    await nextTick();
    expect(wrapper.find(".chat-side-load-more button").text()).toContain("加载中…");
    expect(wrapper.find(".chat-side-load-more button").attributes("disabled")).toBeDefined();

    // 直连模式会话在本地分页，不显示服务端「加载更多」
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
    conversationsLoadingMore.value = false;
    await nextTick();
    expect(wrapper.find(".chat-side-load-more").exists()).toBe(false);
  });

  it("内置助手没有操作菜单，自定义助手可打开菜单并进入编辑", async () => {
    customAssistants.value = [
      {
        id: "custom-assistant-7",
        name: "我的助手",
        provider: "自定义助手",
        description: "自定义提示词",
        builtin: false,
        userId: "u1",
        createdAt: 7,
      },
    ];
    const wrapper = mountSidebar();

    const items = wrapper.findAll(".chat-side-assistant");
    const builtinItem = items.find((item) => item.text().includes("内置"));
    expect(builtinItem?.find(".chat-side-item-action").exists()).toBe(false);

    const customItem = items.find((item) => item.text().includes("我的助手"));
    expect(customItem).toBeTruthy();
    const actionButton = customItem?.find(".chat-side-item-action");
    expect(actionButton?.attributes("aria-expanded")).toBe("false");

    await actionButton?.trigger("click");
    expect(assistantMenuOpenId.value).toBe("custom-assistant-7");
    expect(customItem?.find('[role="menu"]').exists()).toBe(true);

    const editItem = customItem
      ?.findAll('[role="menuitem"]')
      .find((item) => item.text().includes("编辑助手"));
    await editItem?.trigger("click");
    expect(assistantDialogVisible.value).toBe(true);
    expect(assistantEditingId.value).toBe("custom-assistant-7");
  });

  it("助手搜索：关键词过滤分组，无匹配时给出提示", async () => {
    const wrapper = mountSidebar();
    await wrapper.find('[aria-label="搜索助手"]').trigger("click");
    const searchInput = wrapper.find('input[aria-label="搜索助手"]');
    expect(searchInput.exists()).toBe(true);

    await searchInput.setValue("不存在的助手");
    await nextTick();
    expect(wrapper.find(".chat-side-empty").text()).toContain("没有匹配的助手");

    await searchInput.setValue("翻译");
    await nextTick();
    const names = wrapper.findAll(".chat-side-assistant-name").map((n) => n.text());
    expect(names.some((name) => name.includes("翻译助理"))).toBe(true);
    expect(names.some((name) => name.includes("学习计划"))).toBe(false);
  });

  it("移动端抽屉：点击遮罩收起侧栏", async () => {
    mountSidebar(true);
    await nextTick();

    const overlay = document.body.querySelector<HTMLElement>(
      ".chat-sidebar-drawer-overlay"
    );
    expect(overlay).not.toBeNull();

    overlay?.click();
    await nextTick();
    expect(toggleHistorySidebar).toHaveBeenCalledTimes(1);

    document.body.innerHTML = "";
  });
});
