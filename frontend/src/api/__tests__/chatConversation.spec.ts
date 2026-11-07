import { describe, expect, it, vi } from "vitest";

// HNSP 按方法形参名解析请求体：Controller 的形参叫 data，请求体就必须包在 data 下
const { post } = vi.hoisted(() => ({ post: vi.fn() }));

vi.mock("../axios", () => ({
  default: { post },
}));

import { chatConversationApi } from "../chatConversation";

describe("chatConversationApi", () => {
  it("列表是无参 POST，只取元数据", async () => {
    await chatConversationApi.list();

    expect(post).toHaveBeenCalledWith("/ai/api/chatConversation/list");
  });

  it("按需正文把 convIds 包在 data 下，否则后端按形参名取不到", async () => {
    await chatConversationApi.detail(["c1", "c2"]);

    expect(post).toHaveBeenCalledWith("/ai/api/chatConversation/detail", {
      data: { convIds: ["c1", "c2"] },
    });
  });
});
