#!/usr/bin/env node
/**
 * 演示/验证脚本自检门禁。
 *
 * 检查两件事（都不需要后端、不联网）：
 * 1. 语法：所有 .mjs 脚本能被解析（防止演示脚本被改坏却没人发现）；
 * 2. 密钥：脚本里不得出现硬编码的真实 API Key（这些脚本应只从环境变量读 key）。
 *
 * 背景：今天新增的 demo-all-features / demo-deep / verify-live-model 都是面试现场要跑的
 * 演示能力，但它们不在任何门禁里。这个检查把它们纳入 verify.ps1，防止悄悄退化。
 *
 * 用法：node scripts/check-demo-scripts.mjs
 */

import fs from 'node:fs'
import path from 'node:path'
import vm from 'node:vm'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SCRIPTS_DIR = path.join(ROOT, 'scripts')

let failed = 0

function fail(msg) { console.error('✗ ' + msg); failed++ }
function pass(msg) { console.log('✓ ' + msg) }

// 只需检查项目自己的顶层脚本；mock-llm-server 也是 .mjs 一并纳入
const targets = fs.readdirSync(SCRIPTS_DIR)
  .filter((f) => f.endsWith('.mjs'))
  .sort()

if (targets.length === 0) {
  fail('scripts/ 下没有找到 .mjs 脚本，检查目标为空（可能是路径错了）')
}

console.log(`检查 ${targets.length} 个脚本：${targets.join(', ')}\n`)

// 密钥模式：sk- 后跟 20+ 位字母数字（覆盖 OpenAI/DeepSeek/硅基风格）
const SECRET_RE = /\bsk-[A-Za-z0-9]{20,}\b/g
// test() 配合 g 标志会推进 lastIndex，逐行检测要用不带 g 的副本，否则可能漏报
const SECRET_LINE_RE = /\bsk-[A-Za-z0-9]{20,}\b/

for (const file of targets) {
  const full = path.join(SCRIPTS_DIR, file)
  const src = fs.readFileSync(full, 'utf-8')

  // 1) 语法检查：用 vm.Script 解析（ESM 的 import/export 会报错，改用动态 import 的 --check 语义）
  //    node 没有在进程内做 --check 的稳定 API，这里用 child_process 调 `node --check`
  try {
    const { execFileSync } = await import('node:child_process')
    execFileSync(process.execPath, ['--check', full], { stdio: 'pipe' })
    pass(`语法 ${file}`)
  } catch (e) {
    const err = (e.stderr ? e.stderr.toString() : e.message).split('\n').slice(-3).join(' ')
    fail(`语法 ${file}: ${err}`)
  }

  // 2) 硬编码密钥检查
  const hits = src.match(SECRET_RE)
  if (hits) {
    fail(`密钥 ${file}: 发现 ${hits.length} 处硬编码 API Key，应改为从环境变量读取`)
    // 不回显 key 本身，只提示位置和形态
    const lines = src.split('\n')
    lines.forEach((line, i) => {
      if (SECRET_LINE_RE.test(line)) fail(`  ${file}:${i + 1} 含疑似真实 key（已脱敏，不回显）`)
    })
  } else {
    pass(`密钥 ${file}`)
  }
}

// 3) 关键脚本必须存在（防止被误删）
const REQUIRED = ['rag-eval.mjs', 'demo-all-features.mjs', 'demo-deep.mjs', 'verify-live-model.mjs']
for (const name of REQUIRED) {
  if (!fs.existsSync(path.join(SCRIPTS_DIR, name))) fail(`必备脚本缺失：${name}`)
}
if (REQUIRED.every((n) => targets.includes(n))) pass('4 个必备演示/验证脚本齐全')

console.log('')
if (failed > 0) {
  console.error(`脚本自检失败：${failed} 项`)
  process.exit(1)
}
console.log('脚本自检通过：语法正常、无硬编码密钥、必备脚本齐全')
