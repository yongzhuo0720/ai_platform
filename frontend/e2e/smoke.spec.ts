import { expect, test } from "@playwright/test";
import { installBackendMocks, loginAsUser } from "./mocks";

/**
 * 浏览器冒烟：在真实 Chromium 里启动完整应用，接口层由 mocks.ts 拦截。
 *
 * 覆盖 jsdom 组件测试看不到的部分：路由守卫、自动导入/全局图标是否生效、
 * Teleport 菜单、真实 fetch + SSE 流式解析、页面启动期未捕获异常。
 */
test.describe("浏览器冒烟", () => {
  test("未登录访问聊天页会被路由守卫重定向到登录页", async ({ page }) => {
    await installBackendMocks(page);
    await page.goto("/#/chat");

    await expect(page).toHaveURL(/#\/login$/);
    await expect(page.getByLabel("平台用户名")).toBeVisible();
    await expect(page.getByLabel("平台密码")).toBeVisible();
  });

  test("登录成功后写入登录 Cookie 并进入聊天页", async ({ page }) => {
    await installBackendMocks(page);
    await page.goto("/#/login");

    await page.getByLabel("平台用户名").fill("tester");
    await page.getByLabel("平台密码").fill("secret");
    await page.getByRole("button", { name: "进入智能平台" }).click();

    await expect(page).toHaveURL(/#\/chat$/);
    const cookies = await page.context().cookies();
    expect(cookies.find((cookie) => cookie.name === "loginToken")?.value).toBe(
      "e2e-token"
    );
    await expect(page.getByPlaceholder("请输入问题，开始对话...")).toBeVisible();
  });

  test("聊天页启动无运行时异常，直连模型流式回答渲染到消息区", async ({
    page,
  }) => {
    await installBackendMocks(page);
    await loginAsUser(page);
    const pageErrors: string[] = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));

    await page.goto("/#/chat");

    const input = page.getByPlaceholder("请输入问题，开始对话...");
    await expect(input).toBeVisible();
    await expect(page.getByText("DeepSeek Chat").first()).toBeVisible();

    await page.locator('label[aria-label="选择模型"] button').first().click();
    await page.locator(".chat-model-menu-item").first().click();
    await input.fill("你好");
    await page.getByRole("button", { name: "发送消息" }).click();

    await expect(page.locator(".chat-message-item.user")).toContainText("你好");
    await expect(
      page.locator(".chat-message-item.assistant .markdown-content")
    ).toContainText("你好，世界");
    expect(pageErrors).toEqual([]);
  });

  test("知识库文件模块未配置时展示引导，不再请求数据集", async ({ page }) => {
    const requested = await installBackendMocks(page, {
      modules: { chat: true, knowledgeChat: true, file: false, models: true },
    });
    await loginAsUser(page);

    await page.goto("/#/knowledge-bases");

    await expect(
      page.locator(".kb-config-alert-title", {
        hasText: "知识库文件模块未配置",
      })
    ).toBeVisible();
    expect(
      requested.some((path) => path.includes("/ai/api/aiGateway/file/"))
    ).toBe(false);
  });

  test("未知路由展示 404 兜底页并保留完整路径", async ({ page }) => {
    await installBackendMocks(page);
    await page.goto("/#/no-such-page?from=e2e");

    await expect(page.getByText("404", { exact: true })).toBeVisible();
    await expect(
      page.getByText("找不到页面：/no-such-page?from=e2e")
    ).toBeVisible();
  });
});
