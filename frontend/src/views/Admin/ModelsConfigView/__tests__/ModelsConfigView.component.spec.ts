import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { ElLoading, ElMessage, ElMessageBox } from "element-plus";

vi.mock("@/api/modelsConfig", () => ({
  modelsConfigApi: {
    getPageList: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
  },
}));

import ModelsConfigView from "../index.vue";
import { modelsConfigApi } from "@/api/modelsConfig";

/**
 * 模型配置后台：列表分页、直连协议的完整性校验（服务地址/上游或向量模型/
 * 密钥）、编辑回填、删除与保存的失败提示。API 全部 mock。
 */

enableAutoUnmount(afterEach);

const ok = (datas: unknown) => ({ data: { resp_code: 0, resp_msg: "ok", datas } } as never);
const fail = (msg: string) => ({ data: { resp_code: 500, resp_msg: msg } } as never);

const rows = {
  content: [
    {
      iid: 1,
      id: "deepseek-chat",
      name: "DeepSeek Chat",
      provider: "DeepSeek",
      protocol: "openai",
      baseUrl: "https://api.deepseek.com",
      upstreamModel: "deepseek-chat",
      embedModel: "text-embedding-v3",
      apiKey: "****abcd",
    },
    {
      iid: 2,
      id: "qwen-max",
      name: "通义千问",
      provider: "",
      protocol: "",
      baseUrl: "",
      upstreamModel: "",
      embedModel: "",
      apiKey: "",
    },
  ],
  totalSize: 2,
};

const mountView = () =>
  mount(ModelsConfigView, {
    global: {
      stubs: { Search: true, CirclePlusFilled: true, Edit: true, Delete: true },
    },
  });

const setupSuccess = () => {
  vi.mocked(modelsConfigApi.getPageList).mockResolvedValue(ok(rows));
  vi.mocked(modelsConfigApi.create).mockResolvedValue(ok(true));
  vi.mocked(modelsConfigApi.update).mockResolvedValue(ok(true));
  vi.mocked(modelsConfigApi.delete).mockResolvedValue(ok(true));
};

/** 每个用例只调用一次：对同一方法二次 spyOn 会丢弃第一次的引用 */
const createMessageSpies = () => ({
  success: vi.spyOn(ElMessage, "success").mockReturnValue({ close: () => {} } as never),
  error: vi.spyOn(ElMessage, "error").mockReturnValue({ close: () => {} } as never),
  warning: vi.spyOn(ElMessage, "warning").mockReturnValue({ close: () => {} } as never),
});

const dialogSaveButton = (wrapper: ReturnType<typeof mountView>) =>
  wrapper
    .find(".el-dialog")
    .findAll(".el-dialog__footer button")
    .find((button) => button.text().includes("确定"))!;

const openAddDialog = async (wrapper: ReturnType<typeof mountView>) => {
  const addButton = wrapper
    .findAll("button")
    .find((button) => button.text().includes("新建"));
  await addButton!.trigger("click");
  await nextTick();
  await nextTick();
};

const pickProtocol = async (wrapper: ReturnType<typeof mountView>) => {
  await wrapper.find(".el-dialog .el-select__wrapper").trigger("click");
  await flushPromises();
  const option = Array.from(
    document.body.querySelectorAll<HTMLElement>(".el-select-dropdown__item")
  ).find((item) => item.textContent?.includes("OpenAI 兼容直连"));
  expect(option).toBeTruthy();
  option!.click();
  await flushPromises();
  await nextTick();
};

const fillRequiredFields = async (wrapper: ReturnType<typeof mountView>) => {
  const dialog = wrapper.find(".el-dialog");
  await dialog.find('input[placeholder="请输入模型ID"]').setValue("gpt-4o-mini");
  await dialog.find('input[placeholder="请输入模型名称"]').setValue("GPT-4o mini");
  await dialog.find('input[placeholder="请输入提供者"]').setValue("OpenAI");
};

beforeEach(() => {
  setupSuccess();
  vi.spyOn(ElLoading, "service").mockReturnValue({ close: vi.fn() } as never);
});

