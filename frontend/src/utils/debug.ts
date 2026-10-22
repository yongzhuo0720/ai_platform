/**
 * 开发期调试日志。
 *
 * 背景：项目里散着 200 多处 console.*，其中大部分是 SSE 解析、数据流水的
 * 调试输出。生产环境（`npm run build`）下这些日志会全部打到用户浏览器控制台，
 * 既刷屏，也把内部数据结构和接口字段暴露给使用者。
 *
 * 用法：
 *   import { debug } from '@/utils/debug'
 *   debug.log('xxx', data)     // 仅开发环境输出
 *
 * 注意：debug.error / debug.warn 仍然保留 console 语义，
 * 因为错误与告警在生产环境同样有价值，不应被静音。
 */
const isDev = import.meta.env.DEV;

export const debug = {
  /** 仅开发环境输出的调试日志 */
  log: (...args: unknown[]): void => {
    if (isDev) console.log(...args);
  },
  /** 仅开发环境输出的调试告警 */
  warn: (...args: unknown[]): void => {
    if (isDev) console.warn(...args);
  },
  /** 错误日志：任何环境都输出 */
  error: (...args: unknown[]): void => {
    console.error(...args);
  },
};
