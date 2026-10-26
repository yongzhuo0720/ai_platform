#!/usr/bin/env node
/**
 * RAG 检索评估跑分脚本
 *
 * 读 docs/eval/golden-set.csv，逐题调用 /ai/api/kbRag/search，
 * 按 docs/eval/README.md 的口径算 Recall@k / Precision@k / MRR / NDCG@k，输出报告。
 *
 * 指标实现与后端 RagEvaluator 完全一致，两边的数字应能对上。
 *
 * 用法：
 *   # 1) 先干跑，只校验黄金集和参数，不联网（现在就能用）
 *   node scripts/rag-eval.mjs --dry-run
 *
 *   # 2) 灌语料 + 跑分（需要后端已启动、已配好向量模型）
 *   node scripts/rag-eval.mjs --account admin --password 'xxx' --seed
 *
 *   # 3) 已有语料，只跑分
 *   node scripts/rag-eval.mjs --account admin --password 'xxx'
 *
 *   # 4) 不想给密码：从浏览器复制 token
 *   node scripts/rag-eval.mjs --token <x-access-token>
 *
 * 参数见 --help。
 */

import { readFile, writeFile, readdir } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const EVAL_DIR = path.join(ROOT, 'docs', 'eval')
const CORPUS_DIR = path.join(EVAL_DIR, 'seed-corpus')
const GOLDEN_SET = path.join(EVAL_DIR, 'golden-set.csv')

// ---------------------------------------------------------------------------
// 参数解析
// ---------------------------------------------------------------------------
function parseArgs(argv) {
  const args = {
    baseUrl: 'http://localhost:8507',
    kbName: '评估语料',
    k: 5,
    seed: false,
    cleanup: false,
    dryRun: false,
    out: null,
    token: null,
    account: null,
    password: null,
    selfCheck: false,
    help: false,
  }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    switch (a) {
      case '--base-url': args.baseUrl = argv[++i]; break
      case '--kb-name': args.kbName = argv[++i]; break
      case '--k': args.k = Number(argv[++i]); break
      case '--out': args.out = argv[++i]; break
      case '--token': args.token = argv[++i]; break
      case '--account': args.account = argv[++i]; break
      case '--password': args.password = argv[++i]; break
      case '--seed': args.seed = true; break
      case '--cleanup': args.cleanup = true; break
      case '--dry-run': args.dryRun = true; break
      case '--self-check': args.selfCheck = true; break
      case '-h': case '--help': args.help = true; break
      default:
        console.error(`未知参数：${a}（用 --help 看用法）`)
        process.exit(2)
    }
  }
  if (!Number.isInteger(args.k) || args.k <= 0) {
    console.error(`--k 必须是正整数，收到：${args.k}`)
    process.exit(2)
  }
  return args
}

function printHelp() {
  console.log(`
RAG 检索评估跑分

  node scripts/rag-eval.mjs [选项]

选项
  --dry-run              只校验黄金集与参数，不联网（无需后端）
  --self-check           校验指标实现与后端 RagEvaluator 口径一致
  --account <账号>        登录账号（与 --password 一起用）
  --password <密码>       登录密码
  --token <token>        已有 x-access-token 时直接用它，跳过登录
  --base-url <url>       后端地址，默认 http://localhost:8507
  --kb-name <名称>        知识库名，默认「评估语料」
  --k <n>                评估截断位置，默认 5
  --seed                 先上传 docs/eval/seed-corpus 的文档
  --cleanup              跑完后删除本次上传的文档
  --out <路径>            额外写一份 Markdown 报告
  -h, --help             本帮助

指标口径（与后端 RagEvaluator 一致）
  Recall@k      相关文档是否出现在前 k 条结果里
  Precision@k   前 k 条里命中正确文档的比例（分母 min(k, 返回条数)）
  MRR           第一条命中正确文档的排名倒数
  NDCG@k        考虑排名位置与折损的归一化增益

注意
  本评估是「文档级」口径：只要前 k 条里出现正确文档即算命中，
  不要求精确到某一个分块。对制度类文档这是合理粒度，
  若要分块级评估，需要给黄金集补 chunk 编号。
`)
}

// ---------------------------------------------------------------------------
// 读取黄金集与语料
// ---------------------------------------------------------------------------
function parseCsvLine(line) {
  // 黄金集字段不含逗号（已由 GoldenSetConsistencyTest 校验），简单切分即可
  return line.split(',')
}

