import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { ElMessage } from "element-plus";
import Cookies from "js-cookie";

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  replace: vi.fn(),
  routePath: "/login",
}));

vi.mock("vue-router", () => ({
  useRouter: () => ({ push: mocks.push, replace: mocks.replace }),
  useRoute: () => ({ path: mocks.routePath }),
}));
vi.mock("js-cookie", () => ({
  default: { get: vi.fn(), set: vi.fn(), remove: vi.fn() },
}));
vi.mock("@/api/user", () => ({
  userApi: { logintest: vi.fn(), getUserList: vi.fn() },
}));
vi.mock("@/stores/difyConfig", () => ({ ensureLoaded: vi.fn() }));

import LoginView from "../index.vue";
import { userApi } from "@/api/user";
import { ensureLoaded } from "@/stores/difyConfig";
import { ADMIN_SESSION_KEYS } from "@/utils/session";

/**
 * 登录页：用户/后台两种模式、Cookie 回填与写入、超管门控、
 * 失败与异常路径不残留登录态、滑动切换的防抖。
 */

enableAutoUnmount(afterEach);

const logintest = userApi.logintest as Mock;
const getUserList = userApi.getUserList as Mock;
const ensureLoadedMock = ensureLoaded as Mock;
const cookieGet = Cookies.get as Mock;
const cookieSet = Cookies.set as Mock;
const cookieRemove = Cookies.remove as Mock;

const ok = (datas: unknown) => ({ data: { resp_code: 0, datas } });
const fail = (resp_msg: string) => ({ data: { resp_code: 500, resp_msg } });
const loginOk = (token = "token-1") => ok({ token });
const usersOk = (users: unknown[]) => ok({ content: users });

const superAdmin = { uid: "u1", uaccess: "admin", uname: "超管", rid: 99999 };
const normalUser = { uid: "u2", uaccess: "alice", uname: "爱丽丝", rid: 2 };

const createMessageSpies = () => ({
  success: vi.spyOn(ElMessage, "success").mockReturnValue({ close: () => {} } as never),
  error: vi.spyOn(ElMessage, "error").mockReturnValue({ close: () => {} } as never),
  warning: vi.spyOn(ElMessage, "warning").mockReturnValue({ close: () => {} } as never),
});

const mountView = () => mount(LoginView);

const submitForm = async (wrapper: ReturnType<typeof mountView>, panel: "user" | "admin") => {
  await wrapper.find(`.panel-${panel} .login-form`).trigger("submit");
  await flushPromises();
};

beforeEach(() => {
  mocks.routePath = "/login";
  cookieGet.mockImplementation((key: string) => {
    if (key === "uaccess") return "alice";
    if (key === "upwd") return "secret";
    return undefined;
  });
});

afterEach(() => {
  vi.useRealTimers();
});

