/**
 * 用户工具函数
 * 用于获取当前登录用户信息
 */
import Cookies from 'js-cookie';

/**
 * 获取当前用户的唯一标识符（UID）
 * @returns 用户 UID，如果未登录则返回默认值 'guest'
 * @description
 * 从 Cookie 中读取用户 UID，用于 Dify API 的用户隔离
 * 每个用户的会话历史将独立存储，互不干扰
 */
export const getCurrentUserId = (): string => {
  const uid = Cookies.get('uid');
  
  // 如果未登录或 UID 不存在，返回默认值
  if (!uid || uid.trim() === '') {
    console.warn('[用户工具] 未找到用户 UID，使用默认值 guest');
    return 'guest';
  }
  
  return uid;
};

/**
 * 获取当前用户的用户名
 * @returns 用户名，如果未登录则返回 null
 */
export const getCurrentUserName = (): string | null => {
  return Cookies.get('uname') || null;
};

/**
 * 获取当前用户的账号
 * @returns 用户账号，如果未登录则返回 null
 */
export const getCurrentUserAccount = (): string | null => {
  return Cookies.get('uaccess') || null;
};

/**
 * 检查用户是否已登录
 * @returns 是否已登录
 */
export const isUserLoggedIn = (): boolean => {
  const loginToken = Cookies.get('loginToken');
  const uid = Cookies.get('uid');
  return !!(loginToken && uid);
};
