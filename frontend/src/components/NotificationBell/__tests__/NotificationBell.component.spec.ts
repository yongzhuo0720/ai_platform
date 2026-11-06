import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";

import type { NotificationItem } from "@/api/notification";

const env = vi.hoisted(() => ({
  store: null as any,
  push: vi.fn(),
}));

vi.mock("vue-router", () => ({
  useRouter: () => ({ push: env.push }),
}));

vi.mock("@/stores/notification", () => ({
  useNotificationStore: () => env.store,
}));

import NotificationBell from "../index.vue";

enableAutoUnmount(afterEach);

const item = (overrides: Partial<NotificationItem> = {}): NotificationItem => ({
  iid: 1,
  title: "文档处理完成",
  content: "《员工手册.txt》已完成解析与向量化",
  level: "success",
  link: "/knowledge-bases",
  readStatus: "unread",
  createtime: Date.now() - 5.5 * 60_000,
  ...overrides,
});

const createStore = () => {
  const storeLike = reactive({
    items: [] as NotificationItem[],
    unreadCount: 0,
    listLoading: false,
    listError: "",
    loadedOnce: true,
    panelOpen: false,
    get hasUnread() {
      return storeLike.unreadCount > 0;
    },
    openPanel: vi.fn(async () => {
      storeLike.panelOpen = true;
    }),
    closePanel: vi.fn(() => {
      storeLike.panelOpen = false;
    }),
    fetchList: vi.fn(async () => undefined),
    markRead: vi.fn(async () => true),
    markAllRead: vi.fn(async () => true),
    startPolling: vi.fn(),
    stopPolling: vi.fn(),
  });
  return storeLike;
};

const mountBell = (props: Record<string, unknown> = {}) =>
  mount(NotificationBell, { props, attachTo: document.body });

beforeEach(() => {
  env.push.mockClear();
  env.store = createStore();
});

describe("NotificationBell - 入口与角标", () => {
  it("挂载时开始轮询，卸载时停止", () => {
    const wrapper = mountBell();

    expect(env.store.startPolling).toHaveBeenCalledTimes(1);

    wrapper.unmount();

    expect(env.store.stopPolling).toHaveBeenCalledTimes(1);
  });

  it("未读数显示在角标上，超过 99 显示 99+，并写入无障碍标签", () => {
    env.store.unreadCount = 120;
    const wrapper = mountBell();

    expect(wrapper.find(".notification-badge").text()).toBe("99+");
    expect(wrapper.find(".notification-trigger").attributes("aria-label")).toBe(
      "通知，120 条未读",
    );
    expect(wrapper.find(".notification-trigger").attributes("aria-expanded")).toBe("false");
  });

  it("没有未读时不渲染角标，aria-label 说明没有未读", () => {
    const wrapper = mountBell();

    expect(wrapper.find(".notification-badge").exists()).toBe(false);
    expect(wrapper.find(".notification-trigger").attributes("aria-label")).toBe(
      "通知，没有未读消息",
    );
  });

  it("collapsed 时文字与计数隐藏，保留图标入口", () => {
    env.store.unreadCount = 2;
    const wrapper = mountBell({ collapsed: true });

    expect(wrapper.find(".notification-trigger").classes()).toContain("is-collapsed");
    expect(wrapper.find(".notification-trigger-text").isVisible()).toBe(false);
    expect(wrapper.find(".notification-trigger-count").isVisible()).toBe(false);
    expect(wrapper.find(".notification-badge").exists()).toBe(true);
  });
});

