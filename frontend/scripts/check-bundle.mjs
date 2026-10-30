import { readFileSync, readdirSync, statSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * 产物体积预算检查。
 *
 * <p>只统计 gzip 后的大小——用户真正下载的是压缩后的字节。
 * 「首屏」取 dist/index.html 里直接引用的 js/css（entry + modulepreload），
 * 路由懒加载的分包不计入首屏，但会单独列出大分包。</p>
 *
 * <p>预算不是拍脑袋：先记录当前值，再留约 10% 余量。要放宽必须
 * 在提交说明里写清楚为什么，否则这个门槛会慢慢失效。</p>
 */

const BUDGETS = {
  initialJsGzipKb: 260,
  initialCssGzipKb: 80,
  chunkGzipKb: 320,
};

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const dist = join(root, "dist");

let html;
try {
  html = readFileSync(join(dist, "index.html"), "utf8");
} catch {
  console.error("未找到 frontend/dist/index.html，请先执行 npm run build");
  process.exit(1);
}

const initialAssets = new Set(
  [...html.matchAll(/(?:src|href)="\.?\/?(assets\/[^"']+\.(?:js|css))"/g)].map((m) => m[1])
);

const assetsDir = join(dist, "assets");
const assets = readdirSync(assetsDir)
  .filter((name) => /\.(js|css)$/.test(name))
  .map((name) => {
    const path = join(assetsDir, name);
    const raw = statSync(path).size;
    const gzip = gzipSync(readFileSync(path)).length;
    return { name, rawKb: raw / 1024, gzipKb: gzip / 1024 };
  })
  .sort((a, b) => b.gzipKb - a.gzipKb);

const initial = assets.filter((a) => initialAssets.has(`assets/${a.name}`));
const initialJs = initial.filter((a) => a.name.endsWith(".js"));
const initialCss = initial.filter((a) => a.name.endsWith(".css"));
const sum = (list) => list.reduce((total, a) => total + a.gzipKb, 0);

console.log("产物体积（gzip 后）");
console.log("首屏 JS：");
for (const asset of initialJs) {
  console.log(`  ${asset.name.padEnd(42)} ${asset.gzipKb.toFixed(1).padStart(7)} kB`);
}
console.log(`  合计 ${sum(initialJs).toFixed(1)} kB（预算 ${BUDGETS.initialJsGzipKb} kB）`);
console.log("首屏 CSS：");
for (const asset of initialCss) {
  console.log(`  ${asset.name.padEnd(42)} ${asset.gzipKb.toFixed(1).padStart(7)} kB`);
}
console.log(`  合计 ${sum(initialCss).toFixed(1)} kB（预算 ${BUDGETS.initialCssGzipKb} kB）`);
console.log("懒加载分包最大的 5 个：");
for (const asset of assets.filter((a) => !initial.includes(a)).slice(0, 5)) {
  console.log(`  ${asset.name.padEnd(42)} ${asset.gzipKb.toFixed(1).padStart(7)} kB`);
}

// worker 走独立线程、只在解析对应格式时按需加载，不占首屏也不阻塞主线程
const isWorker = (name) => /worker/i.test(name);
if (assets.some((a) => isWorker(a.name))) {
  console.log("\n注：*worker*.js 为独立线程按需加载，不计入单分包预算");
}

const failures = [];
if (sum(initialJs) > BUDGETS.initialJsGzipKb) {
  failures.push(`首屏 JS ${sum(initialJs).toFixed(1)} kB 超过预算 ${BUDGETS.initialJsGzipKb} kB`);
}
if (sum(initialCss) > BUDGETS.initialCssGzipKb) {
  failures.push(`首屏 CSS ${sum(initialCss).toFixed(1)} kB 超过预算 ${BUDGETS.initialCssGzipKb} kB`);
}
for (const asset of assets) {
  if (isWorker(asset.name)) {
    continue;
  }
  if (asset.gzipKb > BUDGETS.chunkGzipKb) {
    failures.push(`${asset.name} ${asset.gzipKb.toFixed(1)} kB 超过单分包预算 ${BUDGETS.chunkGzipKb} kB`);
  }
}

if (failures.length > 0) {
  console.error("\n体积预算未通过：");
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exit(1);
}
console.log("\nBundle: 体积预算通过");