describe("ModelsConfigView", () => {
  it("挂载后加载分页列表，区分直连与普通模型", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(modelsConfigApi.getPageList).toHaveBeenCalledTimes(1);
    expect(modelsConfigApi.getPageList).toHaveBeenCalledWith(
      expect.objectContaining({
        data: { name: "" },
        pageRequest: expect.objectContaining({ pageNum: 1, pageSize: 20 }),
      })
    );

    const table = wrapper.find(".el-table");
    expect(table.text()).toContain("deepseek-chat");
    expect(table.text()).toContain("OpenAI 兼容");
    expect(table.text()).toContain("通义千问");
    const secondRow = wrapper.findAll(".el-table__row")[1];
    expect(secondRow.text()).toContain("-");
    expect(wrapper.find(".el-pagination").exists()).toBe(true);
  });

  it("搜索重置到第一页并带上名称条件", async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find('input[placeholder="请输入名称"]').setValue("deepseek");
    const searchButton = wrapper
      .findAll("button")
      .find((button) => button.text().includes("搜索"));
    await searchButton!.trigger("click");
    await flushPromises();

    expect(modelsConfigApi.getPageList).toHaveBeenLastCalledWith(
      expect.objectContaining({
        data: { name: "deepseek" },
        pageRequest: expect.objectContaining({ pageNum: 1 }),
      })
    );
  });

  it("列表失败：错误码与请求异常分别给出提示", async () => {
    const errorSpy = createMessageSpies().error;

    vi.mocked(modelsConfigApi.getPageList).mockResolvedValueOnce(fail("db down"));
    mountView();
    await flushPromises();
    expect(errorSpy).toHaveBeenCalledWith("获取列表失败");

    vi.mocked(modelsConfigApi.getPageList).mockRejectedValueOnce(new Error("network"));
    mountView();
    await flushPromises();
    expect(errorSpy).toHaveBeenCalledWith("获取列表失败，请稍后重试");
  });

  it("新建普通模型：必填校验拦截，补齐后提交", async () => {
    const spies = createMessageSpies();
    const wrapper = mountView();
    await flushPromises();

    await openAddDialog(wrapper);
    expect(wrapper.find(".el-dialog").text()).toContain("新建模型配置");

    await dialogSaveButton(wrapper).trigger("click");
    await flushPromises();
    expect(modelsConfigApi.create).not.toHaveBeenCalled();
    expect(spies.warning).toHaveBeenCalledWith("请填写完整的信息");

    await fillRequiredFields(wrapper);
    await dialogSaveButton(wrapper).trigger("click");
    await flushPromises();

    expect(modelsConfigApi.create).toHaveBeenCalledTimes(1);
    expect(vi.mocked(modelsConfigApi.create).mock.calls[0][0]).toEqual({
      data: {
        id: "gpt-4o-mini",
        name: "GPT-4o mini",
        provider: "OpenAI",
        protocol: "",
        baseUrl: "",
        upstreamModel: "",
        embedModel: "",
        apiKey: "",
      },
    });
    expect(spies.success).toHaveBeenCalledWith("新建成功");
    expect(modelsConfigApi.getPageList).toHaveBeenCalledTimes(2);
  });

  it("直连模型：服务地址、上游/向量模型、密钥三项校验依次拦截", async () => {
    const spies = createMessageSpies();
    const wrapper = mountView();
    await flushPromises();

    await openAddDialog(wrapper);
    await fillRequiredFields(wrapper);
    await pickProtocol(wrapper);
    expect(wrapper.find(".el-dialog").text()).toContain("服务地址");

    // 只有协议、没有服务地址与模型名
    await dialogSaveButton(wrapper).trigger("click");
    await flushPromises();
    expect(modelsConfigApi.create).not.toHaveBeenCalled();
    expect(spies.warning).toHaveBeenCalledWith(
      "启用直连时必须填写服务地址，以及上游模型名或向量模型名（至少一个）"
    );

    // 纯向量配置合法（可以没有上游对话模型），但新建必须带密钥
    const dialog = wrapper.find(".el-dialog");
    await dialog
      .find('input[placeholder^="如 https://api.deepseek.com"]')
      .setValue("https://api.deepseek.com");
    await dialog
      .find('input[placeholder^="选填，如 text-embedding-v3"]')
      .setValue("text-embedding-v3");
    await dialogSaveButton(wrapper).trigger("click");
    await flushPromises();
    expect(modelsConfigApi.create).not.toHaveBeenCalled();
    expect(spies.warning).toHaveBeenCalledWith("启用直连时必须填写服务密钥");

    await dialog
      .find('input[placeholder="未配置时必填；已配置时留空表示不修改"]')
      .setValue("sk-test");
    await dialogSaveButton(wrapper).trigger("click");
    await flushPromises();

    expect(modelsConfigApi.create).toHaveBeenCalledTimes(1);
    const payload = vi.mocked(modelsConfigApi.create).mock.calls[0][0] as {
      data: Record<string, unknown>;
    };
    expect(payload.data).toMatchObject({
      protocol: "openai",
      baseUrl: "https://api.deepseek.com",
      embedModel: "text-embedding-v3",
      apiKey: "sk-test",
    });
    expect(spies.success).toHaveBeenCalledWith("新建成功");
  });

  it("编辑模型：回填配置并按 iid 更新", async () => {
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
    expect(dialog.text()).toContain("编辑模型配置");
    expect(
      (dialog.find('input[placeholder="请输入模型ID"]').element as HTMLInputElement)
        .value
    ).toBe("deepseek-chat");
    expect(
      (dialog.find('input[placeholder^="如 https://api.deepseek.com"]').element as HTMLInputElement)
        .value
    ).toBe("https://api.deepseek.com");

    await dialog.find('input[placeholder="请输入模型名称"]').setValue("DeepSeek Chat V2");
    await dialogSaveButton(wrapper).trigger("click");
    await flushPromises();

    expect(modelsConfigApi.update).toHaveBeenCalledTimes(1);
    const payload = vi.mocked(modelsConfigApi.update).mock.calls[0][0] as {
      data: Record<string, unknown>;
      where: Record<string, unknown>;
    };
    expect(payload.where).toEqual({ iid: 1 });
    expect(payload.data.name).toBe("DeepSeek Chat V2");
    expect(payload.data.protocol).toBe("openai");
    expect(successSpy).toHaveBeenCalledWith("保存成功");
  });

  it("删除模型配置：确认/取消/失败三条路径", async () => {
    const spies = createMessageSpies();
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
    expect(modelsConfigApi.delete).toHaveBeenCalledWith({ data: { iid: 1 } });
    expect(spies.success).toHaveBeenCalledWith("删除成功");
    expect(modelsConfigApi.getPageList).toHaveBeenCalledTimes(2);

    confirmSpy.mockRejectedValue("cancel");
    await deleteButton!.trigger("click");
    await flushPromises();
    expect(modelsConfigApi.delete).toHaveBeenCalledTimes(1);

    confirmSpy.mockResolvedValue("confirm" as never);
    vi.mocked(modelsConfigApi.delete).mockResolvedValueOnce(fail("locked"));
    await deleteButton!.trigger("click");
    await flushPromises();
    expect(spies.error).toHaveBeenCalledWith("删除失败");

    vi.mocked(modelsConfigApi.delete).mockRejectedValueOnce(new Error("network"));
    await deleteButton!.trigger("click");
    await flushPromises();
    expect(spies.error).toHaveBeenCalledWith("删除失败，请稍后重试");
    // 失败不刷新列表
    expect(modelsConfigApi.getPageList).toHaveBeenCalledTimes(2);
  });

  it("保存失败：错误码与请求异常分别提示并关闭 loading", async () => {
    const errorSpy = createMessageSpies().error;
    const closeSpy = vi.fn();
    vi.spyOn(ElLoading, "service").mockReturnValue({ close: closeSpy } as never);
    const wrapper = mountView();
    await flushPromises();

    await openAddDialog(wrapper);
    await fillRequiredFields(wrapper);

    vi.mocked(modelsConfigApi.create).mockResolvedValueOnce(fail("duplicate id"));
    await dialogSaveButton(wrapper).trigger("click");
    await flushPromises();
    expect(errorSpy).toHaveBeenCalledWith("新建失败");
    expect(closeSpy).toHaveBeenCalledTimes(1);

    vi.mocked(modelsConfigApi.create).mockRejectedValueOnce(new Error("network"));
    await dialogSaveButton(wrapper).trigger("click");
    await flushPromises();
    expect(errorSpy).toHaveBeenCalledWith("保存失败，请稍后重试");
    expect(closeSpy).toHaveBeenCalledTimes(2);
  });
});
