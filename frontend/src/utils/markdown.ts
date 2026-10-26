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
import DOMPurify from 'dompurify';
// 按需注册而非全量 import 'highlight.js'（全量含 190+ 语言，体积 ~940KB）；
// core + 常用 23 种语言约 150KB，未注册语言自动走 highlightAuto/原文回退
import hljs from 'highlight.js/lib/core';
import 'highlight.js/styles/github.css';
import javascript from 'highlight.js/lib/languages/javascript';
import typescript from 'highlight.js/lib/languages/typescript';
import xml from 'highlight.js/lib/languages/xml';
import css from 'highlight.js/lib/languages/css';
import json from 'highlight.js/lib/languages/json';
import bash from 'highlight.js/lib/languages/bash';
import python from 'highlight.js/lib/languages/python';
import java from 'highlight.js/lib/languages/java';
import sql from 'highlight.js/lib/languages/sql';
import go from 'highlight.js/lib/languages/go';
import rust from 'highlight.js/lib/languages/rust';
import markdownLang from 'highlight.js/lib/languages/markdown';
import yaml from 'highlight.js/lib/languages/yaml';
import c from 'highlight.js/lib/languages/c';
import cpp from 'highlight.js/lib/languages/cpp';
import csharp from 'highlight.js/lib/languages/csharp';
import php from 'highlight.js/lib/languages/php';
import ruby from 'highlight.js/lib/languages/ruby';
import kotlin from 'highlight.js/lib/languages/kotlin';
import swift from 'highlight.js/lib/languages/swift';
import dockerfile from 'highlight.js/lib/languages/dockerfile';
import ini from 'highlight.js/lib/languages/ini';
import plaintext from 'highlight.js/lib/languages/plaintext';

hljs.registerLanguage('javascript', javascript);
hljs.registerLanguage('js', javascript);
hljs.registerLanguage('typescript', typescript);
hljs.registerLanguage('ts', typescript);
hljs.registerLanguage('xml', xml);
hljs.registerLanguage('html', xml);
hljs.registerLanguage('vue', xml);
hljs.registerLanguage('css', css);
hljs.registerLanguage('json', json);
hljs.registerLanguage('bash', bash);
hljs.registerLanguage('sh', bash);
hljs.registerLanguage('shell', bash);
hljs.registerLanguage('python', python);
hljs.registerLanguage('py', python);
hljs.registerLanguage('java', java);
hljs.registerLanguage('sql', sql);
hljs.registerLanguage('go', go);
hljs.registerLanguage('rust', rust);
hljs.registerLanguage('markdown', markdownLang);
hljs.registerLanguage('md', markdownLang);
hljs.registerLanguage('yaml', yaml);
hljs.registerLanguage('yml', yaml);
hljs.registerLanguage('c', c);
hljs.registerLanguage('cpp', cpp);
hljs.registerLanguage('csharp', csharp);
hljs.registerLanguage('cs', csharp);
hljs.registerLanguage('php', php);
hljs.registerLanguage('ruby', ruby);
hljs.registerLanguage('kotlin', kotlin);
hljs.registerLanguage('swift', swift);
hljs.registerLanguage('dockerfile', dockerfile);
hljs.registerLanguage('docker', dockerfile);
hljs.registerLanguage('ini', ini);
hljs.registerLanguage('toml', ini);
hljs.registerLanguage('plaintext', plaintext);
hljs.registerLanguage('text', plaintext);

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
        const langLabel = language && hljs.getLanguage(language) ? language : '';

        let inner: string;
        if (language && hljs.getLanguage(language)) {
          try {
            const highlighted = hljs.highlight(text, { language, ignoreIllegals: true }).value;
            inner = `<code class="language-${escapeAttr(language)}">${highlighted}</code>`;
          } catch {
            // 高亮失败时回退到原文
            inner = `<code>${escapeHtml(text)}</code>`;
          }
        } else {
          // 无语言或不支持时，尝试自动检测
          try {
            inner = `<code>${hljs.highlightAuto(text).value}</code>`;
          } catch {
            inner = `<code>${escapeHtml(text)}</code>`;
          }
        }
        // 包一层 code-block：带语言标签与复制按钮；按钮文案静态、不含用户内容。
        // 复制取 code.textContent（hljs 只加 span 不改文本），经 installCodeCopy 委托实现
        return (
          `<div class="code-block">` +
          `<div class="code-block-bar"><span class="code-block-lang">${escapeHtml(langLabel)}</span>` +
          `<button type="button" class="code-block-copy">复制代码</button></div>` +
          `<pre class="hljs">${inner}</pre>` +
          `</div>`
        );
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
 * HTML 消毒：marked 不做任何消毒，原始 HTML（含 <script>、onerror 等）
 * 会原样通过。渲染「非可信来源」的内容前必须过这一层。
 *
 * <p>为什么要消毒：这个渲染器不只服务 Dify 回复。分享页（ShareView）
 * 会用它渲染**任意登录用户提交的 payload**，且分享链接免登录即可打开——
 * 不消毒就是存储型 XSS，受害者是站内 origin 下的任何访客。
 * 「来源可信」的假设在分享功能上线那天就失效了。</p>
 */
export const sanitizeHtml = (html: string): string =>
  DOMPurify.sanitize(html, {
    // 代码高亮与表格需要的标签/属性保留，其余默认全剥
    USE_PROFILES: { html: true, svg: false, svgFilters: false },
  });

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
    return sanitizeHtml(wrapTables(html));
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

/**
 * 代码块复制按钮的点击委托：绑定一次即可服务所有 v-html 渲染出的 markdown。
 * 在 ChatView 挂载时调用；重复调用不会重复绑定。
 */
let codeCopyInstalled = false;
export const installCodeCopy = () => {
  if (codeCopyInstalled || typeof document === 'undefined') return;
  codeCopyInstalled = true;
  document.addEventListener('click', (event) => {
    const target = event.target as HTMLElement | null;
    const btn = target?.closest?.('.code-block-copy') as HTMLButtonElement | null;
    if (!btn) return;
    const block = btn.closest('.code-block');
    const code = block?.querySelector('code');
    if (!code) return;
    const text = code.textContent ?? '';
    const done = () => {
      btn.textContent = '已复制';
      btn.classList.add('is-copied');
      window.setTimeout(() => {
        btn.textContent = '复制代码';
        btn.classList.remove('is-copied');
      }, 1600);
    };
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(text).then(done).catch(() => fallbackCopy(text, done));
    } else {
      fallbackCopy(text, done);
    }
  });
};

/** execCommand 降级（非安全上下文 / 老浏览器） */
const fallbackCopy = (text: string, done: () => void) => {
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    document.execCommand('copy');
    document.body.removeChild(ta);
    done();
  } catch {
    // 静默失败：不影响阅读
  }
};

export default {
  renderMarkdown,
  renderUserContent,
  preserveNewlines,
  installCodeCopy,
};
