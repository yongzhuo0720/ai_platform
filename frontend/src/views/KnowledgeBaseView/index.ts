import { ref, computed, onMounted, nextTick } from 'vue'
import { isConfirmCancelled } from '@/utils/confirm'
import { useRouter } from 'vue-router'
import { datasetApi } from '@/api/dataset'
import { knowledgeBaseMngApi } from '@/api/knowledgeBaseMng'
import { knowledgeBaseMemberApi } from '@/api/knowledgeBaseMember'
import { unitApi } from '@/api/unit'
import { roleApi } from '@/api/role'
import Cookies from 'js-cookie'
import { isSuperAdmin, SUPER_ADMIN_RID } from '@/config/constants'

import { useTagBinding } from '@/composables/useTagBinding'
import { isGatewayMode, isGatewayModuleConfigured } from '@/stores/difyConfig'
import type { KnowledgeBase, CreateDatasetParams } from '@/types/knowledge'
import type { EmbeddingModelOption } from '@/api/dataset'
import { tagsApi } from '@/api/tags'
import { ElMessage, ElMessageBox, ElLoading } from 'element-plus'
import type { ElTree } from 'element-plus'

export function useIndex() {
  const router = useRouter()

  const knowledgeBases = ref<KnowledgeBase[]>([])
  const isLoading = ref(true)
  const error = ref<string | null>(null)
  // 网关模式下「知识库文件」模块未配置：整页给引导，不发任何数据集请求
  const knowledgeFileUnavailable = ref(false)

  const ensureKnowledgeFileReady = async (): Promise<boolean> => {
    if (!isGatewayMode()) return true
    const configured = await isGatewayModuleConfigured('file')
    knowledgeFileUnavailable.value = configured === false
    return configured !== false
  }
  const searchQuery = ref('')
  const selectedCategories = ref<string[]>([])
  const allTags = ref<any[]>([])
  // 当前用户自己创建的知识库 ID 集合
  const myKbIds = ref<Set<string>>(new Set())

  // ==================== 侧边栏菜单切换 ====================
  type MenuType = 'personal' | 'shared'
  const activeMenu = ref<MenuType>('personal')

  // 共享知识库列表
  const sharedKnowledgeBases = ref<KnowledgeBase[]>([])
  const selectedSharedKbId = ref<string | null>(null)
  const selectedSharedKb = computed(() => {
    if (!selectedSharedKbId.value) return sharedKnowledgeBases.value[0] || null
    return sharedKnowledgeBases.value.find(kb => kb.id === selectedSharedKbId.value) || null
  })

  // 已加入的知识库列表
  const joinedKnowledgeBases = ref<KnowledgeBase[]>([])
  const selectedJoinedKbId = ref<string | null>(null)
  const selectedKbId = ref<string | null>(null)
  // 是否展示知识库广场内容
  const showSquareView = ref(false)
  const selectedJoinedKb = computed(() => {
    if (!selectedJoinedKbId.value) return joinedKnowledgeBases.value[0] || null
    return joinedKnowledgeBases.value.find(kb => kb.id === selectedJoinedKbId.value) || null
  })

  // 知识库广场点击进入详情时不切换左侧菜单，单独记录
  const squareDetailKbId = ref<string | null>(null)
  const allKnowledgeBases = computed(() => {
    const map = new Map<string, KnowledgeBase>()
    ;[
      ...knowledgeBases.value,
      ...sharedKnowledgeBases.value,
      ...joinedKnowledgeBases.value,
    ].forEach(kb => {
      if (kb && kb.id) map.set(kb.id, kb)
    })
    return Array.from(map.values())
  })
  const squareDetailKb = computed(() => {
    if (!squareDetailKbId.value) return null
    return allKnowledgeBases.value.find(kb => kb.id === squareDetailKbId.value) || null
  })

  // 成员相关
  const memberList = ref<{ userId: string; userName: string; isOwner: string }[]>([])
  const memberLoading = ref(false)
  const joinedKbIds = ref<Set<string>>(new Set())
  const currentUserJoined = computed(() => {
    const kb = currentMemberDialogKbId.value
    return kb ? joinedKbIds.value.has(kb) : false
  })
  const currentMemberDialogKbId = ref('')

  // ==================== 知识库管理弹窗（增/改） ====================
  const uid = Cookies.get('uid') || ''
  const isAdmin = isSuperAdmin(Cookies.get('rid'))
  const treeRef = ref<InstanceType<typeof ElTree>>()
  const permissionMap = ref<Record<string, any>>({})
  const deptTree = ref<any[]>([])
  const roleList = ref<any[]>([])
  const defaultProps = {
    children: 'childList',
    label: 'unitname',
  }

  const mngDialogVisible = ref(false)
  const mngDialogTitle = ref('新建知识库')
  const isEditingKb = ref(false)
  const editingKb = ref<KnowledgeBase | null>(null)

  const mngFormData = ref<CreateDatasetParams & {
    permissionType: string
    selectedDepts: string[]
    selectedRoles: number[]
  }>({
    name: '',
    description: '',
    indexing_technique: 'high_quality',
    embedding_model: '',
    embedding_model_provider: '',
    permission: 'only_me',
    permissionType: 'public',
    selectedDepts: [],
    selectedRoles: [],
  })

  const embeddingModelOptions = ref<EmbeddingModelOption[]>([])

  const fetchEmbeddingModels = async () => {
    try {
      embeddingModelOptions.value = await datasetApi.getEmbeddingModels()
      if (embeddingModelOptions.value.length > 0 && !mngFormData.value.embedding_model) {
        const first = embeddingModelOptions.value[0]
        mngFormData.value.embedding_model = first.model
        mngFormData.value.embedding_model_provider = first.provider
      }
    } catch (err) {
      console.error('获取Embedding模型失败:', err)
      embeddingModelOptions.value = []
    }
  }

  const mngFormRules = {
    name: [{ required: true, message: '请输入知识库名称', trigger: 'blur' }],
    embedding_model: [{ required: true, message: '请选择Embedding模型', trigger: 'change' }],
  }

  const fetchBackendPermissionRecords = async () => {
    try {
      const { data: res } = await knowledgeBaseMngApi.query({ data: {} })
      if (res.resp_code === 0) {
        const map: Record<string, any> = {}
        ;(res.datas || []).forEach((record: any) => {
          map[record.kbId] = record
        })
        permissionMap.value = map
      }
    } catch (err) {
      console.error('获取权限记录失败:', err)
    }
  }

  const getDeptTree = async () => {
    try {
      const { data: res } = await unitApi.getDataList({ data: {} })
      if (res.resp_code === 0) {
        deptTree.value = res.datas || []
      }
    } catch (err) {
      console.error('获取部门树失败:', err)
    }
  }

  const getRoleList = async () => {
    try {
      const { data: res } = await roleApi.getDataList({
        bean: {},
        pageRequest: { pageSize: 1000, pageNum: 1, order: 'iid', desc: 'desc' },
      })
      if (res.resp_code === 0) {
        roleList.value = (res.datas.content || []).filter((item: any) => item.iid !== SUPER_ADMIN_RID)
      }
    } catch (err) {
      console.error('获取角色列表失败:', err)
    }
  }

  const handleDeptCheckChange = (data: any, checked: boolean) => {
    const deptId = data.unitsno
    if (checked) {
      if (!mngFormData.value.selectedDepts.includes(deptId)) {
        mngFormData.value.selectedDepts.push(deptId)
      }
    } else {
      const index = mngFormData.value.selectedDepts.indexOf(deptId)
      if (index > -1) {
        mngFormData.value.selectedDepts.splice(index, 1)
      }
    }
  }

  const openMngCreateDialog = () => {
    mngDialogTitle.value = '新建知识库'
    isEditingKb.value = false
    editingKb.value = null
    mngFormData.value = {
      name: '',
      description: '',
      indexing_technique: 'high_quality',
      embedding_model: embeddingModelOptions.value[0]?.model || '',
      embedding_model_provider: embeddingModelOptions.value[0]?.provider || '',
      permission: 'only_me',
      permissionType: 'public',
      selectedDepts: [],
      selectedRoles: [],
    }
  
    nextTick(() => {
      if (treeRef.value) {
        treeRef.value.setCheckedKeys([])
      }
    })
    mngDialogVisible.value = true
  }

  const openMngEditDialog = (kb: KnowledgeBase) => {
    mngDialogTitle.value = '编辑知识库'
    isEditingKb.value = true
    editingKb.value = kb

    const permRecord = permissionMap.value[kb.id]
    const permissionType = permRecord?.permissionType || 'public'
    const selectedDepts: string[] = (() => {
      try { return JSON.parse(permRecord?.unitsnos || '[]') } catch { return [] }
    })()
    const selectedRoles: number[] = (() => {
      try { return JSON.parse(permRecord?.roleiids || '[]') } catch { return [] }
    })()

    mngFormData.value = {
      name: kb.name,
      description: kb.description || '',
      indexing_technique: kb.indexing_technique || 'high_quality',
      embedding_model: kb.embedding_model || '',
      embedding_model_provider: kb.embedding_model_provider || '',
      permission: 'only_me',
      permissionType,
      selectedDepts,
      selectedRoles,
    }

    nextTick(() => {
      if (treeRef.value) {
        treeRef.value.setCheckedKeys(selectedDepts)
      }
    })
    mngDialogVisible.value = true
  }

  const handleMngSubmit = async () => {
    const loadingInstance = ElLoading.service({
      lock: true,
      text: '正在保存，请稍后',
      spinner: 'el-icon-loading',
      background: 'rgba(0, 0, 0, 0.7)',
    })
    try {
      if (isEditingKb.value && editingKb.value) {
        await datasetApi.updateDataset(editingKb.value.id, {
          name: mngFormData.value.name,
          description: mngFormData.value.description,
          indexing_technique: mngFormData.value.indexing_technique as any,
          embedding_model: mngFormData.value.embedding_model || undefined,
          embedding_model_provider: mngFormData.value.embedding_model_provider || undefined,
          permission: 'only_me' as any,
        })
        const permRecord = permissionMap.value[editingKb.value.id]
        if (permRecord) {
          await knowledgeBaseMngApi.update({
            data: {
              permissionType: mngFormData.value.permissionType,
              unitsnos: JSON.stringify(mngFormData.value.selectedDepts),
              roleiids: JSON.stringify(mngFormData.value.selectedRoles),
            },
            where: { kbId: editingKb.value.id },
          })
        } else {
          await knowledgeBaseMngApi.create({
            data: {
              kbId: editingKb.value.id,
              ownerUid: uid,
              permissionType: mngFormData.value.permissionType,
              unitsnos: JSON.stringify(mngFormData.value.selectedDepts),
              roleiids: JSON.stringify(mngFormData.value.selectedRoles),
            },
          })
        }
        ElMessage.success('知识库更新成功')
      } else {
        const created = await datasetApi.createDataset({
          name: mngFormData.value.name,
          description: mngFormData.value.description,
          indexing_technique: mngFormData.value.indexing_technique as any,
          embedding_model: mngFormData.value.embedding_model || undefined,
          embedding_model_provider: mngFormData.value.embedding_model_provider || undefined,
          permission: 'only_me',
        })
        await knowledgeBaseMngApi.create({
          data: {
            kbId: created.id,
            ownerUid: uid,
            permissionType: mngFormData.value.permissionType,
            unitsnos: JSON.stringify(mngFormData.value.selectedDepts),
            roleiids: JSON.stringify(mngFormData.value.selectedRoles),
          },
        })
        ElMessage.success('知识库创建成功')
      }
      mngDialogVisible.value = false
      await fetchKnowledgeBases()
      await fetchSharedKnowledgeBases()
      await fetchJoinedKnowledgeBases()
      await fetchBackendPermissionRecords()
    } catch (error) {
      console.error('操作失败:', error)
      ElMessage.error('操作失败，请重试')
    } finally {
      loadingInstance.close()
    }
  }

  const handleMngDelete = async (kb: KnowledgeBase) => {
    try {
      await ElMessageBox.confirm(
        `确定要删除知识库"${kb.name}"吗？此操作不可恢复。`,
        '删除确认',
        {
          confirmButtonText: '删除',
          cancelButtonText: '取消',
          type: 'warning',
        }
      )
      const loadingInstance = ElLoading.service({
        lock: true,
        text: '正在删除，请稍后',
        spinner: 'el-icon-loading',
        background: 'rgba(0, 0, 0, 0.7)',
      })
      try {
        await datasetApi.deleteDataset(kb.id)
        try {
          await knowledgeBaseMngApi.delete({ data: { kbId: kb.id } })
        } catch (e) {
          console.warn('删除后端权限记录失败（可能不存在）:', e)
        }
        ElMessage.success('知识库删除成功')
        // 如果删除的是当前选中的知识库，清除选中状态
        if (selectedKbId.value === kb.id) {
          selectedKbId.value = null
        }
        if (selectedSharedKbId.value === kb.id) {
          selectedSharedKbId.value = null
        }
        if (selectedJoinedKbId.value === kb.id) {
          selectedJoinedKbId.value = joinedKnowledgeBases.value.find(k => k.id !== kb.id)?.id || null
        }
        await fetchKnowledgeBases()
        await fetchSharedKnowledgeBases()
        await fetchJoinedKnowledgeBases()
        await fetchBackendPermissionRecords()
      } finally {
        loadingInstance.close()
      }
    } catch (error: any) {
      if (!isConfirmCancelled(error)) {
        console.error('删除失败:', error)
        ElMessage.error('删除失败，请重试')
      }
    }
  }

  onMounted(async () => {
    // 模块未配置时整页只展示引导，避免每个进入页面的用户都触发一轮必然失败的网关调用
    if (!(await ensureKnowledgeFileReady())) {
      isLoading.value = false
      return
    }
    fetchKnowledgeBases()
    fetchSharedKnowledgeBases()
    fetchJoinedKnowledgeBases()
    fetchJoinedKbIds()
    getAllTags()
    fetchBackendPermissionRecords()
    getDeptTree()
    getRoleList()
    fetchEmbeddingModels()
  })


  const filteredKnowledgeBases = computed(() => {
    let result = knowledgeBases.value

    // 排除已在共享知识库中显示的知识库
    const sharedKbIds = new Set(sharedKnowledgeBases.value.map(kb => kb.id))
    result = result.filter(kb => !sharedKbIds.has(kb.id))

    if (searchQuery.value) {
      const query = searchQuery.value.toLowerCase()
      result = result.filter(kb =>
        kb.name.toLowerCase().includes(query) ||
        (kb.description && kb.description.toLowerCase().includes(query))
      )
    }

    if (selectedCategories.value.length > 0) {
      result = result.filter(kb => {
        return kb.tagList?.some((tag: any) => {
          return selectedCategories.value.includes(String(tag.iid))
        })
      })
    }

    return result
  })
  // 获取所有标签
  const getAllTags = async () => {
    try {
      let reqdata={
        data:{
          type:'knowledge_base',
        }
      }
   
      const {data:res} = await tagsApi.query(reqdata)
      if (res.resp_code == 0) {
        allTags.value = res.datas || []
      }
    } catch (err) {
      console.error('获取标签列表失败:', err)
    }
  }
  const goToSquare = () => {
    selectedKbId.value = null
    selectedJoinedKbId.value = null
    selectedSharedKbId.value = null
    squareDetailKbId.value = null
    activeMenu.value = 'personal'
    showSquareView.value = true
  }

  const hideSquareView = () => {
    showSquareView.value = false
  }

  const goToCapabilities = () => {
    router.push('/capabilities')
  }

  // 切换侧边栏菜单
  const switchMenu = (menu: MenuType) => {
    activeMenu.value = menu
  }

  // 选中共享知识库
  const handleSelectSharedKb = (kb: KnowledgeBase) => {
    selectedSharedKbId.value = kb.id
  }

  // 选中已加入的知识库
  const handleSelectJoinedKb = (kb: KnowledgeBase) => {
    selectedJoinedKbId.value = kb.id
    selectedKbId.value = kb.id
    switchMenu('personal')
    fetchMembers(kb.id)
    refreshTagBind(kb.id)
  }

  const fetchKnowledgeBases = async () => {
    isLoading.value = true
    error.value = null

    const getLocalCoverImage = () => new URL('@/assets/images/brand-cover.svg', import.meta.url).href
    const isPlaceholderImage = (url?: string) => !url || url.includes('placeholder.com')

    try {
      const response = await datasetApi.getDatasets(1, 100, true)
    //  let  response = {
    //   data: [
    //     {id:'1',name:'测试知识库1',description:'这是一个测试知识库',coverImage:getLocalCoverImage(),tags:'测',word_count:1000,updated_at:1694502400},
    //     {id:'2',name:'测试知识库2',description:'这是一个测试知识库',coverImage:getLocalCoverImage(),tags:'测',word_count:1000,updated_at:1694502400},
    //   ]
    //  }
      if (response.data) {
        if (response.data.length > 0) {
          let visibleKbIds: Set<string> | null = null
          try {
            const allKbIds = response.data.map((kb: KnowledgeBase) => kb.id)
            const { data: filterRes } = await knowledgeBaseMngApi.filterVisible({
              data: { context: 'my', kbIds: allKbIds },
            })
            if (filterRes.resp_code === 0) {
              visibleKbIds = new Set(filterRes.datas || [])
            }
          } catch (e) {
            console.warn('权限过滤失败:', e)
          }

          // 缓存当前用户自己创建的知识库 ID，供权限判断使用
          myKbIds.value = visibleKbIds || new Set()

          const filtered = response.data
            .filter((kb: KnowledgeBase) => !visibleKbIds || visibleKbIds.has(kb.id))
            .map(kb => ({
            ...kb,
            coverImage: isPlaceholderImage(kb.coverImage) ? getLocalCoverImage() : kb.coverImage,
            size: kb.word_count ? `${(kb.word_count / 1024).toFixed(1)}K字` : '未知大小',
            updateTime: kb.updated_at ? new Date(kb.updated_at * 1000).toISOString().split('T')[0] : new Date().toISOString().split('T')[0],
            isFeatured: false,
            isRecommended: false,
            documents: kb.documents || [],
          }))

          knowledgeBases.value = filtered
          refreshTagBind()
        } else {
          // 接口正常但返回空数据，保持空列表，不显示静态数据
          knowledgeBases.value = []
          myKbIds.value = new Set()
        }
      }
    } catch (err) {
      console.warn('获取知识库列表失败:', err)
      error.value = '无法从服务器获取知识库数据'
      knowledgeBases.value = []
      myKbIds.value = new Set()
    } finally {
      isLoading.value = false
    }
  }


  // 获取共享知识库列表，与知识库广场使用同一 Dify API 并展示全部可见知识库
  const fetchSharedKnowledgeBases = async () => {
    const getLocalCoverImage = () => new URL('@/assets/images/brand-cover-default.svg', import.meta.url).href
    const isPlaceholderImage = (url?: string) => !url || url.includes('placeholder.com')

    // 工具：把 Dify 数据集映射为共享知识库展示项
    const mapToSharedKb = (kb: any): KnowledgeBase => ({
      ...kb,
      coverImage: isPlaceholderImage(kb.coverImage) ? getLocalCoverImage() : kb.coverImage,
      size: kb.word_count ? `${(kb.word_count / 1024).toFixed(1)}K字` : '未知大小',
      updateTime: kb.updated_at
        ? new Date(kb.updated_at * 1000).toISOString().split('T')[0]
        : new Date().toISOString().split('T')[0],
      creator: kb.author_name || '平台用户',
      member_count: kb.member_count ?? kb.partial_member_list?.length ?? 0,
      document_count: kb.document_count ?? kb.documents?.length ?? 0,
      is_shared: true,
    })

    try {
      // 1. 与知识库广场共用同一接口拉取所有 Dify 数据集
      const response = await datasetApi.getDatasets(1, 100)
      if (!response.data || response.data.length === 0) {
        throw new Error('Dify API 未返回任何知识库数据')
      }

      // 2. 调用与知识库广场相同的可见性过滤接口（context='square'）
      let visibleKbIds: Set<string> | null = null
      try {
        const allKbIds = response.data.map((kb: KnowledgeBase) => kb.id)
        const { data: filterRes } = await knowledgeBaseMngApi.filterVisible({
          data: { context: 'shared', kbIds: allKbIds },
        })
        if (filterRes?.resp_code === 0 && Array.isArray(filterRes.datas)) {
          visibleKbIds = new Set(filterRes.datas as string[])
        }
      } catch (e) {
        console.warn('[共享知识库] 后端 filterVisible 调用失败，将展示全部 Dify 知识库:', e)
      }

      // 3. 展示所有可见知识库（不再按 permission 过滤为仅共享）
      const filtered = response.data
        .filter((kb: any) => !visibleKbIds || visibleKbIds.has(kb.id))
        .map(mapToSharedKb)

      if (filtered.length > 0) {
        sharedKnowledgeBases.value = filtered
        if (!selectedSharedKbId.value) {
          selectedSharedKbId.value = filtered[0].id
        }
        await refreshTagBind()
        return
      }

      // 4. 没有可见知识库，但接口正常：保持空数组，让用户看到空态
      console.info('[共享知识库] 当前没有可见的知识库')
      sharedKnowledgeBases.value = []
      selectedSharedKbId.value = null
    } catch (err) {
      console.warn('[共享知识库] 获取知识库列表失败:', err)
      sharedKnowledgeBases.value = []
      selectedSharedKbId.value = null
    }
  }

  // 处理标签变更
  /**
   * 标签绑定刷新。
   *
   * 这里刻意传「三个数组合并后的新数组」而不是分别传：
   * 合并数组里的元素仍是原对象的引用，修改 tagList 会同时反映到
   * knowledgeBases / sharedKnowledgeBases / joinedKnowledgeBases，
   * 行为与原来逐个数组更新完全一致，但不用改 composable 去支持多数据源。
   */
  const { refreshTagBind } = useTagBinding(
    () => [
      ...knowledgeBases.value,
      ...sharedKnowledgeBases.value,
      ...joinedKnowledgeBases.value,
    ],
    { type: 'knowledge_base', idKey: 'id' }
  )

  // 获取已加入的知识库列表
  const fetchJoinedKnowledgeBases = async () => {
    const getLocalCoverImage = () => new URL('@/assets/images/brand-cover-default.svg', import.meta.url).href
    const isPlaceholderImage = (url?: string) => !url || url.includes('placeholder.com')

    try {
      const response = await datasetApi.getDatasets(1, 100)
      if (!response.data || response.data.length === 0) return

      const allKbIds = response.data.map((kb: KnowledgeBase) => kb.id)
      const { data: filterRes } = await knowledgeBaseMngApi.filterVisible({
        data: { context: 'joined', kbIds: allKbIds },
      })
      let visibleKbIds: Set<string> | null = null
      if (filterRes?.resp_code === 0 && Array.isArray(filterRes.datas)) {
        visibleKbIds = new Set(filterRes.datas as string[])
      }

      const filtered = response.data
        .filter((kb: KnowledgeBase) => !visibleKbIds || visibleKbIds.has(kb.id))
        .map((kb: any): KnowledgeBase => ({
          ...kb,
          coverImage: isPlaceholderImage(kb.coverImage) ? getLocalCoverImage() : kb.coverImage,
          size: kb.word_count ? `${(kb.word_count / 1024).toFixed(1)}K字` : '未知大小',
          updateTime: kb.updated_at
            ? new Date(kb.updated_at * 1000).toISOString().split('T')[0]
            : new Date().toISOString().split('T')[0],
          creator: kb.author_name || '平台用户',
          member_count: kb.member_count ?? kb.partial_member_list?.length ?? 0,
          document_count: kb.document_count ?? kb.documents?.length ?? 0,
        }))

      joinedKnowledgeBases.value = filtered
      if (filtered.length > 0 && !selectedJoinedKbId.value) {
        selectedJoinedKbId.value = filtered[0].id
      }
      await refreshTagBind()
    } catch (err) {
      console.warn('[已加入知识库] 获取失败:', err)
    }
  }

  // 获取已加入的知识库 ID 集合
  const fetchJoinedKbIds = async () => {
    try {
      const { data: res } = await knowledgeBaseMemberApi.joinedIds({ data: {} })
      if (res.resp_code === 0 && Array.isArray(res.datas)) {
        joinedKbIds.value = new Set(res.datas as string[])
      }
    } catch (err) {
      console.warn('获取已加入ID失败:', err)
    }
  }

  // 获取成员列表
  const fetchMembers = async (kbId: string) => {
    memberLoading.value = true
    currentMemberDialogKbId.value = kbId
    try {
      const { data: res } = await knowledgeBaseMemberApi.members({ data: { kbId } })
      if (res.resp_code === 0) {
        memberList.value = res.datas || []
        const count = memberList.value.length
        // 同步更新各列表中的加入人数
        // 下面一行的 filteredSharedKnowledgeBases 并不存在（未定义变量），
        // 求值到该操作数时会抛 ReferenceError 并被外层 catch 吞掉，
        // 导致前四个列表都找不到时「成员数」永远同步失败。sharedKnowledgeBases 已覆盖该场景，删除即可。
        const target = knowledgeBases.value.find(kb => kb.id === kbId)
          || filteredKnowledgeBases.value.find(kb => kb.id === kbId)
          || joinedKnowledgeBases.value.find(kb => kb.id === kbId)
          || sharedKnowledgeBases.value.find(kb => kb.id === kbId)
        if (target) {
          target.member_count = count
        }
      }
    } catch (err) {
      console.warn('获取成员列表失败:', err)
      memberList.value = []
    } finally {
      memberLoading.value = false
    }
  }

  // 加入知识库
  const joinKb = async (kbId: string) => {
    try {
      const { data: res } = await knowledgeBaseMemberApi.join({ data: { kbId } })
      if (res.resp_code === 0) {
        ElMessage.success('加入成功')
        joinedKbIds.value.add(kbId)
        await fetchJoinedKnowledgeBases()
        await fetchMembers(kbId)
      } else {
        ElMessage.error(res.resp_msg || '加入失败')
      }
    } catch (err) {
      console.error('加入失败:', err)
      ElMessage.error('加入失败')
    }
  }

  // 退出知识库
  const leaveKb = async (kbId: string) => {
    try {
      await ElMessageBox.confirm('确定要退出该知识库吗？', '退出确认', {
        confirmButtonText: '退出',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
    try {
      const { data: res } = await knowledgeBaseMemberApi.leave({ data: { kbId } })
      if (res.resp_code === 0) {
        ElMessage.success('已退出')
        joinedKbIds.value.delete(kbId)
        joinedKnowledgeBases.value = joinedKnowledgeBases.value.filter(k => k.id !== kbId)
        if (selectedJoinedKbId.value === kbId) {
          selectedJoinedKbId.value = joinedKnowledgeBases.value[0]?.id || null
        }
        if (selectedKbId.value === kbId) {
          selectedKbId.value = joinedKnowledgeBases.value[0]?.id || filteredKnowledgeBases.value[0]?.id || null
        }
        memberList.value = []
      } else {
        ElMessage.error(res.resp_msg || '退出失败')
      }
    } catch (err) {
      console.error('退出失败:', err)
      ElMessage.error('退出失败')
    }
  }

  // 移除成员（仅所有者）
  const removeMember = async (kbId: string, userId: string) => {
    try {
      await ElMessageBox.confirm('确定要移除该成员吗？', '移除确认', {
        confirmButtonText: '移除',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
    try {
      const { data: res } = await knowledgeBaseMemberApi.remove({ data: { kbId, userId } })
      if (res.resp_code === 0) {
        ElMessage.success('已移除')
        await fetchMembers(kbId)
      } else {
        ElMessage.error(res.resp_msg || '移除失败')
      }
    } catch (err) {
      console.error('移除失败:', err)
      ElMessage.error('移除失败')
    }
  }


  return {
    isLoading,
    error,
    knowledgeFileUnavailable,
    searchQuery,
    selectedCategories,
    allTags,
    filteredKnowledgeBases,
    activeMenu,
    sharedKnowledgeBases,
    selectedSharedKb,
    goToSquare,
    goToCapabilities,
    switchMenu,
    handleSelectSharedKb,
    refreshTagBind,
    getAllTags,
    // 管理弹窗
    mngDialogVisible,
    mngDialogTitle,
    isEditingKb,
    mngFormData,
    mngFormRules,
    isAdmin,
    treeRef,
    deptTree,
    roleList,
    defaultProps,
    embeddingModelOptions,
    fetchEmbeddingModels,
    openMngCreateDialog,
    openMngEditDialog,
    handleMngSubmit,
    handleMngDelete,
    handleDeptCheckChange,
    // 已加入的知识库
    joinedKnowledgeBases,
    selectedJoinedKb,
    selectedKbId,
    showSquareView,
    hideSquareView,
    squareDetailKbId,
    squareDetailKb,
    handleSelectJoinedKb,
    fetchJoinedKnowledgeBases,
    // 成员
    memberList,
    memberLoading,
    currentMemberDialogKbId,
    currentUserJoined,
    joinedKbIds,
    fetchMembers,
    fetchJoinedKbIds,
    joinKb,
    leaveKb,
    removeMember,
    // 当前用户自己创建的知识库 ID
    myKbIds,
  }
}
