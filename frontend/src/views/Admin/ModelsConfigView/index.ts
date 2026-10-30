import { reactive, onMounted, ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox, ElLoading } from 'element-plus'
import { modelsConfigApi } from '@/api/modelsConfig'
import { debug } from '@/utils/debug'

export function useIndex() {
  const ruleFormRef = ref<FormInstance>()

  const state = reactive({
    tableData: [] as any[],
    loading: false,
    formInline: {
      name: '',
    },
    pagination: {
      pageSize: 20,
      pageNum: 1,
      total: 0,
      order: 'iid',
      desc: 'desc',
    },
    params: {
      title: '新建模型配置',
      dialogVisible: false,
      type: 'add' as 'add' | 'edit',
      form: null as any,
    },
    rules: {
      id: [{ required: true, message: '请输入模型ID', trigger: 'change' }],
      name: [{ required: true, message: '请输入模型名称', trigger: 'change' }],
      provider: [{ required: true, message: '请输入提供者', trigger: 'change' }],
    } as FormRules,
  })

  onMounted(() => {
    getDataList()
  })

  const getDataList = async () => {
    state.loading = true
    try {
      const reqdata = {
        data: state.formInline,
        pageRequest: state.pagination,
      }
      const { data: res } = await modelsConfigApi.getPageList(reqdata)
      if (res.resp_code == 0) {
        if (res.datas && Array.isArray(res.datas)) {
          state.tableData = res.datas
          state.pagination.total = res.datas.length
        } else if (res.datas?.content) {
          state.tableData = res.datas.content
          state.pagination.total = res.datas.totalSize
        } else {
          state.tableData = []
          state.pagination.total = 0
        }
      } else {
        ElMessage.error('获取列表失败')
      }
    } catch (err) {
      debug.error('获取模型配置列表失败', err)
      ElMessage.error('获取列表失败，请稍后重试')
    } finally {
      state.loading = false
    }
  }

  const onQuery = () => {
    state.pagination.pageNum = 1
    getDataList()
  }

  const showAdd = () => {
    ruleFormRef.value?.resetFields()
    state.params = {
      title: '新建模型配置',
      dialogVisible: true,
      type: 'add',
      form: {
        id: '',
        name: '',
        provider: '',
        protocol: '',
        baseUrl: '',
        upstreamModel: '',
        embedModel: '',
        apiKey: '',
      },
    }
  }

  const showEdit = (row: any) => {
    ruleFormRef.value?.resetFields()
    state.params = {
      title: '编辑模型配置',
      dialogVisible: true,
      type: 'edit',
      form: { ...row },
    }
  }

  const showDelete = async (row: any) => {
    try {
      await ElMessageBox.confirm('您确定要删除该模型配置吗？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      // 取消 / 关闭确认框：直接返回，避免 .then 链产生未处理拒绝
      return
    }
    try {
      const reqdata = { data: { iid: row.iid } }
      const { data: res } = await modelsConfigApi.delete(reqdata)
      if (res.resp_code == 0) {
        ElMessage.success('删除成功')
        getDataList()
      } else {
        ElMessage.error('删除失败')
      }
    } catch (err) {
      debug.error('删除模型配置失败', err)
      ElMessage.error('删除失败，请稍后重试')
    }
  }

  const onSave = () => {
    ruleFormRef.value?.validate(async (valid) => {
      if (valid) {
        // 直连配置的完整性校验：查询接口不下发密钥（掩码为空），
        // 编辑时密钥留空 = 沿用已配置的密钥，由后端合并
        const form = state.params.form
        if (form.protocol === 'openai') {
          // 允许「纯向量」配置行：对话用 A 家、向量用 B 家时（DeepSeek 没有
          // embeddings 接口，这是必需组合），B 家那行没有上游对话模型，
          // 只填服务地址 + 密钥 + 向量模型即可。
          const hasChatModel = !!form.upstreamModel?.trim()
          const hasEmbedModel = !!form.embedModel?.trim()
          if (!form.baseUrl?.trim() || (!hasChatModel && !hasEmbedModel)) {
            ElMessage.warning('启用直连时必须填写服务地址，以及上游模型名或向量模型名（至少一个）')
            return
          }
          if (state.params.type === 'add' && !form.apiKey?.trim()) {
            ElMessage.warning('启用直连时必须填写服务密钥')
            return
          }
        }
        const loading = ElLoading.service({
          lock: true,
          text: '正在保存，请稍后',
          background: 'rgba(0, 0, 0, 0.7)',
        })
        try {
          if (state.params.type === 'add') {
            const reqdata = { data: state.params.form }
            const { data: res } = await modelsConfigApi.create(reqdata)
            if (res.resp_code == 0) {
              state.params.dialogVisible = false
              getDataList()
              ElMessage.success('新建成功')
            } else {
              ElMessage.error('新建失败')
            }
          } else {
            const reqdata = {
              data: { ...state.params.form },
              where: { iid: state.params.form.iid },
            }
            const { data: res } = await modelsConfigApi.update(reqdata)
            if (res.resp_code == 0) {
              state.params.dialogVisible = false
              getDataList()
              ElMessage.success('保存成功')
            } else {
              ElMessage.error('保存失败')
            }
          }
        } catch (err) {
          debug.error('保存模型配置失败', err)
          ElMessage.error('保存失败，请稍后重试')
        } finally {
          loading.close()
        }
      } else {
        ElMessage.warning('请填写完整的信息')
      }
    })
  }

  const handleSizeChange = (val: number) => {
    state.pagination.pageSize = val
    getDataList()
  }

  const handleCurrentChange = (val: number) => {
    state.pagination.pageNum = val
    getDataList()
  }

  const formatTime = (val: string | null) => {
    if (!val) return '-'
    return val.replace('T', ' ')
  }

  return {
    ruleFormRef,
    state,
    onQuery,
    showAdd,
    showEdit,
    showDelete,
    onSave,
    handleSizeChange,
    handleCurrentChange,
    formatTime,
  }
}
