import { ref, computed, nextTick, onMounted, reactive } from 'vue'
import { ElLoading, ElMessage, ElMessageBox } from 'element-plus'
import { isConfirmCancelled } from '@/utils/confirm'
import { debug } from '@/utils/debug'
import { safeJsonParse } from '@/utils/json'
import { pageDescriptions } from '@/data/platform'
import { tagsApi } from '@/api/tags'
import { useTagBinding } from '@/composables/useTagBinding'
import { applicationsMngApi } from '@/api/applicationsMng'
import { unitApi } from '@/api/unit'
import { roleApi } from '@/api/role'
import Cookies from 'js-cookie'
import { SUPER_ADMIN_RID } from '@/config/constants'
import type { ElTree, FormInstance } from 'element-plus'

export function useIndex() {
  const ruleFormRef = ref<FormInstance>()
  const treeRef = ref<InstanceType<typeof ElTree>>()
  const selectedCategory = ref<string[]>([])
  const searchKeyword = ref('')
  const sortBy = ref('')

  const state = reactive({
    dataList: [] as any[],
    allTags: [] as any[],
    params: {
      title: '新建应用',
      dialogVisible: false,
      type: 'add',
      form: null as any
    },
    rid: parseInt(Cookies.get('rid') || '0'),
    rules: {
      title: [{ required: true, message: '请输入应用名称', trigger: 'blur' }],
      detail: [{ required: true, message: '请输入应用描述', trigger: 'blur' }],
    },
    // 管理权限弹窗
    mngDialogVisible: false,
    mngForm: {
      app: null as any,
      selectedDepts: [] as string[],
      selectedRoles: [] as number[],
    },
    deptTree: [] as any[],
    roleList: [] as any[],
    defaultProps: {
      children: 'childList',
      label: 'unitname',
    },
  })

  onMounted(() => {
    getDataList()
    getAllTags()
    getDeptTree()
    getRoleList()
  })

  const getDataList = async () => {
    try {
      let reqdata = {
        data: {},
      }

      const { data: res } = await applicationsMngApi.query(reqdata)
      if (res.resp_code == 0) {
        state.dataList = res.datas || []
        refreshTagBind()
      }
    } catch (err) {
      console.error('获取应用列表失败:', err)
    }
  }

  // 获取所有标签
  const getAllTags = async () => {
    try {
      let reqdata = {
        data: {
          type: 'applications',
        }
      }

      const { data: res } = await tagsApi.query(reqdata)
      if (res.resp_code == 0) {
        state.allTags = res.datas || []
      }
    } catch (err) {
      console.error('获取标签列表失败:', err)
    }
  }

  const showAdd = () => {
    resetForm(ruleFormRef.value)
    state.params = {
      title: '新建应用',
      dialogVisible: true,
      type: 'add',
      form: {
        title: '',
        detail: '',
        difyUrl: '',
        updatedAt: new Date().toLocaleDateString(),
      }
    }
  }

  // 编辑应用
  const showEdit = (item: any) => {
    resetForm(ruleFormRef.value)
    state.params = {
      title: '编辑应用',
      dialogVisible: true,
      type: 'edit',
      form: { ...item }
    }
  }

  // 删除应用
  const handleDelete = async (item: any) => {
    // 声明提到 try 外：无论成功 / 失败 / 取消，finally 都能关掉遮罩，避免永久转圈
    let loading: ReturnType<typeof ElLoading.service> | null = null
    try {
      await ElMessageBox.confirm(
        `确定要删除应用 "${item.title}" 吗？`,
        '确认删除',
        {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning',
        }
      )

      loading = ElLoading.service({
        lock: true,
        text: '正在删除，请稍后',
        spinner: 'el-icon-loading',
        background: 'rgba(0, 0, 0, 0.7)'
      })

      const reqdata = {
        data: {
          iid: item.iid
        }
      }

      const { data: res } = await applicationsMngApi.delete(reqdata)
      if (res.resp_code == 0) {
        ElMessage.success('删除成功')
        getDataList()
      } else {
        ElMessage.error(res.resp_msg || '删除失败')
      }
    } catch (error) {
      // 用户取消不提示；真失败要提示并关掉 loading（原来两者都被吞了）
      if (!isConfirmCancelled(error)) {
        ElMessage.error('删除失败，请重试')
      }
    } finally {
      loading?.close()
    }
  }

  // 处理标签变更
  const { refreshTagBind } = useTagBinding(() => state.dataList, { type: 'applications' })

  // 过滤后的应用列表
  const filteredDataList = computed(() => {
    let result = [...state.dataList]

    // 根据关键词搜索
    if (searchKeyword.value) {
      const keyword = searchKeyword.value.toLowerCase()
      result = result.filter(item =>
        item.title?.toLowerCase().includes(keyword) ||
        item.detail?.toLowerCase().includes(keyword) ||
        item.model?.toLowerCase().includes(keyword)
      )
    }

    // 根据标签筛选
    if (selectedCategory.value.length > 0) {
      const selected = new Set(selectedCategory.value)
      result = result.filter(item => {
        const tagList = item.tagList || []
        return tagList.some((tag: any) => selected.has(String(tag.iid)))
      })
    }

    // 根据排序方式排序
    if (sortBy.value) {
      result.sort((a, b) => {
        switch (sortBy.value) {
          case 'createtime': // 按创建时间降序
            return new Date(b.createtime || 0).getTime() - new Date(a.createtime || 0).getTime()
          case 'createtime_asc': // 按创建时间升序
            return new Date(a.createtime || 0).getTime() - new Date(b.createtime || 0).getTime()
          case 'updatetime': // 按更新时间降序
            return new Date(b.updatetime || 0).getTime() - new Date(a.updatetime || 0).getTime()
          case 'updatetime_asc': // 按更新时间升序
            return new Date(a.updatetime || 0).getTime() - new Date(b.updatetime || 0).getTime()
          default:
            return 0
        }
      })
    }

    return result
  })

  const onSave = () => {
    ruleFormRef.value?.validate(async (valid: boolean) => {
      if (valid) {
        // 无论成功 / 失败 / 校验异常，finally 都关掉遮罩，避免永久转圈
        let loading: ReturnType<typeof ElLoading.service> | null = null
        try {
          loading = ElLoading.service({
            lock: true,
            text: '正在保存，请稍后',
            spinner: 'el-icon-loading',
            background: 'rgba(0, 0, 0, 0.7)'
          })

          let reqdata = null

          if (state.params.type == 'add') {
            reqdata = {
              data: { ...state.params.form }
            }
            const { data: res } = await applicationsMngApi.create(reqdata)
            if (res.resp_code == 0) {
              state.params.dialogVisible = false
              getDataList()
              ElMessage.success('保存成功')
            } else {
              ElMessage.error('保存失败，请稍后重试')
            }
          } else {
            reqdata = {
              data: { ...state.params.form },
              where: {
                iid: state.params.form.iid
              }
            }
            const { data: res } = await applicationsMngApi.update(reqdata)
            if (res.resp_code == 0) {
              state.params.dialogVisible = false
              getDataList()
              ElMessage.success('保存成功')
            } else {
              ElMessage.error('保存失败，请稍后重试')
            }
          }
        } catch (err) {
          debug.error('保存应用失败', err)
          ElMessage.error('保存失败，请稍后重试')
        } finally {
          loading?.close()
        }
      } else {
        ElMessage({
          message: '请填写完整的信息',
          type: 'error'
        })
      }
    })
  }

  const resetForm = (formEl: FormInstance | undefined) => {
    if (!formEl) return
    formEl.resetFields()
  }

  // 显示管理权限弹窗
  const showMng = async (item: any) => {
    state.mngForm.app = item
    // unitsnos / roleiids 是后端存成的 JSON 字符串，损坏时兜底为空数组，避免权限弹窗打不开
    state.mngForm.selectedDepts = safeJsonParse<any[]>(item.unitsnos, [])
    state.mngForm.selectedRoles = safeJsonParse<any[]>(item.roleiids, [])
    state.mngDialogVisible = true
    // 数据加载完成后，设置树形结构的选中状态
    nextTick(() => {
      if (treeRef.value) {
        treeRef.value.setCheckedKeys(state.mngForm.selectedDepts)
      }
    })
  }

  // 获取部门树形结构
  const getDeptTree = async () => {
    try {
      const reqdata = {
        data: {}
      }
      const { data: res } = await unitApi.getDataList(reqdata)
      if (res.resp_code == 0) {
        state.deptTree = res.datas || []
      }
    } catch (err) {
      console.error('获取部门树失败:', err)
    }
  }

  // 获取角色列表
  const getRoleList = async () => {
    try {
      const reqdata = {
        bean: {},
        pageRequest: {
          pageSize: 1000,
          pageNum: 1,
          order: 'iid',
          desc: 'desc'
        }
      }
      const { data: res } = await roleApi.getDataList(reqdata)
      if (res.resp_code == 0) {
        // datas 可能为空，加守卫避免 TypeError
        state.roleList = (res.datas?.content || []).filter((item: any) => item.iid !== SUPER_ADMIN_RID)
      } else {
        ElMessage.error('获取角色列表失败')
      }
    } catch (err) {
      debug.error('获取角色列表失败', err)
      ElMessage.error('获取角色列表失败，请稍后重试')
    }
  }


  // 保存管理权限配置
  const saveMng = async () => {
    // 声明提到 try 外：无论成功 / 失败，finally 都关掉遮罩，避免永久转圈
    let loading: ReturnType<typeof ElLoading.service> | null = null
    try {
      loading = ElLoading.service({
        lock: true,
        text: '正在保存，请稍后',
        spinner: 'el-icon-loading',
        background: 'rgba(0, 0, 0, 0.7)'
      })

      // 这里假设有一个接口保存部门权限和角色权限
      const reqdata = {
        data: {
          unitsnos: state.mngForm.selectedDepts,
          roleiids: state.mngForm.selectedRoles
        },
        where: {
          iid: state.mngForm.app.iid
        }
      }
      const { data: res } = await applicationsMngApi.update(reqdata)
      if (res.resp_code == 0) {
        getDataList()
        ElMessage.success('权限配置保存成功')
        state.mngDialogVisible = false
      } else {
        ElMessage.error('保存失败，请稍后重试')
      }
    } catch (err) {
      debug.error('保存权限配置失败', err)
      ElMessage.error('保存失败，请稍后重试')
    } finally {
      loading?.close()
    }
  }

  // 处理部门选择变化
  const handleDeptCheckChange = (data: any, checked: boolean) => {
    const deptId = data.unitsno
    if (checked) {
      if (!state.mngForm.selectedDepts.includes(deptId)) {
        state.mngForm.selectedDepts.push(deptId)
      }
    } else {
      const index = state.mngForm.selectedDepts.indexOf(deptId)
      if (index > -1) {
        state.mngForm.selectedDepts.splice(index, 1)
      }
    }
  }

  return {
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
  }
}
