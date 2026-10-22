<template>
  <div class="content ai-gateway-page">
    <div class="gw-header">
      <div class="gw-title-block">
        <h2>AI 调用治理</h2>
        <p>Dify 与智能体调用统一经服务端网关转发：密钥不下发浏览器，并按用户限流、配额与审计。</p>
      </div>
      <div class="gw-status">
        <span class="gw-status-item">
          <i :class="['status-dot', state.config?.enabled ? 'on' : 'off']"></i>
          {{ state.config?.enabled ? '网关运行中' : '网关已关闭' }}
        </span>
        <span class="gw-status-item" v-if="state.config">
          限流 {{ state.config.rateLimitPerMinute }} 次/分钟
        </span>
        <span class="gw-status-item" v-if="state.config">
          默认配额 {{ formatTokens(state.config.defaultDailyTokenQuota) }} tokens/日
        </span>
        <span
          v-for="item in state.config?.modules ?? []"
          :key="item.module"
          :class="['gw-module-chip', item.configured ? 'ok' : 'missing']"
        >
          {{ item.moduleName }} · {{ item.configured ? '已配置' : '未配置' }}
        </span>
      </div>
    </div>

    <el-tabs v-model="state.activeTab" class="gw-tabs" @tab-change="onTabChange">
      <el-tab-pane label="用量概览" name="overview">
        <div class="gw-toolbar">
          <el-date-picker
            v-model="state.range"
            type="daterange"
            unlink-panels
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :clearable="false"
            style="width: 260px"
          />
          <button type="button" class="btn btn-primary" @click="loadSummary">
            <Search />
            查询
          </button>
          <span v-if="state.summary?.truncated" class="gw-warn">
            数据量较大，仅统计最近 10 万条调用
          </span>
        </div>

        <div class="gw-cards" v-loading="state.summaryLoading">
          <div class="gw-card">
            <span class="gw-card-label">总调用次数</span>
            <strong class="gw-card-value">{{ state.summary?.totalCalls ?? 0 }}</strong>
            <span class="gw-card-sub">
              成功 {{ state.summary?.successCalls ?? 0 }} 次
            </span>
          </div>
          <div class="gw-card">
            <span class="gw-card-label">成功率</span>
            <strong class="gw-card-value">{{ (state.summary?.successRate ?? 0).toFixed(1) }}%</strong>
            <span class="gw-card-sub">
              拦截 {{ state.summary?.blockedCalls ?? 0 }} 次
            </span>
          </div>
          <div class="gw-card">
            <span class="gw-card-label">失败 / 中断</span>
            <strong class="gw-card-value">{{ state.summary?.failedCalls ?? 0 }}</strong>
            <span class="gw-card-sub">含上游异常与客户端取消</span>
          </div>
          <div class="gw-card">
            <span class="gw-card-label">Token 消耗</span>
            <strong class="gw-card-value">{{ formatTokens(state.summary?.totalTokens) }}</strong>
            <span class="gw-card-sub">按上游返回值统计</span>
          </div>
          <div class="gw-card">
            <span class="gw-card-label">平均耗时</span>
            <strong class="gw-card-value">{{ formatDuration(state.summary?.avgLatencyMs) }}</strong>
            <span class="gw-card-sub">端到端网关转发耗时</span>
          </div>
          <div class="gw-card">
            <span class="gw-card-label">P95 耗时</span>
            <strong class="gw-card-value">{{ formatDuration(state.summary?.p95LatencyMs) }}</strong>
            <span class="gw-card-sub">95% 的调用不慢于此值</span>
          </div>
        </div>

        <div class="gw-grid">
          <section class="gw-panel gw-panel-trend">
            <header>
              <h3>每日调用趋势</h3>
              <span>{{ state.range[0] }} ~ {{ state.range[1] }}</span>
            </header>
            <div class="trend-chart">
              <div
                v-for="(point, index) in state.summary?.trend ?? []"
                :key="point.date"
                class="trend-col"
              >
                <div class="trend-bar-wrap">
                  <div
                    class="trend-bar"
                    :style="{ height: trendHeight(point.calls) }"
                    :title="`${point.date}：调用 ${point.calls} 次，Token ${formatTokens(point.tokens)}，失败/拦截 ${point.failed} 次`"
                  >
                    <i
                      class="trend-bar-failed"
                      :style="{ height: failedHeight(point.calls, point.failed) }"
                    ></i>
                  </div>
                </div>
                <span class="trend-label">
                  {{ (state.summary?.trend.length ?? 0) <= 10 || index % 5 === 0 ? point.date.slice(5) : '' }}
                </span>
              </div>
              <div v-if="!state.summary?.trend?.length" class="gw-empty">暂无调用数据</div>
            </div>
          </section>

          <section class="gw-panel">
            <header>
              <h3>模块分布</h3>
              <span>调用次数</span>
            </header>
            <div class="module-list">
              <div v-for="item in state.summary?.modules ?? []" :key="item.module" class="module-item">
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
            </div>

            <h3 class="gw-sub-title">调用 Top 10</h3>
            <div class="top-users">
              <div v-for="(user, index) in state.summary?.topUsers ?? []" :key="user.userId" class="top-user">
                <span class="top-rank">{{ index + 1 }}</span>
                <span class="top-name">{{ user.userName }}</span>
                <div class="top-bar">
                  <i :style="{ width: userWidth(user.calls) }"></i>
                </div>
                <span class="top-num">{{ user.calls }} 次</span>
                <span class="top-num">{{ formatTokens(user.tokens) }}</span>
              </div>
              <div v-if="!state.summary?.topUsers?.length" class="gw-empty">暂无数据</div>
            </div>
          </section>
        </div>

        <section class="gw-panel">
          <header>
            <h3>模型细分</h3>
            <span>按模型聚合的调用量与耗时</span>
          </header>
          <div class="model-list">
            <div v-for="item in state.summary?.models ?? []" :key="item.model" class="module-item">
              <div class="module-row">
                <span class="table-cell-mono">{{ item.model }}</span>
                <span>
                  {{ item.calls }} 次 · {{ formatTokens(item.tokens) }} tokens
                  · 平均 {{ formatDuration(item.avgLatencyMs) }}
                  <em v-if="item.failed"> · 失败/拦截 {{ item.failed }}</em>
                </span>
              </div>
              <div class="module-bar">
                <i :style="{ width: modelWidth(item.calls) }"></i>
              </div>
            </div>
            <div v-if="!state.summary?.models?.length" class="gw-empty">暂无数据</div>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="调用日志" name="logs">
        <div class="gw-toolbar gw-toolbar-wrap">
          <el-date-picker
            v-model="state.range"
            type="daterange"
            unlink-panels
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :clearable="false"
            style="width: 260px"
          />
          <el-input v-model="state.logQuery.userId" placeholder="用户 UID" clearable style="width: 160px" />
          <el-select v-model="state.logQuery.module" placeholder="全部模块" clearable style="width: 150px">
            <el-option label="问答聊天" value="chat" />
            <el-option label="知识库对话" value="knowledgeChat" />
            <el-option label="知识库文件" value="file" />
            <el-option label="模型直连" value="models" />
          </el-select>
          <el-select v-model="state.logQuery.status" placeholder="全部状态" clearable style="width: 130px">
            <el-option label="成功" value="success" />
            <el-option label="失败" value="failed" />
            <el-option label="已拦截" value="blocked" />
            <el-option label="已中断" value="aborted" />
          </el-select>
          <button type="button" class="btn btn-primary" @click="loadLogs(true)">
            <Search />
            查询
          </button>
          <button type="button" class="btn" :disabled="exportLoading" @click="exportLogs">
            <Download />
            {{ exportLoading ? '导出中…' : '导出 CSV' }}
          </button>
        </div>

        <div class="user-table-card">
          <el-table
            :data="state.logs"
            v-loading="state.logsLoading"
            style="width: 100%"
            size="small"
            stripe
            max-height="calc(100vh - 400px)"
          >
            <el-table-column prop="createtime" label="时间" width="165" align="center">
              <template #default="scope">{{ formatTime(scope.row.createtime) }}</template>
            </el-table-column>
            <el-table-column prop="userId" label="用户 UID" width="150" align="center" show-overflow-tooltip />
            <el-table-column label="模块" width="110" align="center">
              <template #default="scope">{{ moduleLabel(scope.row.module) }}</template>
            </el-table-column>
            <el-table-column label="接口" min-width="220" show-overflow-tooltip>
              <template #default="scope">
                <span class="table-cell-mono">{{ scope.row.method }} {{ scope.row.endpoint }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90" align="center">
              <template #default="scope">
                <el-tag :type="statusTagType(scope.row.status)" size="small">
                  {{ statusLabel(scope.row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="httpStatus" label="HTTP" width="75" align="center">
              <template #default="scope">{{ scope.row.httpStatus ?? '-' }}</template>
            </el-table-column>
            <el-table-column label="Token" width="90" align="center">
              <template #default="scope">{{ formatTokens(scope.row.totalTokens) }}</template>
            </el-table-column>
            <el-table-column label="耗时" width="90" align="center">
              <template #default="scope">{{ formatDuration(scope.row.latencyMs) }}</template>
            </el-table-column>
            <el-table-column prop="modelName" label="模型" width="130" align="center" show-overflow-tooltip>
              <template #default="scope">{{ scope.row.modelName || '-' }}</template>
            </el-table-column>
            <el-table-column prop="errorMessage" label="错误信息" min-width="200" show-overflow-tooltip>
              <template #default="scope">
                <span :class="{ 'gw-error-text': scope.row.errorMessage }">
                  {{ scope.row.errorMessage || '-' }}
                </span>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div class="page-block">
          <el-pagination
            @size-change="handleSizeChange"
            @current-change="handleCurrentChange"
            :current-page="state.pagination.pageNum"
            :page-sizes="[20, 50, 100]"
            :page-size="state.pagination.pageSize"
            layout="total, sizes, prev, pager, next, jumper"
            :total="state.pagination.total"
          >
          </el-pagination>
        </div>
      </el-tab-pane>

      <el-tab-pane label="配额管理" name="quotas">
        <div class="gw-toolbar">
          <span class="gw-quota-default">
            默认每日配额：
            <strong>{{ formatTokens(state.quotas?.defaultDailyTokenQuota ?? state.config?.defaultDailyTokenQuota) }}</strong>
            tokens（0 或负数表示不限制）
          </span>
          <button type="button" class="btn btn-primary" @click="openQuotaDialog()">
            <Plus />
            新增配额
          </button>
          <button type="button" class="btn" @click="loadQuotas">
            <Refresh />
            刷新
          </button>
        </div>

        <div class="user-table-card">
          <el-table
            :data="state.quotas?.items ?? []"
            v-loading="state.quotasLoading"
            style="width: 100%"
            size="small"
            stripe
            max-height="calc(100vh - 330px)"
          >
            <el-table-column label="用户" min-width="190">
              <template #default="scope">
                <div class="gw-user-cell">
                  <span class="table-cell-strong">{{ scope.row.userName }}</span>
                  <span class="gw-user-id">{{ scope.row.userId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="每日配额" width="160" align="center">
              <template #default="scope">{{ quotaText(scope.row.dailyTokenQuota) }}</template>
            </el-table-column>
            <el-table-column label="今日已用" width="130" align="center">
              <template #default="scope">{{ formatTokens(scope.row.usedToday) }}</template>
            </el-table-column>
            <el-table-column label="使用率" min-width="180">
              <template #default="scope">
                <el-progress
                  v-if="scope.row.effectiveQuota > 0"
                  :percentage="usagePercent(scope.row)"
                  :stroke-width="8"
                  :status="usagePercent(scope.row) >= 100 ? 'exception' : usagePercent(scope.row) >= 80 ? 'warning' : undefined"
                />
                <span v-else class="gw-muted">不限制</span>
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip>
              <template #default="scope">{{ scope.row.remark || '-' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="150" align="center" fixed="right">
              <template #default="scope">
                <el-button link type="primary" size="small" @click="openQuotaDialog(scope.row)">
                  编辑
                </el-button>
                <el-button link type="danger" size="small" @click="deleteQuota(scope.row)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="state.quotaDialogVisible"
      :title="state.quotaEditing ? '编辑 AI 配额' : '新增 AI 配额'"
      width="520px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form :model="state.quotaForm" label-width="110px">
        <el-form-item label="用户">
          <el-input v-if="state.quotaEditing" :model-value="state.quotaForm.userId" disabled />
          <el-select
            v-else
            v-model="state.quotaForm.userId"
            filterable
            placeholder="请选择用户"
            style="width: 100%"
          >
            <el-option
              v-for="user in state.users"
              :key="user.uid"
              :label="userLabel(user)"
              :value="user.uid"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="每日配额">
          <el-input-number
            v-model="state.quotaForm.dailyTokenQuota"
            :min="0"
            :step="10000"
            controls-position="right"
            style="width: 100%"
          />
          <span class="gw-form-tip">单位 tokens，0 表示该用户不限制</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="state.quotaForm.remark"
            type="textarea"
            :rows="2"
            maxlength="120"
            show-word-limit
            placeholder="例如：算法组测试账号"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="state.quotaDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="state.quotaSaving" @click="saveQuota">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import { useIndex } from './index'

const {
  state,
  loadSummary,
  loadLogs,
  loadQuotas,
  exportLoading,
  exportLogs,
  onTabChange,
  handleSizeChange,
  handleCurrentChange,
  openQuotaDialog,
  saveQuota,
  deleteQuota,
  moduleLabel,
  statusLabel,
  statusTagType,
  formatTokens,
  formatDuration,
  formatTime,
  trendHeight,
  failedHeight,
  moduleWidth,
  modelWidth,
  userWidth,
  usagePercent,
  quotaText,
  userLabel,
} = useIndex()
</script>

<style lang="less" scoped>
@import url('./index.less');
</style>
