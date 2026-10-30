import { describe, expect, it, vi } from "vitest";
import {
  installPreloadRecovery,
  PRELOAD_RELOAD_GUARD_KEY,
  type PreloadEventTarget,
  type ReloadStorage,
} from "../preloadRecovery";

function fakeTarget() {
  let handler: ((event: Event) => void) | null = null;
  const target: PreloadEventTarget = {
    addEventListener(type, listener) {
      if (type === "vite:preloadError") {
        handler = listener;
      }
    },
  };
  return {
    target,
    fire() {
      const event = new Event("vite:preloadError", { cancelable: true });
      handler?.(event);
      return event;
    },
  };
}

function fakeStorage(): ReloadStorage & { values: Map<string, string> } {
  const values = new Map<string, string>();
  return {
    values,
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => {
      values.set(key, value);
    },
  };
}

describe("preloadRecovery", () => {
  it("动态 import 失败时阻止 Vite 抛错并重载一次", () => {
    const { target, fire } = fakeTarget();
    const reload = vi.fn();
    installPreloadRecovery({ target, reload, storage: fakeStorage(), now: () => 1_000 });

    const event = fire();

    expect(event.defaultPrevented).toBe(true);
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it("30 秒内再次失败不重复重载，避免资源真缺失时死循环", () => {
    const { target, fire } = fakeTarget();
    const reload = vi.fn();
    let nowMs = 1_000;
    installPreloadRecovery({ target, reload, storage: fakeStorage(), now: () => nowMs });

    fire();
    nowMs += 5_000;
    fire();

    expect(reload).toHaveBeenCalledTimes(1);
  });

  it("超过 30 秒后允许再次自动重载", () => {
    const { target, fire } = fakeTarget();
    const reload = vi.fn();
    let nowMs = 1_000;
    installPreloadRecovery({ target, reload, storage: fakeStorage(), now: () => nowMs });

    fire();
    nowMs += 30_000;
    fire();

    expect(reload).toHaveBeenCalledTimes(2);
  });

  it("storage 不可用时不节流也要恢复，且不抛出异常", () => {
    const { target, fire } = fakeTarget();
    const reload = vi.fn();
    const brokenStorage: ReloadStorage = {
      getItem: () => {
        throw new Error("SecurityError");
      },
      setItem: () => {
        throw new Error("SecurityError");
      },
    };
    installPreloadRecovery({ target, reload, storage: brokenStorage, now: () => 1_000 });

    expect(() => fire()).not.toThrow();
    expect(reload).toHaveBeenCalledTimes(1);

    fire();
    expect(reload).toHaveBeenCalledTimes(2);
  });

  it("节流标记写入 sessionStorage 的固定键", () => {
    const { target, fire } = fakeTarget();
    const storage = fakeStorage();
    installPreloadRecovery({ target, reload: vi.fn(), storage, now: () => 42_000 });

    fire();

    expect(storage.values.get(PRELOAD_RELOAD_GUARD_KEY)).toBe("42000");
  });
});
