import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";

const routerMocks = vi.hoisted(() => ({
  route: { query: {} as Record<string, unknown> },
  replace: vi.fn(),
  back: vi.fn(),
}));

vi.mock("vue-router", () => ({
  useRoute: () => routerMocks.route,
  useRouter: () => ({ replace: routerMocks.replace, back: routerMocks.back }),
}));
vi.mock("@/api/datasetsMng", () => ({
  datasetsMngApi: { getDownloadUrl: vi.fn() },
}));
vi.mock("@/api/modelsMng", () => ({
  modelsMngApi: { getDownloadUrl: vi.fn() },
}));

import PreviewView from "../index.vue";
import { datasetsMngApi } from "@/api/datasetsMng";
import { modelsMngApi } from "@/api/modelsMng";

/**
 * 资源下载预览页：按 type 选择取链接接口、iframe 沙箱属性、错误分支与返回逻辑。
 */

enableAutoUnmount(afterEach);

const datasetsDownload = datasetsMngApi.getDownloadUrl as Mock;
const modelsDownload = modelsMngApi.getDownloadUrl as Mock;

const ok = (datas: unknown, resp_msg = "ok") => ({
  data: { resp_code: 0, resp_msg, datas },
});
const fail = (resp_msg: string) => ({ data: { resp_code: 500, resp_msg } });

const mountView = () => mount(PreviewView);

const stubHistoryLength = (value: number) =>
  Object.defineProperty(window.history, "length", { configurable: true, get: () => value });

let originalOwnHistoryLength: PropertyDescriptor | undefined;

beforeEach(() => {
  originalOwnHistoryLength = Object.getOwnPropertyDescriptor(window.history, "length");
  routerMocks.route.query = { type: "datasets", iid: "ds-1" };
  routerMocks.replace.mockReset();
  routerMocks.back.mockReset();
  datasetsDownload.mockResolvedValue(ok("https://example.com/datasets/ds-1.zip"));
  modelsDownload.mockResolvedValue(ok("https://example.com/models/m-1.bin"));
});

afterEach(() => {
  if (originalOwnHistoryLength) {
    Object.defineProperty(window.history, "length", originalOwnHistoryLength);
  } else {
    delete (window.history as any).length;
  }
});

describe("PreviewView", () => {
  it("数据集：按 iid 获取下载地址并渲染沙箱 iframe", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(datasetsDownload).toHaveBeenCalledWith({ data: { iid: "ds-1" } });
    expect(modelsDownload).not.toHaveBeenCalled();
    expect(wrapper.find(".preview-title").text()).toBe("数据集下载预览");
    expect(wrapper.find(".preview-overlay").exists()).toBe(false);

    const iframe = wrapper.find("iframe");
    expect(iframe.attributes("src")).toBe("https://example.com/datasets/ds-1.zip");
    expect(iframe.attributes("sandbox")).toBe(
      "allow-scripts allow-forms allow-popups allow-downloads"
    );
  });

  it("模型：走 modelsMng 且标题为模型下载预览", async () => {
    routerMocks.route.query = { type: "models", iid: "m-1" };
    const wrapper = mountView();
    await flushPromises();

    expect(modelsDownload).toHaveBeenCalledWith({ data: { iid: "m-1" } });
    expect(datasetsDownload).not.toHaveBeenCalled();
    expect(wrapper.find(".preview-title").text()).toBe("模型下载预览");
    expect(wrapper.find("iframe").attributes("src")).toBe("https://example.com/models/m-1.bin");
  });

  it("缺少 iid 或 type 时提示参数错误且不请求接口", async () => {
    routerMocks.route.query = { type: "datasets" };
    const missingIid = mountView();
    await flushPromises();
    expect(missingIid.find(".preview-error").text()).toBe("参数错误");
    expect(datasetsDownload).not.toHaveBeenCalled();

    routerMocks.route.query = { iid: "ds-1" };
    const missingType = mountView();
    await flushPromises();
    expect(missingType.find(".preview-error").text()).toBe("参数错误");
    expect(datasetsDownload).not.toHaveBeenCalled();
    expect(modelsDownload).not.toHaveBeenCalled();
  });

  it("错误码展示后端原因，请求异常提示请求失败，均不渲染 iframe", async () => {
    datasetsDownload.mockResolvedValueOnce(fail("链接已过期"));
    const byCode = mountView();
    await flushPromises();
    expect(byCode.find(".preview-error").text()).toBe("链接已过期");
    expect(byCode.find("iframe").exists()).toBe(false);

    datasetsDownload.mockRejectedValueOnce(new Error("network"));
    const byException = mountView();
    await flushPromises();
    expect(byException.find(".preview-error").text()).toBe("请求失败");
    expect(byException.find("iframe").exists()).toBe(false);
  });

  it("返回：有历史记录时回退，直接打开时按类型回列表页", async () => {
    stubHistoryLength(2);
    const withHistory = mountView();
    await flushPromises();
    await withHistory.find(".preview-back").trigger("click");
    expect(routerMocks.back).toHaveBeenCalledTimes(1);
    expect(routerMocks.replace).not.toHaveBeenCalled();

    stubHistoryLength(1);
    const direct = mountView();
    await flushPromises();
    await direct.find(".preview-back").trigger("click");
    expect(routerMocks.back).toHaveBeenCalledTimes(1);
    expect(routerMocks.replace).toHaveBeenCalledWith("/datasets");

    routerMocks.route.query = { type: "models", iid: "m-1" };
    const directModel = mountView();
    await flushPromises();
    await directModel.find(".preview-back").trigger("click");
    expect(routerMocks.replace).toHaveBeenCalledWith("/models");
  });
});
