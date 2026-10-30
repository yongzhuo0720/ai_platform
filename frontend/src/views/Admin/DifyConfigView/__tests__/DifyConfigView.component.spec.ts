import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { ElLoading, ElMessage } from "element-plus";

vi.mock("@/api/difyConfig", () => ({
  difyConfigApi: { getList: vi.fn(), update: vi.fn() },
}));
vi.mock("@/stores/difyConfig", () => ({
  invalidate: vi.fn(),
  ensureLoaded: vi.fn(),
}));

import DifyConfigView from "../index.vue";
import { difyConfigApi } from "@/api/difyConfig";
import { invalidate, ensureLoaded } from "@/stores/difyConfig";

/**
 * Dify 配置后台：列表展示、密钥掩码状态、编辑提交与失败提示。
 * API 与配置缓存全部 mock。
 */

enableAutoUnmount(afterEach);

const ok = (datas: unknown) => ({ data: { resp_code: 0, resp_msg: "ok", datas } } as never);
const fail = (msg: string) => ({ data: { resp_code: 500, resp_msg: msg } } as never);

const configs = [
  {
    iid: 1,
    moduleName: "问答聊天对话",
    apiUrl: "https://dify.example/v1",
    apiKey: "",
    apiKeyConfigured: true,
    description: "聊天配置",
    updatetime: "2026-10-01T10:00:00",
  },
  {
    iid: 2,
    moduleName: "知识库文件",
    apiUrl: "",
    apiKey: "",
    apiKeyConfigured: false,
    description: "",
    updatetime: null,
  },
];

const mountView = () =>
  mount(DifyConfigView, {
    global: { stubs: { Edit: true } },
  });

const createMessageSpies = () => ({
  success: vi.spyOn(ElMessage, "success").mockReturnValue({ close: () => {} } as never),
  error: vi.spyOn(ElMessage, "error").mockReturnValue({ close: () => {} } as never),
});

const openEdit = async (wrapper: ReturnType<typeof mountView>, rowIndex: number) => {
  const button = wrapper
    .findAll(".el-table__row")[rowIndex]
    .findAll("button")
    .find((item) => item.text().includes("编辑"));
  await button!.trigger("click");
  await nextTick();
  await nextTick();
};

const saveButton = (wrapper: ReturnType<typeof mountView>) =>
  wrapper
    .find(".el-dialog")
    .findAll(".el-dialog__footer button")
    .find((button) => button.text().includes("保存"))!;

beforeEach(() => {
  vi.mocked(difyConfigApi.getList).mockResolvedValue(ok(configs));
  vi.mocked(difyConfigApi.update).mockResolvedValue(ok(true));
  vi.mocked(ensureLoaded).mockResolvedValue(undefined as never);
  vi.spyOn(ElLoading, "service").mockReturnValue({ close: vi.fn() } as never);
});

