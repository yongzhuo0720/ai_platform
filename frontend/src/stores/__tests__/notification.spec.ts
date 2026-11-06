import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { flushPromises } from "@vue/test-utils";

const api = vi.hoisted(() => ({
  list: vi.fn(),
  unreadCount: vi.fn(),
  markRead: vi.fn(),
  markAllRead: vi.fn(),
}));

vi.mock("@/api/notification", () => ({
  default: api,
  notificationApi: api,
}));

import { useNotificationStore } from "../notification";
import type { NotificationItem } from "@/api/notification";

const ok = (datas: unknown) => ({ data: { resp_code: 0, resp_msg: "ok", datas } });
const fail = (msg: string) => ({ data: { resp_code: -1, resp_msg: msg } });

const listPayload = (items: NotificationItem[], unreadCount: number) => ({
  items,
  unreadCount,
  totalSize: items.length,
  pageNum: 1,
  pageSize: 20,
});

const item = (iid: number, readStatus: "unread" | "read" = "unread"): NotificationItem => ({
  iid,
  title: `通知 ${iid}`,
  readStatus,
});

let visibility: DocumentVisibilityState = "visible";

const setVisibility = (value: DocumentVisibilityState) => {
  visibility = value;
};

let store: ReturnType<typeof useNotificationStore>;

beforeEach(() => {
  setActivePinia(createPinia());
  store = useNotificationStore();
  setVisibility("visible");
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    get: () => visibility,
  });
});

afterEach(() => {
  // 幂等收尾：确保模块级定时器与监听器不跨用例泄漏
  store.stopPolling();
  store.stopPolling();
  vi.useRealTimers();
});

describe("notification store - 列表与未读数", () => {
  it("拉列表后写入条目与未读数，并标记已加载过", async () => {
    api.list.mockResolvedValueOnce(ok(listPayload([item(1), item(2)], 2)));

    await store.fetchList();

    expect(store.items).toHaveLength(2);
    expect(store.unreadCount).toBe(2);
    expect(store.loadedOnce).toBe(true);
    expect(store.listLoading).toBe(false);
    expect(store.listError).toBe("");
  });

  it("列表失败时保留错误文案，不把服务故障伪装成空列表", async () => {
    api.list.mockResolvedValueOnce(fail("查询通知失败，请稍后重试"));

    await store.fetchList();

    expect(store.listError).toBe("查询通知失败，请稍后重试");
    expect(store.loadedOnce).toBe(false);
    expect(store.listLoading).toBe(false);
  });

  it("静默刷新失败不覆盖已有条目与错误态", async () => {
    api.list.mockResolvedValueOnce(ok(listPayload([item(1)], 1)));
    await store.fetchList();

    api.list.mockRejectedValueOnce(new Error("offline"));
    await store.fetchList({ silent: true });

    expect(store.items).toHaveLength(1);
    expect(store.listError).toBe("");
  });

  it("未读数失败时保留上一次成功值", async () => {
    api.unreadCount.mockResolvedValueOnce(ok(5));
    await store.fetchUnreadCount();
    expect(store.unreadCount).toBe(5);

    api.unreadCount.mockRejectedValueOnce(new Error("offline"));
    await store.fetchUnreadCount();

    expect(store.unreadCount).toBe(5);
  });

  it("旧请求晚到时被序号拦截，不覆盖新结果", async () => {
    let resolveFirst!: (value: unknown) => void;
    api.list
      .mockReturnValueOnce(new Promise((resolve) => {
        resolveFirst = resolve;
      }))
      .mockResolvedValueOnce(ok(listPayload([item(2)], 1)));

    const first = store.fetchList();
    const second = store.fetchList();
    await second;
    resolveFirst(ok(listPayload([item(1)], 9)));
    await first;

    expect(store.items.map((row) => row.iid)).toEqual([2]);
    expect(store.unreadCount).toBe(1);
  });
});

