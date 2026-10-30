import { afterEach, describe, expect, it, vi, type Mock } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";

vi.mock("@/api/kbRag", () => ({
  kbRagApi: {
    listDocs: vi.fn(),
    uploadDoc: vi.fn(),
    deleteDoc: vi.fn(),
    search: vi.fn(),
  },
}));

import KbSearchView from "../index.vue";
import { kbRagApi } from "@/api/kbRag";

/**
 * 知识检索页：文档列表、上传、删除、检索结果渲染
 * 以及 skipped/warning 降级提示。API 全部 mock。
 */

enableAutoUnmount(afterEach);

const listDocs = kbRagApi.listDocs as Mock;
const uploadDoc = kbRagApi.uploadDoc as Mock;
const deleteDoc = kbRagApi.deleteDoc as Mock;
const search = kbRagApi.search as Mock;

const ok = (datas: unknown, resp_msg = "ok") => ({
  data: { resp_code: 0, resp_msg, datas },
});
const fail = (resp_msg: string) => ({ data: { resp_code: 500, resp_msg } });

const docs = [
  {
    iid: 1,
    name: "部署手册",
    kbName: "默认知识库",
    chunkCount: 12,
    charCount: 3400,
    createtime: "2026/10/20 08:05:00",
  },
  {
    iid: 2,
    name: "FAQ",
    kbName: "产品库",
    chunkCount: 3,
    charCount: 800,
    createtime: null,
  },
];

const searchResult = {
  model: "bge-m3",
  scanned: 15,
  skipped: 2,
  warning: "有 2 个分块因向量维度不一致被跳过，请重新上传文档",
  items: [
    { docName: "部署手册", chunkIndex: 0, score: 0.8765, content: "先安装 JDK 8" },
    { docName: "", chunkIndex: 2, score: 0.35, content: "端口默认 8080" },
    { docName: "FAQ", chunkIndex: 4, score: 0.2, content: "如何重置密码" },
  ],
};

const createMessageSpies = () => ({
  success: vi.spyOn(ElMessage, "success").mockReturnValue({ close: () => {} } as never),
  error: vi.spyOn(ElMessage, "error").mockReturnValue({ close: () => {} } as never),
  warning: vi.spyOn(ElMessage, "warning").mockReturnValue({ close: () => {} } as never),
  info: vi.spyOn(ElMessage, "info").mockReturnValue({ close: () => {} } as never),
});

const mountView = () => mount(KbSearchView);

const clickByText = async (
  scope: { findAll: (selector: string) => Array<{ text: () => string; trigger: (event: string) => Promise<void> }> },
  selector: string,
  text: string
) => {
  const target = scope.findAll(selector).find((item) => item.text().includes(text));
  expect(target, `${selector} ${text}`).toBeTruthy();
  await target!.trigger("click");
  await flushPromises();
  await nextTick();
};

const setFile = async (
  wrapper: ReturnType<typeof mountView>,
  file: File
) => {
  const input = wrapper.find('input[type="file"]');
  Object.defineProperty(input.element, "files", {
    value: [file],
    configurable: true,
  });
  await input.trigger("change");
};

