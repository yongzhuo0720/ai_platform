<template>
  <div class="content">
    <div class="user-search-bar">
      <el-form :inline="true" class="search-form">
        <el-form-item class="view-mode-item">
          <el-radio-group v-model="viewMode" size="default" @change="handleViewModeChange" class="view-mode-radio">
            <el-radio-button value="all">全部</el-radio-button>
            <el-radio-button value="localOnly">仅显示本系统</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <label class="search-input">
            <Search />
            <input type="text" :placeholder="viewMode === 'all' ? '搜索知识库...' : '搜索本地知识库...'" v-model="searchQuery" @input="handleSearch" />
          </label>
        </el-form-item>
      </el-form>
      <button v-if="viewMode === 'all'" type="button" class="btn btn-primary" @click="openCreateDialog">
        <Plus />
        新建知识库
      </button>
      <button v-if="viewMode === 'localOnly'" type="button" class="btn btn-primary" @click="openAssociateDialog">
        <Plus />
        新建关联
      </button>
    </div>

    <div class="user-table-card">
      <!-- 全部模式 -->
      <el-table
        v-if="viewMode === 'all'"
        v-loading="loading"
        :data="paginatedDatasets"
        style="width: 100%"
        size="small"
        height="calc(100vh - 280px)"
        :key="'all-table'"
      >
        <el-table-column label="序号" width="70" align="center">
          <template #default="{ $index }">
            {{ (currentPage - 1) * pageSize + $index + 1 }}
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="150" />
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column label="所有者" width="120" align="center">
          <template #default="{ row }">
            {{ getOwnerDisplay(row.id) || '—' }}
          </template>
        </el-table-column>
        <el-table-column prop="document_count" label="文档数" width="100" align="center" />
        <el-table-column label="权限" width="110" align="center">
          <template #default="{ row }">
            <span :class="['permission-tag', getPermissionLabel(row.id)]">
              {{ getPermissionLabel(row.id) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="120" align="center" />
        <el-table-column label="操作" width="280" align="center" fixed="right">
          <template #default="{ row }">
            <button type="button" class="action-btn" @click="openEditDialog(row)">
              <Edit />
              <span>编辑</span>
            </button>
            <button type="button" class="action-btn" @click="handleDelete(row)">
              <Delete />
              <span>删除</span>
            </button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 仅显示本系统模式 -->
      <el-table
        v-else
        v-loading="localLoading"
        :data="paginatedLocalRecords"
        style="width: 100%"
        size="small"
        height="calc(100vh - 280px)"
        :key="'local-table'"
      >
        <el-table-column label="序号" width="70" align="center">
          <template #default="{ $index }">
            {{ (currentPage - 1) * pageSize + $index + 1 }}
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="kbId" label="dify知识库ID" min-width="220" show-overflow-tooltip />
        <el-table-column label="所有者" width="120" align="center">
          <template #default="{ row }">
            {{ getLocalOwnerDisplay(row) || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="权限" width="110" align="center">
          <template #default="{ row }">
            <span :class="['permission-tag', getPermissionLabelForRecord(row)]">
              {{ getPermissionLabelForRecord(row) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <span :class="['status-tag', Number(row.enable) === 0 ? 'status-disabled' : 'status-enabled']">
              {{ Number(row.enable) === 0 ? '已弃用' : '启用中' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" align="center" fixed="right">
          <template #default="{ row }">
            <button type="button" class="action-btn" @click="openLocalEditDialog(row)">
              <Edit />
              <span>编辑</span>
            </button>
            <button type="button" class="action-btn" @click="handleDeleteLocal(row)">
              <Delete />
              <span>删除</span>
            </button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="page-block">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="viewMode === 'all' ? filteredDatasets.length : filteredLocalRecords.length"
        layout="total, sizes, prev, pager, next"
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </div>

    <!-- 全部模式：新建/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="650px">
      <el-form ref="formRef" :model="formData" :rules="formRules" label-width="100px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="formData.name" placeholder="请输入知识库名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="formData.description"
            type="textarea"
            :rows="3"
            placeholder="请输入知识库描述"
          />
        </el-form-item>
        <el-form-item label="所有者">
          <el-select v-model="formData.ownerUid" filterable placeholder="请选择所有者" style="width: 100%">
            <el-option v-for="u in userOptions" :key="u.value" :label="u.label" :value="u.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="索引技术">
          <el-select v-model="formData.indexing_technique" style="width: 100%">
            <el-option label="高质量（推荐）" value="high_quality" />
            <el-option label="经济模式" value="economy" />
          </el-select>
        </el-form-item>

        <el-divider content-position="left">权限配置</el-divider>

        <el-form-item label="权限类型">
          <el-radio-group v-model="formData.permissionType">
            <el-radio value="public">所有人可见</el-radio>
            <el-radio value="only_me">仅个人可见</el-radio>
            <el-radio value="partial_invisible">部分不可见</el-radio>
          </el-radio-group>
        </el-form-item>

        <template v-if="formData.permissionType === 'partial_invisible'">
          <el-form-item label="部门权限">
            <el-tag type="warning" size="small" style="margin-bottom: 8px">选中的部门将看不到该知识库</el-tag>
            <div class="mng-tree-container">
              <el-tree
                ref="treeRef"
                :data="deptTree"
                :props="defaultProps"
                node-key="unitsno"
                :default-checked-keys="formData.selectedDepts"
                @check-change="handleDeptCheckChange"
                show-checkbox
                default-expand-all
              />
            </div>
          </el-form-item>
          <el-form-item label="角色权限">
            <el-tag type="warning" size="small" style="margin-bottom: 8px">选中的角色将看不到该知识库</el-tag>
            <div class="mng-role-container">
              <el-checkbox-group v-model="formData.selectedRoles">
                <el-checkbox
                  v-for="role in roleList"
                  :key="role.iid"
                  :label="role.iid"
                >
                  {{ role.name }}
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 本系统模式：编辑弹窗 -->
    <el-dialog v-model="localDialogVisible" :title="localDialogTitle" width="550px">
      <el-form :model="localFormData" label-width="100px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="localFormData.name" placeholder="请输入名称（本系统别名）" />
        </el-form-item>
        <el-form-item label="所有者">
          <el-select v-model="localFormData.ownerUid" filterable placeholder="请选择所有者" style="width: 100%">
            <el-option v-for="u in userOptions" :key="u.value" :label="u.label" :value="u.value" />
          </el-select>
        </el-form-item>

        <el-form-item label="启用状态">
          <el-radio-group v-model="localFormData.enable">
            <el-radio value="1">启用</el-radio>
            <el-radio value="0">弃用</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-divider content-position="left">权限配置</el-divider>

        <el-form-item label="权限类型">
          <el-radio-group v-model="localFormData.permissionType">
            <el-radio value="public">所有人可见</el-radio>
            <el-radio value="only_me">仅个人可见</el-radio>
            <el-radio value="partial_invisible">部分不可见</el-radio>
          </el-radio-group>
        </el-form-item>

        <template v-if="localFormData.permissionType === 'partial_invisible'">
          <el-form-item label="部门权限">
            <el-tag type="warning" size="small" style="margin-bottom: 8px">选中的部门将看不到该知识库</el-tag>
            <div class="mng-tree-container">
              <el-tree
                ref="localTreeRef"
                :data="deptTree"
                :props="defaultProps"
                node-key="unitsno"
                :default-checked-keys="localFormData.selectedDepts"
                @check-change="handleLocalDeptCheckChange"
                show-checkbox
                default-expand-all
              />
            </div>
          </el-form-item>
          <el-form-item label="角色权限">
            <el-tag type="warning" size="small" style="margin-bottom: 8px">选中的角色将看不到该知识库</el-tag>
            <div class="mng-role-container">
              <el-checkbox-group v-model="localFormData.selectedRoles">
                <el-checkbox
                  v-for="role in roleList"
                  :key="role.iid"
                  :label="role.iid"
                >
                  {{ role.name }}
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="localDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleLocalSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 新建关联弹窗 -->
    <el-dialog v-model="associateDialogVisible" title="新建关联" width="550px">
      <el-form :model="associateForm" :rules="associateRules" label-width="120px">
        <el-form-item label="Dify知识库ID" prop="kbId">
          <el-input v-model="associateForm.kbId" placeholder="输入Dify平台已有的知识库ID" />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="associateForm.name" placeholder="请输入本系统别名" />
        </el-form-item>
        <el-form-item label="所有者">
          <el-select v-model="associateForm.ownerUid" filterable placeholder="请选择所有者" style="width: 100%">
            <el-option v-for="u in userOptions" :key="u.value" :label="u.label" :value="u.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="权限类型">
          <el-radio-group v-model="associateForm.permissionType">
            <el-radio value="public">所有人可见</el-radio>
            <el-radio value="only_me">仅个人可见</el-radio>
            <el-radio value="partial_invisible">部分不可见</el-radio>
          </el-radio-group>
        </el-form-item>

        <template v-if="associateForm.permissionType === 'partial_invisible'">
          <el-form-item label="部门权限">
            <el-tag type="warning" size="small" style="margin-bottom: 8px">选中的部门将看不到该知识库</el-tag>
            <div class="mng-tree-container">
              <el-tree
                ref="associateTreeRef"
                :data="deptTree"
                :props="defaultProps"
                node-key="unitsno"
                :default-checked-keys="associateForm.selectedDepts"
                @check-change="handleAssociateDeptCheckChange"
                show-checkbox
                default-expand-all
              />
            </div>
          </el-form-item>
          <el-form-item label="角色权限">
            <el-tag type="warning" size="small" style="margin-bottom: 8px">选中的角色将看不到该知识库</el-tag>
            <div class="mng-role-container">
              <el-checkbox-group v-model="associateForm.selectedRoles">
                <el-checkbox
                  v-for="role in roleList"
                  :key="role.iid"
                  :label="role.iid"
                >
                  {{ role.name }}
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="associateDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleAssociateSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { useIndex } from './index'

const {
  Plus, Search, Delete, Edit,
  loading, searchQuery, currentPage, pageSize,   viewMode, localLoading,
  userOptions,
  dialogVisible, dialogTitle,   localDialogVisible, localDialogTitle, localFormData,
  associateDialogVisible, associateForm,
  formData, formRules, associateRules,
  treeRef, localTreeRef, deptTree, roleList, defaultProps, formRef,
  filteredDatasets, paginatedDatasets,
  filteredLocalRecords, paginatedLocalRecords,
  handleSearch, handlePageChange, handleSizeChange,
  handleViewModeChange,
  openCreateDialog, openEditDialog, handleSubmit, handleDelete,
  openLocalEditDialog, handleLocalSubmit,
  handleDeleteLocal,
  openAssociateDialog, handleAssociateSubmit,
  handleDeptCheckChange, handleLocalDeptCheckChange, handleAssociateDeptCheckChange,
  getPermissionLabel, getPermissionLabelForRecord,
  getOwnerDisplay, getLocalOwnerDisplay,
  associateTreeRef,
} = useIndex()
</script>
<style scoped>
@import './index.less';
</style>
