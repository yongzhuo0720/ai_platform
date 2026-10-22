<template>
  <section class="page-section kb-search-page">
    <div class="page-hero">
      <div class="page-eyebrow">知识库中心</div>
      <div class="page-heading">
        <div>
          <h1>知识检索</h1>
          <p>本地 RAG 实验室：上传纯文本文档，服务端自动分块并向量化，支持相似度检索测试。</p>
        </div>
        <div class="hero-stats">
          <button type="button" class="btn btn-primary" @click="openUploadDialog">
            <Plus />
            上传文档
          </button>
        </div>
      </div>
    </div>

    <div class="kb-grid">
      <section class="kb-panel">
        <header>
          <h3>文档库</h3>
          <span>共 {{ state.docs.length }} 篇</span>
        </header>
        <el-table :data="state.docs" v-loading="state.docsLoading" style="width: 100%" size="small" stripe>
          <el-table-column prop="name" label="文档" min-width="160" show-overflow-tooltip />
          <el-table-column prop="kbName" label="知识库" width="110" align="center" />
          <el-table-column label="分块" width="70" align="center">
            <template #default="scope">{{ scope.row.chunkCount }}</template>
          </el-table-column>
          <el-table-column label="字符数" width="90" align="center">
            <template #default="scope">{{ scope.row.charCount }}</template>
          </el-table-column>
          <el-table-column label="上传时间" width="140" align="center">
            <template #default="scope">{{ formatTime(scope.row.createtime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="80" align="center" fixed="right">
            <template #default="scope">
              <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!state.docsLoading && !state.docs.length" class="kb-empty">
          还没有文档。上传一篇 txt / md 文本试试检索效果。
        </div>
      </section>

      <section class="kb-panel">
        <header>
          <h3>检索测试</h3>
          <span v-if="state.searchResult">命中 {{ state.searchResult.items.length }} / 扫描 {{ state.searchResult.scanned }} 块 · {{ state.searchResult.model }}</span>
        </header>
        <div class="kb-search-bar">
          <el-input
            v-model="state.searchForm.query"
            type="textarea"
            :rows="2"
            maxlength="2000"
            placeholder="输入要检索的内容，例如：如何部署后端服务？"
          />
          <div class="kb-search-actions">
            <el-select v-model="state.searchForm.topK" style="width: 120px">
              <el-option label="Top 3" :value="3" />
              <el-option label="Top 5" :value="5" />
              <el-option label="Top 10" :value="10" />
            </el-select>
            <button type="button" class="btn btn-primary" :disabled="state.searching" @click="submitSearch">
              <Search />
              {{ state.searching ? '检索中…' : '检索' }}
            </button>
          </div>
        </div>

        <div class="kb-hits" v-loading="state.searching">
          <div v-for="(item, index) in state.searchResult?.items ?? []" :key="index" class="kb-hit">
            <div class="kb-hit-head">
              <el-tag :type="scoreTagType(item.score)" size="small">相似度 {{ item.score.toFixed(4) }}</el-tag>
              <span class="kb-hit-doc">{{ item.docName || '未知文档' }} · 第 {{ (item.chunkIndex ?? 0) + 1 }} 块</span>
            </div>
            <p class="kb-hit-content">{{ item.content }}</p>
          </div>
          <div v-if="!state.searching && state.searchResult && !state.searchResult.items.length" class="kb-empty">
            没有命中任何分块。
          </div>
          <div v-if="!state.searching && !state.searchResult" class="kb-empty">
            输入内容后点击「检索」，查看知识库命中的分块与相似度。
          </div>
        </div>
      </section>
    </div>

    <el-dialog
      v-model="state.uploadDialogVisible"
      title="上传文档"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form label-width="90px">
        <el-form-item label="选择文件">
          <input type="file" accept=".txt,.md,.markdown,text/plain" @change="handleFileChange" />
        </el-form-item>
        <el-form-item label="文档名称">
          <el-input v-model="state.uploadForm.name" placeholder="如：部署手册" maxlength="100" />
        </el-form-item>
        <el-form-item label="知识库">
          <el-input v-model="state.uploadForm.kbName" placeholder="默认知识库" maxlength="50" />
        </el-form-item>
        <el-form-item label="内容预览">
          <span class="kb-upload-meta">
            {{ state.uploadForm.content ? `已载入 ${state.uploadForm.content.length} 字符` : '未载入内容' }}
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="state.uploadDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="state.uploading" @click="submitUpload">上传并向量化</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script lang="ts" setup>
import { useIndex } from './index'
import { Plus, Search } from '@element-plus/icons-vue'

const {
  state,
  openUploadDialog,
  handleFileChange,
  submitUpload,
  handleDelete,
  submitSearch,
  scoreTagType,
  formatTime,
} = useIndex()
</script>

<style lang="less" scoped>
@import './index.less';
</style>
