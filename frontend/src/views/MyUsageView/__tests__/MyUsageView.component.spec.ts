import { afterEach, describe, expect, it, vi, type Mock } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { ElMessage } from "element-plus";

vi.mock("@/api/aiGatewayAdmin", () => ({
  aiGatewayAdminApi: { getMyUsage: vi.fn() },
}));

import MyUsageView from "../index.vue";
import { aiGatewayAdminApi } from "@/api/aiGatewayAdmin";

/**
 * 我的用量：区间统计、配额水位、图表比例、调用明细，
 * 以及快速切换区间时旧响应不覆盖新数据的竞态保护。
 */

enableAutoUnmount(afterEach);

const getMyUsage = aiGatewayAdminApi.getMyUsage as Mock;

const ok = (datas: unknown) => ({ data: { resp_code: 0, resp_msg: "ok", datas } });
const fail = (resp_msg: string) => ({ data: { resp_code: 500, resp_msg } });

const makeUsage = (overrides: Record<string, unknown> = {}) => ({
  usedToday: 1200,
  effectiveQuota: 10000,
  remainingTokens: 8800,
  usagePercent: 12,
  usage: {
    totalCalls: 42,
    successCalls: 40,
    failedCalls: 2,
    totalTokens: 15600,
    avgLatencyMs: 850,
    p95LatencyMs: 1600,
    trend: [
      { date: "2026-10-24", calls: 10, tokens: 3000, failed: 1 },
      { date: "2026-10-25", calls: 20, tokens: 6000, failed: 0 },
      { date: "2026-10-26", calls: 12, tokens: 6600, failed: 1 },
    ],
    modules: [
      { module: "chat", calls: 30, tokens: 12000, failed: 1 },
      { module: "custom", calls: 12, tokens: 3600, failed: 1 },
    ],
  },
  recentLogs: [
    {
      createtime: 1761700000000,
      module: "chat",
      status: "success",
      modelName: "gpt-4o-mini",
      totalTokens: 1500,
      latencyMs: 850,
      errorMessage: null,
    },
    {
      createtime: "2026/10/29 08:05:09",
      module: "knowledgeChat",
      status: "failed",
      modelName: null,
      totalTokens: 0,
      latencyMs: 120,
      errorMessage: "upstream timeout",
    },
    {
      createtime: "",
      module: "file",
      status: "blocked",
      modelName: "",
      totalTokens: 2500000,
      latencyMs: null,
      errorMessage: "",
    },
    {
      createtime: "2026/10/28 09:00:00",
      module: "chat",
      status: "weird",
      modelName: "m",
      totalTokens: 10,
      latencyMs: 20,
      errorMessage: null,
    },
  ],
  ...overrides,
});

const createMessageSpies = () => ({
  success: vi.spyOn(ElMessage, "success").mockReturnValue({ close: () => {} } as never),
  error: vi.spyOn(ElMessage, "error").mockReturnValue({ close: () => {} } as never),
  warning: vi.spyOn(ElMessage, "warning").mockReturnValue({ close: () => {} } as never),
});

const daysBetween = (start: string, end: string) =>
  Math.round(
    (new Date(`${end}T00:00:00`).getTime() - new Date(`${start}T00:00:00`).getTime()) /
      86400000
  );

const mountView = () => mount(MyUsageView);

const chooseRange = async (wrapper: ReturnType<typeof mountView>, days: number) => {
  const button = wrapper
    .findAll(".el-radio-button")
    .find((item) => item.text().includes(`最近 ${days} 天`));
  expect(button, `range ${days}`).toBeTruthy();
  await button!.find("input").setValue(true);
  await flushPromises();
};