describe("KbSearchView", () => {
  it("首屏渲染文档列表；空库显示引导空态", async () => {
    listDocs.mockResolvedValue(ok(docs));
    const wrapper = mountView();
    await flushPromises();

    const rows = wrapper.findAll(".el-table__row");
    expect(rows).toHaveLength(2);
    expect(rows[0].text()).toContain("部署手册");
    expect(rows[0].text()).toContain("默认知识库");
    expect(rows[0].text()).toContain("12");
    expect(rows[0].text()).toContain("3400");
    expect(rows[0].text()).toContain("2026-10-20 08:05");
    expect(rows[1].text()).toContain("-");

    listDocs.mockResolvedValue(ok([]));
    const emptyWrapper = mountView();
    await flushPromises();
    expect(emptyWrapper.find(".kb-empty").text()).toContain("还没有文档");
  });

  it("文档列表失败：错误码与请求异常分别提示", async () => {
    const spies = createMessageSpies();
    listDocs.mockResolvedValueOnce(fail("查询超时"));
    mountView();
    await flushPromises();
    expect(spies.error).toHaveBeenCalledWith("查询超时");

    listDocs.mockRejectedValueOnce(new Error("network"));
    mountView();
    await flushPromises();
    expect(spies.error).toHaveBeenCalledWith("获取文档列表失败，请稍后重试");
  });

  it("检索成功：展示计数、相似度标签、块号与降级提示", async () => {
    listDocs.mockResolvedValue(ok([]));
    search.mockResolvedValue(ok(searchResult));
    const wrapper = mountView();
    await flushPromises();

    (wrapper.vm as any).state.searchForm.query = "  如何部署  ";
    (wrapper.vm as any).state.searchForm.topK = 10;
    await clickByText(wrapper, "button", "检索");

    expect(search).toHaveBeenCalledWith({ query: "如何部署", topK: 10 });
    expect(wrapper.text()).toContain("命中 3 / 扫描 15 块 · bge-m3");
    expect(wrapper.find(".kb-warning").text()).toContain("向量维度不一致");

    const hits = wrapper.findAll(".kb-hit");
    expect(hits).toHaveLength(3);
    expect(hits[0].text()).toContain("相似度 0.8765");
    expect(hits[0].text()).toContain("部署手册 · 第 1 块");
    expect(hits[0].find(".el-tag").classes()).toContain("el-tag--success");
    expect(hits[1].text()).toContain("未知文档 · 第 3 块");
    expect(hits[1].find(".el-tag").classes()).toContain("el-tag--warning");
    expect(hits[2].find(".el-tag").classes()).toContain("el-tag--info");
    expect(search).toHaveBeenCalledTimes(1);
  });

  it("检索校验与失败路径：空内容拦截，错误码与异常分别提示", async () => {
    const spies = createMessageSpies();
    listDocs.mockResolvedValue(ok([]));
    search.mockResolvedValueOnce(fail("向量服务不可用"));
    const wrapper = mountView();
    await flushPromises();

    await clickByText(wrapper, "button", "检索");
    expect(spies.warning).toHaveBeenCalledWith("请输入检索内容");
    expect(search).not.toHaveBeenCalled();

    (wrapper.vm as any).state.searchForm.query = "部署";
    await clickByText(wrapper, "button", "检索");
    expect(spies.error).toHaveBeenCalledWith("向量服务不可用");

    search.mockRejectedValueOnce(new Error("network"));
    await clickByText(wrapper, "button", "检索");
    expect(spies.error).toHaveBeenCalledWith("检索失败，请稍后重试");
    expect(wrapper.find(".kb-search-actions .btn").text()).toContain("检索");
  });

  it("上传：名称与内容必填，成功后按默认知识库提交并刷新列表", async () => {
    const spies = createMessageSpies();
    listDocs.mockResolvedValue(ok([]));
    uploadDoc.mockResolvedValue(ok(true, "文档已入库（4 块）"));
    const wrapper = mountView();
    await flushPromises();

    await clickByText(wrapper, "button", "上传文档");
    await clickByText(wrapper, ".el-dialog__footer button", "上传并向量化");
    expect(spies.warning).toHaveBeenCalledWith("请填写文档名称");

    (wrapper.vm as any).state.uploadForm.name = "  部署手册  ";
    await clickByText(wrapper, ".el-dialog__footer button", "上传并向量化");
    expect(spies.warning).toHaveBeenCalledWith("请选择文件或粘贴文档内容");

    (wrapper.vm as any).state.uploadForm.content = "正文内容";
    (wrapper.vm as any).state.uploadForm.kbName = "";
    await clickByText(wrapper, ".el-dialog__footer button", "上传并向量化");

    expect(uploadDoc).toHaveBeenCalledWith({
      name: "部署手册",
      kbName: "默认知识库",
      content: "正文内容",
    });
    expect(spies.success).toHaveBeenCalledWith("文档已入库（4 块）");
    expect((wrapper.vm as any).state.uploadDialogVisible).toBe(false);
    expect(listDocs).toHaveBeenCalledTimes(2);
  });

  it("上传失败：错误码提示后端原因，异常提示稍后重试", async () => {
    const spies = createMessageSpies();
    listDocs.mockResolvedValue(ok([]));
    uploadDoc.mockResolvedValueOnce(fail("向量化服务离线"));
    const wrapper = mountView();
    await flushPromises();

    (wrapper.vm as any).state.uploadDialogVisible = true;
    await nextTick();
    (wrapper.vm as any).state.uploadForm.name = "部署手册";
    (wrapper.vm as any).state.uploadForm.content = "正文";
    await clickByText(wrapper, ".el-dialog__footer button", "上传并向量化");
    expect(spies.error).toHaveBeenCalledWith("向量化服务离线");
    expect((wrapper.vm as any).state.uploadForm.name).toBe("部署手册");

    uploadDoc.mockRejectedValueOnce(new Error("network"));
    await clickByText(wrapper, ".el-dialog__footer button", "上传并向量化");
    expect(spies.error).toHaveBeenCalledWith("上传失败，请稍后重试");
  });

  it("删除：确认后按 iid 删除并刷新；取消不调用；接口异常给出提示", async () => {
    const spies = createMessageSpies();
    listDocs.mockResolvedValue(ok(docs));
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
    deleteDoc.mockResolvedValue(ok(true));
    const wrapper = mountView();
    await flushPromises();

    await clickByText(wrapper.findAll(".el-table__row")[0], "button", "删除");
    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining("部署手册"),
      "删除确认",
      expect.objectContaining({ type: "warning" })
    );
    expect(deleteDoc).toHaveBeenCalledWith(1);
    expect(spies.success).toHaveBeenCalledWith("删除成功");
    expect(listDocs).toHaveBeenCalledTimes(2);

    confirm.mockRejectedValueOnce("cancel");
    await clickByText(wrapper.findAll(".el-table__row")[1], "button", "删除");
    expect(deleteDoc).toHaveBeenCalledTimes(1);

    confirm.mockResolvedValueOnce("confirm" as never);
    deleteDoc.mockRejectedValueOnce(new Error("network"));
    await clickByText(wrapper.findAll(".el-table__row")[1], "button", "删除");
    expect(spies.error).toHaveBeenCalledWith("删除失败，请稍后重试");
  });

  it("选择文本文件自动载入内容与名称；超过 20MB 直接拒绝", async () => {
    const spies = createMessageSpies();
    listDocs.mockResolvedValue(ok([]));
    const wrapper = mountView();
    await flushPromises();
    (wrapper.vm as any).state.uploadDialogVisible = true;
    await nextTick();

    const file = new File(["# 部署\n先安装 JDK 8"], "deploy.md", {
      type: "text/markdown",
    });
    await setFile(wrapper, file);
    await vi.waitFor(() => {
      expect((wrapper.vm as any).state.uploadForm.content).toContain("先安装 JDK 8");
    });
    expect((wrapper.vm as any).state.uploadForm.name).toBe("deploy.md");

    const huge = new File(["x"], "huge.txt", { type: "text/plain" });
    Object.defineProperty(huge, "size", { value: 21 * 1024 * 1024 });
    await setFile(wrapper, huge);
    expect(spies.warning).toHaveBeenCalledWith("文件过大，单篇最多 20MB");
    expect((wrapper.vm as any).state.uploadForm.content).toContain("先安装 JDK 8");
  });
});
