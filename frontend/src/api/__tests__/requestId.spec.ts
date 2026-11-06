import { afterEach, describe, expect, it, vi } from "vitest";
import { newIdempotencyKey, newRequestId } from "../requestId";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("请求追踪 ID", () => {
  it("生成的 ID 不超过后端 64 字符上限", () => {
    expect(newRequestId().length).toBeLessThanOrEqual(64);
  });

  it("没有 crypto.randomUUID 时退回时间戳随机串", () => {
    vi.stubGlobal("crypto", undefined);

    const id = newRequestId();

    expect(id).toMatch(/^req-[a-z0-9]+-[a-z0-9]+$/);
    expect(id.length).toBeLessThanOrEqual(64);
  });
});

describe("幂等键", () => {
  it("满足后端 8~128 位字符集约束", () => {
    expect(newIdempotencyKey()).toMatch(/^[A-Za-z0-9._:-]{8,128}$/);
  });

  it("每次生成都不同，用户重发属于新意图", () => {
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey());
  });
});
