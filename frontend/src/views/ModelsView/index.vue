

<template>
  

  <section class="page-section">
    <RepoPreviewPanel
      v-if="previewVisible"
      type="models"
      :iid="previewItem.iid"
      :name="previewItem.name"
      style="min-height: calc(100vh - 24px);"
      @back="closePreview"
    />
    <template v-else>

    <div class="page-hero">
      <div class="page-eyebrow">基础模型</div>
      <div class="page-heading">
        <div>
          <h1>模型库</h1>
          <p>{{ pageDescriptions.models }}</p>
        </div>
        <div class="hero-stats">
          <button type="button" class="btn btn-primary" @click="showAdd" v-if="state.rid === SUPER_ADMIN_RID">
            <Plus />
            新建模型
          </button>
          <div class="hero-stat">
            <span>在线模型</span>
            <strong>{{ state.dataList.length }}</strong>
          </div>
          <div class="hero-stat">
            <span>周下载量</span>
            <strong>{{ state.weeklyDownloads }}</strong>
          </div>
        </div>
      </div>
    </div>

    <div class="page-toolbar models-toolbar">
      <label class="search-input models-search">
        <Search />
        <input v-model="searchKeyword" type="text" placeholder="搜索模型..." />
      </label>

       <TagFilterDropdown
          v-model="selectedCategory"
          :allTags="state.allTags"
          type="models"
          teleport
          class="models-tag-filter"
          @visible-change="(visible: boolean) => { if (visible) getAllTags() }"
        />
      <el-select v-model="sortBy" placeholder="排序方式" clearable class="models-sort">
         <el-option label="按下载量-降序" value="downloads" />
         <el-option label="按下载量-升序" value="downloads_asc" />
         <el-option label="按创建时间-降序" value="createtime" />
         <el-option label="按创建时间-升序" value="createtime_asc" />
         <el-option label="文件大小-降序" value="size" />
         <el-option label="文件大小-升序" value="size_asc" />
      </el-select>
    </div>

    <div class="model-list">
      <article v-for="item in filteredDataList" :key="item.name" class="list-card">
        <div class="list-card-content">
          <h3 class="list-card-title">{{ item.name }}</h3>
          <p class="list-card-description">{{ item.description }}</p>
          <div class="list-card-meta">
            <span v-show="item.provider">{{ item.provider }}</span>
            <!-- <span v-show="item.category">{{ item.category }}</span> -->
 <span>创建时间：{{ item.createtime }}</span>
          </div>
            <!-- 标签区域 -->
              <div class="ima-card-tags">
                <!-- 非管理员用户只能显示标签，不可操作 -->
                <span v-if="state.rid!=SUPER_ADMIN_RID" v-for="tag in item.tagList || []" :key="tag.iid" class="ima-card-tag">
                   <PriceTag class="tag-icon" />{{ tag.name }}</span>
                <!-- 显示已有标签，点击可编辑 -->
                <TagDropdown
                  v-if="state.rid==SUPER_ADMIN_RID"
                  v-for="(tag, index) in item.tagList || []"
                  :key="item.iid + '-tag-' + index"
                  :parent="item"
                  :allTags="state.allTags"
                  :show-add-button="false"
                  type="models"
                  @refreshTagBind="(iid) => refreshTagBind(iid)"
                  @getAllTags="getAllTags"
                >
                  <template #trigger>
                    <span class="ima-card-tag">
                      <PriceTag class="tag-icon" />
                      {{ tag.name }}
                    </span>
                  </template>
                </TagDropdown>
                <!-- 添加标签按钮（仅当没有标签时显示） -->
                <TagDropdown
                  v-if="(!item.tagList || item.tagList.length === 0)&&state.rid==SUPER_ADMIN_RID"
                  :key="item.iid + '-add-btn'"
                  :parent="item"
                  :allTags="state.allTags"
                  type="models"
                  :show-add-button="true"
                  @refreshTagBind="(iid) => refreshTagBind(iid)"
                  @getAllTags="getAllTags"
                />
              </div>
        </div>
        <div class="list-card-actions">
          <button type="button" class="action-btn action-btn-edit" @click="showEdit(item)" v-if="state.rid === SUPER_ADMIN_RID">
            <Edit />
            <span>修改</span>
          </button>
          <button type="button" class="action-btn action-btn-delete" @click="handleDelete(item)" v-if="state.rid === SUPER_ADMIN_RID">
            <Delete />
            <span>删除</span>
          </button>
          <button type="button" class="action-btn action-btn-download" @click="downloadModel(item)">
            <Download />
            <span>下载</span>
          </button>
        </div>
       
      </article>
    </div>

    <!-- 新建/编辑弹窗 -->
    <el-dialog :title="state.params.title" v-model="state.params.dialogVisible" width="700px" center :close-on-click-modal="false">
      <el-form ref="ruleFormRef" :model="state.params.form" label-width="100px" :rules="state.rules" size="large">
        <el-form-item label="名称" prop="name">
          <el-input v-model="state.params.form.name" placeholder="请输入模型名称" clearable></el-input>
        </el-form-item>
        <el-form-item label="提供商" prop="provider">
          <el-input v-model="state.params.form.provider" placeholder="请输入提供商" clearable></el-input>
        </el-form-item>
        <!-- <el-form-item label="分类" prop="category">
          <el-input v-model="state.params.form.category" placeholder="请输入分类" clearable></el-input>
        </el-form-item> -->
        <el-form-item label="大小" prop="size">
          <el-input v-model="state.params.form.size" placeholder="请输入大小" clearable></el-input>
        </el-form-item>
        <el-form-item label="下载地址" prop="downloadUrl">
          <el-input v-model="state.params.form.downloadUrl" placeholder="请输入下载地址" clearable></el-input>
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="state.params.form.description" type="textarea" :rows="3" placeholder="请输入模型描述" clearable></el-input>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="state.params.dialogVisible = false">取 消</el-button>
        <el-button type="primary" @click="onSave">确 定</el-button>
      </template>
    </el-dialog>
    </template>
  </section>
</template>
<script setup lang="ts">
import { useIndex } from './index'
import { SUPER_ADMIN_RID } from '@/config/constants'
import TagDropdown from '@/components/TagDropdown/index.vue'
import TagFilterDropdown from '@/components/TagFilterDropdown/index.vue'
import RepoPreviewPanel from '@/components/RepoPreviewPanel/index.vue'
import { Plus, Edit, Delete, Download, Search, PriceTag } from '@element-plus/icons-vue'
const {
  state,
  ruleFormRef,
  pageDescriptions,
  selectedCategory,
  sortBy,
  searchKeyword,
  filteredDataList,
  showAdd,
  showEdit,
  handleDelete,
  onSave,
  refreshTagBind,
  getAllTags,
  downloadModel,
  previewVisible,
  previewItem,
  closePreview
} = useIndex()


</script>
<style scoped>
@import './index.less';
</style>
