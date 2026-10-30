import { describe, expect, it, vi } from "vitest";
import { readErrorMessage } from "../request";

vi.mock("@/stores/difyConfig", () => ({
  getChatConfig: () => ({ apiUrl: "/gateway", apiKey: "server-side" }),
  ensureLoaded: async () => undefined,
  gatewayAuthHeaders: () => ({}),
}));

describe("readErrorMessage", () => {
  it("网关错误体只展示 message，不把整段 JSON 丢给用户", async () => {
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
      "今日 AI 额度已用完（已用 1500 / 1500 tokens），请明天再试或联系管理员"
    );
  });

  it("非 JSON 响应保留原文与状态码，便于定位网关层故障", async () => {
    const response = new Response("<html>bad gateway</html>", { status: 502 });

    await expect(readErrorMessage(response)).resolves.toBe(
      "请求失败 (502): <html>bad gateway</html>"
    );
  });

  it("空响应体回退到 statusText，并支持自定义动作名", async () => {
    const response = new Response(null, { status: 503, statusText: "Service Unavailable" });

    await expect(readErrorMessage(response, "文件上传失败")).resolves.toBe(
      "文件上传失败 (503): Service Unavailable"
    );
  });
});
