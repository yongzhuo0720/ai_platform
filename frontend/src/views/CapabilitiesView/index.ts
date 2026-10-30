
import { debug } from '@/utils/debug'
import { isConfirmCancelled } from '@/utils/confirm'
import { safeJsonParse } from '@/utils/json'
import { safeOpenUrl } from '@/utils/url'
import { pageDescriptions } from '@/data/platform'
import { ref, computed, nextTick, onMounted, reactive } from 'vue'
import { ElLoading, ElMessage, ElMessageBox } from 'element-plus'
import { tagsApi } from '@/api/tags'
import { useTagBinding } from '@/composables/useTagBinding'
import { capabilitiesMngApi } from '@/api/capabilitiesMng'
import { unitApi } from '@/api/unit'
import { roleApi } from '@/api/role'
import Cookies from 'js-cookie'
import { SUPER_ADMIN_RID } from '@/config/constants'
import type { ElTree, FormInstance, UploadProps, UploadUserFile } from 'element-plus'


const colorMap = ['blue', 'purple', 'cyan', 'blue']

// 能力默认封面（未上传图片时显示）
const DEFAULT_COVER = new URL('@/assets/images/ai_logo.svg', import.meta.url).href

const isImageUrl = (url: string): boolean => {
  if (!url) return false
  // base64 数据或常见图片 URL
  return /^data:image\//.test(url) || /\.(png|jpe?g|gif|webp|svg|bmp|ico)(\?|#|$)/i.test(url)
}