async function loadGoldenSet() {
  let raw = await readFile(GOLDEN_SET, 'utf8')
  // 去掉 UTF-8 BOM：编辑器 / Excel 导出的 CSV 常带，不去掉会让表头首字段变成 "\uFEFFid"
  if (raw.charCodeAt(0) === 0xfeff) {
    raw = raw.slice(1)
  }
  const lines = raw.split(/\r?\n/).filter((l) => l.trim() !== '')
  if (lines.length < 2) {
    throw new Error(`黄金集为空或只有表头：${GOLDEN_SET}`)
  }
  const header = parseCsvLine(lines[0])
  const expectedHeader = ['id', 'question', 'expected_doc', 'expected_keywords', 'category']
  if (header.length !== 5 || header[0] !== 'id') {
    throw new Error(`黄金集表头不符合预期，实际为：${header.join('|')}`)
  }
  const rows = []
  for (let i = 1; i < lines.length; i++) {
    const cols = parseCsvLine(lines[i].trim())
    if (cols.length !== 5) {
      throw new Error(`第 ${i + 1} 行列数为 ${cols.length}，应为 5：${lines[i]}`)
    }
    rows.push({
      id: cols[0].trim(),
      question: cols[1].trim(),
      expectedDoc: cols[2].trim(),
      keywords: cols[3].trim().split(/\s+/).filter(Boolean),
      category: cols[4].trim(),
    })
  }
  return { header: expectedHeader, rows }
}

async function loadCorpus() {
  if (!existsSync(CORPUS_DIR)) {
    throw new Error(`语料目录不存在：${CORPUS_DIR}`)
  }
  const files = (await readdir(CORPUS_DIR)).filter((f) => f.endsWith('.md')).sort()
  const docs = []
  for (const file of files) {
    const content = await readFile(path.join(CORPUS_DIR, file), 'utf8')
    docs.push({
      // 上传时用不带扩展名的文件名作文档名，与黄金集的 expected_doc 对齐
      name: file.replace(/\.md$/, ''),
      content,
      file,
    })
  }
  return docs
}

// ---------------------------------------------------------------------------
// 指标（与后端 RagEvaluator 口径一致）
// ---------------------------------------------------------------------------
function recallAtK(retrieved, relevant, k) {
  if (relevant.size === 0) return 1
  if (retrieved.length === 0 || k <= 0) return 0
  const topK = new Set(retrieved.slice(0, k))
  let hit = 0
  for (const id of relevant) if (topK.has(id)) hit++
  return hit / relevant.size
}

function precisionAtK(retrieved, relevant, k) {
  if (retrieved.length === 0 || k <= 0 || relevant.size === 0) return 0
  const limit = Math.min(k, retrieved.length)
  let hit = 0
  // 同一相关结果出现多次只计一次：文档级评估下 top-k 里会混着同一文档的
  // 多个分块，不去重 Precision 会被虚高（真实跑分暴露）
  const counted = new Set()
  for (let i = 0; i < limit; i++) {
    const id = retrieved[i]
    if (relevant.has(id) && !counted.has(id)) {
      counted.add(id)
      hit++
    }
  }
  return hit / limit
}

function reciprocalRank(retrieved, relevant) {
  if (relevant.size === 0) return 0
  for (let i = 0; i < retrieved.length; i++) {
    if (relevant.has(retrieved[i])) return 1 / (i + 1)
  }
  return 0
}

function ndcgAtK(retrieved, relevant, k) {
  if (retrieved.length === 0 || relevant.size === 0 || k <= 0) return 0
  const limit = Math.min(k, retrieved.length)
  let dcg = 0
  // 同一相关结果只计最靠前的一次：重复累加会让 DCG 超过 IDCG，
  // NDCG 冒出大于 1 的值（真实跑分暴露）
  const counted = new Set()
  for (let i = 0; i < limit; i++) {
    const id = retrieved[i]
    if (relevant.has(id) && !counted.has(id)) {
      counted.add(id)
      dcg += 1 / (Math.log(i + 2) / Math.log(2))
    }
  }
  let idcg = 0
  const idealHits = Math.min(k, relevant.size)
  for (let i = 0; i < idealHits; i++) idcg += 1 / (Math.log(i + 2) / Math.log(2))
  return idcg === 0 ? 0 : dcg / idcg
}

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------
class ApiClient {
  constructor(baseUrl, token, account) {
    this.baseUrl = baseUrl.replace(/\/$/, '')
    this.token = token
    this.account = account
  }

