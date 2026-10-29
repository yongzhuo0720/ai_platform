import Cookies from "js-cookie";

/**
 * 登录态 Cookie 的统一清理（2026-10-29 新增）。
 *
 * <p>修复前项目里有<b>三套互相不一致</b>的登出实现：</p>
 * <ul>
 *   <li>LoginView 清了 admin_* 五个，但<b>漏掉 admin_access</b>；
 *       而路由守卫正是靠 admin_access === '1' 判断能不能进后台，
 *       从这里登出后残留的 admin_access 会让守卫做出错误判断。</li>
 *   <li>MainLayout 清用户态时<b>没传 { path: '/' }</b>，
 *       而这些 Cookie 当初是用 { path: '/' } 写入的，
 *       不带同样的 path 删除会失败——等于登出后 Cookie 还在。</li>
 *   <li>AdminLayout 是唯一完整的那份。</li>
 * </ul>
 *
 * <p>统一到这里，保证「写用什么 path，删就用什么 path」，
 * 且两边的键列表各自维护一份，不会再漏。</p>
 */

/** 后台管理侧的登录态键（写入时用的是 { path: '/' }，删除必须带同样的 path） */
export const ADMIN_SESSION_KEYS = [
  "admin_loginToken",
  "admin_rid",
  "admin_uaccess",
  "admin_uname",
  "admin_uid",
  "admin_access",
] as const;

/** 业务侧的登录态键 */
export const USER_SESSION_KEYS = ["loginToken", "rid", "uaccess", "uname", "uid"] as const;

const COOKIE_OPTIONS = { path: "/" } as const;

/** 清理后台管理登录态 */
export const clearAdminSession = (): void => {
  ADMIN_SESSION_KEYS.forEach((key) => Cookies.remove(key, COOKIE_OPTIONS));
};

/**
 * 清理业务侧登录态，并清掉按 uid 存放在 localStorage 里的聊天偏好。
 * （这些偏好如果不清，换账号登录后会串到上一个账号的助手/模型选择上。）
 */
export const clearUserSession = (): void => {
  const uid = Cookies.get("uid");
  if (uid) {
    localStorage.removeItem(`chat-hidden-assistants-${uid}`);
    localStorage.removeItem(`chat-active-assistant-${uid}`);
    localStorage.removeItem(`chat-active-model-${uid}`);
  }
  USER_SESSION_KEYS.forEach((key) => Cookies.remove(key, COOKIE_OPTIONS));
};

/** 两边都清（用于「退出全部登录」或确认不再需要区分场景时） */
export const clearAllSessions = (): void => {
  clearAdminSession();
  clearUserSession();
};
