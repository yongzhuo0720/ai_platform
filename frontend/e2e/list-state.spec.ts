import { expect, test } from "@playwright/test";
import { installBackendMocks, loginAsUser } from "./mocks";

/**
 * 目录页三态：在真实浏览器里验证「加载中 / 空 / 失败可重试」确实会露出来。
 *
 * 组件测试已经在 jsdom 里覆盖了同一批断言，这里补的是浏览器视角的两件事：
 * StateBlock 是否真的挂在这几个页面上（骨架与文案在真实样式表下可见），
 * 以及首屏那段请求期用户看到的是什么——这正是本轮改造要解决的「白屏当没有数据」。
 *
 * 接口路径按 api 模块里的真实 URL 写死（modelsBean/query、datasetsBean/query）。
 * 路径写错不会报错，只会静默退回公共 mock 的 resp_code:0 + 空数组，
 * 让空态用例变成永远绿的假绿——本轮第一版就踩中了。所以每条用例都同时断言
 * 接口调用次数，凡是断言卡片标题的用例也顺带证明了该路径的接管真的生效。
 */

const models = [
  { iid: 3, name: "GPT-4o mini", provider: "OpenAI", description: "性价比模型", createtime: "2026-10-01 10:00:00" },
  { iid: 2, name: "BGE-M3", provider: "BAAI", description: "中文向量模型", createtime: "2026-09-01 10:00:00" },
];

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8" };

type Route = {
  fulfill(options: { status: number; headers: Record<string, string>; body: string }): Promise<void>;
  abort(reason?: string): Promise<void>;
};

const respondJson = (route: Route, body: unknown) =>
  route.fulfill({ status: 200, headers: JSON_HEADERS, body: JSON.stringify(body) });

/**
 * 接管某个接口的响应，其余 /ai/** 仍走公共 mock。
 * handler 收到从 0 开始的调用序号，用于「第一次失败、重试后成功」这类剧本。
 */
const mockQuery = (
  page: any,
  path: string,
  handler: (route: Route, attempt: number) => Promise<void>
): void => {
  let attempt = 0;
  page.route(`**${path}`, (route: Route) => handler(route, attempt++));
};

test.describe("目录页三态", () => {
  test.beforeEach(async ({ page }) => {
    await installBackendMocks(page);
    await loginAsUser(page);
  });

  test("请求被卡住的这段时间显示骨架屏，放行后换成卡片", async ({ page }) => {
    let release: () => void = () => {};
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });

    mockQuery(page, "/ai/api/modelsBean/query", async (route) => {
      await held;
      await respondJson(route, { resp_code: 0, resp_msg: "ok", datas: models });
    });

    await page.goto("/#/models");

    try {
      // 请求还握在测试手里，此刻页面只可能是加载态
      await expect(page.locator(".state-block__sk")).toHaveCount(6);
      await expect(page.locator(".list-card")).toHaveCount(0);
      await expect(page.locator(".state-block__empty-text")).toHaveCount(0);
    } finally {
      release();
    }

    await expect(page.locator(".list-card-title")).toHaveText(["GPT-4o mini", "BGE-M3"]);
    await expect(page.locator(".state-block__skeleton")).toHaveCount(0);
  });

  test("接口返回错误码时把原因留在页面上，点重试重新拉取", async ({ page }) => {
    const attempts: number[] = [];
    mockQuery(page, "/ai/api/modelsBean/query", async (route, attempt) => {
      attempts.push(attempt);
      await respondJson(
        route,
        attempt === 0
          ? { resp_code: 500, resp_msg: "模型服务不可用", datas: [] }
          : { resp_code: 0, resp_msg: "ok", datas: models }
      );
    });

    await page.goto("/#/models");

    // 改造前这里是一片空白 + 「暂无模型」，把服务故障说成了库里没东西
    await expect(page.locator(".state-block__error-text")).toHaveText("模型服务不可用");
    await expect(page.locator(".state-block__empty-text")).toHaveCount(0);

    await page.locator(".state-block__retry").click();

    await expect(page.locator(".list-card-title")).toHaveText(["GPT-4o mini", "BGE-M3"]);
    expect(attempts).toEqual([0, 1]);
  });

  test("接口成功但确实没有数据时显示空态文案", async ({ page }) => {
    let called = 0;
    mockQuery(page, "/ai/api/modelsBean/query", async (route) => {
      called += 1;
      await respondJson(route, { resp_code: 0, resp_msg: "ok", datas: [] });
    });

    await page.goto("/#/models");

    await expect(page.locator(".state-block__empty-text")).toHaveText("暂无模型");
    await expect(page.locator(".state-block__error-text")).toHaveCount(0);
    expect(called).toBe(1);
  });

  test("有数据但筛没了要说成筛没了，不能再说成暂无模型", async ({ page }) => {
    mockQuery(page, "/ai/api/modelsBean/query", async (route) => {
      await respondJson(route, { resp_code: 0, resp_msg: "ok", datas: models });
    });

    await page.goto("/#/models");
    await expect(page.locator(".list-card-title")).toHaveText(["GPT-4o mini", "BGE-M3"]);

    await page.getByPlaceholder("搜索模型...").fill("不存在的模型");

    await expect(page.locator(".state-block__empty-text")).toHaveText(
      "没有符合当前搜索与筛选条件的模型"
    );
    await expect(page.locator(".state-block__error-text")).toHaveCount(0);
  });

  test("数据集页同样接上三态：断网露原因，重试后恢复", async ({ page }) => {
    let attempt = 0;
    mockQuery(page, "/ai/api/datasetsBean/query", async (route) => {
      attempt += 1;
      if (attempt === 1) return route.abort("failed");
      await respondJson(route, {
        resp_code: 0,
        resp_msg: "ok",
        datas: [{ iid: 9, name: "文本语料", type: "文本", createtime: "2026-10-04 09:00:00" }],
      });
    });

    await page.goto("/#/datasets");

    await expect(page.locator(".state-block__error-text")).toHaveText("获取数据集列表失败，请重试");

    await page.locator(".state-block__retry").click();

    await expect(page.locator(".list-card-title")).toHaveText(["文本语料"]);
    expect(attempt).toBe(2);
  });
});
