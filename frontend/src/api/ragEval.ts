import axios from './axios'

export type RagEvalRunStatus = 'queued' | 'running' | 'success' | 'partial' | 'failed'

export interface RagEvalDataset {
  iid?: number | null
  datasetKey: string
  name: string
  kbName: string
  description?: string | null
  caseCount: number
  caseSetKey?: string | null
  createtime?: string | number | null
}

export interface RagEvalCase {
  iid?: number | null
  question: string
  expectedDoc: string
  keywords: string[]
  category: string
}

export interface RagEvalDatasetDetail {
  dataset: RagEvalDataset
  cases: RagEvalCase[]
}

export interface RagEvalCaseInput {
  question: string
  expectedDoc: string
  keywords: string[]
  category: string
}

export interface RagEvalDatasetRequest {
  datasetKey?: string
  name: string
  kbName: string
  description: string
  cases: RagEvalCaseInput[]
}

export interface RagEvalRun {
  iid?: number | null
  runKey: string
  datasetKey: string
  caseSetKey?: string | null
  datasetName?: string | null
  kbName?: string | null
  topK: number
  status: RagEvalRunStatus
  total: number
  processed: number
  recall?: number | null
  precision?: number | null
  mrr?: number | null
  ndcg?: number | null
  keywordHitRate?: number | null
  missCount?: number | null
  keywordEvaluatedCount?: number | null
  embeddingModel?: string | null
  metricSpec?: string | null
  retrievalMode?: string | null
  rerankApplied?: boolean | null
  errorMessage?: string | null
  startedAt?: number | null
  finishedAt?: number | null
  durationMs?: number | null
  createtime?: string | number | null
}

/** 单条 Top-K 命中明细：分数用于解释排序，contentPreview 是截断后的片段 */
export interface RagEvalHit {
  rank: number
  docName?: string | null
  kbName?: string | null
  chunkIndex?: number | null
  score?: number | null
  denseScore?: number | null
  lexicalScore?: number | null
  channel?: 'both' | 'dense' | 'lexical' | string | null
  contentPreview?: string | null
}

export interface RagEvalCaseResult {
  caseIndex: number
  question: string
  expectedDoc: string
  keywords: string[]
  category: string
  retrievedDocs: string[]
  retrievedHits: RagEvalHit[]
  topDoc?: string | null
  firstHitRank: number
  recall: number
  precision: number
  reciprocalRank: number
  ndcg: number
  keywordHit: boolean
  keywordEvaluated: boolean
  durationMs: number
  errorMessage?: string | null
}

export type RagEvalDiffStatus = 'improved' | 'regressed' | 'mixed' | 'unchanged' | 'unknown'

/** 逐题对比：只在两次运行用例集版本一致时才有内容 */
export interface RagEvalCaseDiff {
  caseIndex: number
  question: string
  baselineRecall?: number | null
  currentRecall?: number | null
  recallDelta?: number | null
  baselineFirstHitRank?: number | null
  currentFirstHitRank?: number | null
  baselineKeywordHit?: boolean | null
  currentKeywordHit?: boolean | null
  status: RagEvalDiffStatus
  note?: string | null
}

export interface RagEvalRunDetail {
  run: RagEvalRun
  results: RagEvalCaseResult[]
  baseline?: RagEvalRun | null
  baselineExplicit?: boolean
  baselineCompatible?: boolean
  baselineWarnings?: string[]
  caseDiffs?: RagEvalCaseDiff[]
  regressedCount?: number
  improvedCount?: number
}

// HNSP 按方法参数名映射请求体：DTO 必须包在 data 字段里
export const ragEvalApi = {
  listDatasets: () => axios.post('/ai/api/ragEval/listDatasets'),
  getDataset: (datasetKey: string) =>
    axios.post('/ai/api/ragEval/getDataset', { data: { datasetKey } }),
  createDataset: (payload: RagEvalDatasetRequest) =>
    axios.post('/ai/api/ragEval/createDataset', { data: payload }),
  updateDataset: (payload: RagEvalDatasetRequest) =>
    axios.post('/ai/api/ragEval/updateDataset', { data: payload }),
  deleteDataset: (datasetKey: string) =>
    axios.post('/ai/api/ragEval/deleteDataset', { data: { datasetKey } }),
  startRun: (datasetKey: string, topK: number) =>
    axios.post('/ai/api/ragEval/startRun', { data: { datasetKey, topK } }),
  listRuns: (datasetKey?: string) =>
    axios.post('/ai/api/ragEval/listRuns', { data: { datasetKey: datasetKey ?? null } }),
  getRun: (runKey: string, baselineRunKey?: string) =>
    axios.post('/ai/api/ragEval/getRun', {
      data: { runKey, baselineRunKey: baselineRunKey || null },
    }),
  deleteRun: (runKey: string) =>
    axios.post('/ai/api/ragEval/deleteRun', { data: { runKey } }),
}

export default ragEvalApi
