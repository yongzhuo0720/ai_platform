<template>
  <div class="content">
    <div class="user-search-bar">
      <el-form :inline="true" :model="state.formInline" class="search-form">
        <el-form-item label="模型名称">
          <el-input
            v-model="state.formInline.name"
            placeholder="请输入名称"
            clearable
            @keyup.enter="onQuery"
            style="width: 180px"
          />
        </el-form-item>
        <el-form-item>
          <button type="button" class="btn btn-primary" @click="onQuery">
            <Search />
            搜索
          </button>
          <button type="button" class="btn btn-primary" @click="showAdd">
            <CirclePlusFilled />
            新建
          </button>
        </el-form-item>
      </el-form>
    </div>

    <div class="user-table-card">
      <el-table
        v-loading="state.loading"
        :data="state.tableData"
        style="width: 100%"
        size="small"
        stripe
        height="calc(100vh - 200px)"
      >
        <el-table-column type="index" label="序号" align="center" width="80" />
        <el-table-column prop="id" label="模型ID" align="center" min-width="160">
          <template #default="scope">
            <span class="table-cell-strong">{{ scope.row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="模型名称" align="center" min-width="180">
          <template #default="scope">
            <span class="table-cell-strong">{{ scope.row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="provider" label="提供者" align="center" min-width="160">
          <template #default="scope">
            <span>{{ scope.row.provider || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="直连" align="center" width="110">
          <template #default="scope">
            <el-tag v-if="scope.row.protocol === 'openai'" type="success" size="small">OpenAI 兼容</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" align="center" width="80">
          <template #default="scope">
            <el-switch
              :model-value="scope.row.enable !== false"
              :loading="state.togglingIid === scope.row.iid"
              :disabled="scope.row.iid == null"
              @change="(val: string | number | boolean) => onToggleEnable(scope.row, Boolean(val))"
            />
          </template>
        </el-table-column>
        <el-table-column label="单价（分/百万）" align="center" width="150">
          <template #default="scope">
            <span :class="{ 'model-price-missing': priceText(scope.row) === '未定价' }">
              {{ priceText(scope.row) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="连接" align="center" width="110">
          <template #default="scope">
            <el-tooltip :content="connectionResult(scope.row)?.message || '尚未测试'" placement="top">
              <el-tag :type="connectionTagType(scope.row)" size="small">
                {{ connectionLabel(scope.row) }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="操作" align="center" width="250" fixed="right">
          <template #default="scope">
            <button
              v-if="scope.row.protocol === 'openai'"
              type="button"
              class="action-btn"
              :disabled="state.testingIid === scope.row.iid"
              @click="onTestConnection(scope.row)"
            >
              <Connection />
              <span>{{ state.testingIid === scope.row.iid ? '测试中…' : '测试连接' }}</span>
            </button>
            <button type="button" class="action-btn" @click="showEdit(scope.row)">
              <Edit />
              <span>编辑</span>
            </button>
            <button type="button" class="action-btn" @click="showDelete(scope.row)">
              <Delete />
              <span>删除</span>
            </button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="page-block">
      <el-pagination
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
        :current-page="state.pagination.pageNum"
        :page-sizes="[20, 30, 50, 100]"
        :page-size="state.pagination.pageSize"
        layout="total, sizes, prev, pager, next, jumper"
        :total="state.pagination.total"
      />
    </div>

    <el-dialog
      v-model="state.params.dialogVisible"
      :title="state.params.title"
      width="550px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form
        ref="ruleFormRef"
        :model="state.params.form"
        :rules="state.rules"
        label-width="100px"
        size="large"
      >
        <el-form-item label="模型ID" prop="id">
          <el-input
            v-model="state.params.form.id"
            placeholder="请输入模型ID"
            clearable
          />
        </el-form-item>
        <el-form-item label="模型名称" prop="name">
          <el-input
            v-model="state.params.form.name"
            placeholder="请输入模型名称"
            clearable
          />
        </el-form-item>
        <el-form-item label="提供者" prop="provider">
          <el-input
            v-model="state.params.form.provider"
            placeholder="请输入提供者"
            clearable
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="state.params.form.enable" />
          <span class="model-form-tip">停用后网关不再路由到该模型，配置保留</span>
        </el-form-item>
        <el-form-item label="直连协议" prop="protocol">
          <el-select v-model="state.params.form.protocol" placeholder="不启用直连" clearable style="width: 100%">
            <el-option label="OpenAI 兼容直连（可在对话页直接调用）" value="openai" />
          </el-select>
        </el-form-item>
        <template v-if="state.params.form.protocol === 'openai'">
          <el-form-item label="服务地址" prop="baseUrl">
            <el-input
              v-model="state.params.form.baseUrl"
              placeholder="如 https://api.deepseek.com（不含 /chat/completions）"
              clearable
            />
          </el-form-item>
          <el-form-item label="上游模型" prop="upstreamModel">
            <el-input
              v-model="state.params.form.upstreamModel"
              placeholder="如 deepseek-chat（请求体的 model 字段以此为准）"
              clearable
            />
          </el-form-item>
          <el-form-item label="输入单价">
            <el-input-number
              v-model="state.params.form.priceInputPerMillion"
              :min="0"
              :step="1"
              :precision="0"
              controls-position="right"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="输出单价">
            <el-input-number
              v-model="state.params.form.priceOutputPerMillion"
              :min="0"
              :step="1"
              :precision="0"
              controls-position="right"
              style="width: 100%"
            />
          </el-form-item>
          <div class="model-form-tip model-form-tip-block">
            单价单位：分 / 百万 tokens；留空表示未定价，成本看板会标记该模型，不会把 0 当成免费。
          </div>
          <el-form-item label="向量模型" prop="embedModel">
            <el-input
              v-model="state.params.form.embedModel"
              placeholder="选填，如 text-embedding-v3；配置后本地知识检索（RAG）将使用该模型向量化"
              clearable
            />
          </el-form-item>
          <el-form-item label="服务密钥" prop="apiKey">
            <el-input
              v-model="state.params.form.apiKey"
              type="password"
              show-password
              autocomplete="new-password"
              placeholder="未配置时必填；已配置时留空表示不修改"
            />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="state.params.dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import { useIndex } from './index'
import { Search, CirclePlusFilled, Edit, Delete, Connection } from '@element-plus/icons-vue'

const {
  ruleFormRef,
  state,
  onQuery,
  showAdd,
  showEdit,
  showDelete,
  onSave,
  onToggleEnable,
  onTestConnection,
  handleSizeChange,
  handleCurrentChange,
  priceText,
  connectionResult,
  connectionLabel,
  connectionTagType,
} = useIndex()
</script>

<style lang="less" scoped>
@import url('./index.less');
</style>
