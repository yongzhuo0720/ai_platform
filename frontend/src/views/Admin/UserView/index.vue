<template>
    <div class="content">
        <div class="user-search-bar">
            <el-form :inline="true" :model="state.formInline" class="search-form">
                <el-form-item label="用户名">
                   <el-input v-model="state.formInline.uname" placeholder="请输入用户名" clearable @keyup.enter="onQuery" style="width: 180px"/>
                </el-form-item>
                <el-form-item label="账号">
                   <el-input v-model="state.formInline.uaccess" placeholder="请输入账号" clearable @keyup.enter="onQuery" style="width: 180px"/>
                </el-form-item>
                <el-form-item label="部门">
                   <el-tree-select
                        v-model="state.formInline.gid"
                        :data="state.unitList"
                        :props="state.treeProps"
                         node-key="unitsno"
                        :filter-node-method="filterNodeMethod"
                        filterable
                        clearable
                        check-strictly
                        default-expand-all
                        style="width: 180px"
                        @change="onQuery"
                    />
                </el-form-item>

                <el-form-item>
                    <button type="button" class="btn btn-primary" @click="onQuery">
                        <Search />
                        搜索用户
                    </button>
                    <button type="button" class="btn btn-primary" @click="showAdd" v-if="state.isAdmin">
                        <CirclePlusFilled />
                        新增用户
                    </button>
                </el-form-item>
            </el-form>
        </div>
        <div class="user-table-card">
          <el-table :data="state.tableData" style="width: 100%" size="small" stripe height="calc(100vh - 200px)">
              <el-table-column type="index" label="序号" align="center" width="80"></el-table-column>      
              <el-table-column prop="uname" label="用户名" align="center" min-width="100" show-overflow-tooltip>
                <template #default="scope">
                    <span class="table-cell-strong">{{ scope.row.uname || '' }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="uaccess" label="账号" align="center" min-width="100" show-overflow-tooltip></el-table-column>
              <el-table-column prop="rid" label="角色" align="center" min-width="100" show-overflow-tooltip>
                <template #default="scope">
                        {{ state.roleJson[scope.row.rid] || ''  }}
                    </template>
              </el-table-column>
            <el-table-column prop="gname" label="部门" align="center" min-width="100" show-overflow-tooltip> </el-table-column>
            <el-table-column prop="createtime" label="创建时间" align="center" min-width="180" show-overflow-tooltip></el-table-column>
            <el-table-column label="操作" align="center"  width="280" fixed="right" v-if="state.isAdmin">
                <template #default="scope">
                    <button type="button" class="action-btn" @click="showEdit(scope.row)">
                        <Edit />
                        <span>编辑</span>
                    </button>
                    <button type="button" class="action-btn" @click="showDelete(scope.row)" v-if="scope.row.rid!=SUPER_ADMIN_RID">
                        <Delete />
                        <span>删除</span>
                    </button>
                    <button type="button" class="action-btn" @click="updatePwd(scope.row)">
                        <Refresh />
                        <span>重置密码</span>
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
                <el-form-item label="用户名" prop="uname">
                    <el-input v-model="state.params.form.uname" placeholder="请输入用户名" clearable></el-input>
                </el-form-item>
                <el-form-item label="账号" prop="uaccess" v-if="state.params.type=='add'">
                    <el-input v-model="state.params.form.uaccess" placeholder="请输入账号" clearable></el-input>
                </el-form-item>
                <el-form-item label="角色" prop="rid" v-if="state.params.form.rid!=SUPER_ADMIN_RID">
                    <el-select v-model="state.params.form.rid" placeholder="请选择角色" style="width: 100%;">
                       <el-option v-for="(item ,key) in state.roleJson" :label="item" :value="Number(key)" v-show="Number(key)!==SUPER_ADMIN_RID" />
                    </el-select>
                </el-form-item>
                <el-form-item label="部门" prop="gid">
                     <el-tree-select
                        v-model="state.params.form.gid"
                        :data="state.unitList"
                        :props="state.treeProps"
                         node-key="unitsno"
                        :filter-node-method="filterNodeMethod"
                        filterable
                        clearable
                        check-strictly
                        default-expand-all
                        style="width: 100%"
                    />
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
        state,
        onQuery,
        showAdd,
        showEdit,
        showDelete,
        onSave,
        updatePwd,
        handleSizeChange,
        handleCurrentChange,
        filterNodeMethod
        }= useIndex()
</script>
<style lang="less" scoped>
@import url('./index.less');
</style>