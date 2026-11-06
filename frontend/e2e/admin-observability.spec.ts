import { expect, test, type Page } from "@playwright/test";
import { installBackendMocks, loginAsUser } from "./mocks";

/**
 * 模型可观测后台：真实浏览器里验证健康面板、连接测试与模型配置的
 * enable/单价编辑。接口由 page.route 拦截，不依赖本机后端。
 */

const ok = (datas: unknown, respMsg = "ok") => ({ resp_code: 0, resp_msg: respMsg, datas });
const JSON_HEADERS = { "content-type": "application/json; charset=utf-8" };

const summary = {
  totalCalls: 7,
  successCalls: 6,
  failedCalls: 1,
  blockedCalls: 0,
  totalTokens: 1200,
  avgLatencyMs: 600,
  p95LatencyMs: 900,
  avgFirstTokenMs: 280,
  successRate: 85.71,
  truncated: false,
  totalCostCents: 20000,
  hasUnpricedModels: false,
  trend: [{ date: "2026-11-06", calls: 7, tokens: 1200, failed: 1 }],
  modules: [{ module: "chat", calls: 7, tokens: 1200, failed: 1 }],
  models: [
    {
      model: "deepseek-chat",
      calls: 7,
      tokens: 1200,
      failed: 1,
      avgLatencyMs: 600,
      p95LatencyMs: 900,
      successRate: 85.71,
      priced: true,
      costCents: 20000,
    },
  ],
  topUsers: [{ userId: "u1", userName: "测试用户", calls: 7, tokens: 1200 }],
};

const health = {
  gatewayEnabled: true,
  rateLimitPerMinute: 30,
  defaultDailyTokenQuota: 1000000,
  quotaReserveTokens: 2000,
  checkedAt: Date.UTC(2026, 10, 6, 9, 0, 0),
  modules: [
    { module: "chat", moduleName: "问答聊天", configured: true },
    { module: "models", moduleName: "模型直连", configured: true },
  ],
  models: [
    {
      iid: 1,
      name: "DeepSeek Chat",
      upstreamModel: "deepseek-chat",
      enabled: true,
      ready: true,
      priced: true,
      hasEmbedModel: false,
    },
    {
      iid: 2,
      name: "向量模型",
      upstreamModel: "text-embedding-v3",
      enabled: false,
      ready: false,
      priced: false,
      hasEmbedModel: true,
    },
  ],
  today: summary,
};

const probeResult = (status: string, message: string) => ({
  iid: 1,
  modelName: "DeepSeek Chat",
  upstreamModel: "deepseek-chat",
  status,
  modelAvailable: status === "ok",
  httpStatus: status === "ok" ? 200 : null,
  latencyMs: 38,
  message,
  checkedAt: Date.now(),
});

/** 记录请求体，断言前端只把 iid / 变更字段发给后端 */
interface Recorder {
  probes: unknown[];
  updates: unknown[];
}

/** 后台路由守卫要求 admin_loginToken + admin_access=1 */
async function loginAsAdmin(page: Page): Promise<void> {
  await loginAsUser(page);
  await page.context().addCookies([
    { name: "admin_loginToken", value: "e2e-admin-token", domain: "127.0.0.1", path: "/" },
    { name: "admin_access", value: "1", domain: "127.0.0.1", path: "/" },
  ]);
}

