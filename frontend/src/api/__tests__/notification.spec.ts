import { describe, expect, it, vi } from "vitest";

// HNSP 按方法形参名解析请求体：list 的形参是 query，markRead 的是 data
const { post } = vi.hoisted(() => ({ post: vi.fn() }));

vi.mock("../axios", () => ({
  default: { post },
}));

import { notificationApi } from "../notification";

describe("notificationApi", () => {
  it("列表请求包在 query 下，默认静默，避免轮询失败弹全局提示", async () => {
    await notificationApi.list({ pageNum: 1, pageSize: 20 });

    expect(post).toHaveBeenCalledWith(
      "/ai/api/notification/list",
      { query: { pageNum: 1, pageSize: 20 } },
      { silent: true },
    );
  });

  it("未读数是无参 POST，同样静默", async () => {
    await notificationApi.unreadCount();

    expect(post).toHaveBeenCalledWith(
      "/ai/api/notification/unreadCount",
      undefined,
      { silent: true },
    );
  });

  it("标记已读包在 data 下，走非静默请求以便失败时提示用户", async () => {
    await notificationApi.markRead(42);

    expect(post).toHaveBeenCalledWith("/ai/api/notification/markRead", {
      data: { iid: 42 },
    });
  });

  it("全部已读走非静默请求", async () => {
    await notificationApi.markAllRead();

    expect(post).toHaveBeenCalledWith("/ai/api/notification/markAllRead");
  });
});