describe("DifyConfigView", () => {
  it("挂载后加载配置列表，密钥只显示配置状态与更新时间", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(difyConfigApi.getList).toHaveBeenCalledTimes(1);
    expect(difyConfigApi.getList).toHaveBeenCalledWith(
      expect.objectContaining({
        data: {},
        pageRequest: expect.objectContaining({ pageNum: 1, pageSize: 20 }),
      })
    );

    const rows = wrapper.findAll(".el-table__row");
    expect(rows).toHaveLength(2);
    expect(rows[0].text()).toContain("问答聊天对话");
    expect(rows[0].text()).toContain("https://dify.example/v1");
    expect(rows[0].text()).not.toContain("sk-");
    expect(rows[0].text()).toContain("已配置，密钥不下发到前端");
    expect(rows[0].text()).toContain("2026-10-01 10:00:00");
    expect(rows[1].text()).toContain("未配置");
    expect(rows[1].text()).toContain("-");
    expect(wrapper.find(".el-pagination").exists()).toBe(true);
  });

  it("列表失败：错误码与请求异常分别给出提示", async () => {
    const errorSpy = createMessageSpies().error;

    vi.mocked(difyConfigApi.getList).mockResolvedValueOnce(fail("db down"));
    mountView();
    await flushPromises();
    expect(errorSpy).toHaveBeenCalledWith("获取配置列表失败");

    vi.mocked(difyConfigApi.getList).mockRejectedValueOnce(new Error("network"));
    mountView();
    await flushPromises();
    expect(errorSpy).toHaveBeenCalledWith("获取配置列表失败，请稍后重试");
  });

  it("编辑已配置模块：密钥留空提交，成功后失效缓存并刷新", async () => {
    const successSpy = createMessageSpies().success;
    const wrapper = mountView();
    await flushPromises();

    await openEdit(wrapper, 0);
    const dialog = wrapper.find(".el-dialog");
    expect(dialog.text()).toContain("编辑配置");
    const nameInput = dialog.find("input[disabled]");
    expect((nameInput.element as HTMLInputElement).value).toBe("问答聊天对话");
    expect(
      (dialog.find('input[placeholder="请输入 Dify API URL"]').element as HTMLInputElement)
        .value
    ).toBe("https://dify.example/v1");
    expect(
      (dialog.find(".el-input__inner").element as HTMLInputElement).value
    ).toBe("问答聊天对话");
    const keyInput = dialog.findAll("input").find((item) =>
      (item.element as HTMLInputElement).placeholder.includes("留空表示不修改")
    );
    expect(keyInput).toBeTruthy();

    await dialog
      .find('input[placeholder="请输入 Dify API URL"]')
      .setValue("https://dify2.example/v1");
    await saveButton(wrapper).trigger("click");
    await flushPromises();

    expect(difyConfigApi.update).toHaveBeenCalledTimes(1);
    expect(vi.mocked(difyConfigApi.update).mock.calls[0][0]).toEqual({
      data: {
        moduleName: "问答聊天对话",
        apiUrl: "https://dify2.example/v1",
        apiKey: "",
        description: "聊天配置",
      },
      where: { iid: 1 },
    });
    expect(invalidate).toHaveBeenCalledTimes(1);
    expect(ensureLoaded).toHaveBeenCalledTimes(1);
    expect(difyConfigApi.getList).toHaveBeenCalledTimes(2);
    expect(successSpy).toHaveBeenCalledWith("保存成功");
  });

  it("编辑未配置模块：提示输入密钥，填写后一并提交", async () => {
    const wrapper = mountView();
    await flushPromises();

    await openEdit(wrapper, 1);
    const dialog = wrapper.find(".el-dialog");
    const keyInput = dialog.find('input[placeholder="请输入 API Key"]');
    expect(keyInput.exists()).toBe(true);

    await dialog
      .find('input[placeholder="请输入 Dify API URL"]')
      .setValue("https://dify3.example/v1");
    await keyInput.setValue("app-secret");
    await saveButton(wrapper).trigger("click");
    await flushPromises();

    const payload = vi.mocked(difyConfigApi.update).mock.calls[0][0] as {
      data: Record<string, unknown>;
      where: Record<string, unknown>;
    };
    expect(payload.where).toEqual({ iid: 2 });
    expect(payload.data.apiKey).toBe("app-secret");
    expect(payload.data.moduleName).toBe("知识库文件");
  });

  it("保存失败：错误码与请求异常使用同一提示，loading 正常关闭且不失效缓存", async () => {
    const errorSpy = createMessageSpies().error;
    const closeSpy = vi.fn();
    vi.spyOn(ElLoading, "service").mockReturnValue({ close: closeSpy } as never);
    const wrapper = mountView();
    await flushPromises();

    await openEdit(wrapper, 0);

    vi.mocked(difyConfigApi.update).mockResolvedValueOnce(fail("readonly"));
    await saveButton(wrapper).trigger("click");
    await flushPromises();
    expect(errorSpy).toHaveBeenCalledWith("保存失败，请稍后重试");

    vi.mocked(difyConfigApi.update).mockRejectedValueOnce(new Error("network"));
    await saveButton(wrapper).trigger("click");
    await flushPromises();
    expect(errorSpy).toHaveBeenCalledTimes(2);
    expect(closeSpy).toHaveBeenCalledTimes(2);
    expect(invalidate).not.toHaveBeenCalled();
    expect(ensureLoaded).not.toHaveBeenCalled();
    expect(difyConfigApi.getList).toHaveBeenCalledTimes(1);
  });
});
