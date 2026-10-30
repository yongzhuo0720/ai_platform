import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { nextTick } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";

vi.mock("@/api/aiGatewayAdmin", () => ({
  aiGatewayAdminApi: {
    getConfig: vi.fn(),
    getSummary: vi.fn(),
    getLogs: vi.fn(),
    exportLogs: vi.fn(),
    getQuotas: vi.fn(),
    saveQuota: vi.fn(),
    deleteQuota: vi.fn(),
    getUsers: vi.fn(),
  },
}));
vi.mock("@/api/weeklyReport", () => ({
  weeklyReportApi: { list: vi.fn(), generate: vi.fn() },
}));

import AiGatewayView from "../index.vue";
import { aiGatewayAdminApi } from "@/api/aiGatewayAdmin";
import { weeklyReportApi } from "@/api/weeklyReport";

/**
 * AI 调用治理后台：概览卡片/图表比例、日志筛选与导出、配额增删改、
 * 周报生成。API 全部 mock，验证模板接线与格式化函数。
 */

enableAutoUnmount(afterEach);

const ok = (datas: unknown, respMsg = "ok") => ({
  data: { resp_code: 0, resp_msg: respMsg, datas },
} as never);
const fail = (msg: string) => ({ data: { resp_code: 500, resp_msg: msg } } as never);

const config = {
  enabled: true,
  rateLimitPerMinute: 30,
  defaultDailyTokenQuota: 1000000,
  modules: [
    { module: "chat", moduleName: "问答聊天", configured: true },
    { module: "models", moduleName: "模型直连", configured: false },
  ],
};

const summary = {
  totalCalls: 1200,
  successCalls: 1100,
  failedCalls: 50,
  blockedCalls: 50,
  totalTokens: 1234567,
  avgLatencyMs: 850,
  p95LatencyMs: 2400,
  avgFirstTokenMs: 320,
  successRate: 91.7,
  truncated: true,
  totalCostCents: 123456,
  hasUnpricedModels: true,
  trend: [
    { date: "2026-10-24", calls: 100, tokens: 1000, failed: 10 },
    { date: "2026-10-25", calls: 200, tokens: 2000, failed: 0 },
  ],
  modules: [
    { module: "chat", calls: 800, tokens: 9000, failed: 20 },
    { module: "models", calls: 200, tokens: 1000, failed: 0 },
  ],
  models: [
    {
      model: "qwen-max",
      calls: 600,
      tokens: 5000,
      failed: 5,
      avgLatencyMs: 700,
      priced: true,
      costCents: 100000,
    },
    {
      model: "gpt-4o-mini",
      calls: 100,
      tokens: 500,
      failed: 0,
      avgLatencyMs: 400,
      priced: false,
      costCents: 0,
    },
  ],
  topUsers: [{ userId: "u1", userName: "张三", calls: 300, tokens: 3000 }],
};

const logs = [
  {
    iid: 1,
    userId: "u1",
    module: "chat",
    endpoint: "/v1/chat-messages",
    method: "POST",
    status: "failed",
    httpStatus: 500,
    promptTokens: 1000,
    completionTokens: 200,
    totalTokens: 1200,
    latencyMs: 2400,
    modelName: null,
    conversationId: null,
    messageId: null,
    errorMessage: "upstream timeout",
    clientIp: "127.0.0.1",
    createtime: "2026/10/30 12:00:00",
  },
];

const quotas = {
  defaultDailyTokenQuota: 500000,
  items: [
    {
      userId: "u1",
      userName: "张三",
      dailyTokenQuota: null,
      effectiveQuota: 500000,
      usedToday: 400000,
      remark: null,
      custom: false,
    },
    {
      userId: "u2",
      userName: "李四",
      dailyTokenQuota: 0,
      effectiveQuota: 0,
      usedToday: 0,
      remark: "部门共享池",
      custom: true,
    },
    {
      userId: "u3",
      userName: "王五",
      dailyTokenQuota: 100000,
      effectiveQuota: 100000,
      usedToday: 100000,
      remark: null,
      custom: true,
    },
  ],
};

