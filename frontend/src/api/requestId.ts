/**
 * 请求追踪与幂等键生成。
 *
 * 两个约束来自后端：
 * - `X-Request-Id` 最长 64 字符，超长会被网关丢弃并重新生成；
 * - `Idempotency-Key` 只允许 8~128 位 `[A-Za-z0-9._:-]`。
 */

/** 生成一次请求的追踪 ID；后端会原样回显，便于把浏览器与服务端日志对上 */
export function newRequestId(): string {
  const cryptoObj = typeof globalThis !== 'undefined' ? globalThis.crypto : undefined
  if (cryptoObj && typeof cryptoObj.randomUUID === 'function') {
    return cryptoObj.randomUUID()
  }
  // 非安全上下文（内网 HTTP）没有 randomUUID，退回时间戳 + 随机串
  return `req-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

/**
 * 生成写请求的幂等键。
 *
 * 同一个键在 10 分钟内只允许执行一次；浏览器或代理把同一个 POST 重发两次时，
 * 请求头会一起重发，第二次会被网关挡住，避免重复计费与重复建数据。
 * 用户主动「重新生成」是一次新的意图，必须使用新键。
 */
export function newIdempotencyKey(): string {
  return `idem-${newRequestId()}`
}
