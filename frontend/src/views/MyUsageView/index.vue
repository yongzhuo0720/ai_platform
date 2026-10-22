<template>
  <section class="page-section my-usage-page">
    <div class="page-hero">
      <div class="page-eyebrow">用量中心</div>
      <div class="page-heading">
        <div>
          <h1>我的用量</h1>
          <p>查看自己的 AI 调用统计、Token 配额水位与最近调用记录。</p>
        </div>
      </div>
    </div>

    <div class="mu-toolbar">
      <el-radio-group :model-value="state.rangeDays" @update:model-value="setRangeDays">
        <el-radio-button v-for="days in RANGE_OPTIONS" :key="days" :value="days">
          最近 {{ days }} 天
        </el-radio-button>
      </el-radio-group>
      <span class="mu-range-text">{{ range[0] }} ~ {{ range[1] }}</span>
      <button type="button" class="btn" :disabled="state.loading" @click="load">
        <Refresh />
        刷新
      </button>
    </div>

    <div class="mu-quota" v-loading="state.loading">
      <div class="mu-quota-main">
        <div class="mu-quota-item">
          <span class="mu-quota-label">今日已用</span>
          <strong class="mu-quota-value">{{ formatTokens(state.usage?.usedToday) }}</strong>
        </div>
        <div class="mu-quota-item">
          <span class="mu-quota-label">每日配额</span>
          <strong class="mu-quota-value">
            {{ state.usage && state.usage.effectiveQuota > 0 ? formatTokens(state.usage.effectiveQuota) : '不限制' }}
          </strong>
        </div>
        <div class="mu-quota-item" v-if="quotaLimited">
          <span class="mu-quota-label">今日剩余</span>
          <strong class="mu-quota-value">{{ formatTokens(state.usage?.remainingTokens) }}</strong>
        </div>
        <div class="mu-quota-progress" v-if="quotaLimited">
          <el-progress
            :percentage="state.usage?.usagePercent ?? 0"
            :stroke-width="10"
            :status="quotaExceeded ? 'exception' : quotaWarn ? 'warning' : undefined"
          />
          <span class="mu-quota-percent">{{ (state.usage?.usagePercent ?? 0).toFixed(1) }}%</span>
        </div>
      </div>
      <el-alert
        v-if="quotaExceeded"
        type="error"
        :closable="false"
        show-icon
        title="今日 Token 配额已用完，调用将被网关拦截，请明日再试或联系管理员调整配额。"
        class="mu-alert"
      />
      <el-alert
        v-else-if="quotaWarn"
        type="warning"
        :closable="false"
        show-icon
        title="今日 Token 用量已超过配额的 80%，请注意控制调用量。"
        class="mu-alert"
      />
    </div>

    <div class="mu-cards" v-loading="state.loading">
      <div class="mu-card">
        <span class="mu-card-label">调用次数</span>
        <strong class="mu-card-value">{{ state.usage?.usage.totalCalls ?? 0 }}</strong>
        <span class="mu-card-sub">成功 {{ state.usage?.usage.successCalls ?? 0 }} 次</span>
      </div>
      <div class="mu-card">
        <span class="mu-card-label">失败 / 中断</span>
        <strong class="mu-card-value">{{ state.usage?.usage.failedCalls ?? 0 }}</strong>
        <span class="mu-card-sub">含上游异常与取消</span>
      </div>
      <div class="mu-card">
        <span class="mu-card-label">Token 消耗</span>
        <strong class="mu-card-value">{{ formatTokens(state.usage?.usage.totalTokens) }}</strong>
        <span class="mu-card-sub">最近 {{ state.rangeDays }} 天合计</span>
      </div>
      <div class="mu-card">
        <span class="mu-card-label">平均耗时</span>
        <strong class="mu-card-value">{{ formatDuration(state.usage?.usage.avgLatencyMs) }}</strong>
        <span class="mu-card-sub">P95 {{ formatDuration(state.usage?.usage.p95LatencyMs) }}</span>
      </div>
    </div>

    <div class="mu-grid">
      <section class="mu-panel">
        <header>
          <h3>每日调用趋势</h3>
          <span>{{ range[0] }} ~ {{ range[1] }}</span>
        </header>
        <div class="trend-chart">
          <div
            v-for="(point, index) in state.usage?.usage.trend ?? []"
            :key="point.date"
            class="trend-col"
          >
            <div class="trend-bar-wrap">
              <div
                class="trend-bar"
                :style="{ height: trendHeight(point.calls) }"
                :title="`${point.date}：调用 ${point.calls} 次，Token ${formatTokens(point.tokens)}`"
              >
                <i class="trend-bar-failed" :style="{ height: failedHeight(point.calls, point.failed) }"></i>
              </div>
            </div>
            <span class="trend-label">
              {{ (state.usage?.usage.trend.length ?? 0) <= 10 || index % 5 === 0 ? point.date.slice(5) : '' }}
            </span>
          </div>
          <div v-if="!state.usage?.usage.trend?.length" class="mu-empty">暂无调用数据</div>
        </div>
      </section>

      <section class="mu-panel">
        <header>
          <h3>模块分布</h3>
          <span>调用次数</span>
        </header>
        <div class="module-list">
          <div v-for="item in state.usage?.usage.modules ?? []" :key="item.module" class="module-item">
            <div class="module-row">
              <span>{{ moduleLabel(item.module) }}</span>
              <span>
                {{ item.calls }} 次 · {{ formatTokens(item.tokens) }} tokens
                <em v-if="item.failed"> · 失败/拦截 {{ item.failed }}</em>
              </span>
            </div>
            <div class="module-bar">
              <i :style="{ width: moduleWidth(item.calls) }"></i>
            </div>
          </div>
          <div v-if="!state.usage?.usage.modules?.length" class="mu-empty">暂无数据</div>
        </div>
      </section>
    </div>

    <section class="mu-panel">
      <header>
        <h3>最近调用</h3>
        <span>最多展示 20 条</span>
      </header>
      <el-table :data="state.usage?.recentLogs ?? []" style="width: 100%" size="small" stripe>
        <el-table-column label="时间" width="165" align="center">
          <template #default="scope">{{ formatTime(scope.row.createtime) }}</template>
        </el-table-column>
        <el-table-column label="模块" width="110" align="center">
          <template #default="scope">{{ moduleLabel(scope.row.module) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="scope">
            <el-tag :type="statusTagType(scope.row.status)" size="small">
              {{ statusLabel(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="模型" min-width="140" align="center" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.modelName || '-' }}</template>
        </el-table-column>
        <el-table-column label="Token" width="90" align="center">
          <template #default="scope">{{ formatTokens(scope.row.totalTokens) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="90" align="center">
          <template #default="scope">{{ formatDuration(scope.row.latencyMs) }}</template>
        </el-table-column>
        <el-table-column label="错误信息" min-width="180" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.errorMessage || '-' }}</template>
        </el-table-column>
      </el-table>
    </section>
  </section>
</template>

<script lang="ts" setup>
import { useIndex } from './index'
import { Refresh } from '@element-plus/icons-vue'

const {
  state,
  range,
  RANGE_OPTIONS,
  quotaLimited,
  quotaWarn,
  quotaExceeded,
  load,
  setRangeDays,
  moduleLabel,
  statusLabel,
  statusTagType,
  formatTokens,
  formatDuration,
  formatTime,
  trendHeight,
  failedHeight,
  moduleWidth,
} = useIndex()
</script>

<style lang="less" scoped>
@import './index.less';
</style>
