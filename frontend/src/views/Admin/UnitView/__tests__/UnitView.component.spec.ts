import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { ElLoading, ElMessage, ElMessageBox } from "element-plus";
import Cookies from "js-cookie";

vi.mock("js-cookie", () => ({ default: { get: vi.fn() } }));
vi.mock("@/api/unit", () => ({
  unitApi: {
    getDataList: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
  },
}));

import UnitView from "../index.vue";
import { unitApi } from "@/api/unit";

/**
 * 部门管理后台：树形表格、上级部门树选择、新建/编辑提交、
 * 删除确认与失败提示。API 全部 mock。
 */

enableAutoUnmount(afterEach);

const ok = (datas: unknown) => ({ data: { resp_code: 0, resp_msg: "ok", datas } } as never);
const fail = (msg: string) => ({ data: { resp_code: 500, resp_msg: msg } } as never);

const units = [
  {
    iid: 1001,
    unitsno: "1001",
    unitname: "研发部",
    parentsno: "0",
    unittel: "010-1001",
    remark: "核心技术",
    createtime: "2026-10-01 10:00:00",
    childList: [
      {
        iid: 1002,
        unitsno: "1002",
        unitname: "前端组",
        parentsno: "1001",
        unittel: "",
        remark: "",
        createtime: "2026-10-02 10:00:00",
        childList: [],
      },
    ],
  },
];

const mountView = () =>
  mount(UnitView, {
    global: {
      stubs: { Search: true, CirclePlusFilled: true, Edit: true, Delete: true },
    },
  });

const asAdmin = (rid = "99999") =>
  vi.mocked(Cookies.get).mockImplementation(((name: string) => {
    if (name === "admin_rid") return rid;
    return undefined;
  }) as never);