describe("MyUsageView", () => {
  it("首屏加载最近 7 天，渲染配额、统计卡片与进度条", async () => {
    getMyUsage.mockResolvedValue(ok(makeUsage()));
    const wrapper = mountView();
    await flushPromises();

    expect(getMyUsage).toHaveBeenCalledTimes(1);
    const args = getMyUsage.mock.calls[0][0];
    expect(daysBetween(args.startDate, args.endDate)).toBe(6);
    expect(wrapper.find(".mu-range-text").text()).toBe(`${args.startDate} ~ ${args.endDate}`);

    const quotaValues = wrapper.findAll(".mu-quota-value").map((item) => item.text());
    expect(quotaValues).toEqual(["1.2K", "10K", "8.8K"]);
    expect(wrapper.find(".mu-quota-percent").text()).toBe("12.0%");
    expect(wrapper.find(".mu-quota-progress .el-progress").classes()).not.toContain(
      "is-warning"
    );

    const cardValues = wrapper.findAll(".mu-card-value").map((item) => item.text());
    expect(cardValues).toEqual(["42", "2", "16K", "850ms"]);
    expect(wrapper.text()).toContain("P95 1.60s");
  });

  it("不限制配额时隐藏剩余与进度条", async () => {
    getMyUsage.mockResolvedValue(
      ok(makeUsage({ effectiveQuota: 0, remainingTokens: 0, usagePercent: 0 }))
    );
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.findAll(".mu-quota-value").map((item) => item.text())).toEqual([
      "1.2K",
      "不限制",
    ]);
    expect(wrapper.find(".mu-quota-progress").exists()).toBe(false);
    expect(wrapper.find(".mu-alert").exists()).toBe(false);
  });

  it("配额水位：80% 警示、用尽后提示拦截", async () => {
    getMyUsage.mockResolvedValue(
      ok(makeUsage({ usedToday: 8500, remainingTokens: 1500, usagePercent: 85 }))
    );
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find(".el-alert").classes()).toContain("el-alert--warning");
    expect(wrapper.find(".el-alert").text()).toContain("超过配额的 80%");
    expect(wrapper.find(".mu-quota-progress .el-progress").classes()).toContain(
      "is-warning"
    );

    getMyUsage.mockResolvedValue(
      ok(makeUsage({ usedToday: 10000, remainingTokens: 0, usagePercent: 100 }))
    );
    const exceeded = mountView();
    await flushPromises();
    expect(exceeded.find(".el-alert").classes()).toContain("el-alert--error");
    expect(exceeded.find(".el-alert").text()).toContain("配额已用完");
    expect(exceeded.find(".mu-quota-progress .el-progress").classes()).toContain(
      "is-exception"
    );
  });

  it("趋势图与模块分布按最大值缩放，未知模块保留原值", async () => {
    getMyUsage.mockResolvedValue(ok(makeUsage()));
    const wrapper = mountView();
    await flushPromises();

    const bars = wrapper.findAll(".trend-bar");
    expect(bars.map((bar) => (bar.element as HTMLElement).style.height)).toEqual([
      "50%",
      "100%",
      "60%",
    ]);
    expect(
      bars.map(
        (bar) => (bar.find(".trend-bar-failed").element as HTMLElement).style.height
      )
    ).toEqual(["10%", "0%", expect.stringMatching(/^8\.3/)]);
    expect(wrapper.findAll(".trend-label").map((item) => item.text())).toEqual([
      "10-24",
      "10-25",
      "10-26",
    ]);

    const moduleItems = wrapper.findAll(".module-item");
    expect(moduleItems[0].text()).toContain("问答聊天");
    expect(moduleItems[0].text().replace(/\s+/g, " ")).toContain(
      "30 次 · 12K tokens · 失败/拦截 1"
    );
    expect(moduleItems[1].text()).toContain("custom");
    expect(
      moduleItems.map((item) => (item.find(".module-bar i").element as HTMLElement).style.width)
    ).toEqual(["100%", "40%"]);
  });

  it("最近调用：状态标签、模块映射与空值回退", async () => {
    getMyUsage.mockResolvedValue(ok(makeUsage()));
    const wrapper = mountView();
    await flushPromises();

    const rows = wrapper.findAll(".el-table__row");
    expect(rows).toHaveLength(4);

    expect(rows[0].text()).toMatch(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/);
    expect(rows[0].text()).toContain("问答聊天");
    expect(rows[0].find(".el-tag").text()).toBe("成功");
    expect(rows[0].find(".el-tag").classes()).toContain("el-tag--success");
    expect(rows[0].text()).toContain("1.5K");

    expect(rows[1].text()).toContain("2026-10-29 08:05:09");
    expect(rows[1].text()).toContain("知识库对话");
    expect(rows[1].find(".el-tag").classes()).toContain("el-tag--danger");
    expect(rows[1].text()).toContain("upstream timeout");

    expect(rows[2].text()).toContain("-");
    expect(rows[2].text()).toContain("知识库文件");
    expect(rows[2].find(".el-tag").classes()).toContain("el-tag--warning");
    expect(rows[2].text()).toContain("2.5M");

    expect(rows[3].find(".el-tag").text()).toBe("weird");
    expect(rows[3].find(".el-tag").classes()).toContain("el-tag--info");
  });

  it("切换区间按新范围重新请求，重复点击同一区间不重复请求", async () => {
    getMyUsage.mockResolvedValue(ok(makeUsage()));
    const wrapper = mountView();
    await flushPromises();

    await chooseRange(wrapper, 30);
    expect(getMyUsage).toHaveBeenCalledTimes(2);
    const args = getMyUsage.mock.calls[1][0];
    expect(daysBetween(args.startDate, args.endDate)).toBe(29);

    await chooseRange(wrapper, 30);
    expect(getMyUsage).toHaveBeenCalledTimes(2);
  });

  it("刷新按钮请求中禁用，完成后恢复", async () => {
    getMyUsage.mockResolvedValueOnce(ok(makeUsage()));
    const wrapper = mountView();
    await flushPromises();

    let resolveRefresh!: (value: unknown) => void;
    getMyUsage.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveRefresh = resolve;
      })
    );
    await wrapper.find(".mu-toolbar .btn").trigger("click");
    expect(wrapper.find(".mu-toolbar .btn").attributes("disabled")).toBeDefined();

    resolveRefresh(ok(makeUsage()));
    await flushPromises();
    expect(wrapper.find(".mu-toolbar .btn").attributes("disabled")).toBeUndefined();
  });

  it("竞态：旧区间的响应后到时不覆盖新数据", async () => {
    let resolveOld!: (value: unknown) => void;
    getMyUsage.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveOld = resolve;
      })
    );
    const wrapper = mountView();
    await flushPromises();

    const fresh = makeUsage({
      usage: { ...makeUsage().usage, totalCalls: 7 },
    });
    getMyUsage.mockResolvedValueOnce(ok(fresh));
    await chooseRange(wrapper, 30);
    expect(wrapper.findAll(".mu-card-value")[0].text()).toBe("7");

    resolveOld(ok(makeUsage()));
    await flushPromises();
    expect(wrapper.findAll(".mu-card-value")[0].text()).toBe("7");
  });

  it("加载失败：错误码优先展示后端原因，请求异常提示稍后重试", async () => {
    const spies = createMessageSpies();
    getMyUsage.mockResolvedValueOnce(fail("统计服务维护中"));
    mountView();
    await flushPromises();
    expect(spies.error).toHaveBeenCalledWith("统计服务维护中");

    getMyUsage.mockRejectedValueOnce(new Error("network"));
    mountView();
    await flushPromises();
    expect(spies.error).toHaveBeenCalledWith("获取用量失败，请稍后重试");
  });
});
