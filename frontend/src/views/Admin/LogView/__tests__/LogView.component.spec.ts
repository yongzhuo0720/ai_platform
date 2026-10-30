import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { ElMessage } from "element-plus";

vi.mock("@/api/log", () => ({
  logApi: { getLogList: vi.fn() },
}));

import LogView from "../index.vue";
import { logApi } from "@/api/log";

/**
 * 操作日志页：列表渲染、时间筛选与分页参数、失败提示。
 * API 全部 mock。
 */

enableAutoUnmount(afterEach);

const ok = (datas: unknown) => ({ data: { resp_code: 0, resp_msg: "ok", datas } } as never);
const fail = (msg: string) => ({ data: { resp_code: 500, resp_msg: msg } } as never);

const logs = {
  content: [
    {
      iid: 1,
      action: "登录系统",
      clientip: "10.0.0.8",
      res: "成功",
      time: 120,
      user: "张三",
      uaccount: "zhangsan",
      createtime: "2026-10-01 10:00:00",
    },
    {
      iid: 2,
      action: "删除知识库",
      clientip: "10.0.0.9",
      res: "失败",
      time: 35,
      user: "王五",
      uaccount: "wangwu",
      createtime: "2026-10-02 11:00:00",
    },
  ],
  totalSize: 2,
};

const mountView = () =>
  mount(LogView, { global: { stubs: { Search: true } } });

const createMessageSpies = () => ({
  error: vi.spyOn(ElMessage, "error").mockReturnValue({ close: () => {} } as never),
});

beforeEach(() => {
  vi.mocked(logApi.getLogList).mockResolvedValue(ok(logs));
});

describe("LogView", () => {
  it("挂载后加载日志列表，展示操作、耗时与操作者", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(logApi.getLogList).toHaveBeenCalledTimes(1);
    expect(logApi.getLogList).toHaveBeenCalledWith(
      expect.objectContaining({
        data: {},
        timeS: null,
        timeE: null,
        pageRequest: expect.objectContaining({ pageNum: 1, pageSize: 20 }),
      })
    );

    const rows = wrapper.findAll(".el-table__row");
    expect(rows).toHaveLength(2);
    expect(rows[0].text()).toContain("登录系统");
    expect(rows[0].text()).toContain("10.0.0.8");
    expect(rows[0].text()).toContain("120 ms");
    expect(rows[0].text()).toContain("zhangsan");
    expect(rows[1].text()).toContain("删除知识库");
    expect(rows[1].text()).toContain("35 ms");
    expect(wrapper.find(".el-pagination").exists()).toBe(true);
  });

  it("时间筛选后搜索：回到第一页并带上起止时间", async () => {
    const wrapper = mountView();
    await flushPromises();

    (wrapper.vm as any).state.formInline.timeS = "2026-10-01 00:00:00";
    (wrapper.vm as any).state.formInline.timeE = "2026-10-02 23:59:59";
    (wrapper.vm as any).handleCurrentChange(3);
    await flushPromises();

    const searchButton = wrapper
      .findAll("button")
      .find((button) => button.text().includes("搜索"));
    await searchButton!.trigger("click");
    await flushPromises();

    expect(logApi.getLogList).toHaveBeenLastCalledWith(
      expect.objectContaining({
        timeS: "2026-10-01 00:00:00",
        timeE: "2026-10-02 23:59:59",
        pageRequest: expect.objectContaining({ pageNum: 1 }),
      })
    );
  });

  it("分页尺寸与页码变更会重新请求", async () => {
    const wrapper = mountView();
    await flushPromises();

    (wrapper.vm as any).handleSizeChange(50);
    await flushPromises();
    expect(logApi.getLogList).toHaveBeenLastCalledWith(
      expect.objectContaining({
        pageRequest: expect.objectContaining({ pageNum: 1, pageSize: 50 }),
      })
    );

    (wrapper.vm as any).handleCurrentChange(4);
    await flushPromises();
    expect(logApi.getLogList).toHaveBeenLastCalledWith(
      expect.objectContaining({
        pageRequest: expect.objectContaining({ pageNum: 4, pageSize: 50 }),
      })
    );
  });

  it("列表失败：错误码与请求异常分别给出提示", async () => {
    const errorSpy = createMessageSpies().error;

    vi.mocked(logApi.getLogList).mockResolvedValueOnce(fail("db down"));
    mountView();
    await flushPromises();
    expect(errorSpy).toHaveBeenCalledWith("获取日志列表失败");

    vi.mocked(logApi.getLogList).mockRejectedValueOnce(new Error("network"));
    mountView();
    await flushPromises();
    expect(errorSpy).toHaveBeenCalledWith("获取日志列表失败，请稍后重试");
  });
});
