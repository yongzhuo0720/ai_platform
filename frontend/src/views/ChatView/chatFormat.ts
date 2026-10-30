import { renderMarkdown, renderUserContent } from "@/utils/markdown";

/**
 * 渲染助手消息的 Markdown 内容
 * @param content 助手回复的原始 Markdown 文本
 * @returns 渲染后的 HTML 字符串
 */
export const formatContent = (content: string): string => {
  return renderMarkdown(content);
};

/**
 * 渲染用户消息内容（XSS 安全 + 保留换行）
 * @param content 用户输入的原始文本
 * @returns 安全的 HTML 字符串
 */
export const formatUserContent = (content: string): string => {
  return renderUserContent(content);
};

/** 根据文件 MIME 类型返回对应的 emoji 图标 */
export const getFileTypeEmoji = (mimeType: string): string => {
  if (mimeType.startsWith('image/')) return '🖼️';
  if (mimeType === 'application/pdf') return '📕';
  // OpenXML 的 xlsx/docx/pptx 都带 "officedocument" 字样，
  // Excel 必须先判，否则会被 Word 分支抢先命中
  if (mimeType.includes('excel') || mimeType.includes('spreadsheet')) return '📊';
  if (mimeType.includes('word') || mimeType.includes('document')) return '📄';
  if (mimeType.includes('zip') || mimeType.includes('rar') || mimeType.includes('compressed')) return '📦';
  if (mimeType.startsWith('text/')) return '📝';
  if (mimeType.startsWith('video/')) return '🎬';
  if (mimeType.startsWith('audio/')) return '🎵';
  return '📎';
};

/** 格式化文件大小 */
export const formatFileSize = (bytes: number): string => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};
