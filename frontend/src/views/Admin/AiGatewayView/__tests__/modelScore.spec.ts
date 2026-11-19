import { describe, expect, it } from 'vitest'
import { scoreModels, SCORE_WEIGHTS, type ScoreInput } from '../modelScore'

const input = (
  successRate: number,
  p95LatencyMs: number,
  costMilliCents: number | null
): ScoreInput => ({ successRate, p95LatencyMs, costMilliCents })

describe('scoreModels 按模型评测评分', () => {
  it('权重是显式的常数量，且总和为 1（可手算复核）', () => {
    expect(SCORE_WEIGHTS.success + SCORE_WEIGHTS.latency + SCORE_WEIGHTS.cost).toBeCloseTo(1)
    expect(SCORE_WEIGHTS).toEqual({ success: 0.5, latency: 0.3, cost: 0.2 })
  })

  it('唯一模型时延迟/成本归一化给满分，综合分只由成功率决定', () => {
    const [result] = scoreModels([input(80, 500, 1000)])
    expect(result.parts.success).toBeCloseTo(0.8)
    expect(result.parts.latency).toBe(1)
    expect(result.parts.cost).toBe(1)
    expect(result.score).toBe(90) // (0.5*0.8 + 0.3*1 + 0.2*1)*100 = 90
  })

  it('横向比较：同批次内 P95 越高得分越低，成本越高得分越低', () => {
    const [fast, slow] = scoreModels([
      input(100, 100, 100),
      input(100, 900, 900),
    ])
    expect(fast.parts.latency).toBeCloseTo(1 - 100 / 900) // 1 - 100/900
    expect(slow.parts.latency).toBeCloseTo(0) // 1 - 900/900
    expect(fast.parts.cost).toBeCloseTo(1 - 100 / 900)
    expect(slow.parts.cost).toBeCloseTo(0)
    expect(fast.score).toBeGreaterThan(slow.score)
  })

  it('成功率按百分比归一并夹在 0-1，异常值不会算负分', () => {
    const [a, b, c] = scoreModels([
      input(150, 100, 100),
      input(-20, 100, 100),
      input(NaN, 100, 100),
    ])
    expect(a.parts.success).toBe(1)
    expect(b.parts.success).toBe(0)
    expect(c.parts.success).toBe(0)
  })

  it('未定价模型：成本维度置空，权重按比例并入成功率与延迟，不误判为最差或满分', () => {
    const [priced, unpriced] = scoreModels([
      input(100, 100, 100),
      input(100, 100, null),
    ])
    expect(unpriced.costUnavailable).toBe(true)
    expect(unpriced.parts.cost).toBeNull()
    expect(unpriced.weights.cost).toBe(0)
    expect(unpriced.weights.success).toBeCloseTo(0.625) // 0.5 / 0.8
    expect(unpriced.weights.latency).toBeCloseTo(0.375) // 0.3 / 0.8
    expect(unpriced.score).toBe(100)
    expect(priced.costUnavailable).toBe(false)
  })

  it('全零成本不应除零崩溃，全批给成本满分', () => {
    const results = scoreModels([input(100, 0, 0), input(100, 0, 0)])
    results.forEach((r) => {
      expect(r.parts.cost).toBe(1)
      expect(Number.isNaN(r.score)).toBe(false)
    })
  })
})
