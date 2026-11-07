import { spawnSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync, rmSync, statSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Vitest 包装器：跑完比对「磁盘上的 spec 文件数」与「实际执行的文件数」。
 *
 * <p>受限执行环境里 Vitest 可能因内部临时文件写入失败而静默少跑一个测试文件，
 * 退出码仍可能是 0（或只报一条无关警告）。只看「测试项全绿」会漏掉没执行的
 * 文件，所以以磁盘文件数为准做一次对账：少了或多跑了都判失败。</p>
 */

const mode = process.argv[2];
if (mode !== "logic" && mode !== "component") {
  console.error("用法: node scripts/run-vitest.mjs <logic|component>");
  process.exit(1);
}

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const src = join(root, "src");
const reportFile = join(root, `.vitest-${mode}-report.json`);
const vitestBin = join(root, "node_modules", "vitest", "vitest.mjs");

if (!existsSync(vitestBin)) {
  console.error("未找到 vitest，请先执行 npm install");
  process.exit(1);
}

function walk(dir, result = []) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      walk(path, result);
    } else if (name.endsWith(".spec.ts")) {
      result.push(path);
    }
  }
  return result;
}

const isComponent = (file) => file.endsWith(".component.spec.ts");
const expected = walk(src)
  .filter((file) => (mode === "component" ? isComponent(file) : !isComponent(file)))
  .map((file) => relative(root, file).replace(/\\/g, "/"))
  .sort();

const args = [
  vitestBin,
  "run",
  "--config",
  mode === "component" ? "vitest.component.config.ts" : "vitest.config.ts",
  "--reporter=default",
  "--reporter=json",
  `--outputFile.json=${reportFile}`,
];

const result = spawnSync(process.execPath, args, { cwd: root, stdio: "inherit" });

let executed = [];
let totalTests = 0;
try {
  const report = JSON.parse(readFileSync(reportFile, "utf8"));
  executed = (report.testResults || []).map((item) =>
    relative(root, item.name).replace(/\\/g, "/")
  );
  totalTests = report.numTotalTests || 0;
} catch {
  // 报告缺失通常意味着 Vitest 在启动阶段就失败了，下面按 0 个文件对账
} finally {
  rmSync(reportFile, { force: true });
}

const expectedSet = new Set(expected);
const executedSet = new Set(executed);
const missing = expected.filter((file) => !executedSet.has(file));
const unexpected = executed.filter((file) => !expectedSet.has(file));

console.log(
  `对账：磁盘 ${expected.length} 个文件，实际执行 ${executedSet.size} 个文件，共 ${totalTests} 项`
);

if (result.status !== 0) {
  console.error(`Vitest 退出码 ${result.status ?? "未知"}，测试未通过`);
  process.exit(result.status || 1);
}
if (missing.length > 0 || unexpected.length > 0) {
  if (missing.length > 0) {
    console.error("以下测试文件未执行（静默漏跑）：");
    for (const file of missing) console.error(`- ${file}`);
  }
  if (unexpected.length > 0) {
    console.error("以下文件被执行但不在预期集合内：");
    for (const file of unexpected) console.error(`- ${file}`);
  }
  process.exit(1);
}
if (totalTests === 0) {
  console.error("没有执行任何测试项，判失败");
  process.exit(1);
}

console.log(`测试文件对账通过：${executedSet.size}/${expected.length}`);
