import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { ElLoading, ElMessage, ElMessageBox } from "element-plus";
import Cookies from "js-cookie";

vi.mock("js-cookie", () => ({ default: { get: vi.fn() } }));
vi.mock("@/api/role", () => ({
  roleApi: {
    getDataList: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
  },
}));

import RoleView from "../index.vue";
import { roleApi } from "@/api/role";

/**
 * 角色管理后台：角色列表与超管行保护、菜单权限树勾选、
 * 编辑回填（含损坏 viewjson 兜底）、删除确认。API 全部 mock。
 */

enableAutoUnmount(afterEach);

const ok = (datas: unknown, respMsg = "ok") => ({
  data: { resp_code: 0, resp_msg: respMsg, datas },
} as never);
const fail = (msg: string) => ({ data: { resp_code: 500, resp_msg: msg } } as never);

const roles = {
  content: [
    {
      iid: 2,
      name: "运营",
      describe: "运营角色",
      viewjson: '["chat","models"]',
      createtime: "2026-10-01 10:00:00",
    },
    {
      iid: 99999,
      name: "超级管理员",
      describe: "全部权限",
      viewjson: '["chat"]',
      createtime: "2026-09-01 09:00:00",
    },
  ],
  totalSize: 2,
};

const mountView = () =>
  mount(RoleView, {
    global: {
      stubs: { Search: true, CirclePlusFilled: true, Edit: true, Delete: true },
    },
  });

const asAdmin = (rid = "99999") =>
  vi.mocked(Cookies.get).mockImplementation(((name: string) =>
    name === "admin_rid" ? rid : undefined) as never);

const setupSuccess = () => {
  vi.mocked(roleApi.getDataList).mockResolvedValue(ok(roles));
  vi.mocked(roleApi.create).mockResolvedValue(ok(true));
  vi.mocked(roleApi.update).mockResolvedValue(ok(true));
  vi.mocked(roleApi.delete).mockResolvedValue(ok(true));
};

/** 每个用例只调用一次：对同一方法二次 spyOn 会丢弃第一次的引用 */
const createMessageSpies = () => ({
  success: vi.spyOn(ElMessage, "success").mockReturnValue({ close: () => {} } as never),
  error: vi.spyOn(ElMessage, "error").mockReturnValue({ close: () => {} } as never),
});

const dialogSaveButton = (wrapper: ReturnType<typeof mountView>) =>
  wrapper
    .find(".el-dialog")
    .findAll(".el-dialog__footer button")
    .find((button) => button.text().includes("确 定"))!;

/**
 * 按自身标题精确匹配树节点。
 * 不能用 node.text().includes(...)：父节点的文本包含全部子节点文案，
 * 查「模型仓库」会先命中外层「资源仓库」节点。
 */
const menuNode = (wrapper: ReturnType<typeof mountView>, text: string) =>
  wrapper
    .find(".el-dialog")
    .findAll(".el-tree-node")
    .find((item) => item.find(".el-tree-node__label").text() === text);

/** 勾选菜单权限树里指定文案的节点 */
const checkMenuNode = async (
  wrapper: ReturnType<typeof mountView>,
  text: string
) => {
  const node = menuNode(wrapper, text);
  expect(node, `menu node ${text}`).toBeTruthy();
  // jsdom 的 click 不会触发 checkbox 激活行为，手动置位后派发 change
  const input = node!.find("input.el-checkbox__original");
  (input.element as HTMLInputElement).checked = true;
  await input.trigger("change");
  await nextTick();
  return node!;
};

beforeEach(() => {
  asAdmin();
  setupSuccess();
  vi.spyOn(ElLoading, "service").mockReturnValue({ close: vi.fn() } as never);
});

