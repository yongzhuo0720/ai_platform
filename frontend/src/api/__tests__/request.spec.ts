import { describe, expect, it, vi } from "vitest";
import { readErrorMessage } from "../request";

vi.mock("@/stores/difyConfig", () => ({
  getChatConfig: () => ({ apiUrl: "/gateway", apiKey: "server-side" }),
  ensureLoaded: async () => undefined,
  gatewayRequestHeaders: () => ({}),
}));

describe("readErrorMessage", () => {
  it("网关错误体只展示 message，并把请求 ID 附在末尾", async () => {
    const response = new Response(
      JSON.stringify({
        code: "quota_exceeded",
        message: "今日 AI 额度已用完（已用 1500 / 1500 tokens），请明天再试或联系管理员",
        status: 429,
        requestId: "req-1",
      }),
      { status: 429, headers: { "Content-Type": "application/json" } }
    );

    await expect(readErrorMessage(response)).resolves.toBe(
      "今日 AI 额度已用完（已用 1500 / 1500 tokens），请明天再试或联系管理员（请求 ID：req-1）"
    );
  });

  it("响应头里的请求 ID 优先于错误体，避免展示与日志对不上", async () => {
    const response = new Response(
      JSON.stringify({ code: "rate_limited", message: "请求过于频繁", requestId: "body-id" }),
      {
        status: 429,
        headers: { "Content-Type": "application/json", "X-Request-Id": "header-id" },
      }
    );

    await expect(readErrorMessage(response)).resolves.toBe(
      "请求过于频繁（请求 ID：header-id）"
    );
  });

  it("兼容 OpenAI 错误信封，并从 error.request_id 取请求 ID", async () => {
    const response = new Response(
      JSON.stringify({
        error: {
          message: "上游模型限流",
          type: "rate_limit_error",
          code: "rate_limit_exceeded",
          request_id: "req-openai",
        },
      }),
      { status: 429, headers: { "Content-Type": "application/json" } }
    );

    await expect(readErrorMessage(response)).resolves.toBe(
      "上游模型限流（请求 ID：req-openai）"
    );
  });

  it("非 JSON 响应保留原文与状态码，并附带响应头请求 ID", async () => {
    const response = new Response("<html>bad gateway</html>", {
      status: 502,
      headers: { "X-Request-Id": "req-502" },
    });

    await expect(readErrorMessage(response)).resolves.toBe(
      "请求失败 (502): <html>bad gateway</html>（请求 ID：req-502）"
    );
  });

  it("没有请求 ID 时不追加任何后缀", async () => {
    const response = new Response(null, { status: 503, statusText: "Service Unavailable" });

    await expect(readErrorMessage(response, "文件上传失败")).resolves.toBe(
      "文件上传失败 (503): Service Unavailable"
    );
  });
});
