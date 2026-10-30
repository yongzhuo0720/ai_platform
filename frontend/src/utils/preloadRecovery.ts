/**
 * 部署新版本后，仍停留在旧页面的用户手里是旧的 hash 资源名：
 * 下一次点击懒加载路由时，浏览器请求的 chunk 已在服务器上被替换成 404，
 * 页面直接卡死（控制台报 Failed to fetch dynamically imported module）。
 *
 * Vite 的动态 import 失败时会派发可取消的 `vite:preloadError` 事件。
 * 这里接管它并重载一次页面——重载会拿到新的 index.html 与新的资源清单，
 * 绝大多数情况一次就恢复。
 *
 * 必须节流：如果资源是真的缺失（而不是发布替换），无节流重载会变成死循环。
 * 用一个 30 秒窗口的 sessionStorage 标记，同一窗口内只允许自动重载一次。
 */

export const PRELOAD_RELOAD_GUARD_KEY = "ai-platform:preload-reload-at";
const RELOAD_MIN_INTERVAL_MS = 30_000;

export interface PreloadEventTarget {
  addEventListener(type: string, handler: (event: Event) => void): void;
}

export interface ReloadStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

interface InstallOptions {
  target?: PreloadEventTarget;
  reload?: () => void;
  /** null 表示不做节流（拿不到 sessionStorage 时） */
  storage?: ReloadStorage | null;
  now?: () => number;
}

function safeSessionStorage(): ReloadStorage | null {
  try {
    return typeof window !== "undefined" ? window.sessionStorage : null;
  } catch {
    // 隐私模式 / 被策略禁用时访问 sessionStorage 会抛异常
    return null;
  }
}

function shouldThrottle(storage: ReloadStorage | null, nowMs: number): boolean {
  if (!storage) {
    return false;
  }
  try {
    const raw = storage.getItem(PRELOAD_RELOAD_GUARD_KEY);
    // 没有标记（首次失败）不能按 0 处理：否则「当前时间 - 0」永远大于窗口，
    // 逻辑会反转成永远节流，第一次都不重载。
    if (raw !== null) {
      const last = Number(raw);
      if (Number.isFinite(last) && nowMs - last < RELOAD_MIN_INTERVAL_MS) {
        return true;
      }
    }
    storage.setItem(PRELOAD_RELOAD_GUARD_KEY, String(nowMs));
    return false;
  } catch {
    // 存储不可用时宁可不节流：卡在坏页面上比多刷一次更糟
    return false;
  }
}

export function installPreloadRecovery(options: InstallOptions = {}): void {
  const target = options.target ?? window;
  const reload = options.reload ?? (() => window.location.reload());
  const storage = options.storage === undefined ? safeSessionStorage() : options.storage;
  const now = options.now ?? Date.now;

  target.addEventListener("vite:preloadError", (event) => {
    // 不 preventDefault 时 Vite 会把原始错误继续抛出，页面仍是坏的
    event.preventDefault();
    if (shouldThrottle(storage, now())) {
      return;
    }
    reload();
  });
}
