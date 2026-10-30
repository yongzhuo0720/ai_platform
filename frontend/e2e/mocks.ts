import type { Page } from "@playwright/test";

/** 后端统一响应包（HNSP Result 结构） */
const ok = (datas: unknown) => ({
  resp_code: 0,
  resp_msg: "ok",
  datas,
});

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8" };

export interface BackendMockOptions {
  /** 网关模块是否已配置；知识库页用例需要 file: false */
  modules?: Record<string, boolean>;
  /**
   * Dify 对话接口返回的错误响应。
   * <p>配置后 /chat/chat-messages 直接返回该状态与错误体（如配额 429），
   * 用于覆盖「发送失败时错误如何呈现」这条路径；不配置则回一段正常的 SSE 回答。</p>
   */
  difyChatError?: { status: number; body: unknown };
}

/**
 * 拦截全部 /ai/** 请求并返回确定性数据。
 *
 * 浏览器冒烟只验证「前端能否在真实浏览器里正确启动与交互」，
 * 不依赖本机后端与数据库；真正的全栈联调仍在本地 start.ps1 环境里做。
 */
export async function installBackendMocks(
  page: Page,
  options: BackendMockOptions = {}
): Promise<string[]> {
  const modules = options.modules ?? {
    chat: true,
    knowledgeChat: true,
    file: true,
    models: true,
  };
  const requested: string[] = [];

  await page.route("**/ai/**", async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    requested.push(path);

    const respond = (body: unknown, status = 200) =>
      route.fulfill({
        status,
        headers: JSON_HEADERS,
        body: JSON.stringify(body),
      });

    if (path.endsWith("/ai/uaccess/logintest")) {
      return respond(ok({ token: "e2e-token" }));
    }
    if (path.endsWith("/ai/myuser/query")) {
      return respond(
        ok({
          content: [
            { uid: "u1", uname: "测试用户", uaccess: "tester", rid: 2, gid: "g1" },
          ],
        })
      );
    }
    // 聊天页的直连模型流式回答：整段 SSE 一次性返回，前端按行解析
    if (path.includes("/ai/api/aiGateway/models/") && path.endsWith("/chat/completions")) {
      return route.fulfill({
        status: 200,
        headers: { "content-type": "text/event-stream; charset=utf-8" },
        body: [
          'data: {"choices":[{"delta":{"content":"你好"}}]}',
          "",
          'data: {"choices":[{"delta":{"content":"，世界"}}]}',
          "",
          "data: [DONE]",
          "",
        ].join("\n"),
      });
    }
    if (path.endsWith("/ai/api/aiGatewayStatus/config")) {
      return respond(
        ok({
          modules: Object.entries(modules).map(([module, configured]) => ({
            module,
            configured,
          })),
        })
      );
    }
    if (path.endsWith("/ai/api/modelsConfigBean/query")) {
      return respond(
        ok([
          {
            id: "m1",
            name: "DeepSeek Chat",
            provider: "直连",
            iid: 1,
            protocol: "openai",
            upstreamModel: "deepseek-chat",
            enable: true,
          },
        ])
      );
    }
    // Dify 协议接口：直接返回业务 JSON，不套 resp_code 外壳
    if (path.endsWith("/ai/api/aiGateway/chat/parameters")) {
      return respond({ user_input_form: [], suggested_questions: [] });
    }
    if (path.endsWith("/ai/api/aiGateway/chat/chat-messages")) {
      if (options.difyChatError) {
        return respond(options.difyChatError.body, options.difyChatError.status);
      }
      return route.fulfill({
        status: 200,
        headers: { "content-type": "text/event-stream; charset=utf-8" },
        body: [
          'data: {"event":"message","answer":"你好","message_id":"e2e-msg-1","conversation_id":"e2e-conv-1"}',
          "",
          'data: {"event":"message_end","message_id":"e2e-msg-1","conversation_id":"e2e-conv-1"}',
          "",
          "data: [DONE]",
          "",
        ].join("\n"),
      });
    }
    if (path.endsWith("/ai/api/aiGateway/chat/conversations")) {
      return respond({ data: [], has_more: false, limit: 30 });
    }
    if (path.endsWith("/ai/api/chatConversation/list")) {
      return respond(ok([]));
    }
    if (path.endsWith("/ai/api/assistantBean/query")) {
      return respond(ok([]));
    }
    if (path.endsWith("/ai/api/kbRag/listDocs")) {
      return respond(ok([]));
    }

    return respond(ok([]));
  });

  return requested;
}

/** 写入登录态 Cookie，跳过登录页直接进入业务页面 */
export async function loginAsUser(page: Page): Promise<void> {
  await page.context().addCookies([
    { name: "loginToken", value: "e2e-token", domain: "127.0.0.1", path: "/" },
    { name: "uid", value: "u1", domain: "127.0.0.1", path: "/" },
    { name: "rid", value: "2", domain: "127.0.0.1", path: "/" },
    { name: "uname", value: "测试用户", domain: "127.0.0.1", path: "/" },
    { name: "uaccess", value: "tester", domain: "127.0.0.1", path: "/" },
  ]);
}
