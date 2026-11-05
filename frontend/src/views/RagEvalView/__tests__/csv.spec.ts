import { describe, expect, it } from 'vitest'
import { parseCsvRows, parseGoldenSetCsv, splitKeywords } from '../csv'

describe('RAG 评估黄金集 CSV', () => {
  it('按表头映射问题、期望文档、关键词与题型', () => {
    const rows = parseGoldenSetCsv(
      [
        'id,question,expected_doc,expected_keywords,category',
        'q01,出差3天以内由谁审批,01-差旅报销制度,直属主管,事实型',
        'q02,在上海出差住宿每晚上限多少,01-差旅报销制度,600,数值型',
      ].join('\n'),
    )

    expect(rows).toEqual([
      {
        question: '出差3天以内由谁审批',
        expectedDoc: '01-差旅报销制度',
        keywordsText: '直属主管',
        category: '事实型',
      },
      {
        question: '在上海出差住宿每晚上限多少',
        expectedDoc: '01-差旅报销制度',
        keywordsText: '600',
        category: '数值型',
      },
    ])
  })

  it('支持引号内的逗号、转义双引号、BOM 与中英文列名', () => {
    const csv =
      '\uFEFF问题,期望文档,关键词,题型\r\n' +
      '"住宿, 交通上限","差旅""特殊""制度","600|住宿",数值型\r\n' +
      '\r\n'
    const rows = parseGoldenSetCsv(csv)

    expect(rows).toHaveLength(1)
    expect(rows[0].question).toBe('住宿, 交通上限')
    expect(rows[0].expectedDoc).toBe('差旅"特殊"制度')
    expect(rows[0].keywordsText).toBe('600|住宿')
    expect(rows[0].category).toBe('数值型')
  })

  it('缺列或行内缺必填字段时明确报错，不静默导入空数据', () => {
    expect(() => parseGoldenSetCsv('question,foo\nq,x')).toThrow(
      'CSV 表头需要包含 question 与 expected_doc 两列',
    )
    expect(() => parseGoldenSetCsv('question,expected_doc\nq1,doc\n,doc2')).toThrow(
      'CSV 第 3 行缺少问题或期望文档',
    )
    expect(() => parseGoldenSetCsv('question,expected_doc')).toThrow(
      'CSV 至少需要表头和一行数据',
    )
  })

  it('关键词按逗号/中文逗号/分号/顿号拆分并去重', () => {
    expect(splitKeywords('600，住宿; 600、交通|餐补')).toEqual([
      '600',
      '住宿',
      '交通',
      '餐补',
    ])
    expect(splitKeywords('  ')).toEqual([])
  })

  it('CSV 解析保留空单元格，但丢弃完全空行', () => {
    const rows = parseCsvRows('a,b,c\n1,,3\n\n')
    expect(rows).toEqual([
      ['a', 'b', 'c'],
      ['1', '', '3'],
    ])
  })
})