const setupSuccess = () => {
  vi.mocked(unitApi.getDataList).mockResolvedValue(ok(units));
  vi.mocked(unitApi.create).mockResolvedValue(ok(true));
  vi.mocked(unitApi.update).mockResolvedValue(ok(true));
  vi.mocked(unitApi.delete).mockResolvedValue(ok(true));
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

const openAddDialog = async (wrapper: ReturnType<typeof mountView>) => {
  const addButton = wrapper
    .findAll("button")
    .find((button) => button.text().includes("新建"));
  await addButton!.trigger("click");
  await nextTick();
  await nextTick();
};

/** 在弹窗的上级部门树里点选指定文案的节点（popper 挂在 body 上） */
const pickParentNode = async (
  wrapper: ReturnType<typeof mountView>,
  text: string
) => {
  await wrapper.find(".el-dialog .el-select__wrapper").trigger("click");
  await flushPromises();
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

describe("UnitView", () => {
  it("挂载后加载部门树，表格展示父子层级", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(unitApi.getDataList).toHaveBeenCalledTimes(1);
    expect(unitApi.getDataList).toHaveBeenCalledWith({
      data: { unitname: "", enable: true },
    });

    const rows = wrapper.findAll(".el-table__row");
    expect(rows).toHaveLength(2);
    expect(rows[0].text()).toContain("研发部");
    expect(rows[0].text()).toContain("010-1001");
    expect(rows[0].text()).toContain("核心技术");
    expect(rows[1].text()).toContain("前端组");
  });

  it("搜索带上名称条件；非管理员看不到新建与操作列", async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('input[placeholder="请输入名称"]').setValue("前端");
    const searchButton = wrapper
      .findAll("button")
      .find((button) => button.text().includes("搜索"));
    await searchButton!.trigger("click");
    await flushPromises();
    expect(unitApi.getDataList).toHaveBeenLastCalledWith({
      data: { unitname: "前端", enable: true },
    });

    asAdmin("2");
    const plainWrapper = mountView();
    await flushPromises();
    expect(plainWrapper.text()).not.toContain("新建");
    expect(plainWrapper.find(".el-table__row").text()).not.toContain("编辑");
  });

  it("部门管理员（admin_access=1）可见新建与操作列", async () => {
    vi.mocked(Cookies.get).mockImplementation(((name: string) => {
      if (name === "admin_rid") return "2";
      if (name === "admin_access") return "1";
      return undefined;
    }) as never);
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).toContain("新建");
    expect(wrapper.find(".el-table__row").text()).toContain("编辑");
  });

  it("新建部门：必填项为空被拦截，选择上级并提交完整表单", async () => {
    const successSpy = createMessageSpies().success;
    const wrapper = mountView();
    await flushPromises();

    await openAddDialog(wrapper);
    expect(wrapper.find(".el-dialog").text()).toContain("新建部门");

    // 未选上级、未填名称：不提交
    await dialogSaveButton(wrapper).trigger("click");
    await flushPromises();
    expect(unitApi.create).not.toHaveBeenCalled();

    await pickParentNode(wrapper, "顶级");
    const dialog = wrapper.find(".el-dialog");
    await dialog.find('input[placeholder="请输入部门名称"]').setValue("运维部");
    await dialog.find('input[placeholder="请输入部门电话"]').setValue("010-2000");
    await dialog.find('textarea[placeholder="请输入备注"]').setValue("新部门");

    await dialogSaveButton(wrapper).trigger("click");
    await flushPromises();

    expect(unitApi.create).toHaveBeenCalledTimes(1);
    expect(vi.mocked(unitApi.create).mock.calls[0][0]).toEqual({
      data: {
        parentsno: "0",
        unitname: "运维部",
        unittel: "010-2000",
        remark: "新部门",
        enable: true,
      },
    });
    expect(successSpy).toHaveBeenCalledWith("保存成功");
    expect(unitApi.getDataList).toHaveBeenCalledTimes(2);
  });

  it("编辑部门：回填名称/电话/上级，提交保留 iid 并用 where 定位", async () => {
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
    expect(dialog.text()).toContain("编辑部门");
    expect(
      (dialog.find('input[placeholder="请输入部门名称"]').element as HTMLInputElement)
        .value
    ).toBe("研发部");
    expect(
      (dialog.find('input[placeholder="请输入部门电话"]').element as HTMLInputElement)
        .value
    ).toBe("010-1001");
    expect(
      (dialog.find('textarea[placeholder="请输入备注"]').element as HTMLTextAreaElement)
        .value
    ).toBe("核心技术");
    expect(dialog.text()).toContain("顶级");

    await dialog.find('input[placeholder="请输入部门名称"]').setValue("研发中心");
    await dialogSaveButton(wrapper).trigger("click");
    await flushPromises();

    expect(unitApi.update).toHaveBeenCalledTimes(1);
    const payload = vi.mocked(unitApi.update).mock.calls[0][0] as {
      data: Record<string, unknown>;
      where: Record<string, unknown>;
    };
    expect(payload.where).toEqual({ iid: 1001 });
    expect(payload.data.unitname).toBe("研发中心");
    expect(payload.data.parentsno).toBe("0");
    expect(payload.data.iid).toBe(1001);
    expect(successSpy).toHaveBeenCalledWith("修改成功");
  });

  it("删除部门：确认后删除并刷新，取消不调用接口", async () => {
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

    expect(unitApi.delete).toHaveBeenCalledWith({ data: { iid: 1001 } });
    expect(successSpy).toHaveBeenCalledWith("删除成功");
    expect(unitApi.getDataList).toHaveBeenCalledTimes(2);

    confirmSpy.mockRejectedValue("cancel");
    await deleteButton!.trigger("click");
    await flushPromises();
    expect(unitApi.delete).toHaveBeenCalledTimes(1);
  });

  it("删除失败：错误码与请求异常分别给出提示", async () => {
    const errorSpy = createMessageSpies().error;
    vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    const wrapper = mountView();
    await flushPromises();

    const deleteButton = wrapper
      .find(".el-table__row")
      .findAll("button")
      .find((button) => button.text().includes("删除"));

    vi.mocked(unitApi.delete).mockResolvedValueOnce(fail("locked"));
    await deleteButton!.trigger("click");
    await flushPromises();
    expect(errorSpy).toHaveBeenCalledWith("删除失败");

    vi.mocked(unitApi.delete).mockRejectedValueOnce(new Error("network"));
    await deleteButton!.trigger("click");
    await flushPromises();
    expect(errorSpy).toHaveBeenCalledWith("删除失败，请稍后重试");
    expect(unitApi.getDataList).toHaveBeenCalledTimes(1);
  });

  it("列表失败：错误码与请求异常分别给出提示", async () => {
    const errorSpy = createMessageSpies().error;

    vi.mocked(unitApi.getDataList).mockResolvedValueOnce(fail("db down"));
    mountView();
    await flushPromises();
    expect(errorSpy).toHaveBeenCalledWith("获取部门列表失败");

    vi.mocked(unitApi.getDataList).mockRejectedValueOnce(new Error("network"));
    mountView();
    await flushPromises();
    expect(errorSpy).toHaveBeenCalledWith("获取部门列表失败，请稍后重试");
  });
});
