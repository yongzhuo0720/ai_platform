import type { FormInstance } from 'element-plus'
import { unitApi } from '@/api/unit'
import Cookies from 'js-cookie'
import { isSuperAdmin } from '@/config/constants'
import { debug } from '@/utils/debug'

export function useIndex() {
  const ruleFormRef = ref<FormInstance>()

  const state = reactive({
    tableData: [] as any[],
    treeData: [{ unitname: '顶级', unitsno: '0', childList: [] as any[] }],
    treeProps: { label: 'unitname', children: 'childList' },
    rid: parseInt(Cookies.get('admin_rid') || '0'),
    isAdmin: isSuperAdmin(Cookies.get('admin_rid')) || Cookies.get('admin_access') === '1',
    params: {
      title: '新增',
      dialogVisible: false,
      type: 'add',
      form: null as any,
    },
    formInline: { unitname: '', enable: true },
    rules: {
      unitname: [{ required: true, message: '请输入部门名称', trigger: 'change' }],
      parentsno: [{ required: true, message: '请选择上级部门', trigger: 'change' }],
    },
  })

  onMounted(() => {
    getDataList()
  })

  const getDataList = async () => {
    try {
      const reqdata = { data: state.formInline }
      const { data: res } = await unitApi.getDataList(reqdata)
      if (res.resp_code == 0) {
        state.tableData = res.datas
        state.treeData = [{ unitname: '顶级', unitsno: '0', childList: state.tableData }]
      } else {
        ElMessage.error('获取部门列表失败')
      }
    } catch (err) {
      debug.error('获取部门列表失败', err)
      ElMessage.error('获取部门列表失败，请稍后重试')
    }
  }

  const onQuery = () => {
    getDataList()
  }

  const showAdd = () => {
    resetForm(ruleFormRef.value)
    state.params = {
      title: '新增部门',
      dialogVisible: true,
      type: 'add',
      form: { parentsno: '', unitname: '', unittel: '', remark: '', enable: true },
    }
  }

  const showEdit = (row: any) => {
    resetForm(ruleFormRef.value)
    state.params = {
      title: '编辑部门',
      dialogVisible: true,
      type: 'edit',
      form: { ...row },
    }
  }

  const showDelete = (row: any) => {
    ElMessageBox.confirm('您确定要删除吗?', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    }).then(async () => {
      const reqdata = { data: { iid: row.iid } }
      const { data: res } = await unitApi.delete(reqdata)
      if (res.resp_code == 0) {
        getDataList()
        ElMessage.success('删除成功')
      }
    })
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
        if (state.params.type == 'add') {
          const reqdata = { data: state.params.form }
          const response = await unitApi.create(reqdata).catch(() => null)
          loading.close()
          if (!response) return
          const res = response.data
          if (res.resp_code == 0) {
            state.params.dialogVisible = false
            getDataList()
            ElMessage.success('保存成功')
          }
        } else {
          // 注意：这里是 where（更新条件），不是 when。
          // 曾经写成 when，api 层签名是 params: any，TypeScript 不报错，
          // 但后端收不到更新条件，编辑部门会静默失败。
          const reqdata = { data: state.params.form, where: { iid: state.params.form.iid } }
          const response = await unitApi.update(reqdata).catch(() => null)
          loading.close()
          if (!response) return
          const res = response.data
          if (res.resp_code == 0) {
            state.params.dialogVisible = false
            getDataList()
            ElMessage.success('修改成功')
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

  return { ruleFormRef, state, onQuery, showAdd, showEdit, showDelete, onSave }
}
