/**
 * element-plus 轻量桩：仅供 Vitest 使用（见 vitest.config.ts 的 alias）。
 *
 * <p>逻辑层模块只用 ElMessage 做用户提示，引入真实 element-plus 会把整套
 * 组件树带进 Node 环境。这里保留同名 API 与调用痕迹（Vi 可 spy），
 * 不产生任何真实 UI 行为。</p>
 */
const noop = (): void => undefined;

export const ElMessage = {
  success: noop,
  warning: noop,
  info: noop,
  error: noop,
  closeAll: noop,
};

export const ElMessageBox = {
  alert: async (): Promise<void> => undefined,
  confirm: async (): Promise<string> => "confirm",
  prompt: async (): Promise<{ value: string }> => ({ value: "" }),
};

export default { ElMessage, ElMessageBox };
