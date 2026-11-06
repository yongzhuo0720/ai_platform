import { describe, expect, it } from "vitest";

import {
  formatNotificationAbsolute,
  formatNotificationTime,
  normalizeNotificationLink,
  parseNotificationTime,
} from "../notification";

const NOW = new Date(2026, 10, 6, 12, 0, 0).getTime();

describe("parseNotificationTime", () => {
  it("兼容毫秒时间戳与数据库时间字符串", () => {
    expect(parseNotificationTime(NOW)?.getTime()).toBe(NOW);
    expect(parseNotificationTime("2026-11-06 12:00:00")?.getTime()).toBe(NOW);
    expect(parseNotificationTime("2026/11/06 12:00:00")?.getTime()).toBe(NOW);
  });

  it("空值与非法文本返回 null，不产生 Invalid Date", () => {
    expect(parseNotificationTime(null)).toBeNull();
    expect(parseNotificationTime(undefined)).toBeNull();
    expect(parseNotificationTime("")).toBeNull();
    expect(parseNotificationTime("not-a-date")).toBeNull();
  });
});

describe("formatNotificationTime", () => {
  const at = (offsetMs: number) => formatNotificationTime(NOW - offsetMs, NOW);

  it("一分钟内显示刚刚，负数（客户端时钟偏差）也按刚刚处理", () => {
    expect(at(30_000)).toBe("刚刚");
    expect(at(59_999)).toBe("刚刚");
    expect(at(-5_000)).toBe("刚刚");
  });

  it("一小时内按分钟，一天内按小时", () => {
    expect(at(5 * 60_000)).toBe("5 分钟前");
    expect(at(59 * 60_000)).toBe("59 分钟前");
    expect(at(2 * 3_600_000)).toBe("2 小时前");
    expect(at(23 * 3_600_000)).toBe("23 小时前");
  });

  it("跨天显示昨天 / N 天前，超过一周显示日期", () => {
    expect(at(25 * 3_600_000)).toBe("昨天");
    expect(at(3 * 86_400_000)).toBe("3 天前");
    expect(formatNotificationTime(new Date(2026, 9, 20, 9, 5).getTime(), NOW)).toBe("10-20 09:05");
  });

  it("跨年显示完整年月日", () => {
    expect(formatNotificationTime(new Date(2025, 11, 31, 23, 59).getTime(), NOW)).toBe("2025-12-31");
  });

  it("非法时间返回空串，由模板决定不展示时间", () => {
    expect(formatNotificationTime("broken", NOW)).toBe("");
  });
});

describe("formatNotificationAbsolute", () => {
  it("输出可读的完整时间用于悬停提示", () => {
    expect(formatNotificationAbsolute("2026-11-06 12:00:00")).toBe("2026-11-06 12:00:00");
    expect(formatNotificationAbsolute(null)).toBe("");
  });
});

describe("normalizeNotificationLink", () => {
  it("只放行站内绝对路径，保留查询参数", () => {
    expect(normalizeNotificationLink("/knowledge-bases")).toBe("/knowledge-bases");
    expect(normalizeNotificationLink("/rag-eval?datasetKey=abc")).toBe("/rag-eval?datasetKey=abc");
    expect(normalizeNotificationLink("  /agent  ")).toBe("/agent");
  });

  it("拦截协议相对地址、反斜杠、绝对 URL 与空值", () => {
    expect(normalizeNotificationLink("//evil.example.com")).toBeNull();
    expect(normalizeNotificationLink("/\\evil.example.com")).toBeNull();
    expect(normalizeNotificationLink("https://evil.example.com")).toBeNull();
    expect(normalizeNotificationLink("javascript:alert(1)")).toBeNull();
    expect(normalizeNotificationLink("knowledge-bases")).toBeNull();
    expect(normalizeNotificationLink("")).toBeNull();
    expect(normalizeNotificationLink(null)).toBeNull();
  });

  it("控制字符会被拦截，避免 header 注入式的异常路径", () => {
    expect(normalizeNotificationLink("/a\u0000b")).toBeNull();
    expect(normalizeNotificationLink("/a\nb")).toBeNull();
  });
});