  async post(pathname, body) {
    const headers = { 'Content-Type': 'application/json; charset=utf-8' }
    if (this.token) headers['x-access-token'] = this.token
    if (this.account) headers['account'] = this.account

    const res = await fetch(`${this.baseUrl}${pathname}`, {
      method: 'POST',
      headers,
      body: JSON.stringify(body ?? {}),
    })
    if (!res.ok) {
      throw new Error(`${pathname} 返回 HTTP ${res.status}`)
    }
    const json = await res.json()
    // HNSP 用 resp_code：0 成功；登录失效有专门码
    if (json.resp_code !== undefined && json.resp_code !== 0) {
      throw new Error(`${pathname} 业务失败 resp_code=${json.resp_code} ${json.resp_msg || ''}`)
    }
    return json
  }

  async login(account, password) {
    // keepUser 必须显式带上：框架的 logintest 第 4 参是 primitive boolean，
    // 缺字段时解析器传 null，Method.invoke 抛 IllegalArgumentException → HTTP 500
    // app 必须留空：非空值会被判「该应用下无此账号」
    const json = await this.post('/ai/uaccess/logintest', {
      uaccess: account,
      upwd: password,
      keepUser: false,
    })
    const token = json?.datas?.token
    if (!token) {
      throw new Error('登录成功但未拿到 token，请检查账号密码或后端版本')
    }
    this.token = token
    this.account = account
    return token
  }

  async uploadDoc(name, kbName, content) {
    return this.post('/ai/api/kbRag/uploadDoc', { data: { name, kbName, content } })
  }

  async listDocs() {
    const json = await this.post('/ai/api/kbRag/listDocs', {})
    return json?.datas ?? []
  }

  async deleteDoc(iid) {
    return this.post('/ai/api/kbRag/deleteDoc', { data: { iid } })
  }

  async search(query, kbName, topK) {
    const json = await this.post('/ai/api/kbRag/search', {
      data: { query, kbName, topK },
    })
    return json?.datas ?? { items: [] }
  }
}

// ---------------------------------------------------------------------------
// 报告
// ---------------------------------------------------------------------------
function pct(x) {
  return `${(x * 100).toFixed(2)}%`
}

function buildMarkdown({ k, args, rows, results, summary, durationMs }) {
  const now = new Date().toISOString().replace('T', ' ').slice(0, 19)
  const lines = []
  lines.push('# RAG 检索评估报告')
  lines.push('')
  lines.push(`- 生成时间：${now}`)
  lines.push(`- 后端地址：${args.baseUrl}`)
  lines.push(`- 知识库：${args.kbName}`)
  lines.push(`- 截断位置：k=${k}`)
  lines.push(`- 题量：${rows.length}`)
  lines.push(`- 耗时：${(durationMs / 1000).toFixed(1)} 秒`)
  lines.push('')
  lines.push('## 总体指标')
  lines.push('')
  lines.push('| 指标 | 数值 |')
  lines.push('|---|---|')
  lines.push(`| Recall@${k} | ${pct(summary.recall)} |`)
  lines.push(`| Precision@${k} | ${pct(summary.precision)} |`)
  lines.push(`| MRR | ${summary.mrr.toFixed(4)} |`)
  lines.push(`| NDCG@${k} | ${summary.ndcg.toFixed(4)} |`)
  lines.push(`| 完全漏召 | ${summary.misses.length} / ${rows.length} |`)
  lines.push(`| 关键词命中 | ${pct(summary.keywordHitRate)} |`)
  lines.push('')
  if (summary.misses.length > 0) {
    lines.push('## 完全漏召的题目（优先排查）')
    lines.push('')
    lines.push('| id | 问题 | 期望文档 |')
    lines.push('|---|---|---|')
    for (const m of summary.misses) {
      lines.push(`| ${m.id} | ${m.question} | ${m.expectedDoc} |`)
    }
    lines.push('')
  }
  lines.push('## 逐题明细')
  lines.push('')
  lines.push(`| id | 问题 | 期望文档 | 首条命中 | 实际首条 | R@${k} | RR | 关键词 |`)
  lines.push('|---|---|---|---|---|---|---|---|')
  for (const r of results) {
    const firstHit = r.firstHitRank > 0 ? `#${r.firstHitRank}` : '—'
    const firstActual = r.topDoc || '—'
    const rr = r.rr > 0 ? r.rr.toFixed(3) : '0'
    const kw = r.keywordHit ? '✓' : (r.error ? '错误' : '✗')
    lines.push(
      `| ${r.id} | ${r.question} | ${r.expectedDoc} | ${firstHit} | ${firstActual} | ${r.recall.toFixed(2)} | ${rr} | ${kw} |`,
    )
  }
  lines.push('')
  lines.push('> 口径说明：文档级命中——前 k 条里出现正确文档即算命中，不要求精确到分块。')
  lines.push('> 关键词命中 = 命中的分块正文里出现了黄金集标注的关键词，用于检查召回的是否为正确段落。')
  return lines.join('\n')
}

