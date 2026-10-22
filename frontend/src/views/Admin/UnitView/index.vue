<template>
    <div class="content">
        <div class="user-search-bar">
            <el-form :inline="true" :model="state.formInline" class="search-form">
                <el-form-item label="名称">
                   <el-input v-model="state.formInline.unitname" placeholder="请输入名称" clearable @keyup.enter="onQuery" style="width: 180px"/>
                </el-form-item>
                <el-form-item>
                    <button type="button" class="btn btn-primary" @click="onQuery">
                        <Search />
                        搜索
                    </button>
                    <button type="button" class="btn btn-primary" @click="showAdd" v-if="state.isAdmin">
                        <CirclePlusFilled />
                        新增
                    </button>
                </el-form-item>
            </el-form>
        </div>
        <div class="user-table-card">
          <el-table :data="state.tableData" style="width: 100%" row-key="iid" default-expand-all :tree-props="{ children: 'childList' }" size="small" stripe height="calc(100vh - 200px)">
              <el-table-column type="index" label="序号" align="center" width="80"></el-table-column>     
              <el-table-column prop="unitname" label="部门名称" align="left" min-width="120" show-overflow-tooltip>
                <template #default="scope">
                    <span class="table-cell-strong">{{ scope.row.unitname }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="unittel" label="部门电话" align="center" min-width="120" show-overflow-tooltip></el-table-column>
              <el-table-column prop="remark" label="备注" align="center" min-width="120" show-overflow-tooltip></el-table-column>
              <el-table-column prop="createtime" label="创建时间" align="center" width="230"></el-table-column>
             
            <el-table-column label="操作" align="center"  width="200" fixed="right" v-if="state.isAdmin">
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

        <el-dialog :title="state.params.title"  v-model="state.params.dialogVisible"  width="700px"  center :close-on-click-modal="false" >
            <el-form ref="ruleFormRef" :model="state.params.form" label-width="100px"  :rules="state.rules" size="large">
                <el-form-item label="上级部门" prop="parentsno">
                    <el-tree-select v-model="state.params.form.parentsno" :data="state.treeData" check-strictly default-expand-all
                    :render-after-expand="false" node-key="unitsno" :props="state.treeProps" style="width:100%;"/>
                </el-form-item>
                <el-form-item label="部门名称" prop="unitname">
                    <el-input v-model="state.params.form.unitname" placeholder="请输入部门名称" clearable></el-input>
                </el-form-item>
                <el-form-item label="部门电话" prop="unittel">
                    <el-input v-model="state.params.form.unittel" placeholder="请输入部门电话" clearable></el-input>
                </el-form-item>
                <el-form-item label="备注" prop="remark">
                    <el-input type="textarea" resize="none" v-model="state.params.form.remark" placeholder="请输入备注" clearable></el-input>
                </el-form-item>
            </el-form>
            <template #footer align="right">
                 <el-button @click="state.params.dialogVisible = false" >取 消</el-button>
                 <el-button type="primary" @click="onSave">确 定</el-button>
            </template>
        </el-dialog>

    </div>
</template>
<script lang="ts" setup>
import { useIndex } from './index'
const {
        ruleFormRef,
        state,
        onQuery,
        showAdd,
        showEdit,
        showDelete,
        onSave
        }= useIndex()
</script>
<style lang="less" scoped>
@import url('./index.less');
</style>