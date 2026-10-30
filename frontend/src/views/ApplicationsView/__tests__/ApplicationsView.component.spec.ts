import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { ElLoading, ElMessage, ElMessageBox } from "element-plus";

const mocks = vi.hoisted(() => ({ rid: "99999" }));

vi.mock("js-cookie", () => ({
  default: { get: vi.fn((key: string) => (key === "rid" ? mocks.rid : undefined)) },
}));
vi.mock("@/api/applicationsMng", () => ({
  applicationsMngApi: {
    query: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
  },
}));
vi.mock("@/api/tags", () => ({
  tagsApi: { query: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
}));
vi.mock("@/api/tagsbind", () => ({
  tagsBindApi: { query: vi.fn(), create: vi.fn(), delete: vi.fn() },
  default: { query: vi.fn(), create: vi.fn(), delete: vi.fn() },
}));
vi.mock("@/api/unit", () => ({ unitApi: { getDataList: vi.fn() } }));
vi.mock("@/api/role", () => ({ roleApi: { getDataList: vi.fn() } }));

import ApplicationsView from "../index.vue";
import { applicationsMngApi } from "@/api/applicationsMng";
import { tagsApi } from "@/api/tags";
import { tagsBindApi } from "@/api/tagsbind";
import { unitApi } from "@/api/unit";
import { roleApi } from "@/api/role";

/**
 * 场景应用：列表/搜索/标签筛选/排序、超管门控、新建编辑删除、
 * 管理权限弹窗（部门树 + 角色勾选）与安全跳转。
 */

enableAutoUnmount(afterEach);

const query = applicationsMngApi.query as Mock;
const create = applicationsMngApi.create as Mock;
const update = applicationsMngApi.update as Mock;
const remove = applicationsMngApi.delete as Mock;
const tagsQuery = tagsApi.query as Mock;
const bindQuery = tagsBindApi.query as Mock;
const getDeptTree = unitApi.getDataList as Mock;
const getRoleList = roleApi.getDataList as Mock;

const ok = (datas: unknown, resp_msg = "ok") => ({
  data: { resp_code: 0, resp_msg, datas },
});
const fail = (resp_msg: string) => ({ data: { resp_code: 500, resp_msg } });

const apps = [
  {
    iid: 11,
    title: "合同审查",
    detail: "自动审查合同条款",
    model: "gpt-4o",
    difyUrl: "https://dify.example.com/app/1",
    createtime: "2026-10-02 09:00:00",
    updatetime: "2026-10-20 09:00:00",
    unitsnos: '["D1"]',
    roleiids: "[2]",
  },
  {
    iid: 12,
    title: "智能客服",
    detail: "问答机器人",
    model: "deepseek",
    difyUrl: "",
    createtime: "2026-09-15 09:00:00",
    updatetime: "2026-10-25 09:00:00",
    unitsnos: "{bad json",
    roleiids: "",
  },
  {
    iid: 13,
    title: "数据分析",
    detail: "报表生成",
    model: "qwen",
    difyUrl: "javascript:alert(1)",
    createtime: "2026-08-01 09:00:00",
    updatetime: "2026-09-01 09:00:00",
  },
];

const tags = [
  { iid: 7, name: "办公" },
  { iid: 8, name: "客服" },
];

const bindings = [{ hash: "11", lableBeans: [{ iid: 7, name: "办公" }] }];

const deptTree = [
  {
    unitsno: "D1",
    unitname: "总部",
    childList: [{ unitsno: "D2", unitname: "研发部" }],
  },
  { unitsno: "D3", unitname: "分部" },
];

const roles = {
  content: [
    { iid: 1, name: "管理员" },
    { iid: 99999, name: "超管" },
    { iid: 2, name: "普通用户" },
  ],
};

const createMessageSpies = () => ({
  success: vi.spyOn(ElMessage, "success").mockReturnValue({ close: () => {} } as never),
  error: vi.spyOn(ElMessage, "error").mockReturnValue({ close: () => {} } as never),
  warning: vi.spyOn(ElMessage, "warning").mockReturnValue({ close: () => {} } as never),
});

const mountView = () => mount(ApplicationsView);

const cardTitles = (wrapper: ReturnType<typeof mountView>) =>
  wrapper.findAll(".card-title").map((item) => item.text());

const clickCardAction = async (
  wrapper: ReturnType<typeof mountView>,
  index: number,
  label: string
) => {
  const card = wrapper.findAll(".capability-card")[index];
  const button = card.findAll(".action-btn").find((item) => item.text().includes(label));
  expect(button, `card ${index} action ${label}`).toBeTruthy();
  await button!.trigger("click");
  await flushPromises();
};

const dialogByTitle = (wrapper: ReturnType<typeof mountView>, title: string) =>
  wrapper.findAll(".el-dialog").find((item) => item.text().includes(title));

const clickFooter = async (wrapper: ReturnType<typeof mountView>, title: string, text: string) => {
  const dialog = dialogByTitle(wrapper, title);
  expect(dialog, `dialog ${title}`).toBeTruthy();
  const button = dialog!
    .findAll(".el-dialog__footer button")
    .find((item) => item.text().includes(text));
  expect(button, `footer ${text}`).toBeTruthy();
  await button!.trigger("click");
  await flushPromises();
};

beforeEach(() => {
  mocks.rid = "99999";
  query.mockResolvedValue(ok([...apps]));
  tagsQuery.mockResolvedValue(ok(tags));
  bindQuery.mockResolvedValue(ok(bindings));
  getDeptTree.mockResolvedValue(ok(deptTree));
  getRoleList.mockResolvedValue(ok(roles));
});

describe("ApplicationsView", () => {
  it("首屏加载应用、标签、部门树与角色，并过滤超管角色", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(query).toHaveBeenCalledWith({ data: {} });
    expect(tagsQuery).toHaveBeenCalledWith({ data: { type: "applications" } });
    expect(bindQuery).toHaveBeenCalledWith({ data: { type: "applications" } });
    expect(getDeptTree).toHaveBeenCalledWith({ data: {} });
    expect(getRoleList).toHaveBeenCalledWith({
      bean: {},
      pageRequest: { pageSize: 1000, pageNum: 1, order: "iid", desc: "desc" },
    });

    expect(cardTitles(wrapper)).toEqual(["合同审查", "智能客服", "数据分析"]);
    expect(wrapper.findAll(".hero-stat strong")[0].text()).toBe("3");
    const cards = wrapper.findAll(".capability-card");
    expect(cards[0].text()).toContain("创建时间：2026-10-02 09:00:00");
    expect(cards[0].text()).toContain("办公");
    expect(cards[1].text()).not.toContain("办公");
  });

  it("搜索按名称、描述、模型匹配且忽略大小写", async () => {
    const wrapper = mountView();
    await flushPromises();

    const input = wrapper.find('input[placeholder="搜索应用..."]');
    await input.setValue("客服");
    expect(cardTitles(wrapper)).toEqual(["智能客服"]);

    await input.setValue("DeepSeek");
    expect(cardTitles(wrapper)).toEqual(["智能客服"]);

    await input.setValue("报表");
    expect(cardTitles(wrapper)).toEqual(["数据分析"]);

    await input.setValue("不存在");
    expect(cardTitles(wrapper)).toEqual([]);
  });

  it("排序与标签筛选：创建/更新时间升降序、按标签过滤", async () => {
    const wrapper = mountView();
    await flushPromises();

    (wrapper.vm as any).sortBy = "createtime";
    await flushPromises();
    expect(cardTitles(wrapper)).toEqual(["合同审查", "智能客服", "数据分析"]);

    (wrapper.vm as any).sortBy = "createtime_asc";
    await flushPromises();
    expect(cardTitles(wrapper)).toEqual(["数据分析", "智能客服", "合同审查"]);

    (wrapper.vm as any).sortBy = "updatetime";
    await flushPromises();
    expect(cardTitles(wrapper)).toEqual(["智能客服", "合同审查", "数据分析"]);

    (wrapper.vm as any).sortBy = "updatetime_asc";
    await flushPromises();
    expect(cardTitles(wrapper)).toEqual(["数据分析", "合同审查", "智能客服"]);

    (wrapper.vm as any).sortBy = "";
    (wrapper.vm as any).selectedCategory = ["7"];
    await flushPromises();
    expect(cardTitles(wrapper)).toEqual(["合同审查"]);
  });

  it("普通用户看不到新建/修改/删除/管理，只能查看与只读标签", async () => {
    mocks.rid = "2";
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).not.toContain("新建应用");
    expect(wrapper.find(".action-btn").exists()).toBe(true);
    expect(wrapper.findAll(".action-btn").map((item) => item.text())).toEqual([
      "查看",
      "查看",
      "查看",
    ]);
    expect(wrapper.findAll(".tag-dropdown")).toHaveLength(0);
    expect(wrapper.findAll(".ima-card-tag")[0].text()).toContain("办公");
  });

  it("新建应用：必填拦截，提交后关闭弹窗并刷新列表", async () => {
    const spies = createMessageSpies();
    create.mockResolvedValue(ok(true));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find(".btn-primary").trigger("click");
    await flushPromises();
    await clickFooter(wrapper, "新建应用", "确 定");
    expect(create).not.toHaveBeenCalled();
    expect(dialogByTitle(wrapper, "新建应用")!.find(".el-form-item.is-error").exists()).toBe(true);

    await wrapper.find('input[placeholder="请输入应用名称"]').setValue("新应用");
    await wrapper.find('textarea[placeholder="请输入应用描述"]').setValue("应用描述");
    await clickFooter(wrapper, "新建应用", "确 定");

    expect(create).toHaveBeenCalledWith({
      data: {
        title: "新应用",
        detail: "应用描述",
        difyUrl: "",
        updatedAt: expect.any(String),
      },
    });
    expect(spies.success).toHaveBeenCalledWith("保存成功");
    expect((wrapper.vm as any).state.params.dialogVisible).toBe(false);
    expect(query).toHaveBeenCalledTimes(2);
  });

  it("编辑应用：回填、按 iid 更新并提交改名", async () => {
    createMessageSpies();
    update.mockResolvedValue(ok(true));
    const wrapper = mountView();
    await flushPromises();

    await clickCardAction(wrapper, 0, "修改");
    const titleInput = wrapper.find('input[placeholder="请输入应用名称"]');
    expect((titleInput.element as HTMLInputElement).value).toBe("合同审查");
    await titleInput.setValue("合同审查 PRO");
    await clickFooter(wrapper, "编辑应用", "确 定");

    expect(update).toHaveBeenCalledWith({
      data: expect.objectContaining({ iid: 11, title: "合同审查 PRO" }),
      where: { iid: 11 },
    });
  });

  it("删除：确认后删除并刷新；取消不报错；错误码与异常都有提示", async () => {
    const spies = createMessageSpies();
    const close = vi.fn();
    vi.spyOn(ElLoading, "service").mockReturnValue({ close } as never);
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    remove.mockResolvedValue(ok(true));
    const wrapper = mountView();
    await flushPromises();

    await clickCardAction(wrapper, 0, "删除");
    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining("合同审查"),
      "确认删除",
      expect.objectContaining({ type: "warning" })
    );
    expect(remove).toHaveBeenCalledWith({ data: { iid: 11 } });
    expect(spies.success).toHaveBeenCalledWith("删除成功");
    expect(query).toHaveBeenCalledTimes(2);

    confirm.mockRejectedValueOnce("cancel");
    await clickCardAction(wrapper, 1, "删除");
    expect(remove).toHaveBeenCalledTimes(1);
    expect(spies.error).not.toHaveBeenCalled();

    confirm.mockResolvedValueOnce("confirm" as never);
    remove.mockResolvedValueOnce(fail("应用已被引用"));
    await clickCardAction(wrapper, 1, "删除");
    expect(spies.error).toHaveBeenCalledWith("应用已被引用");

    confirm.mockResolvedValueOnce("confirm" as never);
    remove.mockRejectedValueOnce(new Error("network"));
    await clickCardAction(wrapper, 1, "删除");
    expect(spies.error).toHaveBeenCalledWith("删除失败，请重试");
    expect(close).toHaveBeenCalledTimes(3);
  });

  it("保存失败：错误码与异常都提示，弹窗保留且 loading 关闭", async () => {
    const spies = createMessageSpies();
    const close = vi.fn();
    vi.spyOn(ElLoading, "service").mockReturnValue({ close } as never);
    create.mockResolvedValueOnce(fail("名称重复"));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find(".btn-primary").trigger("click");
    await flushPromises();
    await wrapper.find('input[placeholder="请输入应用名称"]').setValue("新应用");
    await wrapper.find('textarea[placeholder="请输入应用描述"]').setValue("描述");
    await clickFooter(wrapper, "新建应用", "确 定");

    expect(spies.error).toHaveBeenCalledWith("保存失败，请稍后重试");
    expect((wrapper.vm as any).state.params.dialogVisible).toBe(true);
    expect(close).toHaveBeenCalled();

    create.mockRejectedValueOnce(new Error("network"));
    await clickFooter(wrapper, "新建应用", "确 定");
    expect(spies.error).toHaveBeenCalledTimes(2);
    expect(close).toHaveBeenCalledTimes(2);
  });

  it("管理权限：回填部门/角色，超管角色不出现，保存按 iid 更新", async () => {
    const spies = createMessageSpies();
    update.mockResolvedValue(ok(true));
    const wrapper = mountView();
    await flushPromises();

    await clickCardAction(wrapper, 0, "管理");
    const mng = dialogByTitle(wrapper, "管理权限")!;
    expect(mng.exists()).toBe(true);
    expect((wrapper.vm as any).state.mngForm.selectedDepts).toEqual(["D1"]);
    expect((wrapper.vm as any).state.mngForm.selectedRoles).toEqual([2]);

    const roleLabels = wrapper.findAll(".mng-role-container .el-checkbox").map((item) => item.text());
    expect(roleLabels).toEqual(["管理员", "普通用户"]);
    expect(wrapper.findAll(".mng-tree-container .el-tree-node").length).toBeGreaterThan(0);

    await clickFooter(wrapper, "管理权限", "确 定");
    expect(update).toHaveBeenCalledWith({
      data: { unitsnos: ["D1"], roleiids: [2] },
      where: { iid: 11 },
    });
    expect(spies.success).toHaveBeenCalledWith("权限配置保存成功");
    expect((wrapper.vm as any).state.mngDialogVisible).toBe(false);
  });

  it("管理权限：损坏的 unitsnos/roleiids 兜底为空，树勾选可增删部门", async () => {
    createMessageSpies();
    const wrapper = mountView();
    await flushPromises();

    await clickCardAction(wrapper, 1, "管理");
    expect((wrapper.vm as any).state.mngForm.selectedDepts).toEqual([]);
    expect((wrapper.vm as any).state.mngForm.selectedRoles).toEqual([]);

    const nodes = wrapper.findAll(".mng-tree-container .el-tree-node__content");
    const branch = nodes.find((item) => item.text().includes("分部"));
    expect(branch).toBeTruthy();
    const checkbox = branch!.find('input[type="checkbox"]');
    await checkbox.setValue(true);
    expect((wrapper.vm as any).state.mngForm.selectedDepts).toEqual(["D3"]);

    await checkbox.setValue(false);
    expect((wrapper.vm as any).state.mngForm.selectedDepts).toEqual([]);
  });

  it("查看：仅放行安全地址，缺失或 javascript: 地址不打开新窗口", async () => {
    const open = vi.spyOn(window, "open").mockReturnValue(null as never);
    const wrapper = mountView();
    await flushPromises();

    await clickCardAction(wrapper, 0, "查看");
    expect(open).toHaveBeenCalledWith(
      "https://dify.example.com/app/1",
      "_blank",
      "noopener,noreferrer"
    );

    await clickCardAction(wrapper, 1, "查看");
    await clickCardAction(wrapper, 2, "查看");
    expect(open).toHaveBeenCalledTimes(1);
  });
});
