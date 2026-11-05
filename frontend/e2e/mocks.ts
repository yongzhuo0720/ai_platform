import type { Page } from "@playwright/test";

/** 后端统一响应包（HNSP Result 结构） */
const ok = (datas: unknown, respMsg = "ok") => ({
  resp_code: 0,
  resp_msg: respMsg,
  datas,
});

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8" };

/**
 * 一条成功的 RAG 评估运行。
 * <p>列表与详情共用同一份数据，避免两处 mock 字段漂移后出现
 * 「列表有指标、详情全空」这类只在假数据里成立的假象。</p>
 */
const ragEvalRun = () => ({
  runKey: "r-main",
  datasetKey: "d-e2e",
  datasetName: "制度黄金集",
  kbName: "默认知识库",
  topK: 5,
  status: "success" as const,
  total: 2,
  processed: 2,
  recall: 1,
  precision: 0.6,
  mrr: 0.75,
  ndcg: 0.81,
  keywordHitRate: 0.75,
  missCount: 0,
  keywordEvaluatedCount: 2,
  embeddingModel: "bge-m3",
  startedAt: Date.UTC(2026, 10, 5, 9, 0, 0),
  durationMs: 2300,
});

export interface BackendMockOptions {
  /** 网关模块是否已配置；知识库页用例需要 file: false */
  modules?: Record<string, boolean>;
  /**
   * Dify 对话接口返回的错误响应。
   * <p>配置后 /chat/chat-messages 直接返回该状态与错误体（如配额 429），
   * 用于覆盖「发送失败时错误如何呈现」这条路径；不配置则回一段正常的 SSE 回答。</p>
   */
  difyChatError?: { status: number; body: unknown };
  /**
   * 收集直连模型（/models/{iid}/chat/completions）的请求体。
   * <p>用于断言前端真的把压缩后的图片 dataURL 放进了多模态 messages，
   * 而不只是界面上显示了附件。</p>
   */
  directRequests?: unknown[];
  /**
   * Dify 应用参数（/chat/parameters）的响应体。
   * <p>默认空表单；聊天页用例需要验证「应用参数在直连模式下不出现」时，
   * 可传入带必填项的 user_input_form。</p>
   */
  appParameters?: unknown;
  /**
   * 收集 RAG 评估启动请求体。
   * <p>用于断言前端是否按 HNSP 约定把 DTO 包在 data 字段里，
   * 以及 datasetKey/topK 是否真的来自当前页面状态。</p>
   */
  ragEvalStartRequests?: unknown[];
  /**
   * 知识检索页的文档列表。每次调用都会重新求值，
   * 便于模拟「上传后 queued → 轮询后 ready」的真实状态推进。
   */
  kbDocs?: () => unknown[];
  /**
   * 收集知识文档上传请求。multipart 无法用 postDataJSON 解析，
   * 这里保留 Content-Type 与原始请求体，用于断言确实走了文件上传接口。
   */
  kbUploads?: Array<{ contentType: string; postData: string | null }>;
  /** 上传接口返回的文档；通常在闭包里同时更新 kbDocs 的状态 */
  kbUploadResponse?: () => unknown;
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
      try {
        options.directRequests?.push(route.request().postDataJSON());
      } catch {
        // 无请求体（理论上不会发生）：跳过采集，不影响响应
      }
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
      return respond(
        options.appParameters ?? { user_input_form: [], suggested_questions: [] }
      );
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
    if (path.endsWith("/ai/api/kbRag/uploadFile")) {
      options.kbUploads?.push({
        contentType: route.request().headers()["content-type"] ?? "",
        postData: route.request().postData(),
      });
      return respond(
        ok(
          options.kbUploadResponse?.() ?? { iid: 1, status: "queued" },
          "文档已受理，正在后台处理"
        )
      );
    }
    if (path.endsWith("/ai/api/kbRag/retryDoc")) {
      return respond(ok(true, "文档已重新排队"));
    }
    if (path.endsWith("/ai/api/kbRag/listDocs")) {
      return respond(ok(options.kbDocs ? options.kbDocs() : []));
    }
    if (path.endsWith("/ai/api/ragEval/listDatasets")) {
      return respond(
        ok([
          {
            datasetKey: "d-e2e",
            name: "制度黄金集",
            kbName: "默认知识库",
            description: "差旅与考勤制度问答",
            caseCount: 2,
          },
        ])
      );
    }
    if (path.endsWith("/ai/api/ragEval/getDataset")) {
      return respond(
        ok({
          dataset: {
            datasetKey: "d-e2e",
            name: "制度黄金集",
            kbName: "默认知识库",
            description: "差旅与考勤制度问答",
            caseCount: 2,
          },
          cases: [
            {
              question: "出差住宿每晚上限多少",
              expectedDoc: "差旅报销制度",
              keywords: ["600"],
              category: "数值型",
            },
            {
              question: "出差 3 天以内由谁审批",
              expectedDoc: "差旅报销制度",
              keywords: [],
              category: "事实型",
            },
          ],
        })
      );
    }
    if (path.endsWith("/ai/api/ragEval/listRuns")) {
      return respond(ok([ragEvalRun()]));
    }
    if (path.endsWith("/ai/api/ragEval/startRun")) {
      options.ragEvalStartRequests?.push(route.request().postDataJSON());
      return respond(ok("r-new", "评估已启动，共 2 条用例"));
    }
    if (path.endsWith("/ai/api/ragEval/getRun")) {
      return respond(
        ok({
          run: ragEvalRun(),
          baseline: {
            ...ragEvalRun(),
            runKey: "r-base",
            recall: 0.9,
            precision: 0.5,
            mrr: 0.7,
            ndcg: 0.75,
            keywordHitRate: 0.5,
            startedAt: Date.UTC(2026, 10, 4, 9, 0, 0),
          },
          results: [
            {
              caseIndex: 0,
              question: "出差住宿每晚上限多少",
              expectedDoc: "差旅报销制度",
              keywords: ["600"],
              category: "数值型",
              retrievedDocs: ["差旅报销制度", "考勤制度"],
              topDoc: "差旅报销制度",
              firstHitRank: 1,
              recall: 1,
              precision: 0.5,
              reciprocalRank: 1,
              ndcg: 1,
              keywordHit: true,
              keywordEvaluated: true,
              durationMs: 320,
            },
            {
              caseIndex: 1,
              question: "出差 3 天以内由谁审批",
              expectedDoc: "差旅报销制度",
              keywords: ["审批"],
              category: "事实型",
              retrievedDocs: ["考勤制度", "差旅报销制度"],
              topDoc: "考勤制度",
              firstHitRank: 2,
              recall: 1,
              precision: 0.5,
              reciprocalRank: 0.5,
              ndcg: 0.6309,
              keywordHit: false,
              keywordEvaluated: true,
              durationMs: 280,
            },
          ],
        })
      );
    }
    if (path.endsWith("/ai/api/ragEval/deleteDataset") ||
        path.endsWith("/ai/api/ragEval/deleteRun")) {
      return respond(ok(true));
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
