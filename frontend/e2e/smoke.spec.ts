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

  test("直连模式选择图片后，压缩过的 dataURL 随请求发到网关", async ({
    page,
  }) => {
    const directRequests: unknown[] = [];
    await installBackendMocks(page, { directRequests });
    await loginAsUser(page);

    await page.goto("/#/chat");

    const input = page.getByPlaceholder("请输入问题，开始对话...");
    await expect(input).toBeVisible();
    await page.locator('label[aria-label="选择模型"] button').first().click();
    await page.locator(".chat-model-menu-item").first().click();

    // 1×1 PNG：验证真实浏览器里的 FileReader → Image → Canvas 压缩链路
    await page.locator("input.chat-composer-file-input").first().setInputFiles({
      name: "pixel.png",
      mimeType: "image/png",
      buffer: Buffer.from(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        "base64"
      ),
    });
    await expect(page.locator(".attached-file-name")).toHaveText("pixel.png");

    // 只有附件没有文字：使用默认 query，正好覆盖附件兜底提问
    await page.getByRole("button", { name: "发送消息" }).click();
    await expect(
      page.locator(".chat-message-item.assistant .markdown-content")
    ).toContainText("你好，世界");

    await expect.poll(() => directRequests.length).toBe(1);
    const body = directRequests[0] as {
      messages: Array<{ role: string; content: unknown }>;
    };
    const lastUser = [...body.messages].reverse().find((m) => m.role === "user");
    expect(Array.isArray(lastUser?.content)).toBe(true);
    const parts = lastUser?.content as Array<Record<string, any>>;
    expect(parts[0]).toEqual({ type: "text", text: "请分析上传的文件" });
    expect(parts[1]?.type).toBe("image_url");
    expect(parts[1]?.image_url?.url).toMatch(/^data:image\/jpeg;base64,/);
  });

  test("直连模式隐藏 Dify 应用参数，必填项不拦截发送", async ({ page }) => {
    const directRequests: unknown[] = [];
    await installBackendMocks(page, {
      directRequests,
      appParameters: {
        user_input_form: [
          { "text-input": { variable: "topic", label: "主题", required: true } },
          {
            "file-upload": {
              variable: "input_file",
              label: "资料",
              required: true,
              allowed_file_types: ["document"],
            },
          },
        ],
        suggested_questions: ["应用专属建议"],
      },
    });
    await loginAsUser(page);

    await page.goto("/#/chat");

    const input = page.getByPlaceholder("请输入问题，开始对话...");
    await expect(input).toBeVisible();
    // Dify 通道下应用参数正常渲染，确认这条用例的场景成立
    await expect(
      page.locator(".chat-composer-form-label", { hasText: "主题" })
    ).toBeVisible();
    // 文件变量由附件按钮负责，不应渲染成文本输入框
    await expect(
      page.locator(".chat-composer-form-label", { hasText: "资料" })
    ).toHaveCount(0);

    await page.locator('label[aria-label="选择模型"] button').first().click();
    await page.locator(".chat-model-menu-item").first().click();

    // 切到直连：应用参数整组消失，空状态改用通用开场问题
    await expect(page.locator(".chat-composer-form")).toHaveCount(0);
    await expect(
      page.locator(".suggested-question-btn", { hasText: "用一句话介绍你自己" })
    ).toBeVisible();
    await expect(
      page.locator(".suggested-question-btn", { hasText: "应用专属建议" })
    ).toHaveCount(0);

    // 点击建议问题原文直接发送（不再拼「请帮我」前缀），未填「主题」也不拦截
    await page
      .locator(".suggested-question-btn", { hasText: "用一句话介绍你自己" })
      .click();
    const userMessage = page.locator(".chat-message-item.user");
    await expect(userMessage).toContainText("用一句话介绍你自己");
    await expect(userMessage).not.toContainText("请帮我");
    await expect(
      page.locator(".chat-message-item.assistant .markdown-content")
    ).toContainText("你好，世界");

    await expect.poll(() => directRequests.length).toBe(1);
    const body = directRequests[0] as {
      messages: Array<{ role: string; content: unknown }>;
    };
    const lastUser = [...body.messages].reverse().find((m) => m.role === "user");
    expect(lastUser?.content).toBe("用一句话介绍你自己");
  });

  test("配额用尽时对话展示服务端原因，而不是裸错误码", async ({ page }) => {
    await installBackendMocks(page, {
      difyChatError: {
        status: 429,
        body: {
          code: "quota_exceeded",
          message: "今日 AI 额度已用完（已用 1500 / 1500 tokens），请明天再试或联系管理员",
          status: 429,
          requestId: "e2e-quota",
        },
      },
    });
    await loginAsUser(page);

    await page.goto("/#/chat");

    // 不选模型 = Dify 通道，正是后端配额拦截覆盖的路径
    const input = page.getByPlaceholder("请输入问题，开始对话...");
    await expect(input).toBeVisible();
    await input.fill("你好");
    await page.getByRole("button", { name: "发送消息" }).click();

    const assistant = page.locator(".chat-message-item.assistant").last();
    await expect(assistant).toContainText("今日 AI 额度已用完");
    await expect(assistant).not.toContainText("quota_exceeded");
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
