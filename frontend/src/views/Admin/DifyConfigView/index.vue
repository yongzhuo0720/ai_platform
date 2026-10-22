<template>
    <div class="content">
        <div class="user-table-card">
            <el-table :data="state.tableData" style="width: 100%" size="small" stripe
                height="calc(100vh - 120px)">
                <el-table-column type="index" label="序号" align="center" width="80"></el-table-column>
                <el-table-column prop="moduleName" label="模块名称" align="center" min-width="160">
                    <template #default="scope">
                        <span class="table-cell-strong">{{ scope.row.moduleName }}</span>
                    </template>
                </el-table-column>
                <el-table-column prop="apiUrl" label="URL" align="center" min-width="260">
                    <template #default="scope">
                        <span>{{ scope.row.apiUrl || '-' }}</span>
                    </template>
                </el-table-column>
                <el-table-column prop="apiKey" label="API Key" align="center" min-width="280">
                    <template #default="scope">
                        <template v-if="scope.row.apiKey">
                            <span v-if="scope.row._showKey" style="word-break: break-all">{{ scope.row.apiKey }}</span>
                            <span v-else>●●●●●●●●●●</span>
                            <el-button link type="primary" size="small" style="margin-left: 8px"
                                @click="scope.row._showKey = !scope.row._showKey">
                                {{ scope.row._showKey ? '隐藏' : '显示' }}
                            </el-button>
                        </template>
                        <span v-else style="color: var(--text-secondary)">未配置</span>
                    </template>
                </el-table-column>
                <el-table-column prop="lasttime" label="更新时间" align="center" width="180">
                    <template #default="scope">
                        <span>{{ formatTime(scope.row.lasttime || scope.row.lastTime) }}</span>
                    </template>
                </el-table-column>
                <el-table-column label="操作" align="center" width="120" fixed="right">
                    <template #default="scope">
                        <button type="button" class="action-btn" @click="showEdit(scope.row)">
                            <Edit />
                            <span>编辑</span>
                        </button>
                    </template>
                </el-table-column>
            </el-table>
        </div>
        <div class="page-block">
            <el-pagination @size-change="handleSizeChange" @current-change="handleCurrentChange"
                :current-page="state.pagination.pageNum" :page-sizes="[20, 30, 50, 100]"
                :page-size="state.pagination.pageSize" layout="total, sizes, prev, pager, next, jumper"
                :total="state.pagination.total">
            </el-pagination>
        </div>

        <el-dialog v-model="state.params.dialogVisible" :title="state.params.title" width="600px"
            :close-on-click-modal="false" destroy-on-close>
            <el-form :model="state.params.form" label-width="100px" size="large">
                <el-form-item label="模块名称">
                    <el-input :model-value="state.params.form.moduleName" disabled />
                </el-form-item>
                <el-form-item label="URL">
                    <el-input v-model="state.params.form.apiUrl" placeholder="请输入 Dify API URL" clearable />
                </el-form-item>
                <el-form-item label="API Key">
                    <el-input v-model="state.params.form.apiKey" placeholder="请输入 API Key" clearable
                        show-password />
                </el-form-item>
            </el-form>
            <template #footer>
                <el-button @click="state.params.dialogVisible = false">取消</el-button>
                <el-button type="primary" @click="onSave">保存</el-button>
            </template>
        </el-dialog>
    </div>
</template>
<script lang="ts" setup>
import { useIndex } from './index'
import { Edit } from '@element-plus/icons-vue'

const { state, showEdit, onSave, handleSizeChange, handleCurrentChange, formatTime } = useIndex()
</script>
<style lang="less" scoped>
@import url('./index.less');
</style>