// Search / Download / Plus / Refresh 由主入口全局注册，测试里用空桩占位
const mountView = () =>
  mount(AiGatewayView, {
    global: { stubs: { Search: true, Download: true, Plus: true, Refresh: true } },
  });

const setupSuccess = () => {
  vi.mocked(aiGatewayAdminApi.getConfig).mockResolvedValue(ok(config));
  vi.mocked(aiGatewayAdminApi.getSummary).mockResolvedValue(ok(summary));
  vi.mocked(aiGatewayAdminApi.getLogs).mockResolvedValue(
    ok({ content: logs, totalSize: 1 })
  );
  vi.mocked(aiGatewayAdminApi.getQuotas).mockResolvedValue(ok(quotas));
  vi.mocked(aiGatewayAdminApi.getUsers).mockResolvedValue(
    ok([{ uid: "u1", uaccess: "zhangsan", uname: "张三" }])
  );
  vi.mocked(aiGatewayAdminApi.saveQuota).mockResolvedValue(ok(true, "配额已保存"));
  vi.mocked(aiGatewayAdminApi.deleteQuota).mockResolvedValue(ok(true));
  vi.mocked(weeklyReportApi.list).mockResolvedValue(ok([]));
  vi.mocked(weeklyReportApi.generate).mockResolvedValue(ok(true, "周报已生成"));
};

/**
 * 每个用例只调用一次：对同一方法二次 spyOn 会丢弃第一次的引用，
 * 后续断言拿到的 mock 永远收不到调用记录。
 */
const createMessageSpies = () => ({
  success: vi.spyOn(ElMessage, "success").mockReturnValue({ close: () => {} } as never),
  error: vi.spyOn(ElMessage, "error").mockReturnValue({ close: () => {} } as never),
  warning: vi.spyOn(ElMessage, "warning").mockReturnValue({ close: () => {} } as never),
});

const clickTab = async (wrapper: ReturnType<typeof mountView>, label: string) => {
  const tab = wrapper.findAll(".el-tabs__item").find((t) => t.text() === label);
  expect(tab, `tab ${label}`).toBeTruthy();
  await tab!.trigger("click");
  await flushPromises();
  await nextTick();
};

const cardText = (wrapper: ReturnType<typeof mountView>, label: string) => {
  const card = wrapper.findAll(".gw-card").find((c) => c.text().includes(label));
  expect(card, `card ${label}`).toBeTruthy();
  return card!.text();
};

const mondayOfThisWeek = () => {
  const now = new Date();
  const day = now.getDay() === 0 ? 7 : now.getDay();
  const monday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - day + 1);
  const mm = String(monday.getMonth() + 1).padStart(2, "0");
  const dd = String(monday.getDate()).padStart(2, "0");
  return `${monday.getFullYear()}-${mm}-${dd}`;
};

beforeEach(() => {
  setupSuccess();
  // jsdom 未实现 Blob URL，导出功能需要这两个方法存在
  Object.defineProperty(URL, "createObjectURL", {
    value: vi.fn(() => "blob:mock"),
    configurable: true,
    writable: true,
  });
  Object.defineProperty(URL, "revokeObjectURL", {
    value: vi.fn(),
    configurable: true,
    writable: true,
  });
});

