<template>
    <div class="content">
        <div class="user-search-bar">
            <el-form :inline="true" :model="state.formInline" class="search-form">
                <el-form-item label="名称">
                   <el-input v-model="state.formInline.name" placeholder="请输入名称" clearable @keyup.enter="onQuery" style="width: 180px"/>
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
          <el-table :data="state.tableData" style="width: 100%" size="small" stripe height="calc(100vh - 200px)">
              <el-table-column type="index" label="序号" align="center" width="80"></el-table-column>      
              <el-table-column prop="name" label="名称" align="center">
                <template #default="scope">
                    <span class="table-cell-strong">{{ scope.row.name || '' }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="createtime" label="创建时间" align="center"></el-table-column>
             
            <el-table-column label="操作" align="center"  width="200" fixed="right" v-if="state.isAdmin">
                <template #default="scope">
                    <button type="button" class="action-btn" @click="showEdit(scope.row)">
                        <Edit />
                        <span>编辑</span>
                    </button>
                    <button type="button" class="action-btn" @click="showDelete(scope.row)" v-if="scope.row.iid!=SUPER_ADMIN_RID">
                        <Delete />
                        <span>删除</span>
                    </button>
                </template>
            </el-table-column>
        </el-table>
        </div>
        <div class="page-block">
            <el-pagination @size-change="handleSizeChange" @current-change="handleCurrentChange"
				:current-page="state.pagination.pageNum" :page-sizes="[15, 30, 50, 100]" :page-size="state.pagination.pageSize"
				layout="total, sizes, prev, pager, next, jumper" :total="state.pagination.total">
	    	</el-pagination>
        </div>
        
        <el-dialog :title="state.params.title"  v-model="state.params.dialogVisible"  width="700px"  center :close-on-click-modal="false" >
            <el-form ref="ruleFormRef" :model="state.params.form" label-width="100px"  :rules="state.rules" size="large">
                <el-form-item label="角色名称" prop="name">
                   <el-input v-model="state.params.form.name" placeholder="请输入角色名称" clearable style="width: 100%;" />
                 </el-form-item>
                 <el-form-item label="菜单权限" v-if="state.params.form.iid!=SUPER_ADMIN_RID">
                    <div style="height:28.571rem;overflow-y:auto;width:100%">
                       <el-tree
                        ref="treemenuRef"
                        :data="state.menuListTree"
                        :props="state.defaultProps"
                        show-checkbox
                        node-key="key"
                        default-expand-all
                        :expand-on-click-node="false">
                        </el-tree>  
                    </div>
                    
                </el-form-item>
                <el-form-item label="描述" prop="describe" >
                    <el-input v-model="state.params.form.describe" placeholder="请输入描述" clearable></el-input>
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
import { SUPER_ADMIN_RID } from '@/config/constants'

const {
        ruleFormRef,
        treemenuRef,
        state,
        onQuery,
        showAdd,
        showEdit,
        showDelete,
        onSave,
        handleSizeChange,
        handleCurrentChange
        }= useIndex()
</script>
<style lang="less" scoped>
@import url('./index.less');
</style>