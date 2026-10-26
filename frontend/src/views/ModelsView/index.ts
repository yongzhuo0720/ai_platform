import { debug } from '@/utils/debug'
import { isConfirmCancelled } from '@/utils/confirm'
import { pageDescriptions } from '@/data/platform'
import { ref, computed } from 'vue'
import { tagsApi } from '@/api/tags'
import { useTagBinding } from '@/composables/useTagBinding'
import { modelsMngApi } from '@/api/modelsMng'
import Cookies from 'js-cookie'
import type { FormInstance } from 'element-plus'

export function useIndex() {
  const ruleFormRef = ref<FormInstance>()
  const selectedCategory = ref<string[]>([])
  const sortBy = ref('')
  const searchKeyword = ref('')
  const state = reactive({
     dataList:[] as any[],  
     allTags: [] as any[],
     weeklyDownloads:0,
     rid: parseInt(Cookies.get('rid') || '0'),
    params: {
      title: '新建模型',
      dialogVisible: false,
      type: 'add',
      form: null as any
    },
    rules: {
      name: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
      provider: [{ required: true, message: '请输入提供商', trigger: 'blur' }],
      description: [{ required: true, message: '请输入模型描述', trigger: 'blur' }],
    }
  })

  onMounted(() => {
    getDataList()
    getAllTags()
    queryWeeklyDownloads()//查询本周下载量统计
  })

  // 过滤后的模型列表
  const filteredDataList = computed(() => {
    let result = [...state.dataList]

    // 根据关键词搜索
    if (searchKeyword.value) {
      const keyword = searchKeyword.value.toLowerCase()
      result = result.filter(item =>
        item.name?.toLowerCase().includes(keyword) ||
        item.description?.toLowerCase().includes(keyword) ||
        item.provider?.toLowerCase().includes(keyword)
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
          case 'downloads': // 按下载量降序
            return (b.downloads || 0) - (a.downloads || 0)
          case 'downloads_asc': // 按下载量升序
            return (a.downloads || 0) - (b.downloads || 0)
          case 'createtime': // 按创建时间降序
            return new Date(b.createtime || 0).getTime() - new Date(a.createtime || 0).getTime()
          case 'createtime_asc': // 按创建时间升序
            return new Date(a.createtime || 0).getTime() - new Date(b.createtime || 0).getTime()
          case 'size': // 按文件大小降序
            return parseFileSize(b.size) - parseFileSize(a.size)
          case 'size_asc': // 按文件大小升序
            return parseFileSize(a.size) - parseFileSize(b.size)
          default:
            return 0
        }
      })
    }

    return result
  })

   const getDataList = async () => {
    try {
      let reqdata={
        data: {},
      }

      const {data:res} = await modelsMngApi.query(reqdata)
      if (res.resp_code == 0) {
        state.dataList = (res.datas || []).sort((a: any, b: any) => (b.iid || 0) - (a.iid || 0))
        refreshTagBind()//刷新标签绑定
      }
    } catch (err) {
      console.error('获取模型列表失败:', err)
    }
  }

  // 解析文件大小字符串为字节数
  const parseFileSize = (sizeStr: string | number | undefined): number => {
    if (!sizeStr) return 0
    if (typeof sizeStr === 'number') return sizeStr

    const units: Record<string, number> = {
      'B': 1,
      'KB': 1024,
      'MB': 1024 * 1024,
      'GB': 1024 * 1024 * 1024,
      'TB': 1024 * 1024 * 1024 * 1024,
    }

    const match = sizeStr.toString().trim().match(/^([\d.]+)\s*(B|KB|MB|GB|TB)$/i)
    if (!match) return 0

    const value = parseFloat(match[1])
    const unit = match[2].toUpperCase()
    return value * (units[unit] || 1)
  }
  const queryWeeklyDownloads = async () => {
    try {
      // 页面头部的「周下载数量」是全站口径，走专门的统计端点；
      // 单模型口径的 queryWeeklyDownloads 要求 iid，传空会被后端拒绝并弹错
      const {data:res} = await modelsMngApi.queryWeeklyDownloadsTotal()
      if (res.resp_code == 0) {
        state.weeklyDownloads = res.datas || 0
      }
    } catch (err) {
      console.error('查询本周下载量统计失败:', err)
    }
  }

    // 获取所有标签
    const getAllTags = async () => {
      try {
        let reqdata={
          data:{
            type:'models',
          }
        }
     
        const {data:res} = await tagsApi.query(reqdata)
        if (res.resp_code == 0) {
          state.allTags = res.datas || []
          debug.log(state.allTags)
        }
      } catch (err) {
        console.error('获取标签列表失败:', err)
      }
    }

      // 处理标签变更
      const { refreshTagBind } = useTagBinding(() => state.dataList, { type: 'models' })

  const resetForm = (formEl: FormInstance | undefined) => {
    if (!formEl) return
    formEl.resetFields()
  }

  const showAdd = () => {
    resetForm(ruleFormRef.value)
    state.params = {
      title: '新建模型',
      dialogVisible: true,
      type: 'add',
      form: {
        name: '',
        provider: '',
        description: '',
        category: '',
        size: '',
        downloads: '',
        downloadUrl: '',
      }
    }
  }

  const showEdit = (item: any) => {
    resetForm(ruleFormRef.value)
    state.params = {
      title: '编辑模型',
      dialogVisible: true,
      type: 'edit',
      form: { ...item }
    }
  }

  const handleDelete = async (item: any) => {
    // 声明提到 try 外：无论成功 / 失败 / 取消，finally 都能关掉遮罩，避免永久转圈
    let loading: ReturnType<typeof ElLoading.service> | null = null
    try {
      await ElMessageBox.confirm(
        `确定要删除模型 "${item.name}" 吗？`,
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

      const { data: res } = await modelsMngApi.delete(reqdata)
      if (res.resp_code == 0) {
        ElMessage.success('删除成功')
        getDataList()
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
            const { data: res } = await modelsMngApi.create(reqdata)
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
            const { data: res } = await modelsMngApi.update(reqdata)
            if (res.resp_code == 0) {
              state.params.dialogVisible = false
              getDataList()
              ElMessage.success('保存成功')
            } else {
              ElMessage.error('保存失败，请稍后重试')
            }
          }
        } catch (err) {
          debug.error('保存模型失败', err)
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
  const previewVisible = ref(false)
  const previewItem = ref<any>(null)

  const downloadModel = async (row: any) => {
    previewItem.value = row
    previewVisible.value = true
  }

  const closePreview = () => {
    previewItem.value = null
    previewVisible.value = false
  }

  return {
    state,
    ruleFormRef,
    pageDescriptions,
    selectedCategory,
    sortBy,
    searchKeyword,
    filteredDataList,
    showAdd,
    showEdit,
    handleDelete,
    onSave,
    getAllTags,
    refreshTagBind,
    downloadModel,
    previewVisible,
    previewItem,
    closePreview
  }
}
