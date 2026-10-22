import assert from 'node:assert/strict';
import { build } from 'esbuild';

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
