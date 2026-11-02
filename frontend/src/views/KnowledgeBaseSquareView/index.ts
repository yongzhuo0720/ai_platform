import { ref, computed, onMounted } from 'vue'
import { datasetApi } from '@/api/dataset'
import { knowledgeBaseMngApi } from '@/api/knowledgeBaseMng'
import { tagsApi } from '@/api/tags'
import { useTagBinding } from '@/composables/useTagBinding'
import type { KnowledgeBase } from '@/types/knowledge'

export function useIndex() {
  const knowledgeBases = ref<KnowledgeBase[]>([])
  const loading = ref(false)
  const listError = ref('')
  const searchQuery = ref('')
  const selectedCategories = ref<string>('')
  const allTags = ref<any[]>([])

  const filteredKnowledgeBases = computed(() => {
    let result = knowledgeBases.value

    if (searchQuery.value) {
      const query = searchQuery.value.toLowerCase()
      result = result.filter(kb =>
        kb.name.toLowerCase().includes(query) ||
        (kb.description && kb.description.toLowerCase().includes(query))
      )
    }

    if (selectedCategories.value) {
      const selectedId = String(selectedCategories.value)
      result = result.filter(kb => {
        return kb.tagList?.some((tag: any) => {
          return String(tag.iid) === selectedId
        })
      })
    }

    return result
  })

  const recommendedKnowledgeBases = computed(() => {
    return filteredKnowledgeBases.value.filter(kb => kb.isRecommended)
  })

  // 空态要分清「库里就没有」和「被筛选筛没了」：前者的下一步是新建，后者是放宽条件
  const emptyText = computed(() =>
    knowledgeBases.value.length === 0 ? '暂无可展示的知识库' : '没有符合当前搜索与筛选条件的知识库'
  )

  onMounted(() => {
    fetchKnowledgeBases()
    getAllTags()
  })

  const getAllTags = async () => {
    try {
      const reqdata = {
        data: {
          type: 'knowledge_base',
        }
      }
      const { data: res } = await tagsApi.query(reqdata)
      if (res.resp_code == 0) {
        allTags.value = res.datas || []
      }
    } catch (err) {
      console.error('获取标签列表失败:', err)
    }
  }

  const { refreshTagBind } = useTagBinding(() => knowledgeBases.value, {
    type: 'knowledge_base',
    idKey: 'id',
  })

  const fetchKnowledgeBases = async () => {
    loading.value = true
    listError.value = ''
    try {
      const response = await datasetApi.getDatasets(1, 100)
      let visibleKbIds: Set<string> | null = null
      if (response.data && response.data.length > 0) {
        try {
          const allKbIds = response.data.map((kb: KnowledgeBase) => kb.id)
          const { data: filterRes } = await knowledgeBaseMngApi.filterVisible({
            data: { context: 'square', kbIds: allKbIds },
          })
          if (filterRes.resp_code === 0) {
            visibleKbIds = new Set(filterRes.datas || [])
          }
        } catch (e) {
          console.warn('权限过滤失败:', e)
        }
      }
      knowledgeBases.value = response.data
        .filter((kb: KnowledgeBase) => !visibleKbIds || visibleKbIds.has(kb.id))
        .map((kb: any) => ({
          ...kb,
          member_count: kb.member_count ?? kb.partial_member_list?.length ?? 0,
          document_count: kb.document_count ?? kb.documents?.length ?? 0,
          creator: kb.creator || kb.created_by || '平台用户',
        }))
      await refreshTagBind()
    } catch (err) {
      console.error('获取知识库列表失败:', err)
      knowledgeBases.value = []
      // 拦截器已弹过 toast，这里只把原因留在页面上，让人能看到并点重试
      listError.value = '获取知识库列表失败，请重试'
    } finally {
      loading.value = false
    }
  }

  return {
    loading,
    listError,
    emptyText,
    searchQuery,
    selectedCategories,
    filteredKnowledgeBases,
    recommendedKnowledgeBases,
    allTags,
    refreshTagBind,
    getAllTags,
    fetchKnowledgeBases,
  }
}
