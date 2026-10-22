/**
 * 安全的 JSON 解析：失败时返回兜底值，并打印错误。
 *
 * 原来项目里有 4 处这样的写法：
 *   let x: T[] = [];
 *   try { x = JSON.parse(raw || '[]'); } catch {}
 * 空 catch 会把解析失败的原因整个吞掉 —— 出问题时只能看到"结果为空"，
 * 不知道是数据本身坏了还是字段名变了。这里统一收口，既保留兜底行为，
 * 也让失败可追溯。
 *
 * @param raw 待解析的字符串；为空 / null / undefined 时直接返回 fallback
 * @param fallback 解析失败或输入为空时返回的值
 */
export function safeJsonParse<T>(raw: string | null | undefined, fallback: T): T {
  if (!raw) return fallback;
  try {
    return JSON.parse(raw) as T;
  } catch (err) {
    console.error('[safeJsonParse] JSON 解析失败，使用兜底值:', err);
    return fallback;
  }
}