// ---------------------------------------------------------------------------
// 指标自检：与后端 RagEvaluatorTest 用同一批手算用例
// ---------------------------------------------------------------------------
/**
 * 校验 JS 指标实现与 Java 侧完全一致。
 *
 * 为什么需要：报告里的数字由这个脚本算，而 CI 门禁由 Java 的 RagEvaluator 算。
 * 两边若悄悄漂移（改了一边忘了另一边），就会出现"CI 说 92%、报告说 78%"
 * 这种最难排查的不一致。这里用同一批手算用例把两边钉在一起。
 */
function selfCheck() {
  const eq = (name, actual, expected, eps = 1e-9) => {
    const ok = Math.abs(actual - expected) < eps
    console.log(`  ${ok ? '✓' : '✗'} ${name}  实际=${actual.toFixed(6)} 期望=${expected.toFixed(6)}`)
    return ok
  }
  const gt = (name, a, b) => {
    const ok = a > b
    console.log(`  ${ok ? '✓' : '✗'} ${name}  ${a.toFixed(6)} > ${b.toFixed(6)}`)
    return ok
  }

  let pass = true
  console.log('Recall@k')
  pass &= eq('recall([1,2,9],{1,2,3},3)', recallAtK(['1', '2', '9'], new Set(['1', '2', '3']), 3), 2 / 3)
  pass &= eq('recall([9,8,7,1],{1},3)', recallAtK(['9', '8', '7', '1'], new Set(['1']), 3), 0)
  pass &= eq('recall([9,8,7,1],{1},4)', recallAtK(['9', '8', '7', '1'], new Set(['1']), 4), 1)
  pass &= eq('recall 无相关片段=1', recallAtK(['1'], new Set(), 5), 1)

  console.log('Precision@k')
  pass &= eq('precision([1,9],{1},5)=1/2（分母取实际条数）',
    precisionAtK(['1', '9'], new Set(['1']), 5), 0.5)
  pass &= eq('precision([1,9,2,8,7],{1,2},5)=2/5',
    precisionAtK(['1', '9', '2', '8', '7'], new Set(['1', '2']), 5), 0.4)

  console.log('MRR')
  pass &= eq('rr 第1位', reciprocalRank(['1', '9'], new Set(['1'])), 1)
  pass &= eq('rr 第3位', reciprocalRank(['9', '8', '1'], new Set(['1'])), 1 / 3)
  pass &= eq('rr 未命中', reciprocalRank(['9', '8'], new Set(['1'])), 0)

  console.log('NDCG@k')
  pass &= eq('ndcg 完美排序', ndcgAtK(['1', '9', '8'], new Set(['1']), 3), 1)
  pass &= eq('ndcg 排第2', ndcgAtK(['9', '1', '8'], new Set(['1']), 3), 1 / (Math.log(3) / Math.log(2)))
  const good = ndcgAtK(['1', '2', '9', '8', '7'], new Set(['1', '2']), 5)
  const bad = ndcgAtK(['9', '8', '7', '1', '2'], new Set(['1', '2']), 5)
  pass &= gt('ndcg 惩罚后置（Recall 相同）', good, bad)
  pass &= eq('ndcg 相关多于k时IDCG只算k', ndcgAtK(['1', '2'], new Set(['1', '2', '3', '4', '5']), 2), 1)

  console.log('重复命中去重（文档级评估的真实场景）')
  pass &= eq('precision 重复只计一次',
    precisionAtK(['1', '1', '1', '9', '8'], new Set(['1']), 5), 0.2)
  pass &= eq('ndcg 重复只计最靠前一次',
    ndcgAtK(['1', '1', '1', '9', '8'], new Set(['1']), 5), 1)

  console.log(pass ? '\n自检通过：JS 指标与 Java RagEvaluator 口径一致' : '\n自检失败：指标实现与后端不一致')
  return pass
}