async function installAdminMocks(page: Page): Promise<Recorder> {
  const recorder: Recorder = { probes: [], updates: [] };

  await page.route("**/ai/api/aiGatewayAdmin/config", (route) =>
    route.fulfill({
      status: 200,
      headers: JSON_HEADERS,
      body: JSON.stringify(
        ok({
          enabled: true,
          rateLimitPerMinute: 30,
          defaultDailyTokenQuota: 1000000,
          modules: [
            { module: "chat", moduleName: "问答聊天", configured: true },
            { module: "models", moduleName: "模型直连", configured: true },
          ],
        })
      ),
    })
  );
  await page.route("**/ai/api/aiGatewayAdmin/summary", (route) =>
    route.fulfill({ status: 200, headers: JSON_HEADERS, body: JSON.stringify(ok(summary)) })
  );
  await page.route("**/ai/api/aiGatewayAdmin/health", (route) =>
    route.fulfill({ status: 200, headers: JSON_HEADERS, body: JSON.stringify(ok(health)) })
  );
  await page.route("**/ai/api/aiWeeklyReport/list", (route) =>
    route.fulfill({ status: 200, headers: JSON_HEADERS, body: JSON.stringify(ok([])) })
  );
  await page.route("**/ai/api/modelsConfigBean/queryPage", (route) =>
    route.fulfill({
      status: 200,
      headers: JSON_HEADERS,
      body: JSON.stringify(
        ok({
          content: [
            {
              iid: 1,
              id: "deepseek-chat",
              name: "DeepSeek Chat",
              provider: "DeepSeek",
              protocol: "openai",
              baseUrl: "https://api.deepseek.com",
              upstreamModel: "deepseek-chat",
              enable: true,
              priceInputPerMillion: 200,
              priceOutputPerMillion: 800,
            },
            {
              iid: 2,
              id: "qwen-max",
              name: "通义千问",
              provider: "",
              protocol: "",
              enable: false,
            },
          ],
          totalSize: 2,
        })
      ),
    })
  );
  await page.route("**/ai/api/modelsConfigBean/update", (route) => {
    recorder.updates.push(route.request().postDataJSON());
    return route.fulfill({ status: 200, headers: JSON_HEADERS, body: JSON.stringify(ok(true)) });
  });
  await page.route("**/ai/api/modelsConfigBean/testConnection", (route) => {
    recorder.probes.push(route.request().postDataJSON());
    return route.fulfill({
      status: 200,
      headers: JSON_HEADERS,
      body: JSON.stringify(ok(probeResult("ok", "服务连接成功，模型已就绪"))),
    });
  });

  return recorder;
}

test.describe("模型可观测后台", () => {
  test("健康面板展示今日指标、区分模型配置完整性并支持连接测试", async ({ page }) => {
    await installBackendMocks(page);
    const adminRecorder = await installAdminMocks(page);
    await loginAsAdmin(page);

    const pageErrors: string[] = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));

    await page.goto("/#/admin/ai-gateway");
    await expect(page.getByRole("heading", { name: "AI 调用治理" })).toBeVisible();

    await page.getByRole("tab", { name: "模型健康" }).click();
    const panel = page.locator("#pane-health");
    await expect(panel).toBeVisible();

    await expect(
      page.locator(".gw-card").filter({ hasText: "今日成功率" })
    ).toContainText("85.7%");
    await expect(
      page.locator(".gw-card").filter({ hasText: "预扣 tokens" })
    ).toContainText("2.0K");

    const table = panel.locator(".el-table");
    await expect(table).toContainText("DeepSeek Chat");
    await expect(table).toContainText("deepseek-chat");
    await expect(table).toContainText("已启用");
    await expect(table).toContainText("齐全");
    await expect(table).toContainText("未定价");

    // 缺地址/密钥的行不能测试
    const secondRow = table.locator(".el-table__row").nth(1);
    await expect(secondRow.getByRole("button", { name: "测试", exact: true })).toBeDisabled();

    await table
      .locator(".el-table__row")
      .first()
      .getByRole("button", { name: "测试", exact: true })
      .click();
    await expect(page.locator(".el-message--success").last()).toContainText("服务连接成功");
    await expect(table.locator(".el-table__row").first()).toContainText("可用");
    expect(adminRecorder.probes).toEqual([{ data: { iid: 1 } }]);

    await page
      .locator("#pane-health .gw-toolbar")
      .getByRole("button", { name: "测试全部" })
      .click();
    await expect(page.locator(".el-message--success").last()).toContainText("测试完成：可用 1");
    expect(adminRecorder.probes).toHaveLength(2);

    expect(pageErrors).toEqual([]);
  });

  test("模型配置页展示单价，启用开关只提交 iid 与 enable，连接测试只传 iid", async ({
    page,
  }) => {
    await installBackendMocks(page);
    const recorder = await installAdminMocks(page);
    await loginAsAdmin(page);

    await page.goto("/#/admin/models-config");
    const table = page.locator(".el-table").first();
    await expect(table).toContainText("200 / 800");
    await expect(table).toContainText("未定价");

    await table.locator(".el-switch").first().click();
    await expect(page.locator(".el-message--success").last()).toContainText("已停用该模型");
    expect(recorder.updates).toEqual([{ data: { iid: 1, enable: false }, where: { iid: 1 } }]);

    await table
      .locator(".el-table__row")
      .first()
      .getByRole("button", { name: "测试连接" })
      .click();
    await expect(page.locator(".el-message--success").last()).toContainText("服务连接成功");
    await expect(table.locator(".el-table__row").first()).toContainText("可用");
    expect(recorder.probes).toEqual([{ data: { iid: 1 } }]);
  });
});
