import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  enableAutoUnmount,
  flushPromises,
  mount,
  type DOMWrapper,
} from "@vue/test-utils";
import { nextTick } from "vue";
import { ElLoading, ElMessage, ElMessageBox } from "element-plus";
import Cookies from "js-cookie";

vi.mock("js-cookie", () => ({ default: { get: vi.fn() } }));
vi.mock("@/api/dataset", () => ({
  datasetApi: {
    getDatasets: vi.fn(),
    createDataset: vi.fn(),
    updateDataset: vi.fn(),
    deleteDataset: vi.fn(),
  },
}));
vi.mock("@/api/knowledgeBaseMng", () => ({
  knowledgeBaseMngApi: {
    query: vi.fn(),
    queryAll: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    filterVisible: vi.fn(),
  },
}));
vi.mock("@/api/user", () => ({ userApi: { getUserList: vi.fn() } }));
vi.mock("@/api/unit", () => ({ unitApi: { getDataList: vi.fn() } }));
vi.mock("@/api/role", () => ({ roleApi: { getDataList: vi.fn() } }));

import KnowledgeBaseMngView from "../index.vue";
import { datasetApi } from "@/api/dataset";
import { knowledgeBaseMngApi } from "@/api/knowledgeBaseMng";
import { userApi } from "@/api/user";
import { unitApi } from "@/api/unit";
import { roleApi } from "@/api/role";

/**
 * 知识库管理后台：全部/本系统两种视图、可见性过滤、新建/编辑/删除，
 * 以及确认框「取消」「ESC 关闭」不误报错误。API 全部 mock。
 */

enableAutoUnmount(afterEach);

const ok = (datas: unknown) => ({ data: { resp_code: 0, resp_msg: "ok", datas } } as never);

const difyKbs = {
  data: [
    {
      id: "kb-1",
      name: "产品知识库",
      description: "产品文档",
      document_count: 3,
      updateTime: "2026-10-01",
    },
    {
      id: "kb-2",
      name: "研发知识库",
      description: "内部资料",
      document_count: 1,
      updateTime: "2026-10-02",
    },
    {
      id: "kb-3",
      name: "秘密库",
      description: "不可见",
      document_count: 0,
      updateTime: "2026-10-03",
    },
  ],
  has_more: false,
  limit: 100,
  total: 3,
  page: 1,
};

const permissionRecords = [
  {
    iid: 1,
    kbId: "kb-1",
    name: "产品知识库",
    ownerUid: "u1",
    enable: "1",
    permissionType: "public",
    unitsnos: "[]",
    roleiids: "[]",
  },
  {
    iid: 2,
    kbId: "kb-2",
    name: "研发知识库",
    ownerUid: "u2",
    enable: "1",
    permissionType: "only_me",
    unitsnos: "[]",
    roleiids: "[]",
  },
];

const localRecords = [
  {
    kbId: "kb-1",
    name: "本地别名",
    ownerUid: "u1",
    permissionType: "partial_invisible",
    unitsnos: "[]",
    roleiids: "[]",
    enable: "0",
  },
];

const users = {
  data: {
    resp_code: 0,
    datas: {
      content: [
        { uid: "u1", uaccess: "alice", uname: "爱丽丝" },
        { uid: "u2", uaccess: "bob", uname: "鲍勃" },
      ],
    },
  },
} as never;

const depts = ok([
  {
    unitsno: "1001",
    unitname: "研发部",
    childList: [{ unitsno: "1002", unitname: "前端组", childList: [] }],
  },
]);

const roles = ok({
  content: [
    { iid: 2, name: "运营" },
    { iid: 99999, name: "超级管理员" },
  ],
  totalSize: 2,
});

const mountView = () => mount(KnowledgeBaseMngView);

const setupSuccess = () => {
  vi.mocked(datasetApi.getDatasets).mockResolvedValue(difyKbs as never);
  vi.mocked(knowledgeBaseMngApi.query).mockResolvedValue(ok(permissionRecords));
  vi.mocked(knowledgeBaseMngApi.filterVisible).mockResolvedValue(ok(["kb-1", "kb-2"]));
  vi.mocked(knowledgeBaseMngApi.queryAll).mockResolvedValue(ok(localRecords));
  vi.mocked(userApi.getUserList).mockResolvedValue(users);
  vi.mocked(unitApi.getDataList).mockResolvedValue(depts);
  vi.mocked(roleApi.getDataList).mockResolvedValue(roles);
  vi.mocked(datasetApi.createDataset).mockResolvedValue({
    id: "kb-new",
    name: "新知识库",
    description: "",
  } as never);
  vi.mocked(datasetApi.updateDataset).mockResolvedValue({} as never);
  vi.mocked(datasetApi.deleteDataset).mockResolvedValue(undefined as never);
  vi.mocked(knowledgeBaseMngApi.create).mockResolvedValue(ok(true));
  vi.mocked(knowledgeBaseMngApi.update).mockResolvedValue(ok(true));
  vi.mocked(knowledgeBaseMngApi.delete).mockResolvedValue(ok(true));
};

