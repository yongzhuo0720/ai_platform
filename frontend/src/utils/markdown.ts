/**
 * Markdown 渲染工具
 * @description 统一处理对话模块的 Markdown 渲染，集成 highlight.js 代码高亮
 *
 * 设计要点：
 * 1. 单例配置 marked，避免重复初始化
 * 2. breaks: true —— 单换行 \n 转换为 <br>，符合聊天场景
 * 3. gfm: true —— 支持 GitHub 风格 Markdown（表格、删除线、任务列表等）
 * 4. 集成 highlight.js 进行代码块高亮
 * 5. 对用户输入内容做 HTML 转义后再保留换行，防止 XSS
 */

import { marked } from 'marked';
import hljs from 'highlight.js';
import 'highlight.js/styles/github.css';

/**
 * 配置 marked 渲染器（仅执行一次）
 */
let configured = false;
// 注意：额外放行 blob: 与 data:image/*，用于支持应用内联图片(base64 / 对象 URL)，
// 但显式排除 data:text/html、javascript: 等危险类型，避免 XSS。
const SAFE_URL_PATTERN = /^(https?:|mailto:|tel:|\/|#|blob:|data:image\/[a-zA-Z0-9.+-]+)/i;

const normalizeMarkdown = (content: string): string => {
  return String(content ?? '').replace(/\r\n?/g, '\n');
};

const wrapTables = (html: string): string => {
  return html
    .replace(/<table>/g, '<div class="markdown-table-wrap"><table>')
    .replace(/<\/table>/g, '</table></div>');
};

const configureMarked = (): void => {
  if (configured) return;
  configured = true;

  marked.setOptions({
    gfm: true,
    breaks: true,
    pedantic: false,
  });

  // 自定义 code 渲染以支持 highlight.js
  marked.use({
    renderer: {
      code(this: unknown, code: { text: string; lang?: string } | string, lang?: string) {
        // marked v12 中 code 回调签名：({ text, lang }) 优先
        let text: string;
        let language: string | undefined;
        if (typeof code === 'object' && code !== null) {
          text = code.text;
          language = code.lang;
        } else {
          text = String(code ?? '');
          language = lang;
        }

        if (language && hljs.getLanguage(language)) {
          try {
            const highlighted = hljs.highlight(text, { language, ignoreIllegals: true }).value;
            return `<pre class="hljs"><code class="language-${escapeAttr(language)}">${highlighted}</code></pre>`;
          } catch {
            // 高亮失败时回退到原文
          }
        }
        // 无语言或不支持时，尝试自动检测
        try {
          const detected = hljs.highlightAuto(text).value;
          return `<pre class="hljs"><code>${detected}</code></pre>`;
        } catch {
          return `<pre class="hljs"><code>${escapeHtml(text)}</code></pre>`;
        }
      },
      html(html: string) {
        return escapeHtml(html);
      },
      image(href: string, title: string | null | undefined, text: string) {
        const source = escapeUrl(href);
        if (!source) return escapeHtml(text);
        const titleAttr = title ? ` title="${escapeHtml(title)}"` : '';
        return `<img src="${source}" alt="${escapeHtml(text)}"${titleAttr}>`;
      },
      link(rawHref: string, title: string | null | undefined, text: string) {
        const href = escapeUrl(rawHref);

        if (!href) {
          return text;
        }

        const titleAttr = title ? ` title="${escapeHtml(title)}"` : '';
        const externalAttr = /^(https?:|mailto:)/i.test(href)
          ? ' target="_blank" rel="noopener noreferrer"'
          : '';

        return `<a href="${href}"${externalAttr}${titleAttr}>${text}</a>`;
      },
    },
  });
};

/**
 * 渲染助手消息内容（可信任来源，来自 Dify）
 * @param content 原始 Markdown 文本
 * @returns 渲染后的 HTML 字符串
 */
export const renderMarkdown = (content: string): string => {
  const normalizedContent = normalizeMarkdown(content);
  if (!normalizedContent.trim()) return '';
  configureMarked();
  try {
    const result = marked.parse(normalizedContent);
    const html = typeof result === 'string' ? result : '';
    return wrapTables(html);
  } catch (err) {
    console.warn('[markdown] 渲染失败，使用安全降级:', err);
    return `<p>${escapeHtml(normalizedContent)}</p>`;
  }
};

/**
 * 渲染用户消息内容（不信任来源）
 * @description 转义 HTML 防止 XSS，但保留换行（\n -> <br>）
 * @param content 用户原始输入
 * @returns 安全的 HTML 字符串
 */
export const renderUserContent = (content: string): string => {
  if (!content) return '';
  return escapeHtml(content).replace(/\n/g, '<br>');
};

/**
 * 仅保留换行的纯文本（用于 v-text / {{}} 插值）
 * @description 配合 CSS `white-space: pre-wrap` 使用
 */
export const preserveNewlines = (content: string): string => content ?? '';

/**
 * HTML 实体转义
 */
const escapeHtml = (str: string): string => {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
};

/**
 * HTML 属性值转义
 */
const escapeAttr = (str: string): string => {
  return String(str).replace(/[^a-zA-Z0-9_-]/g, '');
};

const escapeUrl = (str: string): string => {
  const normalized = String(str ?? '').trim();
  if (!normalized || !SAFE_URL_PATTERN.test(normalized)) {
    return '';
  }
  return escapeHtml(normalized);
};

export default {
  renderMarkdown,
  renderUserContent,
  preserveNewlines,
};
