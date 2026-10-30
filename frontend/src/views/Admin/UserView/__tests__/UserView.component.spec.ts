import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { ElLoading, ElMessage, ElMessageBox } from "element-plus";
import Cookies from "js-cookie";

vi.mock("js-cookie", () => ({ default: { get: vi.fn() } }));
vi.mock("@/api/user", () => ({
  userApi: {
    getUserList: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
  },
}));
vi.mock("@/api/role", () => ({ roleApi: { getDataList: vi.fn() } }));
vi.mock("@/api/unit", () => ({ unitApi: { getDataList: vi.fn() } }));

import UserView from "../index.vue";
import { userApi } from "@/api/user";
import { roleApi } from "@/api/role";
import { unitApi } from "@/api/unit";

/**
 * 用户管理后台：列表/角色映射、搜索与分页、新建/编辑表单、
 * 重置密码与删除确认。API 全部 mock，验证模板与 useIndex 的接线。
 */

enableAutoUnmount(afterEach);

const ok = (datas: unknown, respMsg = "ok") => ({
  data: { resp_code: 0, resp_msg: respMsg, datas },
} as never);
const fail = (msg: string) => ({ data: { resp_code: 500, resp_msg: msg } } as never);

const users = {
  content: [
    {
      uid: "u1",
      uname: "张三",
      uaccess: "zhangsan",
      rid: 2,
      gid: "1001",
      gname: "研发部",
      createtime: "2026-10-01 10:00:00",
    },
    {
      uid: "u2",
      uname: "王超管",
      uaccess: "admin",
      rid: 99999,
      gname: "总部",
      createtime: "2026-09-01 09:00:00",
    },
  ],
  totalSize: 2,
};

const roles = {
  content: [
    { iid: 2, name: "普通用户" },
    { iid: 99999, name: "超级管理员" },
  ],
  totalSize: 2,
};

const units = [
  {
    unitsno: "1001",
    unitname: "研发部",
    childList: [{ unitsno: "1002", unitname: "前端组", childList: [] }],
  },
];

const mountView = () =>
  mount(UserView, {
    global: {
      stubs: {
        Search: true,
        CirclePlusFilled: true,
        Edit: true,
        Delete: true,
        Refresh: true,
      },
    },
  });

const setupSuccess = () => {
  vi.mocked(userApi.getUserList).mockResolvedValue(ok(users));
  vi.mocked(roleApi.getDataList).mockResolvedValue(ok(roles));
  vi.mocked(unitApi.getDataList).mockResolvedValue(ok(units));
  vi.mocked(userApi.create).mockResolvedValue(ok(true));
  vi.mocked(userApi.update).mockResolvedValue(ok(true));
  vi.mocked(userApi.delete).mockResolvedValue(ok(true));
};

/** 每个用例只调用一次：对同一方法二次 spyOn 会丢弃第一次的引用 */
const createMessageSpies = () => ({
  success: vi.spyOn(ElMessage, "success").mockReturnValue({ close: () => {} } as never),
  error: vi.spyOn(ElMessage, "error").mockReturnValue({ close: () => {} } as never),
});

/** js-cookie 的 get 是重载函数，按单参签名替换实现时需绕过重载类型检查 */
const mockCookieGet = (impl: (name: string) => string | undefined) =>
  vi.mocked(Cookies.get).mockImplementation(impl as never);

const asAdmin = (rid = "99999") => {
  mockCookieGet((name) => {
    if (name === "admin_rid") return rid;
    return undefined;
  });
};

/** 打开弹窗里的下拉并点选指定文案的选项（popper 挂在 body 上） */
const pickOption = async (text: string) => {
  await nextTick();
  const option = Array.from(
    document.body.querySelectorAll<HTMLElement>(".el-select-dropdown__item")
  ).find((item) => item.textContent?.includes(text));
  expect(option, `option ${text}`).toBeTruthy();
  option!.click();
  await flushPromises();
  await nextTick();
};

/**
 * 打开部门树并点选指定文案的节点。
 * 页面上有两个部门树（搜索栏 + 弹窗），必须挑下拉可见的那个，
 * 否则点到隐藏的搜索栏树上，弹窗表单拿不到值。
 */
const pickTreeNode = async (text: string) => {
  await nextTick();
  const node = Array.from(
    document.body.querySelectorAll<HTMLElement>(".el-tree-node__content")
  ).find(
    (item) =>
      item.textContent?.includes(text) &&
      item.closest<HTMLElement>(".el-select__popper")?.style.display !== "none"
  );
  expect(node, `tree node ${text}`).toBeTruthy();
  node!.click();
  await flushPromises();
  await nextTick();
};

