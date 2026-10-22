import { ref } from 'vue'
import { debug } from '@/utils/debug'
import { isConfirmCancelled } from '@/utils/confirm'
import { pageDescriptions } from '@/data/platform'
import { tagsApi } from '@/api/tags'
import { useTagBinding } from '@/composables/useTagBinding'
import { datasetsMngApi } from '@/api/datasetsMng'
import Cookies from 'js-cookie'
import type { FormInstance } from 'element-plus'


export function useIndex() {
  const ruleFormRef = ref<FormInstance>()
  const selectedCategory = ref<string[]>([])
  const searchKeyword = ref('')
  const sortBy = ref('')

  const state = reactive({
    dataList: [] as any[],
    allTags: [] as any[],
    params: {
      title: '新增数据集',
      dialogVisible: false,
      type: 'add',
      form: null as any
    },
    rid: parseInt(Cookies.get('rid') || '0'),
    rules: {
      name: [{ required: true, message: '请输入数据集名称', trigger: 'blur' }],
      type: [{ required: true, message: '请选择数据集类型', trigger: 'change' }],
      description: [{ required: true, message: '请输入数据集描述', trigger: 'blur' }],
    }
  })



  onMounted(() => {
    getDataList()
    getAllTags()
  })

  const getDataList = async () => {
    try {
      let reqdata = {
        data: {},
      }

      const { data: res } = await datasetsMngApi.query(reqdata)
      if (res.resp_code == 0) {
        state.dataList = (res.datas || []).sort((a: any, b: any) => (b.iid || 0) - (a.iid || 0))
        refreshTagBind()//刷新标签绑定
      }
    } catch (err) {
      console.error('获取模型列表失败:', err)
    }
  }

  // 获取所有标签
  const getAllTags = async () => {
    try {
      let reqdata = {
        data: {
          type: 'datasets',
        }
      }

      const { data: res } = await tagsApi.query(reqdata)
      if (res.resp_code == 0) {
        state.allTags = res.datas || []
        debug.log(state.allTags)
      }
    } catch (err) {
      console.error('获取标签列表失败:', err)
    }
  }
  const showAdd = () => {

    resetForm(ruleFormRef.value)
    state.params = {
      title: '新增数据集',
      dialogVisible: true,
      type: 'add',
      form: {
        name: '',
        type: '',
        description: '',
        volume: '',
        size: '',
        downloadUrl: '',
      }
    }

  }

  // 编辑数据集
  const showEdit = (item: any) => {
    resetForm(ruleFormRef.value)
    state.params = {
      title: '编辑数据集',
      dialogVisible: true,
      type: 'edit',
      form: { ...item }
    }
  }

  // 删除数据集
  const handleDelete = async (item: any) => {
    // 声明提到 try 外：无论成功 / 失败 / 取消，finally 都能关掉遮罩，避免永久转圈
    let loading: ReturnType<typeof ElLoading.service> | null = null
    try {
      await ElMessageBox.confirm(
        `确定要删除数据集 "${item.name}" 吗？`,
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

      const { data: res } = await datasetsMngApi.delete(reqdata)
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

  // 处理标签变更
  const { refreshTagBind } = useTagBinding(() => state.dataList, { type: 'datasets' })
  // 过滤后的模型列表
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
          });
          let reqdata = null;

          if (state.params.type == 'add') {
            reqdata = {
              data: { ...state.params.form }
            };
            const { data: res } = await datasetsMngApi.create(reqdata)
            if (res.resp_code == 0) {
              state.params.dialogVisible = false;
              getDataList()
              ElMessage.success('保存成功');
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

            const { data: res } = await datasetsMngApi.update(reqdata)
            if (res.resp_code == 0) {
              state.params.dialogVisible = false;
              getDataList()
              ElMessage.success('保存成功');
            } else {
              ElMessage.error('保存失败，请稍后重试')
            }
          }
        } catch (err) {
          debug.error('保存数据集失败', err)
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

  const handleDownload = async (item: any) => {
    previewItem.value = item
    previewVisible.value = true
  }

  const closePreview = () => {
    previewItem.value = null
    previewVisible.value = false
  }

  const resetForm = (formEl: FormInstance | undefined) => {
    if (!formEl) return
    formEl.resetFields()
  }
  return {
    state,
    ruleFormRef,
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
    handleDownload,
    previewVisible,
    previewItem,
    closePreview
  }
}
