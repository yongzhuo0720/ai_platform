<template>
  <section class="page-section api-keys-page">
    <div class="page-hero">
      <div class="page-eyebrow">用量中心</div>
      <div class="page-heading">
        <div>
          <h1>API 密钥</h1>
          <p>给自己的程序签发密钥，用 OpenAI 协议直接调用平台已配置的模型。</p>
        </div>
      </div>
    </div>

    <div class="ak-toolbar">
      <button type="button" class="btn btn-primary" @click="openCreate">
        <Plus />
        新建密钥
      </button>
      <button type="button" class="btn" :disabled="state.loading" @click="load">
        <Refresh />
        刷新
      </button>
      <span class="ak-toolbar-text">
        共 {{ state.rows.length }} 把，可用 {{ activeCount }} 把
      </span>
    </div>

    <el-alert
      class="ak-note"
      type="info"
      :closable="false"
      show-icon
      title="明文只在创建成功时显示一次，关闭后无法找回，只能重新签发。密钥消耗的是你自己的额度：限流、每日 Token 配额与调用审计都与浏览器对话同一套账。"
    />

    <el-table :data="state.rows" v-loading="state.loading" style="width: 100%" size="small" stripe>
      <el-table-column label="名称" min-width="140" show-overflow-tooltip prop="name" />
      <el-table-column label="密钥" width="150" align="center">
        <template #default="scope">
          <code class="ak-prefix">{{ scope.row.tokenPrefix }}…</code>
        </template>
      </el-table-column>
      <el-table-column label="可用模型" min-width="160" show-overflow-tooltip>
        <template #default="scope">{{ allowedLabel(scope.row) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="scope">
          <el-tag :type="statusTagType(scope.row)" size="small">{{ statusLabel(scope.row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="过期" width="140" align="center">
        <template #default="scope">{{ expireLabel(scope.row) }}</template>
      </el-table-column>
      <el-table-column label="最后使用" width="140" align="center">
        <template #default="scope">{{ formatTime(scope.row.lastUsedAt) }}</template>
      </el-table-column>
      <el-table-column label="启用" width="80" align="center">
        <template #default="scope">
          <el-switch
            :model-value="scope.row.enable !== false"
            :disabled="isExpired(scope.row)"
            @update:model-value="(value: boolean | string | number) => toggleEnable(scope.row, Boolean(value))"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" align="center" fixed="right">
        <template #default="scope">
          <el-button link type="primary" size="small" @click="openEdit(scope.row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="remove(scope.row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <span class="ak-empty">还没有密钥，点「新建密钥」签发第一把。</span>
      </template>
    </el-table>

    <el-dialog v-model="state.formVisible" :title="state.formMode === 'create' ? '新建密钥' : '编辑密钥'" width="480px">
      <el-form label-width="88px" @submit.prevent>
        <el-form-item label="名称">
          <el-input v-model="state.form.name" maxlength="50" placeholder="例如：本地脚本 / CI" />
        </el-form-item>
        <el-form-item label="可用模型">
          <el-input
            v-model="state.form.allowModels"
            type="textarea"
            :rows="2"
            placeholder="留空表示全部模型；多个用逗号分隔，可填模型名或平台 iid"
          />
        </el-form-item>
        <el-form-item label="有效期">
          <el-select v-model="state.form.expireDays" style="width: 100%">
            <el-option v-for="item in EXPIRE_OPTIONS" :key="item.days" :label="item.label" :value="item.days" />
          </el-select>
          <div class="ak-hint" v-if="state.formMode === 'edit'">
            编辑时选「不过期」不会清空原有到期时间，只能改成更晚的时刻；要立即停用请用列表里的开关。
          </div>
        </el-form-item>
        <el-form-item label="启用" v-if="state.formMode === 'edit'">
          <el-switch v-model="state.form.enable" />
        </el-form-item>
      </el-form>
      <template #footer>
        <button type="button" class="btn" @click="state.formVisible = false">取消</button>
        <button type="button" class="btn btn-primary" :disabled="state.saving" @click="save">
          {{ state.saving ? '提交中…' : '确定' }}
        </button>
      </template>
    </el-dialog>

    <el-dialog
      :model-value="!!state.issued"
      title="密钥已生成"
      width="560px"
      :close-on-click-modal="false"
      @close="closeIssued"
    >
      <p class="ak-issued-tip">
        「{{ state.issuedName }}」的明文只有这一次机会，请立即复制保存。关掉本窗后无法再次查看。
      </p>
      <el-input :model-value="state.issued" readonly type="textarea" :rows="2" class="ak-issued-key" />
      <div class="ak-issued-actions">
        <button type="button" class="btn btn-primary" @click="copyIssued">复制密钥</button>
      </div>
      <p class="ak-usage-title">调用示例</p>
      <pre class="ak-usage">{{ usageSample }}</pre>
      <p class="ak-usage-title">先看看有哪些模型可用</p>
      <pre class="ak-usage">curl {{ origin }}/ai/v1/models \
  -H "Authorization: Bearer {{ state.issued }}"</pre>
      <template #footer>
        <button type="button" class="btn" @click="closeIssued">我已保存，关闭</button>
      </template>
    </el-dialog>
  </section>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { useIndex } from './index'
import { Plus, Refresh } from '@element-plus/icons-vue'

const {
  EXPIRE_OPTIONS,
  state,
  activeCount,
  load,
  openCreate,
  openEdit,
  save,
  toggleEnable,
  remove,
  closeIssued,
  copyIssued,
  isExpired,
  statusLabel,
  statusTagType,
  expireLabel,
  allowedLabel,
  formatTime,
  usageSample,
} = useIndex()

const origin = computed(() => window.location.origin)
</script>

<style lang="less" scoped>
@import './index.less';
</style>
