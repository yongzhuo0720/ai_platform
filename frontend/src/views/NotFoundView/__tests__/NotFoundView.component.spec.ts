import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { enableAutoUnmount, mount } from "@vue/test-utils";

const routerMocks = vi.hoisted(() => ({
  route: { fullPath: "/not-exist" },
  replace: vi.fn(),
  back: vi.fn(),
}));
const cookieMocks = vi.hoisted(() => ({ adminToken: undefined as string | undefined }));

vi.mock("vue-router", () => ({
  useRoute: () => routerMocks.route,
  useRouter: () => ({ replace: routerMocks.replace, back: routerMocks.back }),
}));
vi.mock("js-cookie", () => ({
  default: {
    get: vi.fn((key: string) =>
      key === "admin_loginToken" ? cookieMocks.adminToken : undefined
    ),
  },
}));

import NotFoundView from "../index.vue";

/**
 * 404 兜底页：展示原始路径，并按是否处于管理后台决定「回到首页」的去向。
 */

enableAutoUnmount(afterEach);

const mountView = () => mount(NotFoundView);

const clickButton = async (wrapper: ReturnType<typeof mountView>, text: string) => {
  const button = wrapper.findAll(".el-button").find((item) => item.text() === text);
  expect(button, `button ${text}`).toBeTruthy();
  await button!.trigger("click");
};

let originalOwnHistoryLength: PropertyDescriptor | undefined;

beforeEach(() => {
  originalOwnHistoryLength = Object.getOwnPropertyDescriptor(window.history, "length");
  routerMocks.route.fullPath = "/not-exist?from=bookmark";
  routerMocks.replace.mockReset();
  routerMocks.back.mockReset();
  cookieMocks.adminToken = undefined;
  window.location.hash = "";
});

afterEach(() => {
  if (originalOwnHistoryLength) {
    Object.defineProperty(window.history, "length", originalOwnHistoryLength);
  } else {
    delete (window.history as any).length;
  }
  window.location.hash = "";
});

describe("NotFoundView", () => {
  it("展示 404 与完整访问路径", () => {
    const wrapper = mountView();

    expect(wrapper.text()).toContain("404");
    expect(wrapper.text()).toContain("找不到页面：/not-exist?from=bookmark");
  });

  it("管理后台且仍有后台登录态时回到管理台，否则回聊天页", async () => {
    window.location.hash = "#/admin/users";
    cookieMocks.adminToken = "admin-token";
    const admin = mountView();
    await clickButton(admin, "回到首页");
    expect(routerMocks.replace).toHaveBeenCalledWith("/admin");

    // 后台登录态已失效：不再回管理台
    cookieMocks.adminToken = undefined;
    await clickButton(admin, "回到首页");
    expect(routerMocks.replace).toHaveBeenLastCalledWith("/chat");

    window.location.hash = "#/chat/abc";
    cookieMocks.adminToken = "admin-token";
    await clickButton(admin, "回到首页");
    expect(routerMocks.replace).toHaveBeenLastCalledWith("/chat");
  });

  it("有历史记录时返回上一页", async () => {
    Object.defineProperty(window.history, "length", { configurable: true, get: () => 3 });
    const wrapper = mountView();
    await clickButton(wrapper, "返回上一页");

    expect(routerMocks.back).toHaveBeenCalledTimes(1);
    expect(routerMocks.replace).not.toHaveBeenCalled();
  });

  it("直接输入网址打开（无历史）时返回首页兜底", async () => {
    Object.defineProperty(window.history, "length", { configurable: true, get: () => 1 });
    const wrapper = mountView();
    await clickButton(wrapper, "返回上一页");

    expect(routerMocks.back).not.toHaveBeenCalled();
    expect(routerMocks.replace).toHaveBeenCalledWith("/chat");
  });
});
