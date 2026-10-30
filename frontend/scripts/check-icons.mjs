import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import * as icons from '@element-plus/icons-vue';

// main.ts 只全局注册少量图标，其余图标由各组件自行 import。
// 模板里用到但当前组件既没 import 也没全局注册的图标，运行时会留白，
// 而类型检查与构建都不会报错，所以单独做一次静态核对。

const ROOT = path.resolve(import.meta.dirname, '..');
const SRC = path.join(ROOT, 'src');
const ICON_NAMES = new Set(Object.keys(icons));

const files = [];
const walk = (dir) => {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full);
    else if (/\.(vue|ts)$/.test(entry.name)) files.push(full);
  }
};
walk(SRC);

const mainSource = fs.readFileSync(path.join(SRC, 'main.ts'), 'utf8');
assert.doesNotMatch(
  mainSource,
  /import\s+\*\s+as\s+\w+\s+from\s+['"]@element-plus\/icons-vue['"]/,
  'main.ts 又改成了全量注册图标：会把 293 个图标全部打进首屏，请恢复为按需 import',
);

const globalBlock = mainSource.match(/const globalIcons = \{([^}]+)\}/);
assert.ok(globalBlock, 'main.ts 里找不到 globalIcons 注册表');
const registered = new Set(globalBlock[1].split(',').map((s) => s.trim()).filter(Boolean));

const importRe = /import\s*\{([^}]+)\}\s*from\s*['"]@element-plus\/icons-vue['"]/gs;
const readImports = (src) => {
  const names = new Set();
  let match;
  while ((match = importRe.exec(src))) {
    for (const part of match[1].split(',')) {
      const name = part.trim().split(/\s+as\s+/)[0];
      if (name) names.add(name);
    }
  }
  importRe.lastIndex = 0;
  return names;
};

const used = new Set();
const unresolved = [];

for (const file of files) {
  const src = fs.readFileSync(file, 'utf8');
  const imports = readImports(src);
  const rel = path.relative(ROOT, file);

  if (file.endsWith('.vue')) {
    for (const name of ICON_NAMES) {
      if (!new RegExp(`<${name}[\\s/>]`).test(src)) continue;
      used.add(name);
      if (!imports.has(name) && !registered.has(name)) {
        unresolved.push(`${rel}: <${name} /> 未 import 也未全局注册`);
      }
    }
  }

  // 数据文件里的字符串图标最终经 <component :is="icon"> 解析，只能靠全局注册
  for (const name of ICON_NAMES) {
    if (!new RegExp(`icon:\\s*['"\`]${name}['"\`]`).test(src)) continue;
    used.add(name);
    if (!registered.has(name)) {
      unresolved.push(`${rel}: icon: '${name}' 未在 main.ts 的 globalIcons 注册`);
    }
  }
}

assert.deepEqual(unresolved, [], `以下图标运行时会显示空白：\n${unresolved.join('\n')}`);

const deadGlobal = [...registered].filter((name) => !used.has(name)).sort();
if (deadGlobal.length > 0) {
  console.warn(`提示：globalIcons 里这些图标已无人使用，可以删掉：${deadGlobal.join(', ')}`);
}

console.log(`Icons: ${used.size} used, all resolved (${registered.size} global)`);