describe("AiGatewayView", () => {
  it("挂载后加载配置/用量/周报，并展示网关状态与模块配置情况", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(aiGatewayAdminApi.getConfig).toHaveBeenCalledTimes(1);
    expect(aiGatewayAdminApi.getSummary).toHaveBeenCalledTimes(1);
    expect(weeklyReportApi.list).toHaveBeenCalledTimes(1);

    expect(wrapper.find(".status-dot").classes()).toContain("on");
    expect(wrapper.find(".gw-status").text()).toContain("网关运行中");
    expect(wrapper.find(".gw-status").text()).toContain("限流 30 次/分钟");
    expect(wrapper.find(".gw-status").text()).toContain("默认配额 1.0M tokens/日");

    const chips = wrapper.findAll(".gw-module-chip");
    expect(chips).toHaveLength(2);
    expect(chips[0].classes()).toContain("ok");
    expect(chips[0].text()).toContain("问答聊天 · 已配置");
    expect(chips[1].classes()).toContain("missing");
    expect(chips[1].text()).toContain("模型直连 · 未配置");
  });

  it("概览卡片按单位格式化 token、成本与耗时", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(cardText(wrapper, "总调用次数")).toContain("1200");
    expect(cardText(wrapper, "总调用次数")).toContain("成功 1100 次");
    expect(cardText(wrapper, "成功率")).toContain("91.7%");
    expect(cardText(wrapper, "成功率")).toContain("拦截 50 次");
    expect(cardText(wrapper, "Token 消耗")).toContain("1.2M");
    expect(cardText(wrapper, "累计成本")).toContain("¥1.23");
    expect(cardText(wrapper, "累计成本")).toContain("仅含已定价模型");
    expect(cardText(wrapper, "平均耗时")).toContain("850ms");
    expect(cardText(wrapper, "P95 耗时")).toContain("2.40s");
    expect(cardText(wrapper, "平均首字耗时")).toContain("320ms");

    expect(wrapper.find(".gw-warn").text()).toContain("仅统计最近 10 万条调用");
  });

  it("趋势/模块/模型条形宽度按最大值缩放，未定价模型标注", async () => {
    const wrapper = mountView();
    await flushPromises();

    const bars = wrapper.findAll(".trend-bar");
    expect(bars).toHaveLength(2);
    expect(bars[0].attributes("style")).toContain("height: 50%");
    expect(bars[1].attributes("style")).toContain("height: 100%");
    const failed = bars[0].find(".trend-bar-failed");
    expect(failed.attributes("style")).toContain("height: 10%");
    expect(bars[1].find(".trend-bar-failed").attributes("style")).toContain("height: 0%");

    const moduleBars = wrapper.findAll(".module-list .module-bar i");
    expect(moduleBars[0].attributes("style")).toContain("width: 100%");
    expect(moduleBars[1].attributes("style")).toContain("width: 25%");

    const modelList = wrapper.find(".model-list");
    expect(modelList.text()).toContain("qwen-max");
    expect(modelList.text()).toContain("成本 ¥1.00");
    expect(modelList.text()).toContain("gpt-4o-mini");
    expect(modelList.text()).toContain("未定价");

    expect(wrapper.find(".top-users").text()).toContain("张三");
    expect(wrapper.findAll(".top-user")[0].find(".top-bar i").attributes("style")).toContain(
      "width: 100%"
    );
  });

  it("无用量数据时显示空态；加载失败给出重试提示", async () => {
    const { error } = createMessageSpies();
    vi.mocked(aiGatewayAdminApi.getSummary).mockResolvedValue(
      ok({ ...summary, trend: [], modules: [], models: [], topUsers: [] })
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find(".trend-chart").text()).toContain("暂无调用数据");
    expect(wrapper.find(".top-users").text()).toContain("暂无数据");

    vi.mocked(aiGatewayAdminApi.getSummary).mockRejectedValue(new Error("network"));
    const failedWrapper = mountView();
    await flushPromises();
    expect(error).toHaveBeenCalledWith("获取用量汇总失败，请稍后重试");
    expect(failedWrapper.text()).toContain("用量数据加载失败，请点击「查询」重试");
  });

  it("调用日志：首次切换加载一次，表格展示状态与错误信息，查询带上过滤条件", async () => {
    const wrapper = mountView();
    await flushPromises();
    expect(aiGatewayAdminApi.getLogs).not.toHaveBeenCalled();

    await clickTab(wrapper, "调用日志");
    expect(aiGatewayAdminApi.getLogs).toHaveBeenCalledTimes(1);
    expect(aiGatewayAdminApi.getLogs).toHaveBeenCalledWith(
      expect.objectContaining({ pageNum: 1, pageSize: 20 })
    );

    const table = wrapper.find("#pane-logs .el-table");
    expect(table.text()).toContain("2026-10-30 12:00:00");
    expect(table.text()).toContain("问答聊天");
    expect(table.text()).toContain("/v1/chat-messages");
    expect(table.text()).toContain("失败");
    expect(table.text()).toContain("1.2K");
    expect(table.text()).toContain("2.40s");
    expect(table.find(".gw-error-text").text()).toContain("upstream timeout");
    expect(table.find(".el-tag").classes().join(" ")).toContain("danger");

    await wrapper.find('input[placeholder="用户 UID"]').setValue("  u1  ");
    const queryButtons = wrapper
      .findAll(".gw-toolbar .btn-primary")
      .filter((b) => b.text().includes("查询"));
    await queryButtons[queryButtons.length - 1].trigger("click");
    await flushPromises();
    expect(aiGatewayAdminApi.getLogs).toHaveBeenCalledTimes(2);
    expect(aiGatewayAdminApi.getLogs).toHaveBeenLastCalledWith(
      expect.objectContaining({ userId: "u1", pageNum: 1 })
    );

    // 已有数据后再次切回不重复请求
    await clickTab(wrapper, "用量概览");
    await clickTab(wrapper, "调用日志");
    expect(aiGatewayAdminApi.getLogs).toHaveBeenCalledTimes(2);
  });

  it("导出日志：成功生成下载并提示，失败给出错误提示", async () => {
    const { success, error } = createMessageSpies();
    const createSpy = vi.spyOn(URL, "createObjectURL");
    const clickSpy = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => {});
    vi.mocked(aiGatewayAdminApi.exportLogs).mockResolvedValue(
      ok("a,b\n1,2", "导出成功")
    );

    const wrapper = mountView();
    await flushPromises();
    await clickTab(wrapper, "调用日志");

    const exportButton = wrapper
      .findAll(".gw-toolbar .btn")
      .find((b) => b.text().includes("导出 CSV"));
    await exportButton!.trigger("click");
    await flushPromises();

    expect(aiGatewayAdminApi.exportLogs).toHaveBeenCalledTimes(1);
    expect(createSpy).toHaveBeenCalled();
    expect(clickSpy).toHaveBeenCalled();
    expect(success).toHaveBeenCalledWith("导出成功");

    vi.mocked(aiGatewayAdminApi.exportLogs).mockResolvedValue(fail("没有可导出的数据"));
    await exportButton!.trigger("click");
    await flushPromises();
    expect(error).toHaveBeenCalledWith("没有可导出的数据");
  });

  it("配额管理：切换时加载，展示默认/不限制与使用率", async () => {
    const wrapper = mountView();
    await flushPromises();
    expect(aiGatewayAdminApi.getQuotas).not.toHaveBeenCalled();

    await clickTab(wrapper, "配额管理");
    expect(aiGatewayAdminApi.getQuotas).toHaveBeenCalledTimes(1);

    expect(wrapper.find(".gw-quota-default").text()).toContain("500K");
    const table = wrapper.find("#pane-quotas .user-table-card .el-table");
    expect(table.text()).toContain("张三");
    expect(table.text()).toContain("默认");
    expect(table.text()).toContain("李四");
    expect(table.text()).toContain("不限制");
    expect(table.text()).toContain("400K");
    const progresses = table.findAll(".el-progress");
    expect(progresses[0].attributes("aria-valuenow")).toBe("80");
    expect(progresses[0].classes()).toContain("is-warning");
    expect(progresses[1].attributes("aria-valuenow")).toBe("100");
    expect(progresses[1].classes()).toContain("is-exception");
  });

  it("新建配额：默认填配额并按部门共享池校验后提交 unit: 前缀", async () => {
    const { warning, success } = createMessageSpies();
    const wrapper = mountView();
    await flushPromises();

    await clickTab(wrapper, "配额管理");
    const newButton = wrapper
      .findAll(".gw-toolbar .btn")
      .find((b) => b.text().includes("新建配额"));
    await newButton!.trigger("click");
    await flushPromises();
    await nextTick();

    const dialog = wrapper.find(".el-dialog");
    expect(dialog.text()).toContain("新建 AI 配额");
    const quotaInput = dialog.find(".el-input-number input");
    expect((quotaInput.element as HTMLInputElement).value).toBe("500000");

    const saveButton = dialog
      .findAll(".el-dialog__footer button")
      .find((b) => b.text().includes("保存"));
    await saveButton!.trigger("click");
    await flushPromises();
    expect(warning).toHaveBeenCalledWith("请选择用户");
    expect(aiGatewayAdminApi.saveQuota).not.toHaveBeenCalled();

    // 切换为部门共享池，未填编号时拦截
    const radios = dialog.findAll('.el-radio input[type="radio"]');
    await radios[1].setValue(true);
    await nextTick();
    await saveButton!.trigger("click");
    await flushPromises();
    expect(warning).toHaveBeenCalledWith("请填写部门编号");
    expect(aiGatewayAdminApi.saveQuota).not.toHaveBeenCalled();

    await dialog.find('input[placeholder*="部门 gid"]').setValue(" 1001 ");
    await saveButton!.trigger("click");
    await flushPromises();

    expect(aiGatewayAdminApi.saveQuota).toHaveBeenCalledWith({
      userId: "unit:1001",
      dailyTokenQuota: 500000,
      remark: "",
    });
    expect(success).toHaveBeenCalledWith("配额已保存");
    expect(aiGatewayAdminApi.getQuotas).toHaveBeenCalledTimes(2);
  });

  it("编辑与删除配额：编辑预填且身份不可改，删除需确认", async () => {
    const { success } = createMessageSpies();
    const confirmSpy = vi
      .spyOn(ElMessageBox, "confirm")
      .mockResolvedValue("confirm" as never);
    const wrapper = mountView();
    await flushPromises();

    await clickTab(wrapper, "配额管理");
    const rows = wrapper.findAll("#pane-quotas .el-table__row");
    expect(rows.length).toBeGreaterThan(0);

    const editButton = rows[0].findAll("button").find((b) => b.text().includes("编辑"));
    await editButton!.trigger("click");
    await nextTick();
    const dialog = wrapper.find(".el-dialog");
    expect(dialog.text()).toContain("编辑 AI 配额");
    const disabledValues = dialog
      .findAll("input[disabled]")
      .map((input) => (input.element as HTMLInputElement).value);
    expect(disabledValues).toContain("u1");
    expect(dialog.findAll(".el-radio")[0].classes()).toContain("is-disabled");

    const deleteButton = rows[1].findAll("button").find((b) => b.text().includes("删除"));
    await deleteButton!.trigger("click");
    await flushPromises();
    expect(confirmSpy).toHaveBeenCalled();
    expect(aiGatewayAdminApi.deleteQuota).toHaveBeenCalledWith({ userId: "u2" });
    expect(success).toHaveBeenCalledWith("已恢复默认配额");

    confirmSpy.mockRejectedValue("cancel");
    const otherRow = wrapper.findAll("#pane-quotas .el-table__row")[2];
    const anotherDelete = otherRow.findAll("button").find((b) => b.text().includes("删除"));
    await anotherDelete!.trigger("click");
    await flushPromises();
    expect(aiGatewayAdminApi.deleteQuota).toHaveBeenCalledTimes(1);
  });

  it("调用周报：手动生成本周快照并刷新列表", async () => {
    const { success } = createMessageSpies();
    const wrapper = mountView();
    await flushPromises();

    await clickTab(wrapper, "调用周报");
    const generateButton = wrapper
      .findAll("button")
      .find((b) => b.text().includes("生成本周报告"));
    await generateButton!.trigger("click");
    await flushPromises();

    expect(weeklyReportApi.generate).toHaveBeenCalledWith(mondayOfThisWeek());
    expect(success).toHaveBeenCalledWith("周报已生成");
    expect(weeklyReportApi.list).toHaveBeenCalledTimes(2);
  });
});
