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

/**
 * 逐条流式事件日志的独立开关。
 *
 * 一条长回答会产生几百个 SSE 事件，每个都带完整 payload——即使只在开发环境打印，
 * 也足够把控制台刷到没法用、并把每一帧对象都留在内存里。所以这类日志除了 DEV，
 * 还要显式打开：浏览器控制台执行 `localStorage.debug_stream = '1'` 后刷新才输出。
 */
const isStreamDebug = (): boolean => {
  try {
    return typeof localStorage !== 'undefined' && localStorage.getItem('debug_stream') === '1';
  } catch {
    // 隐私模式下访问 localStorage 会抛，按关闭处理
    return false;
  }
};

export const debug = {
  /** 仅开发环境输出的调试日志 */
  log: (...args: unknown[]): void => {
    if (isDev) console.log(...args);
  },
  /** 逐条 SSE 事件 / chunk 的跟踪日志：默认关，需要时置 localStorage.debug_stream='1' */
  stream: (...args: unknown[]): void => {
    if (isDev && isStreamDebug()) console.log(...args);
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
