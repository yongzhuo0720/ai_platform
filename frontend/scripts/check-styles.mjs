import fs from 'node:fs';
import path from 'node:path';

// 硬编码色值棘轮门禁：只允许减少，不允许新增。
//
// 为什么要棘轮而不是「一步清零」：现存两千多处字面色分布在 40 多个文件里，
// 一次性换成 var(--) 会同时改动大量视觉细节，风险远大于收益；但完全不设闸，
// 新写的组件会继续抄旧文件的字面色，token 体系永远收不拢。
// 棘轮把「不再变差」变成机器约束，收敛可以按文件慢慢来。
//
// 基线放在同目录的 check-styles.baseline.json，数字就是当前实测值。
// 把某个文件改干净后跑 `node scripts/check-styles.mjs --write` 重算，只会越改越小。

const ROOT = path.resolve(import.meta.dirname, '..');
const SRC = path.join(ROOT, 'src');
const BASELINE_FILE = path.join(ROOT, 'scripts', 'check-styles.baseline.json');

// tokens.css 是色值的定义处：这里的字面色就是要被 var(--) 引用的源头，不计入待清理量。
// theme-light.css / element-override.css 只是「按组件适配主题」，不是 token，照常计数。
const EXEMPT = new Set(['src/styles/tokens.css']);

// 从宽到窄匹配；否定断言保证 #4f7bff 不会被 3/4 位规则重复数一次。
const COLOR_RES = [
  /#[0-9a-fA-F]{8}(?![0-9a-zA-Z_-])/g,
  /(?<![\w#])#[0-9a-fA-F]{6}(?![0-9a-zA-Z_-])/g,
  /(?<![\w#])#[0-9a-fA-F]{4}(?![0-9a-zA-Z_-])/g,
  /(?<![\w#])#[0-9a-fA-F]{3}(?![0-9a-zA-Z_-])/g,
  /\brgba?\s*\(/g,
];

const targets = [];
const walk = (dir) => {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full);
    else if (/\.(vue|ts|css|less|scss)$/.test(entry.name)) targets.push(full);
  }
};
walk(SRC);

const current = {};
for (const file of targets) {
  const rel = path.relative(ROOT, file).split(path.sep).join('/');
  if (EXEMPT.has(rel)) continue;
  const src = fs.readFileSync(file, 'utf8');
  const hits = COLOR_RES.reduce((sum, re) => sum + (src.match(re) || []).length, 0);
  if (hits > 0) current[rel] = hits;
}

const total = (map) => Object.values(map).reduce((sum, n) => sum + n, 0);

if (process.argv.includes('--write')) {
  fs.writeFileSync(BASELINE_FILE, JSON.stringify(current, null, 2) + '\n', 'utf8');
  console.log(`基线已重写：${Object.keys(current).length} 个文件 / ${total(current)} 处`);
  process.exit(0);
}

if (!fs.existsSync(BASELINE_FILE)) {
  console.error(`缺少基线文件 ${path.relative(ROOT, BASELINE_FILE)}，先跑 node scripts/check-styles.mjs --write`);
  process.exit(1);
}

const baseline = JSON.parse(fs.readFileSync(BASELINE_FILE, 'utf8'));

const worse = [];
for (const [rel, hits] of Object.entries(current)) {
  const allowed = baseline[rel] ?? 0;
  if (hits > allowed) worse.push(`${rel}: ${allowed} → ${hits}（新增 ${hits - allowed} 处）`);
}

if (worse.length > 0) {
  console.error('硬编码色值只减不增，以下文件变差了：\n' + worse.join('\n'));
  console.error('改法：色值走 var(--xxx)（token 见 src/styles/tokens.css）；确需新色时先把 token 加进 tokens.css 再引用。');
  process.exit(1);
}

const decreased = Object.entries(current).filter(([rel, hits]) => hits < (baseline[rel] ?? 0));
const cleaned = Object.keys(baseline).filter((rel) => !(rel in current));
const summary = `硬编码色值：${Object.keys(current).length} 个文件 / ${total(current)} 处（基线 ${total(baseline)} 处）`;

if (decreased.length > 0 || cleaned.length > 0) {
  console.log(summary + ' —— 已比基线干净，跑 `node scripts/check-styles.mjs --write` 把成果锁进基线');
  for (const [rel, hits] of decreased) console.log(`  ${rel}: ${baseline[rel]} → ${hits}`);
  for (const rel of cleaned) console.log(`  ${rel}: 已清零`);
} else {
  console.log(summary);
}
