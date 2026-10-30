import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { ElLoading, ElMessage, ElMessageBox } from "element-plus";

const mocks = vi.hoisted(() => ({ rid: "99999" }));

vi.mock("js-cookie", () => ({
  default: { get: vi.fn((key: string) => (key === "rid" ? mocks.rid : undefined)) },
}));
vi.mock("@/api/capabilitiesMng", () => ({
  capabilitiesMngApi: {
    query: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    ssoJump: vi.fn(),
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

import CapabilitiesView from "../index.vue";
import { capabilitiesMngApi } from "@/api/capabilitiesMng";
import { tagsApi } from "@/api/tags";
import { tagsBindApi } from "@/api/tagsbind";
import { unitApi } from "@/api/unit";
import { roleApi } from "@/api/role";

/**
 * 能力广场：列表/搜索/排序/标签筛选、超管门控、新建编辑删除、
 * 图标上传（base64 与 2MB 限制）、SSO 跳转与管理权限弹窗。
 */

enableAutoUnmount(afterEach);

const query = capabilitiesMngApi.query as Mock;
const create = capabilitiesMngApi.create as Mock;
const update = capabilitiesMngApi.update as Mock;
const remove = capabilitiesMngApi.delete as Mock;
const ssoJump = capabilitiesMngApi.ssoJump as Mock;
const tagsQuery = tagsApi.query as Mock;
const bindQuery = tagsBindApi.query as Mock;
const getDeptTree = unitApi.getDataList as Mock;
const getRoleList = roleApi.getDataList as Mock;

const ok = (datas: unknown, resp_msg = "ok") => ({
  data: { resp_code: 0, resp_msg, datas },
});
const fail = (resp_msg: string) => ({ data: { resp_code: 500, resp_msg } });

const capabilities = [
  {
    iid: 21,
    title: "智能写作",
    description: "公文写作助手",
    label: "办公",
    difyUrl: "https://cap.example.com/write",
    icon: "https://cdn.example.com/icon.png",
    createtime: "2026-10-03 09:00:00",
    updatetime: "2026-10-21 09:00:00",
    unitsnos: '["D1"]',
    roleiids: "[2]",
    needSso: true,
  },
  {
    iid: 22,
    title: "图表生成",
    description: "自动生成图表",
    label: "数据",
    difyUrl: "javascript:alert(1)",
    icon: "not-an-image",
    createtime: "2026-09-16 09:00:00",
    updatetime: "2026-10-26 09:00:00",
    unitsnos: "{bad",
    roleiids: "",
  },
  {
    iid: 23,
    title: "语音转写",
    description: "会议录音转文字",
    label: "语音",
    difyUrl: "https://cap.example.com/asr",
    icon: "",
    createtime: "2026-08-02 09:00:00",
    updatetime: "2026-09-02 09:00:00",
  },
];

const tags = [
  { iid: 9, name: "办公" },
  { iid: 10, name: "数据" },
];

const bindings = [{ hash: "21", lableBeans: [{ iid: 9, name: "办公" }] }];

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

const mountView = () => mount(CapabilitiesView);

const cardTitles = (wrapper: ReturnType<typeof mountView>) =>
  wrapper.findAll(".cap-card-name").map((item) => item.text());

const clickCardAction = async (
  wrapper: ReturnType<typeof mountView>,
  index: number,
  label: string
) => {
  const card = wrapper.findAll(".cap-card")[index];
  const button = card.findAll(".action-btn").find((item) => item.text().includes(label));
  expect(button, `card ${index} action ${label}`).toBeTruthy();
  await button!.trigger("click");
  await flushPromises();
};

const clickCard = async (wrapper: ReturnType<typeof mountView>, index: number) => {
  await wrapper.findAll(".cap-card")[index].trigger("click");
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
  query.mockResolvedValue(ok([...capabilities]));
  tagsQuery.mockResolvedValue(ok(tags));
  bindQuery.mockResolvedValue(ok(bindings));
  getDeptTree.mockResolvedValue(ok(deptTree));
  getRoleList.mockResolvedValue(ok(roles));
  ssoJump.mockResolvedValue(ok({ hasSession: true, authUrl: "" }));
});

describe("CapabilitiesView", () => {
  it("首屏加载能力、标签、部门树与角色，封面按图标合法性回退", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(query).toHaveBeenCalledWith({ data: {} });
    expect(tagsQuery).toHaveBeenCalledWith({ data: { type: "capabilities" } });
    expect(bindQuery).toHaveBeenCalledWith({ data: { type: "capabilities" } });
    expect(getDeptTree).toHaveBeenCalledWith({ data: {} });
    expect(getRoleList).toHaveBeenCalledWith({
      bean: {},
      pageRequest: { pageSize: 1000, pageNum: 1, order: "iid", desc: "desc" },
    });

    expect(cardTitles(wrapper)).toEqual(["智能写作", "图表生成", "语音转写"]);
    expect(wrapper.findAll(".hero-stat strong")[0].text()).toBe("3");
    const cards = wrapper.findAll(".cap-card");
    expect(cards[0].text()).toContain("创建时间：2026-10-03 09:00:00");
    expect(cards[0].text()).toContain("办公");
    expect(cards[0].find("img").attributes("src")).toContain("icon.png");
    expect(cards[1].find("img").attributes("src")).toContain("ai_logo");
    expect(cards[2].find("img").attributes("src")).toContain("ai_logo");
  });

  it("搜索按名称、描述、标签匹配且忽略大小写", async () => {
    const wrapper = mountView();
    await flushPromises();

    const input = wrapper.find('input[placeholder="搜索能力..."]');
    await input.setValue("写作");
    expect(cardTitles(wrapper)).toEqual(["智能写作"]);

    await input.setValue("录音");
    expect(cardTitles(wrapper)).toEqual(["语音转写"]);

    await input.setValue("数据");
    expect(cardTitles(wrapper)).toEqual(["图表生成"]);

    await input.setValue("不存在");
    expect(wrapper.find(".el-empty").text()).toContain("没有匹配的能力");
  });

  it("排序与标签筛选：创建/更新时间升降序、按标签过滤", async () => {
    const wrapper = mountView();
    await flushPromises();

    (wrapper.vm as any).sortBy = "createtime";
    await flushPromises();
    expect(cardTitles(wrapper)).toEqual(["智能写作", "图表生成", "语音转写"]);

    (wrapper.vm as any).sortBy = "createtime_asc";
    await flushPromises();
    expect(cardTitles(wrapper)).toEqual(["语音转写", "图表生成", "智能写作"]);

    (wrapper.vm as any).sortBy = "updatetime";
    await flushPromises();
    expect(cardTitles(wrapper)).toEqual(["图表生成", "智能写作", "语音转写"]);

    (wrapper.vm as any).sortBy = "updatetime_asc";
    await flushPromises();
    expect(cardTitles(wrapper)).toEqual(["语音转写", "智能写作", "图表生成"]);

    (wrapper.vm as any).sortBy = "";
    (wrapper.vm as any).selectedCategory = ["9"];
    await flushPromises();
    expect(cardTitles(wrapper)).toEqual(["智能写作"]);
  });

  it("空列表提示添加能力，数据加载后展示卡片", async () => {
    query.mockResolvedValueOnce(ok([]));
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find(".el-empty").text()).toContain("暂无能力");
    expect(wrapper.find(".cap-card").exists()).toBe(false);
  });

  it("普通用户看不到新建/修改/删除/管理/查看，标签只读", async () => {
    mocks.rid = "2";
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).not.toContain("新建能力");
    expect(wrapper.find(".action-btn").exists()).toBe(false);
    expect(wrapper.findAll(".tag-dropdown")).toHaveLength(0);
    expect(wrapper.findAll(".ima-card-tag")[0].text()).toContain("办公");
  });

  it("新建能力：必填拦截，提交带 difyUrl 并关闭刷新", async () => {
    const spies = createMessageSpies();
    create.mockResolvedValue(ok(true));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find(".btn-primary").trigger("click");
    await flushPromises();
    await clickFooter(wrapper, "新建能力", "确 定");
    expect(create).not.toHaveBeenCalled();
    expect(dialogByTitle(wrapper, "新建能力")!.find(".el-form-item.is-error").exists()).toBe(true);

    await wrapper.find('input[placeholder="请输入能力名称"]').setValue("新能力");
    await wrapper.find('textarea[placeholder="请输入能力描述"]').setValue("能力描述");
    await wrapper.find('input[placeholder="请输入能力地址"]').setValue("https://cap.example.com/new");
    await clickFooter(wrapper, "新建能力", "确 定");

    expect(create).toHaveBeenCalledWith({
      data: {
        title: "新能力",
        description: "能力描述",
        label: "",
        url: "https://cap.example.com/new",
        icon: "",
        difyUrl: "https://cap.example.com/new",
      },
    });
    expect(spies.success).toHaveBeenCalledWith("保存成功");
    expect((wrapper.vm as any).state.params.dialogVisible).toBe(false);
    expect(query).toHaveBeenCalledTimes(2);
  });

  it("编辑能力：回填 difyUrl 与图标，按 iid 更新", async () => {
    createMessageSpies();
    update.mockResolvedValue(ok(true));
    const wrapper = mountView();
    await flushPromises();

    await clickCardAction(wrapper, 0, "修改");
    expect(
      (wrapper.find('input[placeholder="请输入能力名称"]').element as HTMLInputElement).value
    ).toBe("智能写作");
    expect(
      (wrapper.find('input[placeholder="请输入能力地址"]').element as HTMLInputElement).value
    ).toBe("https://cap.example.com/write");
    expect((wrapper.vm as any).state.params.form.icon).toBe("https://cdn.example.com/icon.png");

    await wrapper.find('input[placeholder="请输入能力名称"]').setValue("智能写作 PRO");
    await clickFooter(wrapper, "编辑能力", "确 定");

    expect(update).toHaveBeenCalledWith({
      data: expect.objectContaining({
        iid: 21,
        title: "智能写作 PRO",
        difyUrl: "https://cap.example.com/write",
        icon: "https://cdn.example.com/icon.png",
      }),
      where: { iid: 21 },
    });
  });

  it("删除：确认、取消、错误码与异常分别处理，遮罩始终关闭", async () => {
    const spies = createMessageSpies();
    const close = vi.fn();
    vi.spyOn(ElLoading, "service").mockReturnValue({ close } as never);
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    remove.mockResolvedValue(ok(true));
    const wrapper = mountView();
    await flushPromises();

    await clickCardAction(wrapper, 0, "删除");
    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining("智能写作"),
      "确认删除",
      expect.objectContaining({ type: "warning" })
    );
    expect(remove).toHaveBeenCalledWith({ data: { iid: 21 } });
    expect(spies.success).toHaveBeenCalledWith("删除成功");
    expect(query).toHaveBeenCalledTimes(2);

    confirm.mockRejectedValueOnce("cancel");
    await clickCardAction(wrapper, 1, "删除");
    expect(remove).toHaveBeenCalledTimes(1);
    expect(spies.error).not.toHaveBeenCalled();

    confirm.mockResolvedValueOnce("confirm" as never);
    remove.mockResolvedValueOnce(fail("能力已被引用"));
    await clickCardAction(wrapper, 1, "删除");
    expect(spies.error).toHaveBeenCalledWith("能力已被引用");

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
    await wrapper.find('input[placeholder="请输入能力名称"]').setValue("新能力");
    await wrapper.find('textarea[placeholder="请输入能力描述"]').setValue("描述");
    await wrapper.find('input[placeholder="请输入能力地址"]').setValue("https://cap.example.com/new");
    await clickFooter(wrapper, "新建能力", "确 定");

    expect(spies.error).toHaveBeenCalledWith("保存失败，请稍后重试");
    expect((wrapper.vm as any).state.params.dialogVisible).toBe(true);
    expect(close).toHaveBeenCalled();

    create.mockRejectedValueOnce(new Error("network"));
    await clickFooter(wrapper, "新建能力", "确 定");
    expect(spies.error).toHaveBeenCalledTimes(2);
    expect(close).toHaveBeenCalledTimes(2);
  });

  it("图标上传：非图片忽略、超 2MB 拦截、合法图片转 base64 并可移除", async () => {
    const spies = createMessageSpies();
    const createObjectURL = vi.fn(() => "blob:icon");
    const revokeObjectURL = vi.fn();
    Object.defineProperty(URL, "createObjectURL", {
      value: createObjectURL,
      writable: true,
      configurable: true,
    });
    Object.defineProperty(URL, "revokeObjectURL", {
      value: revokeObjectURL,
      writable: true,
      configurable: true,
    });

    const wrapper = mountView();
    await flushPromises();
    await wrapper.find(".btn-primary").trigger("click");
    await flushPromises();

    const vm = wrapper.vm as any;
    const textFile = new File(["x"], "note.txt", { type: "text/plain" });
    await vm.handleIconChange({ name: "note.txt", raw: textFile }, [{ name: "note.txt", raw: textFile }]);
    expect(vm.state.params.form.icon).toBe("");

    const huge = new File(["x"], "huge.png", { type: "image/png" });
    Object.defineProperty(huge, "size", { value: 3 * 1024 * 1024 });
    await vm.handleIconChange({ name: "huge.png", raw: huge }, [{ name: "huge.png", raw: huge }]);
    expect(spies.warning).toHaveBeenCalledWith("图片不能超过 2MB，请压缩后重试");

    const image = new File([new Uint8Array([137, 80, 78, 71])], "icon.png", { type: "image/png" });
    await vm.handleIconChange({ name: "icon.png", raw: image }, [{ name: "icon.png", raw: image }]);
    await vi.waitFor(() => {
      expect(vm.state.params.form.icon).toContain("data:image/png");
    });
    expect(createObjectURL).toHaveBeenCalledWith(image);
    expect(vm.iconFiles).toHaveLength(1);
    expect(vm.iconFiles[0].url).toBe("blob:icon");

    vm.handleIconExceed();
    expect(spies.warning).toHaveBeenCalledWith("能力图片仅支持上传 1 张图片");

    vm.handleIconRemove();
    expect(vm.state.params.form.icon).toBe("");
    expect(vm.iconFiles).toEqual([]);
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:icon");
  });

  it("管理权限：回填并过滤超管角色，保存按 iid 更新；损坏 JSON 兜底", async () => {
    const spies = createMessageSpies();
    update.mockResolvedValue(ok(true));
    const wrapper = mountView();
    await flushPromises();

    await clickCardAction(wrapper, 0, "管理");
    expect((wrapper.vm as any).state.mngForm.selectedDepts).toEqual(["D1"]);
    expect((wrapper.vm as any).state.mngForm.selectedRoles).toEqual([2]);
    const roleLabels = wrapper.findAll(".mng-role-container .el-checkbox").map((item) => item.text());
    expect(roleLabels).toEqual(["管理员", "普通用户"]);

    await clickFooter(wrapper, "管理权限", "确 定");
    expect(update).toHaveBeenCalledWith({
      data: { unitsnos: ["D1"], roleiids: [2] },
      where: { iid: 21 },
    });
    expect(spies.success).toHaveBeenCalledWith("权限配置保存成功");
    expect((wrapper.vm as any).state.mngDialogVisible).toBe(false);

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

  it("跳转：SSO 成功用会话/授权地址，接口失败提示；非 SSO 拦截危险协议", async () => {
    const spies = createMessageSpies();
    const close = vi.fn();
    vi.spyOn(ElLoading, "service").mockReturnValue({ close } as never);
    const open = vi.spyOn(window, "open").mockReturnValue(null as never);
    const wrapper = mountView();
    await flushPromises();

    ssoJump.mockResolvedValueOnce(ok({ hasSession: true, authUrl: "https://auth.example.com/x" }));
    await clickCard(wrapper, 0);
    expect(open).toHaveBeenLastCalledWith(
      "https://cap.example.com/write",
      "_blank",
      "noopener,noreferrer"
    );

    ssoJump.mockResolvedValueOnce(ok({ hasSession: false, authUrl: "https://auth.example.com/x" }));
    await clickCard(wrapper, 0);
    expect(open).toHaveBeenLastCalledWith(
      "https://auth.example.com/x",
      "_blank",
      "noopener,noreferrer"
    );

    ssoJump.mockResolvedValueOnce(fail("未授权"));
    await clickCard(wrapper, 0);
    expect(spies.error).toHaveBeenCalledWith("获取跳转地址失败，请重试");

    ssoJump.mockRejectedValueOnce(new Error("network"));
    await clickCard(wrapper, 0);
    expect(spies.error).toHaveBeenCalledWith("获取跳转地址失败，请重试");
    expect(close).toHaveBeenCalledTimes(4);

    await clickCard(wrapper, 1);
    await clickCard(wrapper, 2);
    expect(open).toHaveBeenCalledTimes(3);
    expect(open).toHaveBeenLastCalledWith(
      "https://cap.example.com/asr",
      "_blank",
      "noopener,noreferrer"
    );
  });
});