describe("LoginView", () => {
  it("默认进入用户模式，回填 Cookie 账号，登录成功后写入用户登录态并进入聊天页", async () => {
    let resolveLogin!: (value: unknown) => void;
    logintest.mockReturnValue(
      new Promise((resolve) => {
        resolveLogin = resolve;
      })
    );
    getUserList.mockResolvedValue(usersOk([normalUser]));

    const wrapper = mountView();
    expect(wrapper.find(".login-screen").classes()).not.toContain("is-admin-mode");
    expect(
      (wrapper.find('input[aria-label="平台用户名"]').element as HTMLInputElement).value
    ).toBe("alice");
    expect(
      (wrapper.find('input[aria-label="平台密码"]').element as HTMLInputElement).value
    ).toBe("secret");

    await submitForm(wrapper, "user");
    const button = wrapper.find(".panel-user .login-btn");
    expect(button.attributes("disabled")).toBeDefined();
    expect(button.text()).toContain("登录中");

    resolveLogin(loginOk());
    await flushPromises();
    await nextTick();

    expect(logintest).toHaveBeenCalledWith({
      uaccess: "alice",
      upwd: "secret",
      keepUser: true,
    });
    expect(cookieSet).toHaveBeenCalledWith("loginToken", "token-1", { path: "/" });
    expect(cookieSet).toHaveBeenCalledWith("rid", 2, { path: "/" });
    expect(cookieSet).toHaveBeenCalledWith("uaccess", "alice", { path: "/" });
    expect(cookieSet).toHaveBeenCalledWith("uname", "爱丽丝", { path: "/" });
    expect(cookieSet).toHaveBeenCalledWith("uid", "u2", { path: "/" });
    expect(ensureLoadedMock).toHaveBeenCalledOnce();
    expect(mocks.push).toHaveBeenCalledWith("/chat");
    expect(wrapper.find(".panel-user .login-btn").text()).toContain("进入智能平台");
  });

  it("用户名为空或密码为空时提示，不发起登录请求", async () => {
    cookieGet.mockReturnValue(undefined);
    const spies = createMessageSpies();
    const wrapper = mountView();

    await submitForm(wrapper, "user");
    expect(spies.warning).toHaveBeenCalledWith("请输入用户名");

    await wrapper.find('input[aria-label="平台用户名"]').setValue("alice");
    await submitForm(wrapper, "user");
    expect(spies.warning).toHaveBeenCalledWith("请输入密码");
    expect(logintest).not.toHaveBeenCalled();
  });

  it("登录失败与请求异常都不写 Cookie、不跳转，loading 正常复位", async () => {
    createMessageSpies();
    logintest.mockResolvedValueOnce(fail("账号或密码错误"));
    const wrapper = mountView();

    await submitForm(wrapper, "user");
    expect(cookieSet).not.toHaveBeenCalled();
    expect(mocks.push).not.toHaveBeenCalled();
    expect(wrapper.find(".panel-user .login-btn").attributes("disabled")).toBeUndefined();

    logintest.mockRejectedValueOnce(new Error("network down"));
    await submitForm(wrapper, "user");
    expect(cookieSet).not.toHaveBeenCalled();
    expect(mocks.push).not.toHaveBeenCalled();
    expect(wrapper.find(".panel-user .login-btn").attributes("disabled")).toBeUndefined();
  });

  it("用户不存在时给出提示，不写入登录态", async () => {
    const spies = createMessageSpies();
    logintest.mockResolvedValue(loginOk());
    getUserList.mockResolvedValue(usersOk([]));
    const wrapper = mountView();

    await submitForm(wrapper, "user");

    expect(spies.error).toHaveBeenCalledWith("用户不存在");
    expect(cookieSet).toHaveBeenCalledWith("loginToken", "token-1", { path: "/" });
    expect(cookieSet).not.toHaveBeenCalledWith("uid", expect.anything(), expect.anything());
    expect(mocks.push).not.toHaveBeenCalled();
  });

  it("超管后台登录：写入 admin_* 登录态并进入用户管理", async () => {
    mocks.routePath = "/admin/login";
    logintest.mockResolvedValue(loginOk());
    getUserList.mockResolvedValue(usersOk([superAdmin]));
    const wrapper = mountView();

    expect(wrapper.find(".login-screen").classes()).toContain("is-admin-mode");
    await submitForm(wrapper, "admin");

    expect(cookieSet).toHaveBeenCalledWith("admin_loginToken", "token-1", { path: "/" });
    expect(cookieSet).toHaveBeenCalledWith("admin_rid", 99999, { path: "/" });
    expect(cookieSet).toHaveBeenCalledWith("admin_uaccess", "admin", { path: "/" });
    expect(cookieSet).toHaveBeenCalledWith("admin_uname", "超管", { path: "/" });
    expect(cookieSet).toHaveBeenCalledWith("admin_uid", "u1", { path: "/" });
    expect(cookieSet).toHaveBeenCalledWith("admin_access", "1", { path: "/" });
    expect(cookieSet).not.toHaveBeenCalledWith("loginToken", expect.anything(), expect.anything());
    expect(mocks.push).toHaveBeenCalledWith("/admin/users");
  });

  it("非超管后台登录：提示暂无权限并清空全部后台登录态", async () => {
    mocks.routePath = "/admin/login";
    const spies = createMessageSpies();
    logintest.mockResolvedValue(loginOk());
    getUserList.mockResolvedValue(usersOk([normalUser]));
    const wrapper = mountView();

    await submitForm(wrapper, "admin");

    expect(spies.error).toHaveBeenCalledWith("暂无权限");
    expect(cookieSet).not.toHaveBeenCalledWith("admin_access", "1", { path: "/" });
    for (const key of ADMIN_SESSION_KEYS) {
      expect(cookieRemove).toHaveBeenCalledWith(key, { path: "/" });
    }
    expect(mocks.push).not.toHaveBeenCalled();
  });

  it("切换模式：replace 路由并清空表单，动画期间重复切换被忽略", async () => {
    vi.useFakeTimers();
    const wrapper = mountView();

    await wrapper.find(".slider-btn").trigger("click");
    await nextTick();

    expect(mocks.replace).toHaveBeenCalledWith("/admin/login");
    expect(wrapper.find(".login-screen").classes()).toContain("is-admin-mode");
    expect((wrapper.vm as any).form.uaccess).toBe("");
    expect((wrapper.vm as any).form.upwd).toBe("");

    (wrapper.vm as any).switchMode(false);
    expect(mocks.replace).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(750);
    (wrapper.vm as any).switchMode(false);
    await nextTick();
    expect(mocks.replace).toHaveBeenCalledTimes(2);
    expect(mocks.replace).toHaveBeenLastCalledWith("/login");
    expect(wrapper.find(".login-screen").classes()).not.toContain("is-admin-mode");
  });
});