const createMessageSpies = () => ({
  success: vi.spyOn(ElMessage, "success").mockReturnValue({ close: () => {} } as never),
  error: vi.spyOn(ElMessage, "error").mockReturnValue({ close: () => {} } as never),
  warning: vi.spyOn(ElMessage, "warning").mockReturnValue({ close: () => {} } as never),
});

const dialogByTitle = (wrapper: ReturnType<typeof mountView>, title: string) => {
  const dialog = wrapper
    .findAll(".el-dialog")
    .find((item) => item.text().includes(title));
  expect(dialog, `dialog ${title}`).toBeTruthy();
  return dialog!;
};

const clickFooterButton = async (
  dialog: ReturnType<typeof dialogByTitle>,
  text: string
) => {
  const button = dialog
    .findAll(".el-dialog__footer button")
    .find((item) => item.text().includes(text));
  expect(button, `button ${text}`).toBeTruthy();
  await button!.trigger("click");
  await flushPromises();
};

type ButtonScope = { findAll: (selector: string) => DOMWrapper<Element>[] };

const clickButtonByText = async (scope: ButtonScope, text: string) => {
  const button = scope
    .findAll("button")
    .find((item) => item.text().includes(text));
  expect(button, `button ${text}`).toBeTruthy();
  await button!.trigger("click");
  await flushPromises();
  await nextTick();
};

const pickRadio = async (
  dialog: ReturnType<typeof dialogByTitle>,
  text: string
) => {
  const radio = dialog.findAll(".el-radio").find((item) => item.text().includes(text));
  expect(radio, `radio ${text}`).toBeTruthy();
  await radio!.find("input").setValue(true);
  await nextTick();
};

const checkTreeDept = async (
  dialog: ReturnType<typeof dialogByTitle>,
  text: string
) => {
  const node = dialog
    .findAll(".el-tree-node__content")
    .find((item) => item.text().includes(text));
  expect(node, `tree ${text}`).toBeTruthy();
  const input = node!.find("input.el-checkbox__original");
  (input.element as HTMLInputElement).checked = true;
  await input.trigger("change");
  await nextTick();
};

const checkRole = async (
  dialog: ReturnType<typeof dialogByTitle>,
  text: string
) => {
  const box = dialog.findAll(".el-checkbox").find((item) => item.text() === text);
  expect(box, `role ${text}`).toBeTruthy();
  const input = box!.find("input.el-checkbox__original");
  (input.element as HTMLInputElement).checked = true;
  await input.trigger("change");
  await nextTick();
};

const switchView = async (
  wrapper: ReturnType<typeof mountView>,
  text: string
) => {
  const button = wrapper
    .findAll(".el-radio-button")
    .find((item) => item.text().includes(text));
  expect(button, `view ${text}`).toBeTruthy();
  await button!.find("input").setValue(true);
  await flushPromises();
};

beforeEach(() => {
  vi.mocked(Cookies.get).mockImplementation(((name: string) => {
    if (name === "uid") return "u1";
    if (name === "rid") return "2";
    return undefined;
  }) as never);
  setupSuccess();
  vi.spyOn(ElLoading, "service").mockReturnValue({ close: vi.fn() } as never);
});

