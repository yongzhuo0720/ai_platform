import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { ElMessage } from "element-plus";

vi.mock("@/api/log", () => ({
  logApi: { getLogList: vi.fn() },
}));

import LogView from "../index.vue";
import { logApi } from "@/api/log";

/**
 * 操作日志页：列表渲染、日期筛选与分页参数、半开区间拦截、失败提示。
 * API 全部 mock。
 */

enableAutoUnmount(afterEach);

const ok = (datas: unknown) => ({ data: { resp_code: 0, resp_msg: "ok", datas } } as never);
const fail = (msg: string) => ({ data: { resp_code: -1, resp_msg: msg } } as never);

/** 用本地时间构造，避免用例依赖机器时区 */
const at = (y: number, m: number, d: number, hh = 10, mm = 0, ss = 0) =>
  new Date(y, m - 1, d, hh, mm, ss).getTime();

const logs = {
  content: [
    {
      iid: 1,
      action: "登录系统",
      url: "/ai/api/aiGatewayAdmin/logs",
      clientip: "10.0.0.8",
      res: "成功",
      time: 120,
      uaccount: "zhangsan",
      createtime: at(2026, 10, 1, 10, 0, 0),
    },
    {
      iid: 2,
      action: "删除知识库",
      url: "/ai/api/knowledgeBaseBean/delete",
      clientip: "10.0.0.9",
      res: "",
      time: 35,
      uaccount: "wangwu",
      createtime: at(2026, 10, 2, 11, 30, 5),
    },
  ],
  totalSize: 2,
};

const mountView = () => mount(LogView, { global: { stubs: { Search: true } } });

const createMessageSpies = () => ({
  error: vi.spyOn(ElMessage, "error").mockReturnValue({ close: () => {} } as never),
  warning: vi.spyOn(ElMessage, "warning").mockReturnValue({ close: () => {} } as never),
});

beforeEach(() => {
  vi.mocked(logApi.getLogList).mockResolvedValue(ok(logs));
});

describe("LogView", () => {
  it("挂载后加载日志列表，展示操作、接口、耗时、账号与格式化时间", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(logApi.getLogList).toHaveBeenCalledTimes(1);
    expect(logApi.getLogList).toHaveBeenCalledWith(
      expect.objectContaining({ pageNum: 1, pageSize: 20 })
    );
    // 未选日期时不传空字符串，否则后端会当成非法区间
    expect(vi.mocked(logApi.getLogList).mock.calls[0][0]).not.toHaveProperty("startDate", "");

    const rows = wrapper.findAll(".el-table__row");
    expect(rows).toHaveLength(2);
    expect(rows[0].text()).toContain("登录系统");
    expect(rows[0].text()).toContain("/ai/api/aiGatewayAdmin/logs");
    expect(rows[0].text()).toContain("10.0.0.8");
    expect(rows[0].text()).toContain("120 ms");
    expect(rows[0].text()).toContain("zhangsan");
    expect(rows[0].text()).toContain("2026-10-01 10:00:00");
    expect(rows[1].text()).toContain("删除知识库");
    expect(rows[1].text()).toContain("35 ms");
    expect(rows[1].text()).toContain("2026-10-02 11:30:05");
    expect(wrapper.find(".el-pagination").exists()).toBe(true);
  });

  it("时间筛选后搜索：回到第一页并带上起止日期", async () => {
    const wrapper = mountView();
    await flushPromises();

    (wrapper.vm as any).state.formInline.timeS = "2026-10-01";
    (wrapper.vm as any).state.formInline.timeE = "2026-10-02";
    (wrapper.vm as any).handleCurrentChange(3);
    await flushPromises();

    const searchButton = wrapper
      .findAll("button")
      .find((button) => button.text().includes("搜索"));
    await searchButton!.trigger("click");
    await flushPromises();

    expect(logApi.getLogList).toHaveBeenLastCalledWith(
      expect.objectContaining({
        startDate: "2026-10-01",
        endDate: "2026-10-02",
        pageNum: 1,
      })
    );
  });

  it("只选一头日期：提示并拦住请求，不发半开区间", async () => {
    const { warning } = createMessageSpies();
    const wrapper = mountView();
    await flushPromises();
    expect(logApi.getLogList).toHaveBeenCalledTimes(1);

    (wrapper.vm as any).state.formInline.timeS = "2026-10-01";
    (wrapper.vm as any).onQuery();
    await flushPromises();

    expect(warning).toHaveBeenCalledWith("请同时选择开始日期与结束日期");
    expect(logApi.getLogList).toHaveBeenCalledTimes(1);
  });

  it("分页尺寸与页码变更会重新请求", async () => {
    const wrapper = mountView();
    await flushPromises();

    (wrapper.vm as any).handleSizeChange(50);
    await flushPromises();
    expect(logApi.getLogList).toHaveBeenLastCalledWith(
      expect.objectContaining({ pageNum: 1, pageSize: 50 })
    );

    (wrapper.vm as any).handleCurrentChange(4);
    await flushPromises();
    expect(logApi.getLogList).toHaveBeenLastCalledWith(
      expect.objectContaining({ pageNum: 4, pageSize: 50 })
    );
  });

  it("后端拒绝时透出原因；请求异常给通用提示", async () => {
    const { error } = createMessageSpies();

    vi.mocked(logApi.getLogList).mockResolvedValueOnce(fail("仅管理员可查看操作日志"));
    mountView();
    await flushPromises();
    expect(error).toHaveBeenCalledWith("仅管理员可查看操作日志");

    vi.mocked(logApi.getLogList).mockRejectedValueOnce(new Error("network"));
    mountView();
    await flushPromises();
    expect(error).toHaveBeenCalledWith("获取日志列表失败，请稍后重试");
  });

  it("返回体缺 content 时按空列表处理，不让页面崩", async () => {
    vi.mocked(logApi.getLogList).mockResolvedValueOnce(ok({}));
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.findAll(".el-table__row")).toHaveLength(0);
    expect((wrapper.vm as any).state.pagination.total).toBe(0);
  });
});
