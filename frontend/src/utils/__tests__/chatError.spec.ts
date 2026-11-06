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

describe("parseChatError 请求 ID 与幂等", () => {
  it("幂等冲突不可重试，保留服务端文案与请求 ID", () => {
    const parsed = parseChatError(
      new Error("相同请求已处理或正在处理中，请勿重复提交（请求 ID：req-dup）")
    );

    expect(parsed.message).toBe("相同请求已处理或正在处理中，请勿重复提交（请求 ID：req-dup）");
    expect(parsed.retryable).toBe(false);
  });

  it("幂等服务不可用可以重试", () => {
    const parsed = parseChatError(
      new Error("幂等服务暂不可用，请稍后重试（请求 ID：req-idem）")
    );

    expect(parsed.message).toBe("幂等服务暂不可用，请稍后重试（请求 ID：req-idem）");
    expect(parsed.retryable).toBe(true);
  });

  it("配额文案里的请求 ID 原样保留，不影响不可重试结论", () => {
    const message = "所在部门今日 AI 额度已用完（已用 5000 / 5000 tokens），请联系管理员";
    const parsed = parseChatError(new Error(`${message}（请求 ID：req-quota）`));

    expect(parsed.message).toBe(`${message}（请求 ID：req-quota）`);
    expect(parsed.retryable).toBe(false);
  });

  it("请求 ID 里的数字不参与状态码分类", () => {
    // 未剥离时 "req-400-abc" 会被 includes("400") 误判成请求参数错误
    const parsed = parseChatError(new Error("上游模型异常（请求 ID：req-400-abc）"));

    expect(parsed.message).toContain("消息发送失败");
    expect(parsed.message).toContain("（请求 ID：req-400-abc）");
  });
});
