/**
 * 通知展示相关的纯函数，便于单测覆盖边界。
 */

const pad = (num: number) => String(num).padStart(2, '0')

/** 后端 createtime 有两种形态：毫秒时间戳或 `2026-11-06 10:00:00` 字符串 */
export function parseNotificationTime(value: string | number | null | undefined): Date | null {
  if (value === null || value === undefined || value === '') return null
  const date =
    typeof value === 'number'
      ? new Date(value)
      : new Date(String(value).replace(/\//g, '-').replace(' ', 'T'))
  return Number.isNaN(date.getTime()) ? null : date
}

/** 列表里的相对时间：一小时内按分钟，跨天按天数，超过一周显示日期 */
export function formatNotificationTime(
  value: string | number | null | undefined,
  now: number = Date.now(),
): string {
  const date = parseNotificationTime(value)
  if (!date) return ''
  const diff = now - date.getTime()
  // 客户端时钟慢于服务端时会出现负数，按「刚刚」处理而不是显示未来时间
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  const days = Math.floor(diff / 86_400_000)
  if (days === 1) return '昨天'
  if (days < 7) return `${days} 天前`
  const current = new Date(now)
  if (date.getFullYear() === current.getFullYear()) {
    return `${date.getMonth() + 1}-${date.getDate()} ${pad(date.getHours())}:${pad(date.getMinutes())}`
  }
  return `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`
}

/** 悬停提示用的完整时间 */
export function formatNotificationAbsolute(
  value: string | number | null | undefined,
): string {
  const date = parseNotificationTime(value)
  if (!date) return ''
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}

/**
 * 通知跳转只允许站内路由路径。
 *
 * <p>链接由后端写入，但仍按不可信输入处理：`//evil.com` 是协议相对地址，
 * 反斜杠会被浏览器当成斜杠，两者都会离开本站，必须拦掉。</p>
 */
export function normalizeNotificationLink(link: string | null | undefined): string | null {
  const value = (link ?? '').trim()
  if (!value) return null
  if (!value.startsWith('/') || value.startsWith('//')) return null
  if (value.includes('\\')) return null
  if (/[\u0000-\u001f]/.test(value)) return null
  return value.length > 255 ? value.slice(0, 255) : value
}
