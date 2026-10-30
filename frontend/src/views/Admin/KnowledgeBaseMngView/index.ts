import { ref, computed, onMounted, nextTick } from 'vue'
import { isConfirmCancelled } from '@/utils/confirm'
import { ElMessage, ElMessageBox, ElLoading } from 'element-plus'
import { Plus, Search, Delete, Edit } from '@element-plus/icons-vue'
import { datasetApi } from '@/api/dataset'
import { knowledgeBaseMngApi } from '@/api/knowledgeBaseMng'
import { unitApi } from '@/api/unit'
import { roleApi } from '@/api/role'
import { userApi } from '@/api/user'
import Cookies from 'js-cookie'
import { isSuperAdmin, SUPER_ADMIN_RID } from '@/config/constants'
import type { KnowledgeBase, CreateDatasetParams } from '@/types/knowledge'
import type { ElTree, FormInstance } from 'element-plus'

export function useIndex() {
  const datasets = ref<KnowledgeBase[]>([])
  const loading = ref(false)
  const searchQuery = ref('')
  const currentPage = ref(1)
  const pageSize = ref(10)
  const total = ref(0)

  const viewMode = ref<'all' | 'localOnly'>('all')
  const localRecords = ref<any[]>([])
  const localLoading = ref(false)
  const userMap = ref<Record<string, any>>({})
  const userOptions = ref<Array<{ label: string; value: string }>>([])

  // 全部模式弹窗
  const dialogVisible = ref(false)
  const dialogTitle = ref('新建知识库')
  const isEditing = ref(false)
  const editingDataset = ref<KnowledgeBase | null>(null)

  // 本系统模式弹窗
  const localDialogVisible = ref(false)
  const localDialogTitle = ref('')
  const localEditingRecord = ref<any>(null)
  const localFormData = ref({
    name: '',
    ownerUid: '',
    permissionType: 'public',
    selectedDepts: [] as string[],
    selectedRoles: [] as number[],
    enable: '1',
  })

  // 新建关联弹窗
  const associateDialogVisible = ref(false)
  const associateForm = ref({
    kbId: '',
    name: '',
    ownerUid: '',
    permissionType: 'public',
    selectedDepts: [] as string[],
    selectedRoles: [] as number[],
  })

  const uid = Cookies.get('uid') || ''
  const isAdmin = isSuperAdmin(Cookies.get('rid'))
  const formRef = ref<FormInstance>()
  const treeRef = ref<InstanceType<typeof ElTree>>()
  const localTreeRef = ref<InstanceType<typeof ElTree>>()
  const associateTreeRef = ref<InstanceType<typeof ElTree>>()
  const permissionMap = ref<Record<string, any>>({})
  const deptTree = ref<any[]>([])
  const roleList = ref<any[]>([])
  const defaultProps = {
    children: 'childList',
    label: 'unitname',
  }

  const formData = ref<CreateDatasetParams & {
    permissionType: string
    selectedDepts: string[]
    selectedRoles: number[]
    ownerUid: string
  }>({
    name: '',
    description: '',
    indexing_technique: 'high_quality',
    permission: 'only_me',
    permissionType: 'public',
    selectedDepts: [],
    selectedRoles: [],
    ownerUid: uid,
  })

  const formRules = {
    name: [{ required: true, message: '请输入知识库名称', trigger: 'blur' }],
  }

  const associateRules = {
    kbId: [{ required: true, message: '请输入Dify知识库ID', trigger: 'blur' }],
    name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  }

  // ========== 用户 ==========

  const fetchUsers = async () => {
    try {
      const { data: res } = await userApi.getUserList({
        bean: {},
        pageRequest: { pageSize: 1000, pageNum: 1, order: 'createtime', desc: 'desc' },
      })
      if (res.resp_code === 0) {
        const users = res.datas.content || []
        userMap.value = {}
        users.forEach((u: any) => {
          userMap.value[u.uid] = u
        })
        userOptions.value = users.map((u: any) => ({
          label: `${u.uaccess}${u.uname ? ' (' + u.uname + ')' : ''}`,
          value: u.uid,
        }))
      }
    } catch (err) {
      console.error('获取用户列表失败:', err)
    }
  }

  // ========== 全部模式 ==========

  const filteredDatasets = computed(() => {
    if (!searchQuery.value) return datasets.value
    const query = searchQuery.value.toLowerCase()
    return datasets.value.filter(
      (kb) =>
        kb.name.toLowerCase().includes(query) ||
        kb.description.toLowerCase().includes(query)
    )
  })

  const paginatedDatasets = computed(() => {
    const start = (currentPage.value - 1) * pageSize.value
    const end = start + pageSize.value
    return filteredDatasets.value.slice(start, end)
  })

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

  const fetchDatasets = async () => {
    loading.value = true
    try {
      const [difyRes] = await Promise.all([
        datasetApi.getDatasets(1, 100),
        fetchBackendPermissionRecords(),
      ])

      const allKbIds = difyRes.data.map((kb: KnowledgeBase) => kb.id)
      if (allKbIds.length > 0) {
        const { data: filterRes } = await knowledgeBaseMngApi.filterVisible({
          data: { context: 'manage', kbIds: allKbIds },
        })
        if (filterRes.resp_code === 0) {
          const visibleIds = new Set(filterRes.datas || [])
          datasets.value = difyRes.data.filter((kb: KnowledgeBase) => visibleIds.has(kb.id))
        } else {
          datasets.value = difyRes.data
        }
      } else {
        datasets.value = []
      }
      total.value = datasets.value.length
    } catch (error) {
      console.error('获取知识库列表失败:', error)
      ElMessage.error('获取知识库列表失败')
    } finally {
      loading.value = false
    }
  }

  // ========== 本系统模式 ==========

  const filteredLocalRecords = computed(() => {
    if (!searchQuery.value) return localRecords.value
    const q = searchQuery.value.toLowerCase()
    return localRecords.value.filter(
      (r: any) =>
        (r.name || '').toLowerCase().includes(q) ||
        (r.kbId || '').toLowerCase().includes(q)
    )
  })

  const paginatedLocalRecords = computed(() => {
    const start = (currentPage.value - 1) * pageSize.value
    return filteredLocalRecords.value.slice(start, start + pageSize.value)
  })

  const fetchLocalRecords = async () => {
    localLoading.value = true
    try {
      const { data: res } = await knowledgeBaseMngApi.queryAll()
      if (res.resp_code === 0) {
        localRecords.value = (res.datas || []).map((record: any) => ({
          ...record,
          enable: record.enable === '0' || record.enable === false ? '0' : '1',
        }))
      }
    } catch (err) {
      console.error('获取本地知识库记录失败:', err)
      ElMessage.error('获取本地知识库记录失败')
    } finally {
      localLoading.value = false
    }
  }

  // ========== 切换 ==========

  const handleViewModeChange = async (mode: 'all' | 'localOnly') => {
    viewMode.value = mode
    currentPage.value = 1
    searchQuery.value = ''
    if (mode === 'all') {
      await fetchDatasets()
    } else {
      await fetchLocalRecords()
    }
  }

  // ========== 通用 ==========

  const handleSearch = () => {
    currentPage.value = 1
  }

  const handlePageChange = (page: number) => {
    currentPage.value = page
  }

  const handleSizeChange = (size: number) => {
    pageSize.value = size
    currentPage.value = 1
  }

  const getPermissionLabelForRecord = (row: any) => {
    if (viewMode.value === 'localOnly') {
      switch (row.permissionType) {
        case 'only_me': return '仅个人可见'
        case 'partial_invisible': return '部分不可见'
        default: return '所有人可见'
      }
    }
    return getPermissionLabel(row.id ?? row.kbId)
  }

  const getOwnerDisplay = (kbId: string) => {
    const record = permissionMap.value[kbId]
    if (!record?.ownerUid) return ''
    return userMap.value[record.ownerUid]?.uaccess || record.ownerUid
  }

  const getLocalOwnerDisplay = (record: any) => {
    if (!record?.ownerUid) return ''
    return userMap.value[record.ownerUid]?.uaccess || record.ownerUid
  }

  // ========== 全部模式操作 ==========

  const openCreateDialog = () => {
    dialogTitle.value = '新建知识库'
    isEditing.value = false
    editingDataset.value = null
    formData.value = {
      name: '',
      description: '',
      indexing_technique: 'high_quality',
      permission: 'only_me',
      permissionType: 'public',
      selectedDepts: [],
      selectedRoles: [],
      ownerUid: uid,
    }
    nextTick(() => {
      if (treeRef.value) {
        treeRef.value.setCheckedKeys([])
      }
    })
    dialogVisible.value = true
  }

  const openEditDialog = (kb: KnowledgeBase) => {
    dialogTitle.value = '编辑知识库'
    isEditing.value = true
    editingDataset.value = kb

    const permRecord = permissionMap.value[kb.id]
    const permissionType = permRecord?.permissionType || 'public'
    const selectedDepts: string[] = (() => {
      try { return JSON.parse(permRecord?.unitsnos || '[]') } catch { return [] }
    })()
    const selectedRoles: number[] = (() => {
      try { return JSON.parse(permRecord?.roleiids || '[]') } catch { return [] }
    })()

    formData.value = {
      name: kb.name,
      description: kb.description || '',
      indexing_technique: kb.indexing_technique || 'high_quality',
      permission: 'only_me',
      permissionType,
      selectedDepts,
      selectedRoles,
      ownerUid: permRecord?.ownerUid || uid,
    }

    nextTick(() => {
      if (treeRef.value) {
        treeRef.value.setCheckedKeys(selectedDepts)
      }
    })
    dialogVisible.value = true
  }

  const handleSubmit = async () => {
    // 表单声明了 name 必填，但之前从未调用 validate，空名称也能建库
    const valid = formRef.value ? await formRef.value.validate().catch(() => false) : true
    if (!valid) return
    const loadingInstance = ElLoading.service({
      lock: true,
      text: '正在保存，请稍后',
      spinner: 'el-icon-loading',
      background: 'rgba(0, 0, 0, 0.7)',
    })
    try {
      if (isEditing.value && editingDataset.value) {
        await datasetApi.updateDataset(editingDataset.value.id, {
          name: formData.value.name,
          description: formData.value.description,
          indexing_technique: formData.value.indexing_technique as any,
          permission: 'only_me' as any,
        })
        const permRecord = permissionMap.value[editingDataset.value.id]
        if (permRecord) {
          await knowledgeBaseMngApi.update({
            data: {
              name: formData.value.name,
              ownerUid: formData.value.ownerUid,
              permissionType: formData.value.permissionType,
              unitsnos: JSON.stringify(formData.value.selectedDepts),
              roleiids: JSON.stringify(formData.value.selectedRoles),
            },
            where: { kbId: editingDataset.value.id },
          })
        } else {
          await knowledgeBaseMngApi.create({
            data: {
              kbId: editingDataset.value.id,
              name: formData.value.name,
              ownerUid: formData.value.ownerUid,
              enable: '1',
              permissionType: formData.value.permissionType,
              unitsnos: JSON.stringify(formData.value.selectedDepts),
              roleiids: JSON.stringify(formData.value.selectedRoles),
            },
          })
        }
        ElMessage.success('知识库更新成功')
      } else {
        const created = await datasetApi.createDataset({
          name: formData.value.name,
          description: formData.value.description,
          indexing_technique: formData.value.indexing_technique as any,
          permission: 'only_me',
        })
        await knowledgeBaseMngApi.create({
          data: {
            kbId: created.id,
            name: formData.value.name,
            ownerUid: formData.value.ownerUid,
            enable: '1',
            permissionType: formData.value.permissionType,
            unitsnos: JSON.stringify(formData.value.selectedDepts),
            roleiids: JSON.stringify(formData.value.selectedRoles),
          },
        })
        ElMessage.success('知识库创建成功')
      }
      dialogVisible.value = false
      await fetchDatasets()
    } catch (error) {
      console.error('操作失败:', error)
      ElMessage.error('操作失败，请重试')
    } finally {
      loadingInstance.close()
    }
  }

  const handleDelete = async (kb: KnowledgeBase) => {
    try {
      await ElMessageBox.confirm(
        `确定要删除知识库"${kb.name}"吗？此操作会同步删除Dify知识库和本系统记录，不可恢复。`,
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
        await fetchDatasets()
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

  // ========== 本系统模式操作 ==========

  const openLocalEditDialog = (record: any) => {
    localDialogTitle.value = '编辑本地知识库'
    localEditingRecord.value = record
    const selectedDepts: string[] = (() => {
      try { return JSON.parse(record.unitsnos || '[]') } catch { return [] }
    })()
    const selectedRoles: number[] = (() => {
      try { return JSON.parse(record.roleiids || '[]') } catch { return [] }
    })()
    localFormData.value = {
      name: record.name || '',
      ownerUid: record.ownerUid || '',
      permissionType: record.permissionType || 'public',
      selectedDepts,
      selectedRoles,
      enable: record.enable === '0' ? '0' : '1',
    }
    nextTick(() => {
      if (localTreeRef.value) {
        localTreeRef.value.setCheckedKeys(selectedDepts)
      }
    })
    localDialogVisible.value = true
  }

  const handleLocalSubmit = async () => {
    try {
      await knowledgeBaseMngApi.update({
        data: {
          name: localFormData.value.name,
          ownerUid: localFormData.value.ownerUid,
          permissionType: localFormData.value.permissionType,
          unitsnos: JSON.stringify(localFormData.value.selectedDepts),
          roleiids: JSON.stringify(localFormData.value.selectedRoles),
          enable: localFormData.value.enable,
        },
        where: { kbId: localEditingRecord.value.kbId },
      })
      ElMessage.success('更新成功')
      localDialogVisible.value = false
      await fetchLocalRecords()
    } catch (err) {
      console.error('更新失败:', err)
      ElMessage.error('更新失败')
    }
  }

  const handleDeleteLocal = async (record: any) => {
    try {
      await ElMessageBox.confirm(
        `确定要删除"${record.name || record.kbId}"的本系统关联吗？仅删除本地记录，Dify知识库保留。`,
        '删除确认',
        {
          confirmButtonText: '删除关联',
          cancelButtonText: '取消',
          type: 'warning',
        }
      )
      await knowledgeBaseMngApi.delete({ data: { kbId: record.kbId } })
      ElMessage.success('关联已删除')
      await fetchLocalRecords()
    } catch (e: any) {
      if (!isConfirmCancelled(e)) {
        console.error('删除失败:', e)
        ElMessage.error('删除失败')
      }
    }
  }

  // ========== 新建关联 ==========

  const openAssociateDialog = () => {
    associateForm.value = {
      kbId: '',
      name: '',
      ownerUid: uid,
      permissionType: 'public',
      selectedDepts: [],
      selectedRoles: [],
    }
    nextTick(() => {
      if (associateTreeRef.value) {
        associateTreeRef.value.setCheckedKeys([])
      }
    })
    associateDialogVisible.value = true
  }

  const handleAssociateSubmit = async () => {
    if (!associateForm.value.kbId || !associateForm.value.name) {
      ElMessage.warning('请填写Dify知识库ID和名称')
      return
    }
    try {
      await knowledgeBaseMngApi.create({
        data: {
          kbId: associateForm.value.kbId,
          name: associateForm.value.name,
          ownerUid: associateForm.value.ownerUid,
          permissionType: associateForm.value.permissionType,
          unitsnos: JSON.stringify(associateForm.value.selectedDepts),
          roleiids: JSON.stringify(associateForm.value.selectedRoles),
        },
      })
      ElMessage.success('关联成功')
      associateDialogVisible.value = false
      await fetchLocalRecords()
    } catch (err) {
      console.error('关联失败:', err)
      ElMessage.error('关联失败，请重试')
    }
  }

  // ========== 权限 ==========

  const getPermissionLabel = (kbId: string) => {
    const record = permissionMap.value[kbId]
    if (!record) return '所有人可见'
    switch (record.permissionType) {
      case 'only_me': return '仅个人可见'
      case 'partial_invisible': return '部分不可见'
      default: return '所有人可见'
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
      if (!formData.value.selectedDepts.includes(deptId)) {
        formData.value.selectedDepts.push(deptId)
      }
    } else {
      const index = formData.value.selectedDepts.indexOf(deptId)
      if (index > -1) {
        formData.value.selectedDepts.splice(index, 1)
      }
    }
  }

  const handleLocalDeptCheckChange = (data: any, checked: boolean) => {
    const deptId = data.unitsno
    if (checked) {
      if (!localFormData.value.selectedDepts.includes(deptId)) {
        localFormData.value.selectedDepts.push(deptId)
      }
    } else {
      const index = localFormData.value.selectedDepts.indexOf(deptId)
      if (index > -1) {
        localFormData.value.selectedDepts.splice(index, 1)
      }
    }
  }

  const handleAssociateDeptCheckChange = (data: any, checked: boolean) => {
    const deptId = data.unitsno
    if (checked) {
      if (!associateForm.value.selectedDepts.includes(deptId)) {
        associateForm.value.selectedDepts.push(deptId)
      }
    } else {
      const index = associateForm.value.selectedDepts.indexOf(deptId)
      if (index > -1) {
        associateForm.value.selectedDepts.splice(index, 1)
      }
    }
  }

  // ========== 初始化 ==========

  onMounted(async () => {
    await fetchUsers()
    await fetchDatasets()
    getDeptTree()
    getRoleList()
  })

  return {
    Plus, Search, Delete, Edit,
    datasets, loading, searchQuery, currentPage, pageSize, total,
    viewMode, localRecords, localLoading,
    userMap, userOptions,
    dialogVisible, dialogTitle, isEditing, editingDataset,
    localDialogVisible, localDialogTitle, localEditingRecord, localFormData,
    associateDialogVisible, associateForm,
    formData, formRules, associateRules,
    isAdmin, formRef, treeRef, localTreeRef, associateTreeRef, deptTree, roleList, defaultProps,
    filteredDatasets, paginatedDatasets,
    filteredLocalRecords, paginatedLocalRecords,
    fetchDatasets, fetchLocalRecords,
    handleSearch, handlePageChange, handleSizeChange,
    handleViewModeChange,
    openCreateDialog, openEditDialog, handleSubmit, handleDelete,
    openLocalEditDialog, handleLocalSubmit,
    handleDeleteLocal,
    openAssociateDialog, handleAssociateSubmit,
    handleDeptCheckChange, handleLocalDeptCheckChange, handleAssociateDeptCheckChange,
    getPermissionLabel, getPermissionLabelForRecord,
    getOwnerDisplay, getLocalOwnerDisplay,
  }
}