// ---------------------------------------------------------------------------
// 主流程
// ---------------------------------------------------------------------------
async function main() {
  const args = parseArgs(process.argv.slice(2))
  if (args.help) {
    printHelp()
    return
  }
  if (args.selfCheck) {
    process.exit(selfCheck() ? 0 : 1)
  }

  console.log('== RAG 检索评估 ==')
  console.log(`黄金集：${GOLDEN_SET}`)
  console.log(`语料目录：${CORPUS_DIR}`)

  const golden = await loadGoldenSet()
  const corpus = await loadCorpus()
  console.log(`题量：${golden.rows.length}，语料：${corpus.length} 篇`)

  // ---- 一致性校验（与 GoldenSetConsistencyTest 同口径）----
  const docNames = new Set(corpus.map((d) => d.name))
  const problems = []
  for (const row of golden.rows) {
    if (!docNames.has(row.expectedDoc)) {
      problems.push(`${row.id} 期望文档不存在：${row.expectedDoc}`)
      continue
    }
    const doc = corpus.find((d) => d.name === row.expectedDoc)
    const normalized = doc.content.replace(/[,，]/g, '')
    for (const kw of row.keywords) {
      if (!normalized.includes(kw.replace(/[,，]/g, ''))) {
        problems.push(`${row.id} 关键词「${kw}」不在 ${row.expectedDoc} 中`)
      }
    }
  }
  if (problems.length > 0) {
    console.error('\n黄金集与语料不一致：')
    for (const p of problems) console.error('  - ' + p)
    process.exit(1)
  }
  console.log('一致性校验：通过')

  if (args.dryRun) {
    console.log('\n（--dry-run）以下是将要执行的动作：')
    if (args.seed) {
      console.log(`  上传 ${corpus.length} 篇文档到知识库「${args.kbName}」`)
      for (const doc of corpus) {
        console.log(`    - ${doc.name}（${doc.content.length} 字符）`)
      }
    }
    console.log(`  对 ${golden.rows.length} 道题调用 /ai/api/kbRag/search，k=${args.k}`)
    console.log(`  按 Recall@${args.k} / Precision@${args.k} / MRR / NDCG@${args.k} 出报告`)
    if (args.out) console.log(`  报告写入：${args.out}`)
    console.log('\n干跑通过。去掉 --dry-run 即可实际跑分。')
    return
  }

  // ---- 认证 ----
  const client = new ApiClient(args.baseUrl, args.token, args.account)
  if (!args.token) {
    if (!args.account || !args.password) {
      console.error('\n需要认证：请提供 --token，或 --account + --password')
      process.exit(2)
    }
    console.log(`\n登录中：${args.account} @ ${args.baseUrl}`)
    await client.login(args.account, args.password)
    console.log('登录成功')
  }

  // ---- 灌语料 ----
  const uploaded = []
  if (args.seed) {
    console.log(`\n上传语料（知识库「${args.kbName}」）...`)
    for (const doc of corpus) {
      try {
        const json = await client.uploadDoc(doc.name, args.kbName, doc.content)
        const iid = json?.datas?.iid
        const chunks = json?.datas?.chunkCount
        uploaded.push({ iid, name: doc.name })
        console.log(`  ✓ ${doc.name} 分块 ${chunks ?? '?'} 个`)
      } catch (e) {
        console.error(`  ✗ ${doc.name}：${e.message}`)
        console.error('    上传失败通常意味着向量模型未配置，请先在「模型配置」里启用 OpenAI 直连并填写 embedModel')
        process.exit(1)
      }
    }
  }

  // ---- 跑分 ----
  console.log(`\n开始跑分（${golden.rows.length} 题，k=${args.k}）...`)
  const started = Date.now()
  const results = []
  let recallSum = 0
  let precisionSum = 0
  let rrSum = 0
  let ndcgSum = 0
  let keywordHits = 0
  const misses = []

  for (let i = 0; i < golden.rows.length; i++) {
    const row = golden.rows[i]
    const prefix = `[${String(i + 1).padStart(2, '0')}/${golden.rows.length}] ${row.id}`
    try {
      const data = await client.search(row.question, args.kbName, args.k)
      const items = Array.isArray(data.items) ? data.items : []
      const retrievedDocs = items.map((it) => it.docName || '')
      const relevant = new Set([row.expectedDoc])

      const recall = recallAtK(retrievedDocs, relevant, args.k)
      const precision = precisionAtK(retrievedDocs, relevant, args.k)
      const rr = reciprocalRank(retrievedDocs, relevant)
      const ndcg = ndcgAtK(retrievedDocs, relevant, args.k)

      // 命中分块的正文里是否出现标注的关键词
      let keywordHit = false
      let firstHitRank = 0
      for (let r = 0; r < Math.min(args.k, items.length); r++) {
        if (items[r].docName === row.expectedDoc) {
          if (firstHitRank === 0) firstHitRank = r + 1
          const content = items[r].content || ''
          if (row.keywords.every((kw) => content.includes(kw))) {
            keywordHit = true
            break
          }
        }
      }

      recallSum += recall
      precisionSum += precision
      rrSum += rr
      ndcgSum += ndcg
      if (keywordHit) keywordHits++
      if (recall === 0) misses.push({ id: row.id, question: row.question, expectedDoc: row.expectedDoc })

      const mark = recall === 0 ? '✗' : firstHitRank === 1 ? '✓' : '△'
      console.log(
        `${prefix} ${mark} R=${recall.toFixed(2)} RR=${rr.toFixed(3)} 首条=${items[0]?.docName || '无'}`,
      )

      results.push({
        id: row.id,
        question: row.question,
        expectedDoc: row.expectedDoc,
        recall,
        precision,
        rr,
        ndcg,
        firstHitRank,
        keywordHit,
        topDoc: retrievedDocs[0] || '',
        error: null,
      })
    } catch (e) {
      console.error(`${prefix} ✗ 调用失败：${e.message}`)
      results.push({
        id: row.id,
        question: row.question,
        expectedDoc: row.expectedDoc,
        recall: 0, precision: 0, rr: 0, ndcg: 0,
        firstHitRank: 0, keywordHit: false, topDoc: '', error: e.message,
      })
      misses.push({ id: row.id, question: row.question, expectedDoc: row.expectedDoc })
    }
  }

  const durationMs = Date.now() - started
  const n = golden.rows.length
  const summary = {
    recall: recallSum / n,
    precision: precisionSum / n,
    mrr: rrSum / n,
    ndcg: ndcgSum / n,
    keywordHitRate: keywordHits / n,
    misses,
  }

  // ---- 汇总 ----
  console.log('\n== 结果 ==')
  console.log(`Recall@${args.k}    ${pct(summary.recall)}`)
  console.log(`Precision@${args.k} ${pct(summary.precision)}`)
  console.log(`MRR            ${summary.mrr.toFixed(4)}`)
  console.log(`NDCG@${args.k}      ${summary.ndcg.toFixed(4)}`)
  console.log(`关键词命中      ${pct(summary.keywordHitRate)}`)
  console.log(`完全漏召        ${misses.length} / ${n}`)
  console.log(`耗时            ${(durationMs / 1000).toFixed(1)} 秒`)

  if (misses.length > 0) {
    console.log('\n完全漏召的题目（优先排查这几道）：')
    for (const m of misses) console.log(`  - ${m.id} ${m.question} → 期望 ${m.expectedDoc}`)
  }

  if (args.out) {
    const md = buildMarkdown({ k: args.k, args, rows: golden.rows, results, summary, durationMs })
    await writeFile(args.out, md, 'utf8')
    console.log(`\n报告已写入：${args.out}`)
  }

  // ---- 清理 ----
  if (args.cleanup) {
    console.log('\n清理本次上传的文档...')
    const docs = await client.listDocs()
    const targets = docs.filter((d) => uploaded.some((u) => u.name === d.name))
    for (const doc of targets) {
      try {
        await client.deleteDoc(doc.iid)
        console.log(`  ✓ 已删除 ${doc.name}`)
      } catch (e) {
        console.error(`  ✗ 删除 ${doc.name} 失败：${e.message}`)
      }
    }
  }

  process.exit(misses.length === n ? 1 : 0)
}

main().catch((e) => {
  console.error('\n执行失败：' + e.message)
  if (process.env.DEBUG) console.error(e.stack)
  process.exit(1)
})
