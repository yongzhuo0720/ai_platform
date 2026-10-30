import { reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { logApi } from '@/api/log'
import { debug } from '@/utils/debug'

export function useIndex() {
  const state = reactive({
    tableData: [] as any[],
    formInline: {
      timeS: null as string | null,
      timeE: null as string | null,
    },
    pagination: {
      pageSize: 20,
      pageNum: 1,
      total: 0,
      order: 'iid',
      desc: 'desc',
    },
  })

  onMounted(() => {
    getDataList()
  })

  const getDataList = async () => {
    try {
      const reqdata = {
        data: {},
        ...state.formInline,
        pageRequest: state.pagination,
      }
      const { data: res } = await logApi.getLogList(reqdata)
      if (res.resp_code == 0) {
        state.tableData = res.datas.content
        state.pagination.total = res.datas.totalSize
      } else {
        ElMessage.error('获取日志列表失败')
      }
    } catch (err) {
      debug.error('获取日志列表失败', err)
      ElMessage.error('获取日志列表失败，请稍后重试')
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

  return { state, onQuery, handleSizeChange, handleCurrentChange }
}
