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
