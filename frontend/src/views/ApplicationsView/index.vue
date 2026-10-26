<template>
  <section class="page-section">
    <div class="page-hero">
      <div class="page-eyebrow">应用中心</div>
      <div class="page-heading">
        <div>
          <h1>场景应用</h1>
          <p>{{ pageDescriptions.applications }}</p>
        </div>
        <div class="hero-stats">
          <button type="button" class="btn btn-primary" @click="showAdd" v-if="state.rid === SUPER_ADMIN_RID">
            <Plus />
            新建应用
          </button>
          <div class="hero-stat">
            <span>应用数量</span>
            <strong>{{ state.dataList.length }}</strong>
          </div>
        </div>
      </div>
    </div>

       <div class="page-toolbar applications-toolbar">
      <label class="search-input applications-search">
        <Search />
        <input v-model="searchKeyword" type="text" placeholder="搜索应用..." />
      </label>

       <TagFilterDropdown
          v-model="selectedCategory"
          :allTags="state.allTags"
          type="applications"
          teleport
          class="applications-tag-filter"
          @visible-change="(visible: boolean) => { if (visible) getAllTags() }"
        />
      <el-select v-model="sortBy" placeholder="排序方式" clearable class="applications-sort">
         <el-option label="按创建时间-降序" value="createtime" />
         <el-option label="按创建时间-升序" value="createtime_asc" />
         <el-option label="按更新时间-降序" value="updatetime" />
         <el-option label="按更新时间-升序" value="updatetime_asc" />
      </el-select>
    </div>

    <div class="grid grid-4">
      <article v-for="item in filteredDataList" :key="item.iid || item.title" class="card capability-card">
        <h3 class="card-title">{{ item.title }}</h3>
        <p class="card-description">{{ item.detail }}</p>
        <div class="card-meta">
          <span>创建时间：{{ item.createtime }}</span>
        </div>
        <!-- 标签区域 -->
        <div class="ima-card-tags">
          <!-- 非管理员用户只能显示标签，不可操作 -->
          <el-popover v-if="state.rid!=SUPER_ADMIN_RID" v-for="tag in item.tagList || []" :key="tag.iid" placement="bottom" trigger="hover" :width="200" :show-after="200">
            <template #reference>
              <span class="ima-card-tag">
                <PriceTag class="tag-icon" />{{ tag.name }}
              </span>
            </template>
            <div class="tags-popover">
              <div v-for="t in item.tagList" :key="t.iid" class="tags-popover-item">
                <el-icon><PriceTag /></el-icon>
                {{ t.name }}
              </div>
            </div>
          </el-popover>
          <!-- 显示已有标签，点击可编辑 -->
          <TagDropdown
            v-if="state.rid==SUPER_ADMIN_RID"
            v-for="(tag, index) in item.tagList || []"
            :key="(item.iid || item.title) + '-tag-' + index"
            :parent="item"
            :allTags="state.allTags"
            :show-add-button="false"
            type="applications"
            @refreshTagBind="(iid) => refreshTagBind(iid)"
            @getAllTags="getAllTags"
          >
            <template #trigger>
              <el-popover placement="bottom" trigger="hover" :width="200" :show-after="200">
                <template #reference>
                  <span class="ima-card-tag">
                    <PriceTag class="tag-icon" />
                    {{ tag.name }}
                  </span>
                </template>
                <div class="tags-popover">
                  <div v-for="t in item.tagList" :key="t.iid" class="tags-popover-item">
                    <el-icon><PriceTag /></el-icon>
                    {{ t.name }}
                  </div>
                </div>
              </el-popover>
            </template>
          </TagDropdown>
          <!-- 添加标签按钮（仅当没有标签时显示） -->
          <TagDropdown
            v-if="(!item.tagList || item.tagList.length === 0)&&state.rid==SUPER_ADMIN_RID"
            :key="(item.iid || item.title) + '-add-btn'"
            :parent="item"
            :allTags="state.allTags"
            type="applications"
            :show-add-button="true"
            @refreshTagBind="(iid) => refreshTagBind(iid)"
            @getAllTags="getAllTags"
          />
        </div>
        <div class="card-footer capability-footer">
          <button type="button" class="action-btn" @click.stop="showEdit(item)" v-if="state.rid === SUPER_ADMIN_RID">
            <Edit />
            <span>修改</span>
          </button>
          <button type="button" class="action-btn" @click.stop="handleDelete(item)" v-if="state.rid === SUPER_ADMIN_RID">
            <Delete />
            <span>删除</span>
          </button>
          <button type="button" class="action-btn" @click.stop="showMng(item)" v-if="state.rid === SUPER_ADMIN_RID">
            <Tools />
            <span>管理</span>
          </button>
          <button type="button" class="action-btn" @click.stop="openDifyApp(item.difyUrl)">
            <View />
            <span>查看</span>
          </button>
        </div>
      </article>
    </div>

    <!-- 新建/编辑弹窗 -->
    <el-dialog :title="state.params.title" v-model="state.params.dialogVisible" width="700px" center :close-on-click-modal="false">
      <el-form ref="ruleFormRef" :model="state.params.form" label-width="100px" :rules="state.rules" size="large">
        <el-form-item label="应用名称" prop="title">
          <el-input v-model="state.params.form.title" placeholder="请输入应用名称" clearable></el-input>
        </el-form-item>
        <el-form-item label="应用描述" prop="detail">
          <el-input v-model="state.params.form.detail" type="textarea" :rows="3" placeholder="请输入应用描述" clearable></el-input>
        </el-form-item>
        <el-form-item label="Dify地址" prop="difyUrl">
          <el-input v-model="state.params.form.difyUrl" placeholder="请输入Dify地址" clearable></el-input>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="state.params.dialogVisible = false">取 消</el-button>
        <el-button type="primary" @click="onSave">确 定</el-button>
      </template>
    </el-dialog>

    <!-- 管理权限弹窗 -->
    <el-dialog title="管理权限" v-model="state.mngDialogVisible" width="700px" center :close-on-click-modal="false">
      <div class="mng-dialog-content">
        <div class="mng-section">
          <div class="mng-section-title">
            <span class="title-text">部门权限</span>
            <el-tag type="warning" size="small">选中的部门将看不到该应用</el-tag>
          </div>
          <div class="mng-tree-container">
            <el-tree
              ref="treeRef"
              :data="state.deptTree"
              :props="state.defaultProps"
              node-key="unitsno"
              :default-checked-keys="state.mngForm.selectedDepts"
              @check-change="handleDeptCheckChange"
              show-checkbox
              default-expand-all
            />
          </div>
        </div>
        <div class="mng-section">
          <div class="mng-section-title">
            <span class="title-text">角色权限</span>
            <el-tag type="warning" size="small">选中的角色将看不到该应用</el-tag>
          </div>
          <div class="mng-role-container">
            <el-checkbox-group v-model="state.mngForm.selectedRoles">
              <el-checkbox 
                v-for="role in state.roleList" 
                :key="role.iid" 
                :label="role.name"
                :value="role.iid"
              >
                {{ role.name }}
              </el-checkbox>
            </el-checkbox-group>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="state.mngDialogVisible = false">取 消</el-button>
        <el-button type="primary" @click="saveMng">确 定</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { useIndex } from './index'
import { safeOpenUrl } from '@/utils/url'
import { SUPER_ADMIN_RID } from '@/config/constants'
import TagDropdown from '@/components/TagDropdown/index.vue'
import TagFilterDropdown from '@/components/TagFilterDropdown/index.vue'
import { Plus, Search, Edit, Delete, View, PriceTag, Tools } from '@element-plus/icons-vue'

const {
  state,
  ruleFormRef,
  treeRef,
  searchKeyword,
  pageDescriptions,
  selectedCategory,
  sortBy,
  showAdd,
  showEdit,
  handleDelete,
  onSave,
  filteredDataList,
  refreshTagBind,
  getAllTags,
  showMng,
  saveMng,
  handleDeptCheckChange,
} = useIndex()

/**
 * 打开 Dify Web App
 * @param url - Dify 应用的 URL
 */
const openDifyApp = (url?: string) => {
  if (url) {
    safeOpenUrl(url)
  }
}
</script>

<style scoped>
@import './index.less';
</style>
