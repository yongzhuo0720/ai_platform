<template>
  <section class="page-section">
    <div class="page-hero">
      <div class="page-eyebrow">能力中心</div>
      <div class="page-heading">
        <div>
          <h1>能力广场</h1>
          <p>{{ pageDescriptions.capabilities }}</p>
        </div>
        <div class="hero-stats">
          <button type="button" class="btn btn-primary" @click="showAdd" v-if="state.rid === SUPER_ADMIN_RID">
            <Plus />
            新建能力
          </button>
          <div class="hero-stat">
            <span>可用能力</span>
            <strong>{{ state.dataList.length }}</strong>
          </div>
          <!-- <div class="hero-stat">
            <span>今日调用</span>
            <strong>-</strong>
          </div> -->
        </div>
      </div>
    </div>

    <div class="page-toolbar capabilities-toolbar">
      <label class="search-input capabilities-search">
        <Search />
        <input v-model="searchKeyword" type="text" placeholder="搜索能力..." />
      </label>
      <TagFilterDropdown
        v-model="selectedCategory"
        :allTags="state.allTags"
        type="capabilities"
        teleport
        class="capabilities-tag-filter"
        @visible-change="(visible: boolean) => { if (visible) getAllTags() }"
      />
      <el-select
        v-model="sortBy"
        placeholder="排序方式"
        clearable
        placement="bottom-start"
        :popper-options="{ modifiers: [{ name: 'flip', enabled: false }] }"
        class="capabilities-select"
      >
         <el-option label="按创建时间-降序" value="createtime" />
         <el-option label="按创建时间-升序" value="createtime_asc" />
         <el-option label="按更新时间-降序" value="updatetime" />
         <el-option label="按更新时间-升序" value="updatetime_asc" />
      </el-select>
    </div>

    <el-empty v-if="filteredDataList.length === 0" :description="searchKeyword || selectedCategory.length ? '没有匹配的能力，请调整搜索或标签筛选' : '暂无能力，添加能力后即可在这里查看'" />
    <div v-else class="cap-grid">
      <article
        v-for="item in filteredDataList"
        :key="item.iid || item.title"
        class="cap-card"
        @click="openCapability(item)"
      >
        <div class="cap-card-cover">
          <img
            :src="getCoverImage(item)"
            :alt="item.title"
            @error="handleCoverError"
          />
        </div>
        <div class="cap-card-content">
          <h3 class="cap-card-name">{{ item.title }}</h3>
          <p class="cap-card-desc">{{ item.description }}</p>
          <div class="cap-card-meta">
            <span>创建时间：{{ item.createtime }}</span>
          </div>
          <!-- 标签区域 -->
          <div class="ima-card-tags">
            <!-- 非管理员用户只能显示标签，不可操作 -->
            <el-popover v-if="state.rid!=SUPER_ADMIN_RID" v-for="tag in item.tagList || []" :key="tag.iid" placement="bottom" trigger="hover" :width="200" :show-after="200">
              <template #reference>
                <span class="ima-card-tag" @click.stop>
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
              v-for="(tag, tagIndex) in item.tagList || []"
              :key="(item.iid || item.title) + '-tag-' + tagIndex"
              :parent="item"
              :allTags="state.allTags"
              :show-add-button="false"
              type="capabilities"
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
              type="capabilities"
              :show-add-button="true"
              @refreshTagBind="(iid) => refreshTagBind(iid)"
              @getAllTags="getAllTags"
            />
          </div>
          <div class="cap-card-footer">
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
            <button type="button" class="action-btn" @click.stop="openCapability(item)" v-if="state.rid === SUPER_ADMIN_RID">
              <View />
              <span>查看</span>
            </button>
          </div>
        </div>
      </article>
    </div>

    <!-- 新建/编辑弹窗 -->
    <el-dialog :title="state.params.title" v-model="state.params.dialogVisible" width="700px" center :close-on-click-modal="false">
      <el-form ref="ruleFormRef" :model="state.params.form" label-width="100px" :rules="state.rules" size="large">
        <el-form-item label="能力图片">
          <div class="cap-icon-panel">
            <el-upload
              class="cap-icon-upload"
              action="#"
              :auto-upload="false"
              :limit="1"
              accept="image/*"
              list-type="picture-card"
              :file-list="iconFiles"
              :on-change="handleIconChange"
              :on-remove="handleIconRemove"
              :on-exceed="handleIconExceed"
            >
              <div class="cap-upload-trigger">
                <Plus />
                <span>点击上传图片</span>
              </div>
            </el-upload>
            <p class="cap-upload-helper">支持上传 1 张图片；不上传则显示默认图片。</p>
          </div>
        </el-form-item>
        <el-form-item label="能力名称" prop="title">
          <el-input v-model="state.params.form.title" placeholder="请输入能力名称" clearable></el-input>
        </el-form-item>
        <el-form-item label="能力描述" prop="description">
          <el-input v-model="state.params.form.description" type="textarea" :rows="3" placeholder="请输入能力描述" clearable></el-input>
        </el-form-item>
        <!-- <el-form-item label="能力标签" prop="label">
          <el-input v-model="state.params.form.label" placeholder="请输入能力标签" clearable></el-input>
        </el-form-item> -->
        <el-form-item label="能力地址" prop="url">
          <el-input v-model="state.params.form.url" placeholder="请输入能力地址" clearable></el-input>
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
            <el-tag type="warning" size="small">选中的部门将看不到该能力</el-tag>
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
            <el-tag type="warning" size="small">选中的角色将看不到该能力</el-tag>
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
import { SUPER_ADMIN_RID } from '@/config/constants'
import TagDropdown from '@/components/TagDropdown/index.vue'
import TagFilterDropdown from '@/components/TagFilterDropdown/index.vue'

const {
  state,
  ruleFormRef,
  treeRef,
  searchKeyword,
  selectedCategory,
  sortBy,
  pageDescriptions,
  filteredDataList,
  getCoverImage,
  handleCoverError,
  iconFiles,
  handleIconChange,
  handleIconRemove,
  handleIconExceed,
  showAdd,
  showEdit,
  handleDelete,
  onSave,
  openCapability,
  refreshTagBind,
  getAllTags,
  showMng,
  saveMng,
  handleDeptCheckChange,
} = useIndex()
</script>

<style scoped>
@import './index.less';
</style>
