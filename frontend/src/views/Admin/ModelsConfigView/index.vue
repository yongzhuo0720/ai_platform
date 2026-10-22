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
            新增
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
        <el-table-column label="操作" align="center" width="160" fixed="right">
          <template #default="scope">
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
import { Search, CirclePlusFilled, Edit, Delete } from '@element-plus/icons-vue'

const {
  ruleFormRef,
  state,
  onQuery,
  showAdd,
  showEdit,
  showDelete,
  onSave,
  handleSizeChange,
  handleCurrentChange,
} = useIndex()
</script>

<style lang="less" scoped>
@import url('./index.less');
</style>
