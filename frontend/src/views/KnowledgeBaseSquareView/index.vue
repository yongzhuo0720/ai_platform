<template>
  <section class="kb-square-page">
    <!-- Header -->
    <div class="kb-square-header">
      <h1 class="kb-square-title">知识库广场</h1>
      <div class="kb-square-toolbar">
        <div class="kb-square-search">
          <Search class="kb-square-search-icon" />
          <input
            v-model="searchQuery"
            type="text"
            placeholder="搜索知识库..."
          />
        </div>
        <el-select v-model="selectedCategories" placeholder="全部标签" clearable style="width: 140px">
          <el-option label="全部标签" value="" />
          <el-option v-for="tag in allTags" :key="tag.iid" :value="tag.iid" :label="tag.name" />
        </el-select>
      </div>
    </div>

    <!-- 推荐知识库 -->
    <div v-if="recommendedKnowledgeBases.length > 0" class="kb-square-section">
      <div class="kb-square-section-header">
        <div class="kb-square-section-title">推荐知识库</div>
      </div>
      <div class="kb-square-grid">
        <article
          v-for="kb in recommendedKnowledgeBases"
          :key="kb.id"
          class="kb-square-card"
          @click.stop="handleCardClick(kb)"
        >
          <div class="kb-square-card-cover">
            <img :src="kb.coverImage || defaultCover" :alt="kb.name" />
          </div>
          <div class="kb-square-card-content">
            <h3 class="kb-square-card-name">{{ kb.name }}</h3>
            <p class="kb-square-card-desc">{{ kb.description || '暂无描述' }}</p>
            <div class="kb-square-card-meta">
              {{ kb.member_count || 0 }}人已加入 | {{ kb.document_count || 0 }}个内容 | {{ kb.creator || 'admin' }}
            </div>
            <!-- 标签区域 -->
            <div class="ima-card-tags">
              <template v-if="canManageKbTags(kb)">
                <TagDropdown
                  v-for="(tag, tagIndex) in kb.tagList || []"
                  :key="`${kb.id}-tag-${tagIndex}`"
                  :parent="kb"
                  :allTags="allTags"
                  :show-add-button="false"
                  type="knowledge_base"
                  @refreshTagBind="(id: string) => refreshTagBind(id)"
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
                        <div v-for="t in kb.tagList" :key="t.iid" class="tags-popover-item">
                          <el-icon><PriceTag /></el-icon>
                          {{ t.name }}
                        </div>
                      </div>
                    </el-popover>
                  </template>
                </TagDropdown>
                <TagDropdown
                  v-if="!kb.tagList || kb.tagList.length === 0"
                  :parent="kb"
                  :allTags="allTags"
                  :show-add-button="true"
                  type="knowledge_base"
                  @refreshTagBind="(id: string) => refreshTagBind(id)"
                  @getAllTags="getAllTags"
                />
              </template>
              <template v-else>
                <el-popover
                  v-for="(tag, tagIndex) in kb.tagList || []"
                  :key="`${kb.id}-tag-${tagIndex}`"
                  placement="bottom"
                  trigger="hover"
                  :width="200"
                  :show-after="200"
                >
                  <template #reference>
                    <span class="ima-card-tag">
                      <PriceTag class="tag-icon" />
                      {{ tag.name }}
                    </span>
                  </template>
                  <div class="tags-popover">
                    <div v-for="t in kb.tagList" :key="t.iid" class="tags-popover-item">
                      <el-icon><PriceTag /></el-icon>
                      {{ t.name }}
                    </div>
                  </div>
                </el-popover>
              </template>
            </div>
          </div>
        </article>
      </div>
    </div>

    <!-- 全部 -->
    <div class="kb-square-section">
      <div class="kb-square-tabs">
        <span class="kb-square-tab active">全部</span>
      </div>
      <div class="kb-square-grid">
        <article
          v-for="kb in filteredKnowledgeBases"
          :key="kb.id"
          class="kb-square-card"
          @click.stop="handleCardClick(kb)"
        >
          <div class="kb-square-card-cover">
            <img :src="kb.coverImage || defaultCover" :alt="kb.name" />
          </div>
          <div class="kb-square-card-content">
            <h3 class="kb-square-card-name">{{ kb.name }}</h3>
            <p class="kb-square-card-desc">{{ kb.description || '暂无描述' }}</p>
            <div class="kb-square-card-meta">
              {{ kb.member_count || 0 }}人已加入 | {{ kb.document_count || 0 }}个内容
            </div>
            <!-- 标签区域 -->
            <div class="ima-card-tags">
              <template v-if="canManageKbTags(kb)">
                <TagDropdown
                  v-for="(tag, tagIndex) in kb.tagList || []"
                  :key="`${kb.id}-tag-${tagIndex}`"
                  :parent="kb"
                  :allTags="allTags"
                  :show-add-button="false"
                  type="knowledge_base"
                  @refreshTagBind="(id: string) => refreshTagBind(id)"
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
                        <div v-for="t in kb.tagList" :key="t.iid" class="tags-popover-item">
                          <el-icon><PriceTag /></el-icon>
                          {{ t.name }}
                        </div>
                      </div>
                    </el-popover>
                  </template>
                </TagDropdown>
                <TagDropdown
                  v-if="!kb.tagList || kb.tagList.length === 0"
                  :parent="kb"
                  :allTags="allTags"
                  :show-add-button="true"
                  type="knowledge_base"
                  @refreshTagBind="(id: string) => refreshTagBind(id)"
                  @getAllTags="getAllTags"
                />
              </template>
              <template v-else>
                <el-popover
                  v-for="(tag, tagIndex) in kb.tagList || []"
                  :key="`${kb.id}-tag-${tagIndex}`"
                  placement="bottom"
                  trigger="hover"
                  :width="200"
                  :show-after="200"
                >
                  <template #reference>
                    <span class="ima-card-tag">
                      <PriceTag class="tag-icon" />
                      {{ tag.name }}
                    </span>
                  </template>
                  <div class="tags-popover">
                    <div v-for="t in kb.tagList" :key="t.iid" class="tags-popover-item">
                      <el-icon><PriceTag /></el-icon>
                      {{ t.name }}
                    </div>
                  </div>
                </el-popover>
              </template>
            </div>
          </div>
        </article>
      </div>
      <div v-if="filteredKnowledgeBases.length === 0" class="kb-square-no-more">暂无可展示的知识库</div>
      <div v-else class="kb-square-no-more">没有更多数据了</div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { useIndex } from './index'
import { Search, PriceTag } from '@element-plus/icons-vue'
import TagDropdown from '@/components/TagDropdown/index.vue'

const defaultCover = new URL('@/assets/images/brand-cover-default.svg', import.meta.url).href

const props = defineProps<{
  isAdmin?: boolean
  myKbIds?: Set<string>
}>()

const {
  searchQuery,
  selectedCategories,
  filteredKnowledgeBases,
  recommendedKnowledgeBases,
  allTags,
  getAllTags,
  refreshTagBind,
} = useIndex()

const emit = defineEmits(['select-kb'])

/** 判断当前用户是否可以管理指定知识库的标签 */
function canManageKbTags(kb: any): boolean {
  return !!(props.isAdmin || (kb?.id && props.myKbIds?.has(kb.id)))
}

function handleCardClick(kb: any) {
  emit('select-kb', kb)
}
</script>

<style scoped lang="less">
@import './index.less';
</style>
