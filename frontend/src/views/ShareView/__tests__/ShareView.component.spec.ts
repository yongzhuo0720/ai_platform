import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";

const mocks = vi.hoisted(() => ({ params: {} as Record<string, string> }));

vi.mock("vue-router", () => ({ useRoute: () => ({ params: mocks.params }) }));
vi.mock("@/api/chatShare", () => ({
  chatShareApi: { get: vi.fn(), create: vi.fn(), delete: vi.fn() },
}));

import ShareView from "../index.vue";
import { chatShareApi } from "@/api/chatShare";

/**
 * 免登录分享页：token 缺失/失效/内容损坏时的提示，
 * 以及用户消息转义、助手消息 Markdown 消毒（XSS 边界）。
 */

enableAutoUnmount(afterEach);

const getShare = chatShareApi.get as Mock;

const ok = (datas: unknown) => ({ data: { resp_code: 0, resp_msg: "ok", datas } });

const shareRow = (payload: unknown, overrides: Record<string, unknown> = {}) => ({
  title: "如何部署后端",
  authorName: "爱丽丝",
  createtime: "2026-10-29T08:05:09",
  payload: typeof payload === "string" ? payload : JSON.stringify(payload),
  ...overrides,
});

const mountView = () => mount(ShareView);

beforeEach(() => {
  mocks.params = { token: "share-token-1" };
});

describe("ShareView", () => {
  it("按 token 加载分享：标题/作者/日期、消息过滤、用户消息转义、助手 Markdown 消毒", async () => {
    getShare.mockResolvedValue(
      ok(
        shareRow({
          messages: [
            { role: "user", content: "<img src=x onerror=alert(1)>你好" },
            { role: "assistant", content: "**重点**\n\n<script>alert(1)</script>" },
            { role: "user", content: "   " },
            null,
          ],
        })
      )
    );
    const wrapper = mountView();
    await flushPromises();

    expect(getShare).toHaveBeenCalledWith("share-token-1");
    expect(wrapper.find(".share-header h1").text()).toBe("如何部署后端");
    expect(wrapper.find(".share-meta").text()).toContain("爱丽丝");
    expect(wrapper.find(".share-meta").text()).toContain("2026-10-29");

    const messages = wrapper.findAll(".share-message");
    expect(messages).toHaveLength(2);
    expect(messages[0].find(".share-role").text()).toBe("问");
    expect(messages[1].find(".share-role").text()).toBe("答");

    const userHtml = messages[0].find(".share-user-text").html();
    expect(userHtml).toContain("&lt;img");
    expect(userHtml).not.toContain("<img");
    expect(userHtml).toContain("你好");

    const assistantHtml = messages[1].find(".markdown-content").html();
    expect(assistantHtml).toContain("<strong>重点</strong>");
    expect(assistantHtml).not.toContain("<script>");
  });

  it("缺少 token 时提示分享链接无效且不请求接口", async () => {
    mocks.params = {};
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find(".share-status.is-error").text()).toBe("分享链接无效");
    expect(getShare).not.toHaveBeenCalled();
  });

  it("后端返回错误码时展示原因；datas 为空时提示失效", async () => {
    getShare.mockResolvedValueOnce({ data: { resp_code: 404, resp_msg: "分享已过期" } });
    let wrapper = mountView();
    await flushPromises();
    expect(wrapper.find(".share-status.is-error").text()).toBe("分享已过期");

    getShare.mockResolvedValueOnce({ data: { resp_code: 0, datas: null } });
    wrapper = mountView();
    await flushPromises();
    expect(wrapper.find(".share-status.is-error").text()).toBe("分享不存在或已失效");
  });

  it("payload 不是合法 JSON 时提示内容损坏", async () => {
    getShare.mockResolvedValue(ok(shareRow("{not-json")));
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find(".share-status.is-error").text()).toBe("分享内容已损坏");
    expect(wrapper.findAll(".share-message")).toHaveLength(0);
  });

  it("请求异常时提示稍后重试", async () => {
    getShare.mockRejectedValue(new Error("network"));
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find(".share-status.is-error").text()).toBe("加载失败，请稍后重试");
  });

  it("payload 缺少 messages 时不渲染消息，标题回退默认值", async () => {
    getShare.mockResolvedValue(ok(shareRow({}, { title: "", authorName: "", createtime: "" })));
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find(".share-header h1").text()).toBe("对话分享");
    expect(wrapper.findAll(".share-message")).toHaveLength(0);
    expect(wrapper.find(".share-meta").text()).not.toContain("·");
  });
});
