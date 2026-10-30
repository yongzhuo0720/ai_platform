import { getCurrentUser } from "./chatStorage";

/**
 * 模块级可变运行时状态。
 *
 * <p>请求令牌、停止函数等无法放进 ref：它们不参与渲染，只用于竞态保护
 * 与中止当前流。集中在同一个对象里，拆分模块后仍共享同一份实例。</p>
 */
export const chatRuntime = {
  /** 当前数据归属用户；Cookie 中的用户变化时由 useChat 重置 */
  ownerUserId: getCurrentUser(),
  /** 会话列表刷新令牌：旧分页响应不得追加到新列表 */
  conversationsRequestToken: 0,
  /** 连点会话时的竞态保护：只有最后一次请求可以写入消息列表 */
  historyRequestToken: 0,
  /** 当前流式请求令牌：过期回调直接丢弃 */
  currentRequestToken: 0,
  currentStopFn: null as (() => void) | null,
  currentPartialSaveFn: null as (() => void) | null,
  /** 前置插入更早消息期间暂停自动跟随，避免视口被拽回底部 */
  suppressAutoScroll: false,
};

/**
 * 由 useChat() 注入的「新建对话」回调。
 *
 * <p>confirmDeleteAssistant 是模块顶层函数，删除「当前正在使用」的助手后
 * 需要清空对话，但它访问不到 useChat() 作用域内的 startNewConversation。
 * 这里用注入解耦；未注入时安全跳过。</p>
 */
let startNewConversationHandler: (() => void) | null = null;

export const setStartNewConversationHandler = (handler: (() => void) | null) => {
  startNewConversationHandler = handler;
};

export const runStartNewConversationHandler = () => {
  startNewConversationHandler?.();
};
