import { getCurrentUserId } from "@/utils/user";

/**
 * 获取当前用户标识
 * @description 从 Cookie 中动态获取用户 UID，实现会话隔离
 * 每个用户只能看到自己的会话历史
 */
export const getCurrentUser = (): string => {
  return getCurrentUserId();
};

export const getHiddenAssistantStorageKey = () =>
  `chat-hidden-assistants-${getCurrentUser()}`;
export const getActiveAssistantStorageKey = () =>
  `chat-active-assistant-${getCurrentUser()}`;
export const getActiveModelStorageKey = () => `chat-active-model-${getCurrentUser()}`;

export const readStorage = <T>(key: string, fallback: T): T => {
  if (typeof window === "undefined") {
    return fallback;
  }
  try {
    const raw = window.localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch (error) {
    console.warn(`读取本地存储失败: ${key}`, error);
    return fallback;
  }
};

export const writeStorage = (key: string, value: unknown) => {
  if (typeof window === "undefined") {
    return;
  }
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch (error) {
    console.warn(`写入本地存储失败: ${key}`, error);
  }
};

/**
 * 直连会话的本地墓碑。
 *
 * <p>删除先改本地、再尽力删云端。云端删除失败（离线、服务异常）时记下 id，
 * 登录后合并云端列表时跳过并重试删除，避免已删会话在下次登录时「复活」。</p>
 */
export const getDirectDeletedStorageKey = (uid: string) => `ai-direct-deleted-${uid}`;
/** 墓碑只用于拦截云端旧数据，数量无需无限增长 */
const MAX_DELETED_TOMBSTONES = 200;

export const readDeletedConversationIds = (uid: string): string[] =>
  readStorage<string[]>(getDirectDeletedStorageKey(uid), []);

export const addDeletedConversationId = (uid: string, convId: string) => {
  const ids = readDeletedConversationIds(uid).filter((id) => id !== convId);
  ids.push(convId);
  writeStorage(getDirectDeletedStorageKey(uid), ids.slice(-MAX_DELETED_TOMBSTONES));
};

export const removeDeletedConversationId = (uid: string, convId: string) => {
  const ids = readDeletedConversationIds(uid);
  if (!ids.includes(convId)) {
    return;
  }
  writeStorage(
    getDirectDeletedStorageKey(uid),
    ids.filter((id) => id !== convId)
  );
};