beforeEach(() => {
  asAdmin();
  setupSuccess();
  vi.spyOn(ElLoading, "service").mockReturnValue({ close: vi.fn() } as never);
});

describe("UserView", () => {
  it("挂载后加载用户/角色/部门，表格按角色映射展示名称", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(userApi.getUserList).toHaveBeenCalledTimes(1);
    expect(userApi.getUserList).toHaveBeenCalledWith(
      expect.objectContaining({
        bean: { uname: "", gid: "", uaccess: "" },
        pageRequest: expect.objectContaining({ pageNum: 1, pageSize: 15 }),
      })
    );
    expect(roleApi.getDataList).toHaveBeenCalledTimes(1);
    expect(unitApi.getDataList).toHaveBeenCalledTimes(1);

    const table = wrapper.find(".el-table");
    expect(table.text()).toContain("张三");
    expect(table.text()).toContain("zhangsan");
    expect(table.text()).toContain("普通用户");
    expect(table.text()).toContain("研发部");
    expect(table.text()).toContain("2026-10-01 10:00:00");

    const rows = wrapper.findAll(".el-table__row");
    expect(rows).toHaveLength(2);
    // 超管行不提供删除：其余操作按钮保留
    expect(rows[1].text()).toContain("编辑");
    expect(rows[1].text()).toContain("重置密码");
    expect(rows[1].text()).not.toContain("删除");
    expect(rows[0].text()).toContain("删除");
    expect(wrapper.find(".el-pagination").exists()).toBe(true);
  });

  it("搜索重置到第一页并带上筛选条件", async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('input[placeholder="请输入用户名"]').setValue(" 张 ");
    const searchButton = wrapper
      .findAll("button")
      .find((button) => button.text().includes("搜索用户"));
    await searchButton!.trigger("click");
    await flushPromises();

    expect(userApi.getUserList).toHaveBeenCalledTimes(2);
    expect(userApi.getUserList).toHaveBeenLastCalledWith(
      expect.objectContaining({
        bean: expect.objectContaining({ uname: " 张 " }),
        pageRequest: expect.objectContaining({ pageNum: 1 }),
      })
    );
  });

  it("非管理员不显示新建按钮与操作列，部门管理员可见", async () => {
    asAdmin("2");
    const plainWrapper = mountView();
    await flushPromises();
    expect(plainWrapper.text()).not.toContain("新建用户");
    expect(plainWrapper.find(".el-table__row").text()).not.toContain("编辑");

    mockCookieGet((name) => (name === "admin_access" ? "1" : "2"));
    const unitAdminWrapper = mountView();
    await flushPromises();
    expect(unitAdminWrapper.text()).toContain("新建用户");
    expect(unitAdminWrapper.find(".el-table__row").text()).toContain("编辑");
  });

  it("新建用户：必填校验拦截、角色下拉排除超管、提交随机初始密码", async () => {
    const successSpy = createMessageSpies().success;
    const wrapper = mountView();
    await flushPromises();

    const addButton = wrapper
      .findAll("button")
      .find((button) => button.text().includes("新建用户"));
    await addButton!.trigger("click");
    await nextTick();

    const dialog = wrapper.find(".el-dialog");
    expect(dialog.text()).toContain("新建用户");
    expect(dialog.text()).toContain("账号");

    // 必填项为空：校验失败不调用创建接口
    const saveButton = dialog
      .findAll(".el-dialog__footer button")
      .find((button) => button.text().includes("确 定"));
    await saveButton!.trigger("click");
    await flushPromises();
    expect(userApi.create).not.toHaveBeenCalled();

    await dialog.find('input[placeholder="请输入用户名"]').setValue("李四");
    await dialog.find('input[placeholder="请输入账号"]').setValue("lisi");

    // 角色下拉不提供超管选项：v-show 隐藏，仍留在 DOM 中但不可见
    await dialog.find(".el-select__wrapper").trigger("click");
    await flushPromises();
    const optionItems = Array.from(
      document.body.querySelectorAll<HTMLElement>(".el-select-dropdown__item")
    );
    expect(optionItems.some((item) => item.textContent?.includes("普通用户"))).toBe(true);
    const adminOption = optionItems.find((item) =>
      item.textContent?.includes("超级管理员")
    );
    expect(adminOption?.style.display).toBe("none");
    await pickOption("普通用户");

    // 部门树选择研发部
    await dialog.findAll(".el-select__wrapper")[1].trigger("click");
    await flushPromises();
    await pickTreeNode("研发部");

    await saveButton!.trigger("click");
    await flushPromises();

    expect(userApi.create).toHaveBeenCalledTimes(1);
    const payload = vi.mocked(userApi.create).mock.calls[0][0] as {
      bean: Record<string, unknown>;
    };
    expect(payload.bean).toMatchObject({
      uname: "李四",
      uaccess: "lisi",
      rid: 2,
      gid: "1001",
    });
    expect(String(payload.bean.upwd)).toHaveLength(8);
    expect(successSpy).toHaveBeenCalledWith(
      expect.stringContaining("用户创建成功，初始密码：")
    );
    expect(successSpy).toHaveBeenCalledWith(
      expect.stringContaining(String(payload.bean.upwd))
    );
    expect(userApi.getUserList).toHaveBeenCalledTimes(2);
  });

  it("编辑用户：回填表单、账号不可改、提交时剔除 uid/uaccess/upwd", async () => {
    const successSpy = createMessageSpies().success;
    const wrapper = mountView();
    await flushPromises();

    const editButton = wrapper
      .find(".el-table__row")
      .findAll("button")
      .find((button) => button.text().includes("编辑"));
    await editButton!.trigger("click");
    await nextTick();

    const dialog = wrapper.find(".el-dialog");
    expect(dialog.text()).toContain("编辑用户");
    expect(dialog.text()).not.toContain("账号");
    const nameInput = dialog.find('input[placeholder="请输入用户名"]');
    expect((nameInput.element as HTMLInputElement).value).toBe("张三");

    await nameInput.setValue("张三丰");
    const saveButton = dialog
      .findAll(".el-dialog__footer button")
      .find((button) => button.text().includes("确 定"));
    await saveButton!.trigger("click");
    await flushPromises();

    expect(userApi.update).toHaveBeenCalledTimes(1);
    const payload = vi.mocked(userApi.update).mock.calls[0][0] as {
      bean: Record<string, unknown>;
      where: Record<string, unknown>;
    };
    expect(payload.where).toEqual({ uid: "u1" });
    expect(payload.bean).toMatchObject({ uname: "张三丰", rid: 2, gid: "1001" });
    expect(payload.bean).not.toHaveProperty("uid");
    expect(payload.bean).not.toHaveProperty("uaccess");
    expect(payload.bean).not.toHaveProperty("upwd");
    expect(successSpy).toHaveBeenCalledWith("保存成功");
    expect(userApi.getUserList).toHaveBeenCalledTimes(2);
  });

  it("重置密码：确认后更新并展示新密码，取消不调用接口", async () => {
    const successSpy = createMessageSpies().success;
    const confirmSpy = vi
      .spyOn(ElMessageBox, "confirm")
      .mockResolvedValue("confirm" as never);
    const wrapper = mountView();
    await flushPromises();

    const row = wrapper.find(".el-table__row");
    const resetButton = row
      .findAll("button")
      .find((button) => button.text().includes("重置密码"));
    await resetButton!.trigger("click");
    await flushPromises();

    expect(confirmSpy).toHaveBeenCalled();
    expect(userApi.update).toHaveBeenCalledTimes(1);
    const payload = vi.mocked(userApi.update).mock.calls[0][0] as {
      bean: { upwd: string };
      where: Record<string, unknown>;
    };
    expect(payload.where).toEqual({ uid: "u1" });
    expect(payload.bean.upwd).toHaveLength(8);
    expect(successSpy).toHaveBeenCalledWith(
      `密码已重置，新密码：${payload.bean.upwd}`
    );

    confirmSpy.mockRejectedValue("cancel");
    await resetButton!.trigger("click");
    await flushPromises();
    expect(userApi.update).toHaveBeenCalledTimes(1);
  });

  it("删除用户：确认后删除并刷新列表，取消不调用接口", async () => {
    const successSpy = createMessageSpies().success;
    const confirmSpy = vi
      .spyOn(ElMessageBox, "confirm")
      .mockResolvedValue("confirm" as never);
    const wrapper = mountView();
    await flushPromises();

    const deleteButton = wrapper
      .find(".el-table__row")
      .findAll("button")
      .find((button) => button.text().includes("删除"));
    await deleteButton!.trigger("click");
    await flushPromises();

    expect(userApi.delete).toHaveBeenCalledWith({ bean: { uid: "u1" } });
    expect(successSpy).toHaveBeenCalledWith("操作成功");
    expect(userApi.getUserList).toHaveBeenCalledTimes(2);

    confirmSpy.mockRejectedValue("cancel");
    await deleteButton!.trigger("click");
    await flushPromises();
    expect(userApi.delete).toHaveBeenCalledTimes(1);
  });

  it("接口返回失败时给出错误提示", async () => {
    vi.mocked(userApi.getUserList).mockResolvedValue(fail("db down"));
    const errorSpy = createMessageSpies().error;
    mountView();
    await flushPromises();
    expect(errorSpy).toHaveBeenCalledWith("获取用户列表失败");
  });
});
