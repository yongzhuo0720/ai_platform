<template>
    <div class="content">
        <div class="user-search-bar">
            <el-form :inline="true" :model="state.formInline" class="search-form">
                <el-form-item label="操作时间" >
                    <el-date-picker v-model="state.formInline.timeS" type="date" placeholder="选择开始时间"  value-format="YYYY-MM-DD 00:00:00"/>&nbsp-&nbsp 
                    <el-date-picker v-model="state.formInline.timeE" type="date" placeholder="选择结束时间"  value-format="YYYY-MM-DD 23:59:59"/>
                </el-form-item>
                <el-form-item>
                    <button type="button" class="btn btn-primary" @click="onQuery">
                        <Search />
                        搜索
                    </button>
                </el-form-item>
            </el-form>
        </div>
        <div class="user-table-card">
          <el-table :data="state.tableData" style="width: 100%" size="small" stripe height="calc(100vh - 200px)">
              <el-table-column type="index" label="序号" align="center" width="80"></el-table-column>
              <el-table-column prop="action" label="操作" align="center">
                <template #default="scope">
                    <span class="table-cell-strong">{{ scope.row.action }}</span>
                </template>
              </el-table-column> 
              <el-table-column prop="clientip" label="客户端IP" align="center"></el-table-column>     
              <el-table-column prop="res" label="操作结果" align="center"></el-table-column>
              <el-table-column prop="time" label="执行接口耗时" align="center">
                <template #default="scope">
                   {{scope.row.time}} ms
                </template>
              </el-table-column> 
              <el-table-column prop="user" label="操作者" align="center"></el-table-column>
              <el-table-column prop="uaccount" label="操作者账号" align="center"></el-table-column>
              <el-table-column prop="createtime" label="操作时间" align="center"></el-table-column>
        </el-table>
        </div>
        <div class="page-block">
            <el-pagination @size-change="handleSizeChange" @current-change="handleCurrentChange"
				:current-page="state.pagination.pageNum" :page-sizes="[20, 30, 50, 100]" :page-size="state.pagination.pageSize"
				layout="total, sizes, prev, pager, next, jumper" :total="state.pagination.total">
	    	</el-pagination>
        </div>
  
    </div>
</template>
<script lang="ts" setup>
import { useIndex } from './index'


const {
        state,
        onQuery,
        handleSizeChange,
        handleCurrentChange
        }= useIndex()
</script>
<style lang="less" scoped>
@import url('./index.less');
</style>