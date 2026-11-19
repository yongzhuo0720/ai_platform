/**
 * 按模型评测的只读评分公式（纯函数，不依赖 Vue / 接口 / DOM）。
 *
 * 设计来源：docs/第二轮深度升级-审查报告-2026-11-05.md §4.2
 * 「评分公式写死且可手算复核；权重显式展示；人工评分单独存」。
 *
 * 为什么先做只读评分、不碰网关 doProxy 主链路：
 * - 网关主链路负责真实转发与计费，改动它风险高、会牵动 700+ 后端测试；
 * - 评分只消费 AiGatewayAdminHolder.summary() 已经落库的聚合数据
 *   （成功率 / P95 延迟 / 已定价模型的成本），是纯展示层的派生指标；
 * - 这样能先建立「可复现的比较口径」，再决定是否用于路由，符合审查报告
 *   「先只读可观测、后动主链路」的建议。
 */

export const SCORE_WEIGHTS = {
  success: 0.5,
  latency: 0.3,
  cost: 0.2,
} as const

/** 参与评分的一维原始指标；成本为毫分（1 分 = 1000 毫分）。 */
export interface ScoreInput {
  successRate: number
  p95LatencyMs: number
  /** 未定价模型传 null：不参与成本维度，按其余维度权重重新归一 */
  costMilliCents: number | null
}

export interface ScoreBreakdown {
  /** 综合分 0-100，保留 1 位小数 */
  score: number
  /** 各维度得分（0-1），用于界面展示与手算复核 */
  parts: { success: number; latency: number; cost: number | null }
  /** 本次实际采用的权重（未定价时成本权重并入另两项） */
  weights: { success: number; latency: number; cost: number }
  /** 是否因缺少定价而降级为两维评分 */
  costUnavailable: boolean
}

function clamp01(value: number): number {
  if (Number.isNaN(value)) return 0
  return Math.min(1, Math.max(0, value))
}

/**
 * 归一化：把一组值映射到 0-1，值越小越好（用于延迟、成本）。
 * 用最大值做上界；全为 0 或只有一个样本时返回 1，避免除零和「唯一样本=最差」的误判。
 */
function normalizeLowerIsBetter(all: number[]): number[] {
  if (all.length === 0) return []
  const max = Math.max(...all, 0)
  const min = Math.min(...all)
  // max<=0：全为 0；max===min：全批次相等，无法区分优劣。
  // 这两种情况都给中性满分，避免「大家都一样却被判 0 分」的误导。
  if (max <= 0 || max === min || all.length <= 1) return all.map(() => 1)
  return all.map((value) => 1 - clamp01(value / max))
}

/**
 * 对一组模型批量评分。归一化在同一批次内进行，保证横向可比。
 *
 * 公式：score = 100 × (w1·成功率 + w2·(1−归一化P95) + w3·(1−归一化成本))
 * 未定价模型的成本维度置空，并把它的权重按比例并入成功率与延迟，
 * 避免「没定价 = 成本最差」或「没定价 = 成本满分」两种误导。
 */
export function scoreModels(inputs: ScoreInput[]): ScoreBreakdown[] {
  const p95Values = inputs.map((item) => Math.max(0, Number(item.p95LatencyMs) || 0))
  const costValues = inputs.map((item) =>
    item.costMilliCents === null ? null : Math.max(0, Number(item.costMilliCents) || 0)
  )

  const normalizedLatency = normalizeLowerIsBetter(p95Values)
  const pricedCosts = costValues.filter((v): v is number => v !== null)
  const normalizedCost = normalizeLowerIsBetter(pricedCosts)
  let costCursor = 0

  return inputs.map((item, index) => {
    const success = clamp01((Number(item.successRate) || 0) / 100)
    const latency = normalizedLatency[index]

    let cost: number | null = null
    let weights: { success: number; latency: number; cost: number } = { ...SCORE_WEIGHTS }
    let costUnavailable = false

    if (costValues[index] === null) {
      costUnavailable = true
      const base = SCORE_WEIGHTS.success + SCORE_WEIGHTS.latency
      weights = {
        success: SCORE_WEIGHTS.success / base,
        latency: SCORE_WEIGHTS.latency / base,
        cost: 0,
      }
    } else {
      cost = normalizedCost[costCursor++]
    }

    const weighted =
      weights.success * success +
      weights.latency * latency +
      weights.cost * (cost ?? 0)

    return {
      score: Math.round(weighted * 1000) / 10,
      parts: { success, latency, cost },
      weights,
      costUnavailable,
    }
  })
}
