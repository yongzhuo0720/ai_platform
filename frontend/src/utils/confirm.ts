/**
 * ElMessageBox 确认框的「取消」判断。
 *
 * Element Plus 的 confirm 在用户拒绝时有三种取值：
 *   - 点击「取消」按钮  → reject('cancel')
 *   - 按 ESC 键          → reject('close')
 *   - 点击遮罩层关闭      → reject('close')
 *
 * 项目里原来有 8 处手写判断，其中 5 处只写了 `err !== 'cancel'`，
 * 漏掉 'close' —— 结果是用户按 ESC 关掉确认框，也会弹一个「删除失败」。
 *
 * 统一收口到这里，改一处逻辑全局生效。
 */
export function isConfirmCancelled(err: unknown): boolean {
  return (
    err === 'cancel' ||
    err === 'close' ||
    (err as { message?: unknown } | null)?.message === 'cancel'
  );
}
