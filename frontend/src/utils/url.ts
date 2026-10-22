/**
 * URL 安全校验工具
 * @description 防止 window.open / location 跳转到 javascript: / data: 等危险协议
 */

// 允许的安全协议白名单
const SAFE_PROTOCOLS = ['http:', 'https:', 'mailto:', 'tel:'];

/**
 * 判断 URL 是否使用安全协议
 * - 绝对地址(含协议)：仅放行 http/https/mailto/tel
 * - 相对地址(/、#、./)：视为安全
 * - 其它(如 javascript:、data:)一律拦截
 * @param url 待校验的 URL
 * @returns 安全返回 true，否则 false
 */
export const isSafeUrl = (url: string): boolean => {
  if (!url || typeof url !== 'string') return false;
  try {
    const parsed = new URL(url, window.location.origin);
    return SAFE_PROTOCOLS.includes(parsed.protocol);
  } catch {
    // 解析失败说明是相对路径或非标准地址
    if (url.startsWith('/') || url.startsWith('#') || url.startsWith('./')) return true;
    return false;
  }
};

/**
 * 安全打开新窗口：仅允许安全协议，并默认附加 noopener,noreferrer
 * @param url 目标地址
 * @param target 窗口目标，默认 '_blank'
 * @param features 窗口特性，默认 'noopener,noreferrer'
 * @returns 打开的窗口引用，或 null（地址不安全 / 被浏览器拦截）
 */
export const safeOpenUrl = (
  url: string,
  target = '_blank',
  features = 'noopener,noreferrer',
): Window | null => {
  if (!isSafeUrl(url)) {
    console.warn('[url] 拦截不安全的跳转地址:', url);
    return null;
  }
  return window.open(url, target, features);
};