describe("NotificationBell - 面板", () => {
  it("点击铃铛打开面板并加载列表，展开状态同步到 aria-expanded", async () => {
    const wrapper = mountBell();
    await wrapper.find(".notification-trigger").trigger("click");
    await flushPromises();

    expect(env.store.openPanel).toHaveBeenCalledTimes(1);
    expect(wrapper.find(".notification-panel").exists()).toBe(true);
    expect(wrapper.find('[role="dialog"]').attributes("aria-label")).toBe("通知列表");
    expect(wrapper.find(".notification-trigger").attributes("aria-expanded")).toBe("true");
  });

  it("渲染条目：级别图标、标题、摘要、相对时间与未读样式", () => {
    env.store.panelOpen = true;
    env.store.unreadCount = 1;
    env.store.items = [
      item(),
      item({
        iid: 2,
        title: "RAG 评估失败",
        content: "数据集已被删除",
        level: "error",
        readStatus: "read",
        link: "/rag-eval",
        createtime: Date.now() - 2 * 3_600_000,
      }),
    ];
    const wrapper = mountBell();

    const rows = wrapper.findAll(".notification-item");
    expect(rows).toHaveLength(2);
    expect(rows[0].classes()).toContain("is-unread");
    expect(rows[0].find(".notification-icon").classes()).toContain("is-success");
    expect(rows[0].find(".notification-title").text()).toContain("文档处理完成");
    expect(rows[0].find(".notification-content").text()).toContain("员工手册");
    expect(rows[0].find(".notification-time").text()).toBe("5 分钟前");
    expect(rows[0].find(".notification-dot").exists()).toBe(true);

    expect(rows[1].classes()).not.toContain("is-unread");
    expect(rows[1].find(".notification-icon").classes()).toContain("is-error");
    expect(rows[1].find(".notification-time").text()).toBe("2 小时前");
    expect(rows[1].find(".notification-dot").exists()).toBe(false);
  });

  it("点击未读条目：标记已读、关闭面板并跳转到站内链接", async () => {
    env.store.panelOpen = true;
    env.store.unreadCount = 1;
    env.store.items = [item()];
    const wrapper = mountBell();

    await wrapper.find(".notification-item").trigger("click");
    await flushPromises();

    expect(env.store.markRead).toHaveBeenCalledWith(1);
    expect(env.store.closePanel).toHaveBeenCalled();
    expect(env.push).toHaveBeenCalledWith("/knowledge-bases");
  });

  it("已读条目不发已读请求，仍可跳转", async () => {
    env.store.panelOpen = true;
    env.store.items = [item({ readStatus: "read" })];
    const wrapper = mountBell();

    await wrapper.find(".notification-item").trigger("click");
    await flushPromises();

    expect(env.store.markRead).not.toHaveBeenCalled();
    expect(env.push).toHaveBeenCalledWith("/knowledge-bases");
  });

  it("不安全的链接被拦截，但已读状态照常更新", async () => {
    env.store.panelOpen = true;
    env.store.unreadCount = 1;
    env.store.items = [item({ link: "//evil.example.com" })];
    const wrapper = mountBell();

    await wrapper.find(".notification-item").trigger("click");
    await flushPromises();

    expect(env.store.markRead).toHaveBeenCalledWith(1);
    expect(env.push).not.toHaveBeenCalled();
  });

  it("全部已读按钮按未读数禁用/启用", async () => {
    env.store.panelOpen = true;
    env.store.unreadCount = 2;
    env.store.items = [item(), item({ iid: 2 })];
    const wrapper = mountBell();

    const button = wrapper.find(".notification-mark-all");
    expect(button.attributes("disabled")).toBeUndefined();

    await button.trigger("click");
    expect(env.store.markAllRead).toHaveBeenCalledTimes(1);

    env.store.unreadCount = 0;
    await flushPromises();
    expect(wrapper.find(".notification-mark-all").attributes("disabled")).toBeDefined();
  });

  it("加载中显示骨架并标注 aria-busy", () => {
    env.store.panelOpen = true;
    env.store.listLoading = true;
    const wrapper = mountBell();

    expect(wrapper.find(".notification-loading").attributes("aria-busy")).toBe("true");
    expect(wrapper.findAll(".notification-skeleton")).toHaveLength(3);
    expect(wrapper.find(".notification-empty").exists()).toBe(false);
  });

  it("失败时显示错误与重试入口，空列表不会掩盖错误", async () => {
    env.store.panelOpen = true;
    env.store.listError = "查询通知失败，请稍后重试";
    const wrapper = mountBell();

    expect(wrapper.find('[role="alert"]').text()).toContain("查询通知失败，请稍后重试");
    expect(wrapper.find(".notification-empty").exists()).toBe(false);

    await wrapper.find(".notification-retry").trigger("click");
    expect(env.store.fetchList).toHaveBeenCalledTimes(1);
  });

  it("没有通知时展示空态说明", () => {
    env.store.panelOpen = true;
    const wrapper = mountBell();

    expect(wrapper.find('[role="status"]').text()).toContain("暂无通知");
  });
});

describe("NotificationBell - 关闭交互", () => {
  it("Esc 关闭面板并把焦点还给铃铛", async () => {
    const wrapper = mountBell();
    await wrapper.find(".notification-trigger").trigger("click");
    await flushPromises();
    expect(wrapper.find(".notification-panel").exists()).toBe(true);

    document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    await flushPromises();

    expect(wrapper.find(".notification-panel").exists()).toBe(false);
    expect(document.activeElement).toBe(wrapper.find(".notification-trigger").element);
  });

  it("点击面板外部关闭，点击内部不关闭", async () => {
    const wrapper = mountBell();
    await wrapper.find(".notification-trigger").trigger("click");
    await flushPromises();

    await wrapper.find(".notification-panel").trigger("click");
    expect(wrapper.find(".notification-panel").exists()).toBe(true);

    document.body.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    await flushPromises();

    expect(wrapper.find(".notification-panel").exists()).toBe(false);
  });
});
