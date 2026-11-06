<template>
  <section class="page-section rag-eval-page">
    <div class="page-hero">
      <div class="page-eyebrow">知识库中心</div>
      <div class="page-heading">
        <div>
          <h1>RAG 评估</h1>
          <p>
            用真实检索跑黄金评估集，按文档级口径计算 Recall@K、Precision@K、MRR、NDCG@K，
            并保留逐题明细与历史对比，避免「感觉检索变好了」式的盲调。
          </p>
        </div>
        <div class="hero-stats">
          <button type="button" class="btn btn-primary" @click="openCreateEditor">
            <Plus />
            新建数据集
          </button>
        </div>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="评估会真实调用知识检索接口，每题一次向量请求。启动前会校验期望文档已上传到对应知识库，全部题目跑完前请保持服务运行。"
      class="rag-eval-tip"
    />

    <div class="rag-eval-grid">
      <section class="rag-eval-panel rag-eval-datasets">
        <header>
          <h3>评估数据集</h3>
          <span>共 {{ state.datasets.length }} 个</span>
        </header>
        <div v-loading="state.datasetsLoading" class="rag-eval-dataset-list">
          <button
            v-for="dataset in state.datasets"
            :key="dataset.datasetKey"
            type="button"
            class="rag-eval-dataset-item"
            :class="{ active: dataset.datasetKey === state.selectedDatasetKey }"
            :aria-pressed="dataset.datasetKey === state.selectedDatasetKey"
            @click="selectDataset(dataset.datasetKey)"
          >
            <span class="rag-eval-dataset-head">
              <strong>{{ dataset.name }}</strong>
              <span class="rag-eval-dataset-count">{{ dataset.caseCount }} 条</span>
            </span>
            <span class="rag-eval-dataset-meta">知识库：{{ dataset.kbName }}</span>
            <span v-if="dataset.description" class="rag-eval-dataset-desc">
              {{ dataset.description }}
            </span>
          </button>
          <div v-if="!state.datasetsLoading && !state.datasets.length" class="rag-eval-empty">
            还没有评估数据集。新建一个数据集并导入问题与期望文档，才能开始量化检索质量。
          </div>
        </div>
      </section>

      <div class="rag-eval-main">
        <section v-if="selectedDataset" v-loading="state.detailLoading" class="rag-eval-panel">
          <header>
            <div class="rag-eval-panel-title">
              <h3>{{ selectedDataset.name }}</h3>
              <el-tag size="small" type="info">{{ selectedDataset.kbName }}</el-tag>
            </div>
            <div class="rag-eval-panel-actions">
              <button type="button" class="btn" @click="openEditEditor">
                <Edit />
                编辑
              </button>
              <button type="button" class="btn" @click="deleteDataset">
                <Delete />
                删除
              </button>
            </div>
          </header>
          <p v-if="selectedDataset.description" class="rag-eval-description">
            {{ selectedDataset.description }}
          </p>

          <div class="rag-eval-run-bar">
            <div class="rag-eval-run-k">
              <span>截断位置 K</span>
              <el-select v-model="state.topK" style="width: 96px" :disabled="state.startingRun">
                <el-option v-for="k in [3, 5, 10, 20]" :key="k" :label="`Top ${k}`" :value="k" />
              </el-select>
            </div>
            <button
              type="button"
              class="btn btn-primary"
              :disabled="state.startingRun || hasActiveRun || !state.detail?.cases.length"
              @click="startRun"
            >
              <VideoPlay />
              {{ state.startingRun ? '提交中…' : hasActiveRun ? '评估进行中' : '发起评估' }}
            </button>
            <span v-if="hasActiveRun" class="rag-eval-run-hint">
              任务在后台执行，进度每 2 秒自动刷新
            </span>
          </div>

          <el-table
            :data="state.detail?.cases ?? []"
            size="small"
            stripe
            max-height="280"
            style="width: 100%"
          >
            <el-table-column type="index" label="#" width="48" align="center" />
            <el-table-column prop="question" label="问题" min-width="200" show-overflow-tooltip />
            <el-table-column prop="expectedDoc" label="期望文档" min-width="150" show-overflow-tooltip />
            <el-table-column label="关键词" min-width="150">
              <template #default="scope">
                <template v-if="scope.row.keywords?.length">
                  <el-tag
                    v-for="keyword in scope.row.keywords"
                    :key="keyword"
                    size="small"
                    class="rag-eval-keyword"
                  >
                    {{ keyword }}
                  </el-tag>
                </template>
                <span v-else class="rag-eval-muted">-</span>
              </template>
            </el-table-column>
            <el-table-column prop="category" label="题型" width="92" align="center" />
          </el-table>
          <div v-if="!state.detail?.cases.length" class="rag-eval-empty">
            数据集中还没有用例，点击「编辑」添加问题与期望文档。
          </div>
        </section>

        <section v-else class="rag-eval-panel rag-eval-placeholder">
          <h3>选择一个评估数据集</h3>
          <p>从左侧选择数据集查看用例与运行历史，或新建一个数据集。</p>
        </section>

        <section class="rag-eval-panel">
          <header>
            <h3>运行历史</h3>
            <div class="rag-eval-panel-actions">
              <span class="rag-eval-muted">共 {{ state.runs.length }} 次</span>
              <button type="button" class="btn" :disabled="state.runsLoading" @click="loadRuns">
                <Refresh />
                刷新
              </button>
            </div>
          </header>
          <el-table
            v-loading="state.runsLoading"
            :data="state.runs"
            size="small"
            stripe
            class="rag-eval-runs"
            @row-click="openRun"
          >
            <el-table-column label="状态" width="92">
              <template #default="scope">
                <el-tag :type="statusMeta(scope.row.status).type" size="small">
                  {{ statusMeta(scope.row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="开始时间" width="142">
              <template #default="scope">{{ formatDateTime(scope.row.startedAt) }}</template>
            </el-table-column>
            <el-table-column label="TopK" width="66" align="center">
              <template #default="scope">{{ scope.row.topK }}</template>
            </el-table-column>
            <el-table-column label="进度" width="130">
              <template #default="scope">
                <div class="rag-eval-progress">
                  <el-progress
                    :percentage="runProgress(scope.row)"
                    :stroke-width="6"
                    :status="scope.row.status === 'failed' ? 'exception' : undefined"
                  />
                  <span>{{ scope.row.processed }}/{{ scope.row.total }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="Recall" width="78" align="right">
              <template #default="scope">{{ formatMetric(scope.row.recall) }}</template>
            </el-table-column>
            <el-table-column label="MRR" width="72" align="right">
              <template #default="scope">{{ formatMetric(scope.row.mrr) }}</template>
            </el-table-column>
            <el-table-column label="NDCG" width="78" align="right">
              <template #default="scope">{{ formatMetric(scope.row.ndcg) }}</template>
            </el-table-column>
            <el-table-column label="耗时" width="76" align="right">
              <template #default="scope">{{ formatDuration(scope.row.durationMs) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="110" align="center" fixed="right">
              <template #default="scope">
                <el-button link type="primary" size="small" @click.stop="openRun(scope.row)">
                  详情
                </el-button>
                <el-button link type="danger" size="small" @click.stop="deleteRun(scope.row)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="!state.runsLoading && !state.runs.length" class="rag-eval-empty">
            还没有运行记录。选好数据集与 TopK 后点击「发起评估」。
          </div>
        </section>
      </div>
    </div>

    <el-dialog
      v-model="state.editorVisible"
      :title="isEditing ? '编辑评估数据集' : '新建评估数据集'"
      width="960px"
      :close-on-click-modal="false"
    >
      <el-form label-width="86px">
        <el-form-item label="名称">
          <el-input
            v-model="state.editor.name"
            maxlength="100"
            placeholder="如：员工制度问答黄金集"
          />
        </el-form-item>
        <el-form-item label="知识库">
          <el-input
            v-model="state.editor.kbName"
            maxlength="100"
            placeholder="默认知识库"
          />
        </el-form-item>
        <el-form-item label="说明">
          <el-input
            v-model="state.editor.description"
            type="textarea"
            :rows="2"
            maxlength="2000"
            placeholder="记录评估范围、语料版本或标注口径"
          />
        </el-form-item>
      </el-form>

      <div class="rag-eval-editor-head">
        <div>
          <strong>评估用例</strong>
          <span class="rag-eval-muted">
            {{ state.editor.cases.length }} 条，期望文档名必须与知识库中的文档一致
          </span>
        </div>
        <div class="rag-eval-panel-actions">
          <input
            ref="csvInputRef"
            type="file"
            accept=".csv,text/csv"
            class="rag-eval-csv-input"
            @change="handleCsvFileChange"
          />
          <button type="button" class="btn" @click="triggerCsvImport">
            <Upload />
            导入 CSV
          </button>
          <button type="button" class="btn" @click="addCase">
            <Plus />
            添加用例
          </button>
        </div>
      </div>

      <div class="rag-eval-editor-cases">
        <div
          v-for="(item, index) in state.editor.cases"
          :key="index"
          class="rag-eval-editor-case"
        >
          <div class="rag-eval-editor-case-head">
            <span>用例 {{ index + 1 }}</span>
            <el-button link type="danger" size="small" @click="removeCase(index)">
              删除
            </el-button>
          </div>
          <div class="rag-eval-editor-case-grid">
            <el-input
              v-model="item.question"
              maxlength="500"
              placeholder="问题（必填），如：出差住宿每晚上限多少"
            />
            <el-input
              v-model="item.expectedDoc"
              maxlength="200"
              placeholder="期望文档名（必填），如：差旅报销制度"
            />
            <el-input
              v-model="item.keywordsText"
              maxlength="500"
              placeholder="关键词（逗号分隔，可选），如：600, 住宿"
            />
            <el-input
              v-model="item.category"
              maxlength="32"
              placeholder="题型（可选），如：数值型"
            />
          </div>
        </div>
        <div v-if="!state.editor.cases.length" class="rag-eval-empty">
          还没有用例，点击「添加用例」或导入 CSV。
        </div>
      </div>

      <template #footer>
        <el-button @click="state.editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="state.editorSaving" @click="submitEditor">
          保存
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="state.runDialogVisible"
      :title="state.runDetail ? `运行详情 · ${formatDateTime(state.runDetail.run.startedAt)}` : '运行详情'"
      width="1180px"
    >
      <div v-loading="state.runDetailLoading" class="rag-eval-run-detail">
        <template v-if="state.runDetail">
          <div class="rag-eval-detail-head">
            <el-tag :type="statusMeta(state.runDetail.run.status).type">
              {{ statusMeta(state.runDetail.run.status).label }}
            </el-tag>
            <span>TopK {{ state.runDetail.run.topK }}</span>
            <span>处理 {{ state.runDetail.run.processed }}/{{ state.runDetail.run.total }}</span>
            <span>耗时 {{ formatDuration(state.runDetail.run.durationMs) }}</span>
            <span v-if="state.runDetail.run.embeddingModel">
              向量模型 {{ state.runDetail.run.embeddingModel }}
            </span>
            <el-tag v-if="state.runDetail.run.metricSpec" size="small" type="info">
              口径 {{ state.runDetail.run.metricSpec }}
            </el-tag>
            <el-tag
              v-if="state.runDetail.run.retrievalMode"
              size="small"
              :type="state.runDetail.run.rerankApplied ? 'success' : 'info'"
            >
              检索链路 {{ state.runDetail.run.retrievalMode }}
            </el-tag>
            <span v-if="state.runDetail.baseline" class="rag-eval-baseline">
              对比基线：{{ formatDateTime(state.runDetail.baseline.startedAt) }}（TopK
              {{ state.runDetail.baseline.topK }}）
              <template v-if="baselineMetricMismatch">· 口径不同，差值仅供参考</template>
            </span>
          </div>

          <el-alert
            v-if="state.runDetail.run.errorMessage"
            :type="state.runDetail.run.status === 'failed' ? 'error' : 'warning'"
            :title="state.runDetail.run.errorMessage"
            :closable="false"
            show-icon
            class="rag-eval-alert"
          />

          <div class="rag-eval-metrics">
            <div v-for="card in metricCards" :key="card.key" class="rag-eval-metric">
              <span class="rag-eval-metric-label">{{ card.label }}</span>
              <strong class="rag-eval-metric-value">{{ formatMetric(card.value) }}</strong>
              <span :class="deltaClass(card.value, card.base)">
                {{ formatDelta(card.value, card.base) }}
              </span>
              <span class="rag-eval-metric-hint">{{ card.hint }}</span>
            </div>
          </div>

          <div class="rag-eval-detail-sub">
            完全漏召 {{ state.runDetail.run.missCount ?? 0 }} 条 · 参与关键词统计
            {{ state.runDetail.run.keywordEvaluatedCount ?? 0 }} 条 · 关键词命中率
            {{ formatPercent(state.runDetail.run.keywordHitRate) }}
          </div>

          <el-table
            :data="state.runDetail.results"
            size="small"
            stripe
            max-height="420"
            style="width: 100%"
          >
            <el-table-column type="expand" width="40">
              <template #default="scope">
                <div class="rag-eval-hits">
                  <template v-if="scope.row.retrievedHits?.length">
                    <div class="rag-eval-hits-head">
                      <strong>Top-{{ scope.row.retrievedHits.length }} 命中明细</strong>
                      <span class="rag-eval-muted">
                        「融合排序分」只用于排序，不是相似度；向量分与词项重叠分来自两条召回路径
                      </span>
                    </div>
                    <el-table :data="scope.row.retrievedHits" size="small" border>
                      <el-table-column prop="rank" label="#" width="44" align="center" />
                      <el-table-column label="文档 / 分块" min-width="180" show-overflow-tooltip>
                        <template #default="hit">
                          {{ hit.row.docName || '-' }}
                          <el-tag
                            v-if="hit.row.chunkIndex !== null && hit.row.chunkIndex !== undefined"
                            size="small"
                            type="info"
                            class="rag-eval-chunk-tag"
                          >
                            块 {{ hit.row.chunkIndex }}
                          </el-tag>
                        </template>
                      </el-table-column>
                      <el-table-column label="命中来源" width="94" align="center">
                        <template #default="hit">
                          <el-tag size="small" :type="channelMeta(hit.row.channel).type">
                            {{ channelMeta(hit.row.channel).label }}
                          </el-tag>
                        </template>
                      </el-table-column>
                      <el-table-column label="融合排序分" width="100" align="right">
                        <template #default="hit">{{ formatScore(hit.row.score) }}</template>
                      </el-table-column>
                      <el-table-column label="向量分" width="82" align="right">
                        <template #default="hit">{{ formatScore(hit.row.denseScore) }}</template>
                      </el-table-column>
                      <el-table-column label="词项重叠" width="82" align="right">
                        <template #default="hit">{{ formatLexicalScore(hit.row.lexicalScore) }}</template>
                      </el-table-column>
                      <el-table-column label="片段预览" min-width="260" show-overflow-tooltip>
                        <template #default="hit">{{ hit.row.contentPreview || '-' }}</template>
                      </el-table-column>
                    </el-table>
                  </template>
                  <div v-else class="rag-eval-muted">
                    本次运行未记录 Top-K 明细（旧版本运行或该题检索失败）。
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column type="index" label="#" width="44" align="center" />
            <el-table-column prop="question" label="问题" min-width="180" show-overflow-tooltip />
            <el-table-column prop="expectedDoc" label="期望文档" min-width="130" show-overflow-tooltip />
            <el-table-column label="Top1" min-width="130" show-overflow-tooltip>
              <template #default="scope">
                {{ scope.row.topDoc || '未命中' }}
              </template>
            </el-table-column>
            <el-table-column label="首中排名" width="84" align="center">
              <template #default="scope">
                {{ scope.row.firstHitRank ? `第 ${scope.row.firstHitRank} 位` : '未命中' }}
              </template>
            </el-table-column>
            <el-table-column label="Recall" width="74" align="right">
              <template #default="scope">{{ formatMetric(scope.row.recall) }}</template>
            </el-table-column>
            <el-table-column label="Precision" width="80" align="right">
              <template #default="scope">{{ formatMetric(scope.row.precision) }}</template>
            </el-table-column>
            <el-table-column label="RR" width="66" align="right">
              <template #default="scope">{{ formatMetric(scope.row.reciprocalRank) }}</template>
            </el-table-column>
            <el-table-column label="NDCG" width="70" align="right">
              <template #default="scope">{{ formatMetric(scope.row.ndcg) }}</template>
            </el-table-column>
            <el-table-column label="关键词" width="76" align="center">
              <template #default="scope">
                <el-tag
                  v-if="scope.row.keywordEvaluated"
                  :type="scope.row.keywordHit ? 'success' : 'danger'"
                  size="small"
                >
                  {{ scope.row.keywordHit ? '命中' : '未命中' }}
                </el-tag>
                <span v-else class="rag-eval-muted">-</span>
              </template>
            </el-table-column>
            <el-table-column label="耗时" width="68" align="right">
              <template #default="scope">{{ formatDuration(scope.row.durationMs) }}</template>
            </el-table-column>
            <el-table-column label="失败原因" min-width="140" show-overflow-tooltip>
              <template #default="scope">
                <span v-if="scope.row.errorMessage" class="rag-eval-error">
                  {{ scope.row.errorMessage }}
                </span>
                <span v-else class="rag-eval-muted">-</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="!state.runDetail.results.length" class="rag-eval-empty">
            这次运行没有留下逐题明细。
          </div>
        </template>
        <div v-else class="rag-eval-empty">暂无详情</div>
      </div>
    </el-dialog>
  </section>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import { Delete, Edit, Plus, Refresh, Upload, VideoPlay } from '@element-plus/icons-vue'
import { useIndex } from './index'

const {
  state,
  isEditing,
  hasActiveRun,
  selectedDataset,
  metricCards,
  baselineMetricMismatch,
  loadRuns,
  selectDataset,
  startRun,
  openRun,
  deleteRun,
  deleteDataset,
  openCreateEditor,
  openEditEditor,
  addCase,
  removeCase,
  handleCsvFileChange,
  submitEditor,
  statusMeta,
  runProgress,
  channelMeta,
  formatScore,
  formatLexicalScore,
  formatMetric,
  formatPercent,
  formatDelta,
  deltaClass,
  formatDateTime,
  formatDuration,
} = useIndex()

const csvInputRef = ref<HTMLInputElement | null>(null)
const triggerCsvImport = () => csvInputRef.value?.click()
</script>

<style lang="less" scoped>
@import './index.less';
</style>
