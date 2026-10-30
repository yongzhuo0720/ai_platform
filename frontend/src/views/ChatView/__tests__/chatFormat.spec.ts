import { describe, expect, it } from "vitest";
import {
  formatContent,
  formatFileSize,
  formatUserContent,
  getFileTypeEmoji,
} from "../chatFormat";

describe("getFileTypeEmoji", () => {
  it.each([
    ["image/png", "🖼️"],
    ["image/jpeg", "🖼️"],
    ["application/pdf", "📕"],
    ["application/msword", "📄"],
    [
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "📄",
    ],
    ["application/vnd.ms-excel", "📊"],
    [
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "📊",
    ],
    ["application/zip", "📦"],
    ["application/x-rar-compressed", "📦"],
    ["text/plain", "📝"],
    ["video/mp4", "🎬"],
    ["audio/mpeg", "🎵"],
    ["application/octet-stream", "📎"],
  ])("%s 应返回 %s", (mime, emoji) => {
    expect(getFileTypeEmoji(mime)).toBe(emoji);
  });
});

describe("formatFileSize", () => {
  it("小于 1KB 按字节显示", () => {
    expect(formatFileSize(0)).toBe("0 B");
    expect(formatFileSize(1023)).toBe("1023 B");
  });

  it("KB 与 MB 保留一位小数", () => {
    expect(formatFileSize(1024)).toBe("1.0 KB");
    expect(formatFileSize(1536)).toBe("1.5 KB");
    expect(formatFileSize(1024 * 1024)).toBe("1.0 MB");
    expect(formatFileSize(2 * 1024 * 1024)).toBe("2.0 MB");
  });
});

describe("formatContent", () => {
  it("渲染 Markdown 并输出 HTML", () => {
    expect(formatContent("**加粗**")).toContain("<strong>加粗</strong>");
  });

  it("剥离脚本与危险链接（XSS 防护）", () => {
    const html = formatContent(
      '<script>alert(1)</script>[点我](javascript:evil)\n\n正文'
    );
    // 原始 HTML 被转义，javascript: 链接不生成 <a> 标签
    expect(html).not.toContain("<script");
    expect(html).not.toContain('href="javascript');
    expect(html).not.toContain("<a ");
    expect(html).toContain("点我");
    expect(html).toContain("正文");
  });

  it("空内容返回空串", () => {
    expect(formatContent("   ")).toBe("");
  });
});

describe("formatUserContent", () => {
  it("转义 HTML 标签", () => {
    expect(formatUserContent("<b>hi</b>")).toBe("&lt;b&gt;hi&lt;/b&gt;");
  });

  it("保留换行", () => {
    expect(formatUserContent("第一行\n第二行")).toBe("第一行<br>第二行");
  });

  it("空内容返回空串", () => {
    expect(formatUserContent("")).toBe("");
  });
});
