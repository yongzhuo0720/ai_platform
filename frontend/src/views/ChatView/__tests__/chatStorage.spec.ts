import { beforeEach, describe, expect, it, vi } from "vitest";
import Cookies from "js-cookie";
import {
  getActiveAssistantStorageKey,
  getActiveModelStorageKey,
  getCurrentUser,
  getHiddenAssistantStorageKey,
  readStorage,
  writeStorage,
} from "../chatStorage";

describe("chatStorage", () => {
  beforeEach(() => {
    localStorage.clear();
    Cookies.remove("uid");
  });

  it("未登录时用户标识为 guest", () => {
    expect(getCurrentUser()).toBe("guest");
    expect(getHiddenAssistantStorageKey()).toBe("chat-hidden-assistants-guest");
    expect(getActiveAssistantStorageKey()).toBe("chat-active-assistant-guest");
    expect(getActiveModelStorageKey()).toBe("chat-active-model-guest");
  });

  it("登录后按 uid 隔离存储键", () => {
    Cookies.set("uid", "u-42");
    expect(getCurrentUser()).toBe("u-42");
    expect(getHiddenAssistantStorageKey()).toBe("chat-hidden-assistants-u-42");
  });

  it("键不存在时返回兜底值", () => {
    expect(readStorage("missing-key", ["fallback"])).toEqual(["fallback"]);
  });

  it("写入后可原样读回", () => {
    writeStorage("demo", { list: [1, 2], enabled: true });
    expect(readStorage<{ list: number[]; enabled: boolean }>("demo", { list: [], enabled: false }))
      .toEqual({ list: [1, 2], enabled: true });
  });

  it("JSON 损坏时返回兜底值并告警", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    localStorage.setItem("broken", "{not-json");
    expect(readStorage("broken", "fallback")).toBe("fallback");
    expect(warn).toHaveBeenCalled();
  });

  it("存储写入失败时只告警不抛错", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const setItem = vi
      .spyOn(Storage.prototype, "setItem")
      .mockImplementation(() => {
        throw new Error("quota exceeded");
      });

    expect(() => writeStorage("k", "v")).not.toThrow();
    expect(warn).toHaveBeenCalled();
    setItem.mockRestore();
  });
});