describe("KnowledgeBaseMngView", () => {
  it("全部视图：按可见性过滤 Dify 知识库并映射所有者与权限", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(userApi.getUserList).toHaveBeenCalledTimes(1);
    expect(datasetApi.getDatasets).toHaveBeenCalledWith(1, 100);
    expect(knowledgeBaseMngApi.filterVisible).toHaveBeenCalledWith({
      data: { context: "manage", kbIds: ["kb-1", "kb-2", "kb-3"] },
    });

    const rows = wrapper.findAll(".el-table__row");
    expect(rows).toHaveLength(2);
    expect(rows[0].text()).toContain("产品知识库");
    expect(rows[0].text()).toContain("alice");
    expect(rows[0].text()).toContain("所有人可见");
    expect(rows[0].text()).toContain("3");
    expect(rows[1].text()).toContain("研发知识库");
    expect(rows[1].text()).toContain("bob");
    expect(rows[1].text()).toContain("仅个人可见");
    expect(wrapper.text()).not.toContain("秘密库");
  });

  it("搜索按名称/描述过滤并回到第一页", async () => {
    const wrapper = mountView();
    await flushPromises();

    const search = wrapper.find('input[placeholder="搜索知识库..."]');
    await search.setValue("研发");
    await nextTick();
    expect(wrapper.findAll(".el-table__row")).toHaveLength(1);
    expect(wrapper.find(".el-table").text()).toContain("研发知识库");

    await search.setValue("不存在");
    await nextTick();
    expect(wrapper.findAll(".el-table__row")).toHaveLength(0);

    await search.setValue("");
    await nextTick();
    expect(wrapper.findAll(".el-table__row")).toHaveLength(2);
  });

  it("新建知识库：必填校验拦截，部分不可见时提交部门与角色", async () => {
    const successSpy = createMessageSpies().success;
    const wrapper = mountView();
    await flushPromises();

    await clickButtonByText(wrapper, "新建知识库");
    const dialog = dialogByTitle(wrapper, "新建知识库");

    // 名称为空：表单校验拦截
    await clickFooterButton(dialog, "确定");
    expect(datasetApi.createDataset).not.toHaveBeenCalled();
    expect(dialog.findAll(".el-form-item.is-error").length).toBeGreaterThan(0);

    await dialog.find('input[placeholder="请输入知识库名称"]').setValue("新知识库");
    await dialog
      .find('textarea[placeholder="请输入知识库描述"]')
      .setValue("描述A");
    await pickRadio(dialog, "部分不可见");
    await checkTreeDept(dialog, "研发部");
    await checkRole(dialog, "运营");
    expect(dialog.text()).not.toContain("超级管理员");

    await clickFooterButton(dialog, "确定");

    expect(datasetApi.createDataset).toHaveBeenCalledWith({
      name: "新知识库",
      description: "描述A",
      indexing_technique: "high_quality",
      permission: "only_me",
    });
    const createPayload = vi.mocked(knowledgeBaseMngApi.create).mock.calls[0][0] as {
      data: Record<string, unknown>;
    };
    expect(createPayload.data).toMatchObject({
      kbId: "kb-new",
      name: "新知识库",
      ownerUid: "u1",
      enable: "1",
      permissionType: "partial_invisible",
      roleiids: JSON.stringify([2]),
    });
    // 勾选父部门会连带子部门（Element Plus 默认级联）
    expect(JSON.parse(String(createPayload.data.unitsnos)).sort()).toEqual([
      "1001",
      "1002",
    ]);
    expect(successSpy).toHaveBeenCalledWith("知识库创建成功");
    expect(datasetApi.getDatasets).toHaveBeenCalledTimes(2);
  });

  it("编辑知识库：有权限记录走 update，无记录走 create 补齐", async () => {
    const successSpy = createMessageSpies().success;
    // 只有 kb-1 有权限记录，kb-2 用来覆盖「编辑时补建记录」分支
    vi.mocked(knowledgeBaseMngApi.query).mockResolvedValue(
      ok([permissionRecords[0]])
    );
    const wrapper = mountView();
    await flushPromises();

    // 第一条：已有权限记录 → update
    await clickButtonByText(wrapper.findAll(".el-table__row")[0], "编辑");
    const editDialog = dialogByTitle(wrapper, "编辑知识库");
    expect(
      (editDialog.find('input[placeholder="请输入知识库名称"]').element as HTMLInputElement)
        .value
    ).toBe("产品知识库");
    await editDialog
      .find('input[placeholder="请输入知识库名称"]')
      .setValue("产品知识库V2");
    await clickFooterButton(editDialog, "确定");

    expect(datasetApi.updateDataset).toHaveBeenCalledWith("kb-1", {
      name: "产品知识库V2",
      description: "产品文档",
      indexing_technique: "high_quality",
      permission: "only_me",
    });
    expect(knowledgeBaseMngApi.update).toHaveBeenCalledWith({
      data: {
        name: "产品知识库V2",
        ownerUid: "u1",
        permissionType: "public",
        unitsnos: "[]",
        roleiids: "[]",
      },
      where: { kbId: "kb-1" },
    });
    expect(successSpy).toHaveBeenCalledWith("知识库更新成功");

    // 第二条：没有权限记录 → 编辑时补建记录
    await clickButtonByText(wrapper.findAll(".el-table__row")[1], "编辑");
    const secondDialog = dialogByTitle(wrapper, "编辑知识库");
    await clickFooterButton(secondDialog, "确定");

    expect(knowledgeBaseMngApi.create).toHaveBeenCalledWith({
      data: expect.objectContaining({
        kbId: "kb-2",
        name: "研发知识库",
        enable: "1",
        permissionType: "public",
      }),
    });
  });

  it("切换到本系统视图：展示别名/状态，再切回重新拉取 Dify 列表", async () => {
    const wrapper = mountView();
    await flushPromises();

    await switchView(wrapper, "仅显示本系统");
    expect(knowledgeBaseMngApi.queryAll).toHaveBeenCalledTimes(1);
    const rows = wrapper.findAll(".el-table__row");
    expect(rows).toHaveLength(1);
    expect(rows[0].text()).toContain("本地别名");
    expect(rows[0].text()).toContain("kb-1");
    expect(rows[0].text()).toContain("alice");
    expect(rows[0].text()).toContain("部分不可见");
    expect(rows[0].text()).toContain("已弃用");

    await switchView(wrapper, "全部");
    expect(datasetApi.getDatasets).toHaveBeenCalledTimes(2);
  });

  it("本系统视图：编辑本地记录与新建关联", async () => {
    const spies = createMessageSpies();
    const wrapper = mountView();
    await flushPromises();
    await switchView(wrapper, "仅显示本系统");

    // 编辑本地记录
    await clickButtonByText(wrapper.findAll(".el-table__row")[0], "编辑");
    const editDialog = dialogByTitle(wrapper, "编辑本地知识库");
    expect(
      (editDialog.find('input[placeholder="请输入名称（本系统别名）"]').element as HTMLInputElement)
        .value
    ).toBe("本地别名");
    await editDialog
      .find('input[placeholder="请输入名称（本系统别名）"]')
      .setValue("本地别名2");
    await clickFooterButton(editDialog, "确定");

    expect(knowledgeBaseMngApi.update).toHaveBeenCalledWith({
      data: {
        name: "本地别名2",
        ownerUid: "u1",
        permissionType: "partial_invisible",
        unitsnos: "[]",
        roleiids: "[]",
        enable: "0",
      },
      where: { kbId: "kb-1" },
    });
    expect(spies.success).toHaveBeenCalledWith("更新成功");
    expect(knowledgeBaseMngApi.queryAll).toHaveBeenCalledTimes(2);

    // 新建关联：空值拦截
    await clickButtonByText(wrapper, "新建关联");
    const associateDialog = dialogByTitle(wrapper, "新建关联");
    await clickFooterButton(associateDialog, "确定");
    expect(spies.warning).toHaveBeenCalledWith("请填写Dify知识库ID和名称");
    expect(knowledgeBaseMngApi.create).not.toHaveBeenCalled();

    await associateDialog
      .find('input[placeholder="输入Dify平台已有的知识库ID"]')
      .setValue("kb-9");
    await associateDialog
      .find('input[placeholder="请输入本系统别名"]')
      .setValue("关联库");
    await clickFooterButton(associateDialog, "确定");

    expect(knowledgeBaseMngApi.create).toHaveBeenCalledWith({
      data: {
        kbId: "kb-9",
        name: "关联库",
        ownerUid: "u1",
        permissionType: "public",
        unitsnos: "[]",
        roleiids: "[]",
      },
    });
    expect(spies.success).toHaveBeenCalledWith("关联成功");
    expect(knowledgeBaseMngApi.queryAll).toHaveBeenCalledTimes(3);
  });

  it("删除知识库：取消与 ESC 关闭都不算失败，接口异常才提示", async () => {
    const spies = createMessageSpies();
    const confirmSpy = vi
      .spyOn(ElMessageBox, "confirm")
      .mockResolvedValue("confirm" as never);
    const wrapper = mountView();
    await flushPromises();

    const deleteButton = () =>
      wrapper
        .findAll(".el-table__row")[0]
        .findAll("button")
        .find((item) => item.text().includes("删除"))!;

    await deleteButton().trigger("click");
    await flushPromises();
    expect(datasetApi.deleteDataset).toHaveBeenCalledWith("kb-1");
    expect(knowledgeBaseMngApi.delete).toHaveBeenCalledWith({ data: { kbId: "kb-1" } });
    expect(spies.success).toHaveBeenCalledWith("知识库删除成功");
    expect(datasetApi.getDatasets).toHaveBeenCalledTimes(2);

    confirmSpy.mockRejectedValueOnce("cancel");
    await deleteButton().trigger("click");
    await flushPromises();
    expect(datasetApi.deleteDataset).toHaveBeenCalledTimes(1);

    confirmSpy.mockRejectedValueOnce("close");
    await deleteButton().trigger("click");
    await flushPromises();
    expect(datasetApi.deleteDataset).toHaveBeenCalledTimes(1);
    expect(spies.error).not.toHaveBeenCalled();

    confirmSpy.mockResolvedValueOnce("confirm" as never);
    vi.mocked(datasetApi.deleteDataset).mockRejectedValueOnce(new Error("dify down"));
    await deleteButton().trigger("click");
    await flushPromises();
    expect(spies.error).toHaveBeenCalledWith("删除失败，请重试");
  });

  it("列表接口异常时提示获取失败", async () => {
    const errorSpy = createMessageSpies().error;
    vi.mocked(datasetApi.getDatasets).mockRejectedValueOnce(new Error("network"));
    mountView();
    await flushPromises();
    expect(errorSpy).toHaveBeenCalledWith("获取知识库列表失败");
  });
});