describe("notification store - 已读操作", () => {
  it("单条已读先本地生效，服务端成功后保持", async () => {
    store.items = [item(1), item(2)];
    store.unreadCount = 2;
    let resolveRead!: (value: unknown) => void;
    api.markRead.mockReturnValueOnce(new Promise((resolve) => {
      resolveRead = resolve;
    }));

    const pending = store.markRead(1);
    expect(store.items[0].readStatus).toBe("read");
    expect(store.unreadCount).toBe(1);

    resolveRead(ok(true));
    await expect(pending).resolves.toBe(true);
    expect(store.items[0].readStatus).toBe("read");
  });

  it("单条已读失败时回滚状态与未读数", async () => {
    store.items = [item(1)];
    store.unreadCount = 3;
    api.markRead.mockResolvedValueOnce(fail("通知不存在或不属于当前用户"));

    await expect(store.markRead(1)).resolves.toBe(false);

    expect(store.items[0].readStatus).toBe("unread");
    expect(store.unreadCount).toBe(3);
  });

  it("已读条目再次点击不发请求", async () => {
    store.items = [item(1, "read")];
    store.unreadCount = 0;

    await expect(store.markRead(1)).resolves.toBe(true);

    expect(api.markRead).not.toHaveBeenCalled();
  });

  it("全部已读成功后清空角标，不把服务端返回的标记行数当未读数", async () => {
    store.items = [item(1), item(2)];
    store.unreadCount = 2;
    api.markAllRead.mockResolvedValueOnce(ok(2));

    await expect(store.markAllRead()).resolves.toBe(true);

    expect(store.items.every((row) => row.readStatus === "read")).toBe(true);
    expect(store.unreadCount).toBe(0);
  });

  it("全部已读失败时恢复所有条目与角标", async () => {
    store.items = [item(1), item(2, "read")];
    store.unreadCount = 1;
    api.markAllRead.mockRejectedValueOnce(new Error("offline"));

    await expect(store.markAllRead()).resolves.toBe(false);

    expect(store.items[0].readStatus).toBe("unread");
    expect(store.items[1].readStatus).toBe("read");
    expect(store.unreadCount).toBe(1);
  });

  it("没有未读时全部已读直接返回，不发请求", async () => {
    store.items = [item(1, "read")];
    store.unreadCount = 0;

    await expect(store.markAllRead()).resolves.toBe(true);

    expect(api.markAllRead).not.toHaveBeenCalled();
  });
});

describe("notification store - 轮询", () => {
  it("启动后立即拉一次未读数，之后每 30 秒一次；停止后不再请求", async () => {
    vi.useFakeTimers();
    api.unreadCount.mockResolvedValue(ok(3));

    store.startPolling();
    await flushPromises();
    expect(api.unreadCount).toHaveBeenCalledTimes(1);
    expect(store.unreadCount).toBe(3);

    await vi.advanceTimersByTimeAsync(30_000);
    expect(api.unreadCount).toHaveBeenCalledTimes(2);

    store.stopPolling();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(api.unreadCount).toHaveBeenCalledTimes(2);
  });

  it("多个消费方共用一个定时器，引用计数归零才停止", async () => {
    vi.useFakeTimers();
    api.unreadCount.mockResolvedValue(ok(0));

    store.startPolling();
    store.startPolling();
    await flushPromises();
    expect(api.unreadCount).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(30_000);
    expect(api.unreadCount).toHaveBeenCalledTimes(2);

    store.stopPolling();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(api.unreadCount).toHaveBeenCalledTimes(3);

    store.stopPolling();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(api.unreadCount).toHaveBeenCalledTimes(3);
  });

  it("页面隐藏时跳过轮询，重新可见时立即补一次", async () => {
    vi.useFakeTimers();
    api.unreadCount.mockResolvedValue(ok(1));
    store.startPolling();
    await flushPromises();
    expect(api.unreadCount).toHaveBeenCalledTimes(1);

    setVisibility("hidden");
    await vi.advanceTimersByTimeAsync(30_000);
    expect(api.unreadCount).toHaveBeenCalledTimes(1);

    setVisibility("visible");
    document.dispatchEvent(new Event("visibilitychange"));
    await flushPromises();
    expect(api.unreadCount).toHaveBeenCalledTimes(2);
  });

  it("面板打开时轮询刷新列表而不是只刷角标", async () => {
    vi.useFakeTimers();
    api.list.mockResolvedValue(ok(listPayload([item(1)], 1)));
    api.unreadCount.mockResolvedValue(ok(1));

    await store.openPanel();
    expect(api.list).toHaveBeenCalledTimes(1);

    store.startPolling();
    await flushPromises();
    expect(api.unreadCount).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(30_000);
    expect(api.list).toHaveBeenCalledTimes(2);
    expect(api.unreadCount).toHaveBeenCalledTimes(1);
  });
});
