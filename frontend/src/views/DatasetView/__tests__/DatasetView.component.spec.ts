import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { ElLoading, ElMessage, ElMessageBox } from "element-plus";

const mocks = vi.hoisted(() => ({ rid: "99999" }));

vi.mock("js-cookie", () => ({
  default: { get: vi.fn((key: string) => (key === "rid" ? mocks.rid : undefined)) },
}));
vi.mock("@/api/datasetsMng", () => ({
  datasetsMngApi: {
    query: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    getDownloadUrl: vi.fn(),
  },
}));
vi.mock("@/api/tags", () => ({
  tagsApi: { query: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
}));
vi.mock("@/api/tagsbind", () => ({
  tagsBindApi: { query: vi.fn(), create: vi.fn(), delete: vi.fn() },
  default: { query: vi.fn(), create: vi.fn(), delete: vi.fn() },
}));

import DatasetView from "../index.vue";
import { datasetsMngApi } from "@/api/datasetsMng";
import { tagsApi } from "@/api/tags";
import { tagsBindApi } from "@/api/tagsbind";

/**
 * 数据集市场：列表/搜索/排序/标签筛选、超管门控、新建编辑删除与下载预览。
 */

enableAutoUnmount(afterEach);

const query = datasetsMngApi.query as Mock;
const create = datasetsMngApi.create as Mock;
const update = datasetsMngApi.update as Mock;
const remove = datasetsMngApi.delete as Mock;
const getDownloadUrl = datasetsMngApi.getDownloadUrl as Mock;
const tagsQuery = tagsApi.query as Mock;
const bindQuery = tagsBindApi.query as Mock;

const ok = (datas: unknown, resp_msg = "ok") => ({
  data: { resp_code: 0, resp_msg, datas },
});
const fail = (resp_msg: string) => ({ data: { resp_code: 500, resp_msg } });

const datasets = [
  {
    iid: 31,
    name: "文本语料",
    provider: "开源",
    description: "通用中文语料",
    type: "文本",
    size: "1.2 GB",
    volume: "120 万条",
    createtime: "2026-10-04 09:00:00",
    downloadUrl: "https://example.com/corpus.zip",
  },
  {
    iid: 32,
    name: "图像数据",
    provider: "内部",
    description: "标注图片",
    type: "图像",
    size: "800 MB",
    volume: "5 万张",
    createtime: "2026-09-17 09:00:00",
  },
  {
    iid: 33,
    name: "问答对",
    provider: "开源",
    description: "客服问答",
    type: "文本",
    size: "300 KB",
    volume: "2 千条",
    createtime: "2026-08-03 09:00:00",
  },
];

const tags = [
  { iid: 11, name: "语料" },
  { iid: 12, name: "图像" },
];

const bindings = [{ hash: "31", lableBeans: [{ iid: 11, name: "语料" }] }];

const createMessageSpies = () => ({
  success: vi.spyOn(ElMessage, "success").mockReturnValue({ close: () => {} } as never),
  error: vi.spyOn(ElMessage, "error").mockReturnValue({ close: () => {} } as never),
  warning: vi.spyOn(ElMessage, "warning").mockReturnValue({ close: () => {} } as never),
});

const mountView = () => mount(DatasetView);

const cardTitles = (wrapper: ReturnType<typeof mountView>) =>
  wrapper.findAll(".list-card-title").map((item) => item.text());

const clickFooter = async (wrapper: ReturnType<typeof mountView>, text: string) => {
  const button = wrapper
    .findAll(".el-dialog__footer button")
    .find((item) => item.text().includes(text));
  expect(button, `footer ${text}`).toBeTruthy();
  await button!.trigger("click");
  await flushPromises();
};

beforeEach(() => {
  mocks.rid = "99999";
  query.mockResolvedValue(ok([...datasets]));
  tagsQuery.mockResolvedValue(ok(tags));
  bindQuery.mockResolvedValue(ok(bindings));
  getDownloadUrl.mockResolvedValue(ok("https://example.com/corpus.zip"));
});

describe("DatasetView", () => {
  it("首屏加载数据集与标签，按 iid 倒序并展示类型/时间", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(query).toHaveBeenCalledWith({ data: {} });
    expect(tagsQuery).toHaveBeenCalledWith({ data: { type: "datasets" } });
    expect(bindQuery).toHaveBeenCalledWith({ data: { type: "datasets" } });

    expect(cardTitles(wrapper)).toEqual(["问答对", "图像数据", "文本语料"]);
    const cards = wrapper.findAll(".list-card");
    expect(cards[0].text()).toContain("类型：文本");
    expect(cards[0].text()).toContain("创建时间：2026-08-03 09:00:00");
    expect(cards[2].find(".ima-card-tag").text()).toContain("语料");
  });

  it("搜索按名称、描述、提供商匹配且忽略大小写", async () => {
    const wrapper = mountView();
    await flushPromises();

    const input = wrapper.find('input[placeholder="搜索数据集..."]');
    await input.setValue("问答");
    expect(cardTitles(wrapper)).toEqual(["问答对"]);

    await input.setValue("内部");
    expect(cardTitles(wrapper)).toEqual(["图像数据"]);

    await input.setValue("语料");
    expect(cardTitles(wrapper)).toEqual(["文本语料"]);

    await input.setValue("不存在");
    expect(cardTitles(wrapper)).toEqual([]);
  });

  it("排序与标签筛选：创建时间/文件大小升降序、按标签过滤", async () => {
    const wrapper = mountView();
    await flushPromises();

    (wrapper.vm as any).sortBy = "createtime";
    await flushPromises();
    expect(cardTitles(wrapper)).toEqual(["文本语料", "图像数据", "问答对"]);

    (wrapper.vm as any).sortBy = "createtime_asc";
    await flushPromises();
    expect(cardTitles(wrapper)).toEqual(["问答对", "图像数据", "文本语料"]);

    (wrapper.vm as any).sortBy = "size";
    await flushPromises();
    expect(cardTitles(wrapper)).toEqual(["文本语料", "图像数据", "问答对"]);

    (wrapper.vm as any).sortBy = "size_asc";
    await flushPromises();
    expect(cardTitles(wrapper)).toEqual(["问答对", "图像数据", "文本语料"]);

    (wrapper.vm as any).sortBy = "";
    (wrapper.vm as any).selectedCategory = ["11"];
    await flushPromises();
    expect(cardTitles(wrapper)).toEqual(["文本语料"]);
  });

  it("普通用户看不到新建/修改/删除，只能下载与查看标签", async () => {
    mocks.rid = "2";
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.text()).not.toContain("新建数据集");
    expect(wrapper.find(".action-btn-edit").exists()).toBe(false);
    expect(wrapper.find(".action-btn-delete").exists()).toBe(false);
    expect(wrapper.find(".action-btn-download").exists()).toBe(true);
    expect(wrapper.find(".tag-dropdown").exists()).toBe(false);
    expect(wrapper.findAll(".ima-card-tag")[0].text()).toContain("语料");
  });

  it("新建数据集：必填拦截，提交后关闭弹窗并刷新列表", async () => {
    const spies = createMessageSpies();
    create.mockResolvedValue(ok(true));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find(".btn-primary").trigger("click");
    await flushPromises();
    await clickFooter(wrapper, "确 定");
    expect(create).not.toHaveBeenCalled();
    expect(wrapper.find(".el-dialog .el-form-item.is-error").exists()).toBe(true);

    await wrapper.find('input[placeholder="请输入名称"]').setValue("新数据集");
    await wrapper.find('input[placeholder="请输入类型"]').setValue("文本");
    await wrapper.find('input[placeholder="请输入描述"]').setValue("描述文本");
    await clickFooter(wrapper, "确 定");

    expect(create).toHaveBeenCalledWith({
      data: {
        name: "新数据集",
        type: "文本",
        description: "描述文本",
        volume: "",
        size: "",
        downloadUrl: "",
      },
    });
    expect(spies.success).toHaveBeenCalledWith("保存成功");
    expect((wrapper.vm as any).state.params.dialogVisible).toBe(false);
    expect(query).toHaveBeenCalledTimes(2);
  });

  it("编辑数据集：回填并按 iid 更新", async () => {
    createMessageSpies();
    update.mockResolvedValue(ok(true));
    const wrapper = mountView();
    await flushPromises();

    await wrapper.findAll(".list-card")[2].find(".action-btn-edit").trigger("click");
    await flushPromises();
    expect(
      (wrapper.find('input[placeholder="请输入名称"]').element as HTMLInputElement).value
    ).toBe("文本语料");

    await wrapper.find('input[placeholder="请输入名称"]').setValue("文本语料 PRO");
    await clickFooter(wrapper, "确 定");

    expect(update).toHaveBeenCalledWith({
      data: expect.objectContaining({ iid: 31, name: "文本语料 PRO" }),
      where: { iid: 31 },
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

    await wrapper.findAll(".list-card")[2].find(".action-btn-delete").trigger("click");
    await flushPromises();
    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining("文本语料"),
      "确认删除",
      expect.objectContaining({ type: "warning" })
    );
    expect(remove).toHaveBeenCalledWith({ data: { iid: 31 } });
    expect(spies.success).toHaveBeenCalledWith("删除成功");
    expect(query).toHaveBeenCalledTimes(2);

    confirm.mockRejectedValueOnce("cancel");
    await wrapper.findAll(".list-card")[1].find(".action-btn-delete").trigger("click");
    await flushPromises();
    expect(remove).toHaveBeenCalledTimes(1);
    expect(spies.error).not.toHaveBeenCalled();

    confirm.mockResolvedValueOnce("confirm" as never);
    remove.mockResolvedValueOnce(fail("数据集被引用"));
    await wrapper.findAll(".list-card")[1].find(".action-btn-delete").trigger("click");
    await flushPromises();
    expect(spies.error).toHaveBeenCalledWith("数据集被引用");

    confirm.mockResolvedValueOnce("confirm" as never);
    remove.mockRejectedValueOnce(new Error("network"));
    await wrapper.findAll(".list-card")[1].find(".action-btn-delete").trigger("click");
    await flushPromises();
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
    await wrapper.find('input[placeholder="请输入名称"]').setValue("新数据集");
    await wrapper.find('input[placeholder="请输入类型"]').setValue("文本");
    await wrapper.find('input[placeholder="请输入描述"]').setValue("描述");
    await clickFooter(wrapper, "确 定");

    expect(spies.error).toHaveBeenCalledWith("保存失败，请稍后重试");
    expect((wrapper.vm as any).state.params.dialogVisible).toBe(true);
    expect(close).toHaveBeenCalled();

    create.mockRejectedValueOnce(new Error("network"));
    await clickFooter(wrapper, "确 定");
    expect(spies.error).toHaveBeenCalledTimes(2);
    expect(close).toHaveBeenCalledTimes(2);
  });

  it("下载：进入预览面板，按 iid 取地址后返回列表", async () => {
    const wrapper = mountView();
    await flushPromises();

    await wrapper.findAll(".list-card")[2].find(".action-btn-download").trigger("click");
    await flushPromises();

    expect(getDownloadUrl).toHaveBeenCalledWith({ data: { iid: 31 } });
    expect(wrapper.find(".repo-preview-panel").exists()).toBe(true);
    expect(wrapper.find(".repo-preview-title h3").text()).toBe("数据集：文本语料");
    expect(wrapper.find(".repo-preview-panel").text()).toContain("该资源暂不支持图形列表展示");
    expect(wrapper.find(".repo-preview-panel").text()).toContain("https://example.com/corpus.zip");

    await wrapper.find(".repo-preview-back").trigger("click");
    await flushPromises();
    expect(wrapper.find(".repo-preview-panel").exists()).toBe(false);
    expect(cardTitles(wrapper)).toHaveLength(3);
  });
});
