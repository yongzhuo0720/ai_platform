import { readFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * 前端产物密钥扫描。
 *
 * <p>为什么要单独做这一步：Vite 会把所有 VITE_ 开头的变量在构建时内联进 js 产物。
 * 之前 difyConfig.ts 读取 VITE_DIFY_*_KEY 做「浏览器直连 Dify」的兜底，
 * 结果 .env.local 里的真密钥被原样打进 dist，任何拿到构建产物的人都能直接调用 Dify 应用。
 * 环境变量名叫「local 就不提交」只挡住了 git，挡不住构建内联——
 * 所以这一步要在构建产物上验，而不是在源码上验。</p>
 *
 * <p>报告只输出命中前缀与长度，绝不打印完整密钥，否则这条检查本身会变成新的泄露面。</p>
 */

const PATTERNS = [
  { name: "Dify 应用密钥 (app-)", re: /app-[A-Za-z0-9_-]{20,}/g },
  { name: "Dify 知识库密钥 (dataset-)", re: /dataset-[A-Za-z0-9_-]{20,}/g },
  { name: "OpenAI 风格密钥 (sk-)", re: /sk-[A-Za-z0-9_-]{20,}/g },
  { name: "构建期密钥变量名残留", re: /VITE_[A-Z0-9_]*(KEY|TOKEN|SECRET)/g },
];

const SKIP_EXT = /\.(map|snap)$/i;

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const dist = join(root, "dist");

if (!existsSync(dist)) {
  console.error("未找到 frontend/dist，请先执行 npm run build");
  process.exit(1);
}

const files = [];
(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      walk(path);
    } else if (!SKIP_EXT.test(name)) {
      files.push(path);
    }
  }
})(dist);

const findings = [];
for (const file of files) {
  const text = readFileSync(file, "utf8");
  for (const { name, re } of PATTERNS) {
    // 变量名残留单独处理：允许出现在 .d.ts 之类的声明里，但 dist 里不该有任何 VITE_*KEY 字样
    for (const match of text.matchAll(re)) {
      const value = match[0];
      findings.push({
        pattern: name,
        file: `dist/${file.slice(dist.length + 1).replace(/\\/g, "/")}`,
        hint: `${value.slice(0, 6)}…（长度 ${value.length}）`
      });
    }
  }
}

console.log(`密钥扫描：已检查 ${files.length} 个产物文件`);

if (findings.length > 0) {
  console.error("发现疑似密钥，禁止发布：");
  for (const f of findings) {
    console.error(`- [${f.pattern}] ${f.file} 命中 ${f.hint}`);
  }
  console.error("\n处理顺序：1) 去掉把密钥编进前端的代码路径 2) 到对应平台吊销并轮换该密钥 3) 重新构建");
  process.exit(1);
}

console.log("Secrets: 产物中未发现密钥痕迹");