export function useIndex() {
  const ruleFormRef = ref<FormInstance>()
  const treeRef = ref<InstanceType<typeof ElTree>>()
  const selectedCategory = ref<string[]>([])
  const searchKeyword = ref('')
  const sortBy = ref('')

  // 弹窗中的图标上传状态（仅用于预览，保存时以 base64 存入 form.icon）
  const iconFiles = ref<UploadUserFile[]>([])
  const iconPreviewUrl = ref('')

  const state = reactive({
    dataList: [] as any[],
    allTags: [] as any[],
    params: {
      title: '新建能力',
      dialogVisible: false,
      type: 'add',
      form: null as any
    },
    rid: parseInt(Cookies.get('rid') || '0'),
    rules: {
      title: [{ required: true, message: '请输入能力名称', trigger: 'blur' }],
      description: [{ required: true, message: '请输入能力描述', trigger: 'blur' }],
      label: [{ required: true, message: '请输入能力标签', trigger: 'blur' }],
      url: [{ required: true, message: '请输入能力地址', trigger: 'blur' }],
    },
    // 管理权限弹窗
    mngDialogVisible: false,
    mngForm: {
      capability: null as any,
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

      const { data: res } = await capabilitiesMngApi.query(reqdata)
      if (res.resp_code == 0) {
        state.dataList = res.datas || []
        refreshTagBind()
      }
    } catch (err) {
      console.error('获取能力列表失败:', err)
    }
  }

  // 获取所有标签
  const getAllTags = async () => {
    try {
      let reqdata = {
        data: {
          type: 'capabilities',
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
    // 撤销上一次的 blob 预览
    if (iconPreviewUrl.value.startsWith('blob:')) {
      URL.revokeObjectURL(iconPreviewUrl.value)
    }
    state.params = {
      title: '新建能力',
      dialogVisible: true,
      type: 'add',
      form: {
        title: '',
        description: '',
        label: '',
        url: '',
        icon: '',
      }
    }
    // 重置图标上传状态
    iconFiles.value = []
    iconPreviewUrl.value = ''
  }

  // 编辑能力
  const showEdit = (item: any) => {
    resetForm(ruleFormRef.value)
    // 撤销上一次的 blob 预览
    if (iconPreviewUrl.value.startsWith('blob:')) {
      URL.revokeObjectURL(iconPreviewUrl.value)
    }
    state.params = {
      title: '编辑能力',
      dialogVisible: true,
      type: 'edit',
      form: {
        ...item,
        url: item.difyUrl || item.url || '',
        icon: item.icon || '',
      }
    }
    // 回显已有图标
    const restoredIcon = isImageUrl(item.icon) ? item.icon : ''
    iconPreviewUrl.value = restoredIcon
    iconFiles.value = restoredIcon ? [{ name: 'icon', url: restoredIcon }] : []
    state.params.form.icon = restoredIcon
  }

  /**
   * 将图片文件读取为 base64：data URL，用于本地存储与展示
   */
  const fileToBase64 = (file: File): Promise<string> => {
    return new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result || ''))
      reader.onerror = () => reject(new Error('图片读取失败'))
      reader.readAsDataURL(file)
    })
  }

  const handleIconChange: UploadProps['onChange'] = async (uploadFile, currentFiles) => {
    const latestFile = currentFiles[currentFiles.length - 1]
    if (!latestFile) {
      if (iconPreviewUrl.value.startsWith('blob:')) {
        URL.revokeObjectURL(iconPreviewUrl.value)
      }
      iconFiles.value = []
      iconPreviewUrl.value = ''
      state.params.form.icon = ''
      return
    }
    const raw = latestFile.raw || uploadFile.raw
    if (!raw || !raw.type.startsWith('image/')) {
      return
    }
    // 限制图片大小，避免 base64 体积过大影响传输与存储
    const MAX_ICON_SIZE = 2 * 1024 * 1024 // 2MB
    if (raw.size > MAX_ICON_SIZE) {
      ElMessage.warning('图片不能超过 2MB，请压缩后重试')
      return
    }
    try {
      const b64 = await fileToBase64(raw)
      // 撤销上一个 blob 预览地址，避免内存泄漏
      if (iconPreviewUrl.value.startsWith('blob:')) {
        URL.revokeObjectURL(iconPreviewUrl.value)
      }
      const blobUrl = URL.createObjectURL(raw)
      // picture-card 预览依赖 file-list 中每项的 url
      iconFiles.value = [{ name: raw.name, url: blobUrl }]
      iconPreviewUrl.value = blobUrl
      state.params.form.icon = b64
    } catch (e) {
      console.error('读取图片失败:', e)
      ElMessage.error('图片读取失败，请重新选择')
      if (iconPreviewUrl.value.startsWith('blob:')) {
        URL.revokeObjectURL(iconPreviewUrl.value)
      }
      iconFiles.value = []
      iconPreviewUrl.value = ''
      state.params.form.icon = ''
    }
  }

  const handleIconRemove: UploadProps['onRemove'] = () => {
    if (iconPreviewUrl.value.startsWith('blob:')) {
      URL.revokeObjectURL(iconPreviewUrl.value)
    }
    iconFiles.value = []
    iconPreviewUrl.value = ''
    state.params.form.icon = ''
  }

  const handleIconExceed: UploadProps['onExceed'] = () => {
    ElMessage.warning('能力图片仅支持上传 1 张图片')
  }

  /** 卡片封面：有合法图片用图片，否则显示默认封面 */
  const getCoverImage = (item: any): string => {
    if (!item) return DEFAULT_COVER
    if (isImageUrl(item.icon)) return item.icon
    return DEFAULT_COVER
  }

  /** 封面加载失败（如地址过期）：回退默认封面 */
  const handleCoverError = (event: Event) => {
    const img = event.target as HTMLImageElement
    if (img && img.src !== DEFAULT_COVER) {
      img.src = DEFAULT_COVER
    }
  }

  // 删除能力
  const handleDelete = async (item: any) => {
    // 声明提到 try 外：无论成功 / 失败 / 取消，finally 都能关掉遮罩，避免永久转圈
    let loading: ReturnType<typeof ElLoading.service> | null = null
    try {
      await ElMessageBox.confirm(
        `确定要删除能力 "${item.title}" 吗？`,
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

      const { data: res } = await capabilitiesMngApi.delete(reqdata)
      if (res.resp_code == 0) {
        ElMessage.success('删除成功')
        getDataList()
      } else {
        ElMessage.error(res.resp_msg || '删除失败')
      }
    } catch (error) {
      /*
       * 修复前这里是空 catch，注释写「用户取消删除不处理」，
       * 结果把「用户取消」和「接口真失败」一起吞了：
       * 失败时既没有提示，上面那句 loading.close() 也被跳过，
       * 页面永久卡在遮罩上转圈，只能刷新。
       */
      if (!isConfirmCancelled(error)) {
        ElMessage.error('删除失败，请重试')
      }
    } finally {
      // 成功、失败、取消，三条路径都必须关掉 loading
      loading?.close()
    }
  }

  // 处理标签变更
  const { refreshTagBind } = useTagBinding(() => state.dataList, { type: 'capabilities' })

  // 过滤后的能力列表
  const filteredDataList = computed(() => {
    let result = [...state.dataList]

    // 根据关键词搜索
    if (searchKeyword.value) {
      const keyword = searchKeyword.value.toLowerCase()
      result = result.filter(item =>
        item.title?.toLowerCase().includes(keyword) ||
        item.description?.toLowerCase().includes(keyword) ||
        item.label?.toLowerCase().includes(keyword)
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

          // 图片以 base64 随其它字段一并提交到后端 icon 字段（所有用户可见）
          const formData = {
            ...state.params.form,
            difyUrl: state.params.form.url || state.params.form.difyUrl || '',
            icon: state.params.form.icon || '',
          }

          let saveSuccess = false

          if (state.params.type == 'add') {
            reqdata = {
              data: formData
            }
            const { data: res } = await capabilitiesMngApi.create(reqdata)
            if (res.resp_code == 0) {
              saveSuccess = true
            }
          } else {
            reqdata = {
              data: formData,
              where: {
                iid: state.params.form.iid
              }
            }
            const { data: res } = await capabilitiesMngApi.update(reqdata)
            if (res.resp_code == 0) {
              saveSuccess = true
            }
          }

          if (saveSuccess) {
            state.params.dialogVisible = false
            await getDataList()
            ElMessage.success('保存成功')
          } else {
            ElMessage.error('保存失败，请稍后重试')
          }
        } catch (err) {
          debug.error('保存能力失败', err)
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

  // 判断能力是否需要单点登录（后端返回的标识字段 needSso）
  const isNeedSso = (item: any): boolean => {
    return item.needSso === true || item.needSso === 1 || item.needSso === '1' || item.needSso === 'true'
  }

  const openCapability = async (item: any) => {
    // 需要单点登录：先调用后端接口获取跳转地址，再跳转
    if (isNeedSso(item)) {
      const loading = ElLoading.service({
        lock: true,
        text: '正在获取跳转地址，请稍后',
        spinner: 'el-icon-loading',
        background: 'rgba(0, 0, 0, 0.7)'
      })
      try {

        const { data: res } = await capabilitiesMngApi.ssoJump()
        // 默认使用 item 中的 URL
        let url = item.difyUrl
        if (res?.resp_code === 0 && res?.datas) {
          if (!res.datas.hasSession && res.datas.authUrl) {
            url = res.datas.authUrl
          }
        } else {
          // 接口返回异常
          console.error('单点登录接口返回异常:', res)
          ElMessage.error('获取跳转地址失败，请重试')
          return
        }
        debug.log('url', url)
        // 检查 URL 是否有效
        if (!url) {
          console.error('未获取到有效的跳转地址')
          ElMessage.error('未获取到有效的跳转地址')
          return
        }
        safeOpenUrl(url)
      } catch (err) {
        console.error('获取单点登录跳转地址失败:', err)
        ElMessage.error('获取跳转地址失败，请重试')
      } finally {
        loading.close()
      }
      return
    }

    // 无需单点登录：同样走安全跳转，拦截 javascript:/data: 等危险协议
    const url = item.difyUrl
    if (!url) {
      return
    }
    safeOpenUrl(url)
  }

  const resetForm = (formEl: FormInstance | undefined) => {
    if (!formEl) return
    formEl.resetFields()
  }

  // 显示管理权限弹窗
  const showMng = async (item: any) => {
    state.mngForm.capability = item
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
          iid: state.mngForm.capability.iid
        }
      }
      const { data: res } = await capabilitiesMngApi.update(reqdata)
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
    colorMap,
    state,
    ruleFormRef,
    treeRef,
    searchKeyword,
    selectedCategory,
    sortBy,
    pageDescriptions,
    filteredDataList,
    getCoverImage,
    handleCoverError,
    iconFiles,
    handleIconChange,
    handleIconRemove,
    handleIconExceed,
    showAdd,
    showEdit,
    handleDelete,
    onSave,
    openCapability,
    refreshTagBind,
    getAllTags,
    showMng,
    saveMng,
    handleDeptCheckChange,
  }
}
