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
    },
  })

  onMounted(() => {
    getDataList()
  })

  const getDataList = async () => {
    const timeS = state.formInline.timeS
    const timeE = state.formInline.timeE
    // 后端按「区间」过滤，只填一头无法构成合法条件，与其悄悄查出一个全量
    // 让用户以为这就是筛选结果，不如直接说明
    if ((timeS && !timeE) || (!timeS && timeE)) {
      ElMessage.warning('请同时选择开始日期与结束日期')
      return
    }
    try {
      const { data: res } = await logApi.getLogList({
        pageNum: state.pagination.pageNum,
        pageSize: state.pagination.pageSize,
        startDate: timeS || undefined,
        endDate: timeE || undefined,
      })
      if (res.resp_code === 0) {
        state.tableData = res.datas?.content ?? []
        state.pagination.total = res.datas?.totalSize ?? 0
      } else {
        // 直接透出后端原因：非超管、「日期格式不正确」这类提示只有后端知道
        ElMessage.error(res.resp_msg || '获取日志列表失败')
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

  /**
   * `createtime` 有两种形态：真库这条链路返回的是 `2026-11-02 16:33:59` 这样的字符串，
   * 而按毫秒时间戳序列化时单元格里就是一串 13 位数字。两种都转成「年-月-日 时:分:秒」。
   */
  const formatTime = (value: number | string | null | undefined): string => {
    if (value === null || value === undefined || value === '') return '-'
    const date =
      typeof value === 'number'
        ? new Date(value)
        : new Date(String(value).replace(/\//g, '-').replace(' ', 'T'))
    if (Number.isNaN(date.getTime())) return String(value)
    const pad = (num: number) => String(num).padStart(2, '0')
    return (
      `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
      `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
    )
  }

  return { state, onQuery, handleSizeChange, handleCurrentChange, formatTime }
}
