import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { JSDOM } from 'jsdom';

// ---------------------------------------------------------------------------
// 2026-10-29：给 Node 补一个 DOM 环境。
//
// 这个脚本原本一直是失败的（"purify_default.sanitize is not a function"）：
// markdown.ts 用 DOMPurify 做 XSS 消毒，而 DOMPurify 在**导入时**就要读 window，
// Node 里没有 window，于是拿到一个未初始化的实例，调用 sanitize 直接抛错。
//
// 这意味着：这 10 条 XSS 断言从来没真正跑起来过——"防住了 javascript: 链接"
// 这类结论其实是没有验证的。修的不是断言，是让它真的能运行。
//
// 必须在 import markdown 之前注入，因为 DOMPurify 是在模块初始化时取 window 的。
// ---------------------------------------------------------------------------
const dom = new JSDOM('<!doctype html><html><body></body></html>', {
  url: 'http://localhost/',
});
globalThis.window = dom.window;
globalThis.document = dom.window.document;
globalThis.Node = dom.window.Node;
globalThis.Element = dom.window.Element;
globalThis.HTMLElement = dom.window.HTMLElement;
globalThis.DocumentFragment = dom.window.DocumentFragment;
globalThis.NodeFilter = dom.window.NodeFilter;
globalThis.trustedTypes = undefined;

const result = await build({
  entryPoints: ['src/utils/markdown.ts'],
  bundle: true,
  write: false,
  format: 'esm',
  platform: 'node',
  loader: { '.css': 'empty' },
});
const moduleUrl = `data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString('base64')}`;
const { renderMarkdown, renderUserContent } = await import(moduleUrl);

assert.match(renderMarkdown('[文档](https://example.com "帮助")'), /<a href="https:\/\/example.com"[^>]*>文档<\/a>/);
assert.match(renderMarkdown('[**文档**](/docs)'), /<a href="\/docs"><strong>文档<\/strong><\/a>/);
assert.doesNotMatch(renderMarkdown('[危险](javascript:alert(1))'), /href=/);
assert.doesNotMatch(renderMarkdown('<img src=x onerror=alert(1)>'), /<img/);
assert.doesNotMatch(renderMarkdown('![危险](javascript:alert(1))'), /<img/);
assert.match(renderMarkdown('![示意图](https://example.com/image.png)'), /alt="示意图"/);
assert.match(renderMarkdown('| A | B |\n| --- | --- |\n| 1 | 2 |'), /markdown-table-wrap/);
assert.match(renderMarkdown('```js\nconst answer = 42;\n```'), /language-js/);
assert.equal(renderUserContent('<script>\n你好'), '&lt;script&gt;<br>你好');
assert.equal(renderMarkdown(''), '');
console.log('Markdown: 10 regression checks passed');
