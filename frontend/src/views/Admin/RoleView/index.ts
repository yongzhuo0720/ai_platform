import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElLoading, ElMessage, ElMessageBox } from 'element-plus'
import type { ElTree, FormInstance } from 'element-plus'
import { roleApi } from '@/api/role'
import { aiPlatformMenus } from '@/data/adminPlatform'
import Cookies from 'js-cookie'
import { isSuperAdmin } from '@/config/constants'
import { debug } from '@/utils/debug'
import { safeJsonParse } from '@/utils/json'

const processMenuData = (sections: any[]): any[] => {
  const result: any[] = []
  sections.forEach(section => {
    if (section.items && section.items.length === 1) {
      const singleItem = section.items[0]
      result.push({
        ...singleItem,
        key: singleItem.routeName,
        title: singleItem.title,
      })
    } else {
      const processedSection: any = {
        ...section,
        key: section.id,
      }
      if (section.items && section.items.length > 0) {
        processedSection.items = section.items.map((item: any) => ({
          ...item,
          key: item.routeName,
        }))
      }
      result.push(processedSection)
    }
  })
  return result
}

export function useIndex() {
  const ruleFormRef = ref<FormInstance>()
  const treemenuRef = ref<InstanceType<typeof ElTree>>()

  const state = reactive({
    tableData: [] as any[],
    menuListTree: processMenuData(JSON.parse(JSON.stringify(aiPlatformMenus))),
    rid: parseInt(Cookies.get('admin_rid') || '0'),
    isAdmin: isSuperAdmin(Cookies.get('admin_rid')) || Cookies.get('admin_access') === '1',
    allowAdminAccess: false,
    params: {
      title: '新建',
      dialogVisible: false,
      type: 'add',
      form: null as any,
    },
    formInline: { name: '' },
    defaultProps: { children: 'items', label: 'title' },
    pagination: {
      pageSize: 15,
      pageNum: 1,
      total: 0,
      order: 'iid',
      desc: 'desc',
    },
    rules: {
      name: [{ required: true, message: '请输入角色名', trigger: 'change' }],
    },
  })

  onMounted(() => {
    getDataList()
  })

  const getDataList = async () => {
    try {
      const reqdata = { bean: state.formInline, pageRequest: state.pagination }
      const { data: res } = await roleApi.getDataList(reqdata)
      if (res.resp_code == 0) {
        state.tableData = res.datas.content
        state.pagination.total = res.datas.totalSize
      } else {
        ElMessage.error('获取角色列表失败')
      }
    } catch (err) {
      debug.error('获取角色列表失败', err)
      ElMessage.error('获取角色列表失败，请稍后重试')
    }
  }

  const onQuery = () => {
    state.pagination.pageNum = 1
    getDataList()
  }

  const handleSizeChange = (val: number) => {
    state.pagination.pageSize = val
    getDataList()
  }

  const handleCurrentChange = (val: number) => {
    state.pagination.pageNum = val
    getDataList()
  }

  const showAdd = () => {
    resetForm(ruleFormRef.value)
    state.params = {
      title: '新建角色',
      dialogVisible: true,
      type: 'add',
      form: {
        name: '',
        iid: '',
        describe: '',
        viewjson: '',
        urlgroupids: null,
      },
    }
    nextTick(() => {
      treemenuRef.value!.setCheckedKeys([])
    })
  }

  const showEdit = (row: any) => {
    resetForm(ruleFormRef.value)
    state.params = {
      title: '编辑角色',
      dialogVisible: true,
      type: 'edit',
      form: { ...row },
    }
    nextTick(() => {
      // viewjson 字段可能损坏，解析失败兜底为空数组，避免编辑弹窗里的权限树打不开
      const viewList = safeJsonParse<any[]>(row.viewjson, [])
      treemenuRef.value!.setCheckedKeys(viewList)
    })
  }

  const showDelete = async (row: any) => {
    try {
      await ElMessageBox.confirm('您确定要删除吗?', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      // 取消 / 关闭确认框：直接返回，避免 .then 链产生未处理拒绝
      return
    }
    try {
      const reqdata = { bean: { iid: row.iid } }
      const { data: res } = await roleApi.delete(reqdata)
      if (res.resp_code == 0) {
        ElMessage.success('删除成功')
        getDataList()
      } else {
        ElMessage.error('删除失败')
      }
    } catch (err) {
      debug.error('删除角色失败', err)
      ElMessage.error('删除失败，请稍后重试')
    }
  }

  const onSave = () => {
    ruleFormRef.value?.validate(async (valid) => {
      if (valid) {
        const loading = ElLoading.service({
          lock: true,
          text: '正在保存，请稍后',
          spinner: 'el-icon-loading',
          background: 'rgba(0, 0, 0, 0.7)',
        })
        const viewList = treemenuRef.value!.getCheckedKeys()
        if (!viewList.length) {
          loading.close()
          ElMessage({ message: '请选择菜单', type: 'error' })
          return
        }
        state.params.form.viewjson = JSON.stringify(viewList)
        if (state.params.type == 'add') {
          const reqdata = { bean: state.params.form }
          const response = await roleApi.create(reqdata).catch(() => null)
          loading.close()
          if (!response) return
          const res = response.data
          if (res.resp_code == 0) {
            state.params.dialogVisible = false
            getDataList()
            ElMessage.success('保存成功')
          }
        } else {
          const reqdata = {
            data: { ...state.params.form },
            where: { iid: state.params.form.iid },
          }
          delete reqdata.data.iid
          const response = await roleApi.update(reqdata).catch(() => null)
          loading.close()
          if (!response) return
          const res = response.data
          if (res.resp_code == 0) {
            state.params.dialogVisible = false
            getDataList()
            ElMessage.success('保存成功')
          }
        }
      } else {
        ElMessage({ message: '请填写完整的信息', type: 'error' })
      }
    })
  }

  const resetForm = (formEl: FormInstance | undefined) => {
    if (!formEl) return
    formEl.resetFields()
  }

  return {
    ruleFormRef, treemenuRef, state, onQuery, showAdd, showEdit,
    showDelete, onSave, handleSizeChange, handleCurrentChange,
  }
}
