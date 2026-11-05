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
  errorMessage?: string | null
  startedAt?: number | null
  finishedAt?: number | null
  durationMs?: number | null
  createtime?: string | number | null
}

export interface RagEvalCaseResult {
  caseIndex: number
  question: string
  expectedDoc: string
  keywords: string[]
  category: string
  retrievedDocs: string[]
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

export interface RagEvalRunDetail {
  run: RagEvalRun
  results: RagEvalCaseResult[]
  baseline?: RagEvalRun | null
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
  getRun: (runKey: string) =>
    axios.post('/ai/api/ragEval/getRun', { data: { runKey } }),
  deleteRun: (runKey: string) =>
    axios.post('/ai/api/ragEval/deleteRun', { data: { runKey } }),
}

export default ragEvalApi
