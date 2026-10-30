import { describe, expect, it } from "vitest";
import { parseChatError } from "../chatError";

describe("parseChatError 配额与限流", () => {
  it("配额不足展示服务端原因，且不会被 tokens 误判成认证失败", () => {
    const parsed = parseChatError(
      new Error(
        "请求失败 (429): {\"code\":\"quota_exceeded\",\"message\":\"今日 AI 额度已用完（已用 1500 / 1500 tokens），请明天再试或联系管理员\",\"status\":429}"
      )
    );

    expect(parsed.message).toBe(
      "今日 AI 额度已用完（已用 1500 / 1500 tokens），请明天再试或联系管理员"
    );
    expect(parsed.retryable).toBe(false);
    expect(parsed.suggestCheckConfig).toBe(false);
  });

  it("已清洗的配额文案原样展示（直连模型路径）", () => {
    const message = "所在部门今日 AI 额度已用完（已用 5000 / 5000 tokens），请联系管理员";
    const parsed = parseChatError(new Error(message));

    expect(parsed.message).toBe(message);
    expect(parsed.retryable).toBe(false);
  });

  it("每分钟限流提示可以重试", () => {
    const parsed = parseChatError(
      new Error(
        "请求失败 (429): {\"code\":\"rate_limited\",\"message\":\"请求过于频繁，每分钟最多 120 次，请稍后再试\"}"
      )
    );

    expect(parsed.message).toBe("请求过于频繁，每分钟最多 120 次，请稍后再试");
    expect(parsed.retryable).toBe(true);
  });

  it("401 仍按认证失败处理，不受新增分支影响", () => {
    const parsed = parseChatError(new Error("请求失败 (401): unauthorized"));

    expect(parsed.message).toContain("认证失败");
    expect(parsed.suggestCheckConfig).toBe(true);
  });
});
