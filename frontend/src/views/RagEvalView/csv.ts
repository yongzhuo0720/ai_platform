export interface ParsedGoldenSetRow {
  question: string
  expectedDoc: string
  keywordsText: string
  category: string
}

/** 关键词输入既支持逗号，也支持中文逗号/分号/顿号，兼容从表格粘贴 */
export function splitKeywords(text: string): string[] {
  const seen = new Set<string>()
  const list: string[] = []
  for (const part of String(text ?? '').split(/[,，;；|、]+/)) {
    const value = part.trim()
    if (value && !seen.has(value)) {
      seen.add(value)
      list.push(value)
    }
  }
  return list
}

/** 解析 CSV（支持引号包裹的逗号与转义双引号），用于导入黄金评估集 */
export function parseCsvRows(text: string): string[][] {
  const content = text.replace(/^\uFEFF/, '')
  const rows: string[][] = []
  let row: string[] = []
  let cell = ''
  let quoted = false
  for (let i = 0; i < content.length; i++) {
    const ch = content[i]
    if (quoted) {
      if (ch === '"') {
        if (content[i + 1] === '"') {
          cell += '"'
          i++
        } else {
          quoted = false
        }
      } else {
        cell += ch
      }
      continue
    }
    if (ch === '"') {
      quoted = true
    } else if (ch === ',') {
      row.push(cell)
      cell = ''
    } else if (ch === '\n') {
      row.push(cell)
      rows.push(row)
      row = []
      cell = ''
    } else if (ch !== '\r') {
      cell += ch
    }
  }
  if (cell !== '' || row.length > 0) {
    row.push(cell)
    rows.push(row)
  }
  return rows.filter((item) => item.some((value) => value.trim() !== ''))
}

const normalizeHeader = (value: string) => value.trim().toLowerCase().replace(/[\s_-]/g, '')

/**
 * 黄金集 CSV 约定：question / expected_doc 必填，expected_keywords 与 category 可选。
 * 表头顺序不固定，允许中英文列名；缺列时明确报错，避免把整列问题导成空字符串。
 */
export function parseGoldenSetCsv(text: string): ParsedGoldenSetRow[] {
  const rows = parseCsvRows(text)
  if (rows.length < 2) {
    throw new Error('CSV 至少需要表头和一行数据')
  }
  const header = rows[0].map(normalizeHeader)
  const findColumn = (aliases: string[]) =>
    header.findIndex((item) => aliases.includes(item))
  const questionIndex = findColumn(['question', '问题'])
  const docIndex = findColumn(['expecteddoc', '期望文档', '标准文档'])
  const keywordIndex = findColumn(['expectedkeywords', 'keywords', '关键词'])
  const categoryIndex = findColumn(['category', '题型', '分类'])
  if (questionIndex < 0 || docIndex < 0) {
    throw new Error('CSV 表头需要包含 question 与 expected_doc 两列')
  }
  const result: ParsedGoldenSetRow[] = []
  for (let i = 1; i < rows.length; i++) {
    const row = rows[i]
    const question = (row[questionIndex] ?? '').trim()
    const expectedDoc = (row[docIndex] ?? '').trim()
    if (!question || !expectedDoc) {
      throw new Error(`CSV 第 ${i + 1} 行缺少问题或期望文档`)
    }
    result.push({
      question,
      expectedDoc,
      keywordsText: keywordIndex >= 0 ? (row[keywordIndex] ?? '').trim() : '',
      category: categoryIndex >= 0 ? (row[categoryIndex] ?? '').trim() : '',
    })
  }
  return result
}