describe("RoleView", () => {
  it("挂载后加载角色列表，超管行不显示删除", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(roleApi.getDataList).toHaveBeenCalledTimes(1);
    expect(roleApi.getDataList).toHaveBeenCalledWith(
      expect.objectContaining({
        bean: { name: "" },
        pageRequest: expect.objectContaining({ pageNum: 1, pageSize: 15 }),
      })
    );

    const table = wrapper.find(".el-table");
    expect(table.text()).toContain("运营");
    expect(table.text()).toContain("2026-10-01 10:00:00");

    const rows = wrapper.findAll(".el-table__row");
    expect(rows).toHaveLength(2);
    expect(rows[0].text()).toContain("删除");
    expect(rows[1].text()).toContain("编辑");
    expect(rows[1].text()).not.toContain("删除");
    expect(wrapper.find(".el-pagination").exists()).toBe(true);
  });

  it("搜索重置到第一页并带上名称条件；非管理员看不到新建与操作列", async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('input[placeholder="请输入名称"]').setValue("运营");
    const searchButton = wrapper
      .findAll("button")
      .find((button) => button.text().includes("搜索"));
    await searchButton!.trigger("click");
    await flushPromises();
    expect(roleApi.getDataList).toHaveBeenLastCalledWith(
      expect.objectContaining({
        bean: { name: "运营" },
        pageRequest: expect.objectContaining({ pageNum: 1 }),
      })
    );

    asAdmin("2");
    const plainWrapper = mountView();
    await flushPromises();
    expect(plainWrapper.text()).not.toContain("新建");
    expect(plainWrapper.find(".el-table__row").text()).not.toContain("编辑");
  });

  it("新建角色：未勾选菜单被拦截，勾选后把 key 列表序列化提交", async () => {
    const successSpy = createMessageSpies().success;
    const wrapper = mountView();
    await flushPromises();

    const addButton = wrapper
      .findAll("button")
      .find((button) => button.text().includes("新建"));
    await addButton!.trigger("click");
    await nextTick();
    await nextTick();

    const dialog = wrapper.find(".el-dialog");
    expect(dialog.text()).toContain("新建角色");
    await dialog.find('input[placeholder="请输入角色名称"]').setValue("测试角色");

    // 只填名称、没勾菜单：不提交
    await dialogSaveButton(wrapper).trigger("click");
    await flushPromises();
    expect(roleApi.create).not.toHaveBeenCalled();

    await checkMenuNode(wrapper, "问答交互引擎");
    expect(
      menuNode(wrapper, "问答交互引擎")?.find(".el-checkbox").classes()
    ).toContain("is-checked");

    await dialogSaveButton(wrapper).trigger("click");
    await flushPromises();

    expect(roleApi.create).toHaveBeenCalledTimes(1);
    const payload = vi.mocked(roleApi.create).mock.calls[0][0] as {
      bean: Record<string, unknown>;
    };
    expect(payload.bean.name).toBe("测试角色");
    expect(JSON.parse(String(payload.bean.viewjson))).toEqual(["chat"]);
    expect(successSpy).toHaveBeenCalledWith("保存成功");
    expect(roleApi.getDataList).toHaveBeenCalledTimes(2);
  });

  it("编辑角色：回填名称与已勾选菜单，提交时剔除 iid", async () => {
    const successSpy = createMessageSpies().success;
    const wrapper = mountView();
    await flushPromises();

    const editButton = wrapper
      .find(".el-table__row")
      .findAll("button")
      .find((button) => button.text().includes("编辑"));
    await editButton!.trigger("click");
    await nextTick();
    await nextTick();

    const dialog = wrapper.find(".el-dialog");
    expect(dialog.text()).toContain("编辑角色");
    const nameInput = dialog.find('input[placeholder="请输入角色名称"]');
    expect((nameInput.element as HTMLInputElement).value).toBe("运营");
    expect(
      menuNode(wrapper, "问答交互引擎")?.find(".el-checkbox").classes()
    ).toContain("is-checked");
    expect(
      menuNode(wrapper, "模型仓库")?.find(".el-checkbox").classes()
    ).toContain("is-checked");

    await nameInput.setValue("运营二组");
    await dialogSaveButton(wrapper).trigger("click");
    await flushPromises();

    expect(roleApi.update).toHaveBeenCalledTimes(1);
    const payload = vi.mocked(roleApi.update).mock.calls[0][0] as {
      data: Record<string, unknown>;
      where: Record<string, unknown>;
    };
    expect(payload.where).toEqual({ iid: 2 });
    expect(payload.data.name).toBe("运营二组");
    expect(payload.data).not.toHaveProperty("iid");
    expect(JSON.parse(String(payload.data.viewjson)).sort()).toEqual([
      "chat",
      "models",
    ]);
    expect(successSpy).toHaveBeenCalledWith("保存成功");
  });

  it("损坏的 viewjson 不会阻断编辑弹窗", async () => {
    vi.mocked(roleApi.getDataList).mockResolvedValue(
      ok({
        content: [
          {
            iid: 3,
            name: "坏数据角色",
            describe: "",
            viewjson: "{not-json",
            createtime: "2026-10-01 10:00:00",
          },
        ],
        totalSize: 1,
      })
    );
    const wrapper = mountView();
    await flushPromises();

    const editButton = wrapper
      .find(".el-table__row")
      .findAll("button")
      .find((button) => button.text().includes("编辑"));
    await editButton!.trigger("click");
    await nextTick();
    await nextTick();

    expect(wrapper.find(".el-dialog").text()).toContain("编辑角色");
    expect(
      menuNode(wrapper, "问答交互引擎")?.find(".el-checkbox").classes()
    ).not.toContain("is-checked");
  });

  it("删除角色：确认后删除并刷新，取消不调用接口也不报错", async () => {
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

    expect(roleApi.delete).toHaveBeenCalledWith({ bean: { iid: 2 } });
    expect(successSpy).toHaveBeenCalledWith("删除成功");
    expect(roleApi.getDataList).toHaveBeenCalledTimes(2);

    confirmSpy.mockRejectedValue("cancel");
    await deleteButton!.trigger("click");
    await flushPromises();
    expect(roleApi.delete).toHaveBeenCalledTimes(1);
  });

  it("接口失败时给出错误提示", async () => {
    vi.mocked(roleApi.getDataList).mockResolvedValue(fail("db down"));
    const errorSpy = createMessageSpies().error;
    mountView();
    await flushPromises();
    expect(errorSpy).toHaveBeenCalledWith("获取角色列表失败");
  });
});
