import { reactive, onMounted } from 'vue'
import { ElMessage, ElLoading } from 'element-plus'
import { difyConfigApi } from '@/api/difyConfig'
import { invalidate, ensureLoaded } from '@/stores/difyConfig'
import { debug } from '@/utils/debug'

export function useIndex() {
  const state = reactive({
    tableData: [] as any[],
    pagination: {
      pageSize: 20,
      pageNum: 1,
      total: 0,
      order: 'iid',
      desc: 'asc',
    },
    params: {
      title: '编辑配置',
      dialogVisible: false,
      form: null as any,
    },
  })

  onMounted(() => {
    getDataList()
  })

  const getDataList = async () => {
    try {
      const reqdata = {
        data: {},
        pageRequest: state.pagination,
      }
      const { data: res } = await difyConfigApi.getList(reqdata)
      if (res.resp_code == 0) {
        state.tableData = res.datas
        state.pagination.total = res.datas ? res.datas.length : 0
      } else {
        ElMessage.error('获取配置列表失败')
      }
    } catch (err) {
      debug.error('获取配置列表失败', err)
      ElMessage.error('获取配置列表失败，请稍后重试')
    }
  }

  const showEdit = (row: any) => {
    state.params = {
      title: '编辑配置',
      dialogVisible: true,
      form: { ...row },
    }
  }

  const onSave = async () => {
    // 声明提到 try 外：无论成功 / 失败，finally 都关掉遮罩，避免永久转圈
    let loading: ReturnType<typeof ElLoading.service> | null = null
    try {
      loading = ElLoading.service({ lock: true, text: '正在保存，请稍后', background: 'rgba(0, 0, 0, 0.7)' })
      const { data: res } = await difyConfigApi.update({
        data: {
          moduleName: state.params.form.moduleName,
          apiUrl: state.params.form.apiUrl,
          apiKey: state.params.form.apiKey,
          description: state.params.form.description,
        },
        where: { iid: state.params.form.iid },
      })
      if (res.resp_code == 0) {
        state.params.dialogVisible = false
        invalidate()
        await ensureLoaded()
        getDataList()
        ElMessage.success('保存成功')
      } else {
        ElMessage.error('保存失败，请稍后重试')
      }
    } catch (err) {
      debug.error('保存 Dify 配置失败', err)
      ElMessage.error('保存失败，请稍后重试')
    } finally {
      loading?.close()
    }
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

  return { state, showEdit, onSave, handleSizeChange, handleCurrentChange, formatTime }
}
