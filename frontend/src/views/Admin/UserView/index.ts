import { ref, reactive, onMounted } from 'vue'
import type { FormInstance } from 'element-plus'
import { userApi } from '@/api/user'
import { roleApi } from '@/api/role'
import { unitApi } from '@/api/unit'
import Cookies from 'js-cookie'
import { isSuperAdmin } from '@/config/constants'
import { debug } from '@/utils/debug'
import { isConfirmCancelled } from '@/utils/confirm'

// 生成随机初始密码，避免所有账号共用一个广为人知的弱口令
const genRandomPwd = () => Math.random().toString(36).slice(-8)

export function useIndex() {
  const ruleFormRef = ref<FormInstance>()

  const state = reactive({
    tableData: [] as any[],
    roleList: [] as any[],
    roleJson: {} as Record<string, string>,
    unitList: [] as any[],
    treeProps: { label: 'unitname', children: 'childList' },
    rid: parseInt(Cookies.get('admin_rid') || '0'),
    isAdmin: isSuperAdmin(Cookies.get('admin_rid')) || Cookies.get('admin_access') === '1',

    params: {
      title: '新建',
      dialogVisible: false,
      type: 'add',
      form: null as any,
    },
    formInline: {
      uname: '',
      gid: '',
      uaccess: '',
    },
    pagination: {
      pageSize: 15,
      pageNum: 1,
      total: 0,
      order: 'createtime',
      desc: 'desc',
    },
    rules: {
      uname: [{ required: true, message: '请输入用户名', trigger: 'change' }],
      uaccess: [{ required: true, message: '请输入账号', trigger: 'change' }],
      rid: [{ required: true, message: '请选择角色', trigger: 'change' }],
      gid: [{ required: true, message: '请选择部门', trigger: 'change' }],
    },
  })

  onMounted(() => {
    getDataList()
    getRoleList()
    getUnitList()
  })

  const getDataList = async () => {
    try {
      const reqdata = { bean: state.formInline, pageRequest: state.pagination }
      const { data: res } = await userApi.getUserList(reqdata)
      if (res.resp_code == 0) {
        state.tableData = res.datas.content
        state.pagination.total = res.datas.totalSize
      } else {
        ElMessage.error('获取用户列表失败')
      }
    } catch (err) {
      debug.error('获取用户列表失败', err)
      ElMessage.error('获取用户列表失败，请稍后重试')
    }
  }

  const getRoleList = async () => {
    try {
      const reqdata = { bean: {}, pageRequest: { pageNum: 1, pageSize: 9000 } }
      const { data: res } = await roleApi.getDataList(reqdata)
      if (res.resp_code == 0) {
        // 接口返回分页结构 { content, totalSize }，roleList 取 content，与后面 reduce 保持一致
        const roles = res.datas.content || []
        state.roleList = roles
        state.roleJson = roles.reduce((prev: any, cur: any) => {
          prev[cur.iid] = cur.name
          return prev
        }, {})
      } else {
        ElMessage.error('获取角色列表失败')
      }
    } catch (err) {
      debug.error('获取角色列表失败', err)
      ElMessage.error('获取角色列表失败，请稍后重试')
    }
  }

  const getUnitList = async () => {
    try {
      const reqdata = { data: {} }
      const { data: res } = await unitApi.getDataList(reqdata)
      if (res.resp_code == 0) {
        state.unitList = res.datas
      } else {
        ElMessage.error('获取部门列表失败')
      }
    } catch (err) {
      debug.error('获取部门列表失败', err)
      ElMessage.error('获取部门列表失败，请稍后重试')
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
      title: '新建用户',
      dialogVisible: true,
      type: 'add',
      form: {
        uname: '',
        uaccess: '',
        phone: '',
        rid: '',
        gid: '',
        key1: '',
        key2: '',
        key3: '',
        enable: true,
        upwd: genRandomPwd(),
      },
    }
  }

  const showEdit = (row: any) => {
    resetForm(ruleFormRef.value)
    state.params = {
      title: '编辑用户',
      dialogVisible: true,
      type: 'edit',
      form: { ...row },
    }
  }

  const updatePwd = async (row: any) => {
    try {
      await ElMessageBox.confirm('您确定要重置密码吗?', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
      })
      // 重置为随机密码并一次性展示给管理员，不再使用固定弱口令
      const newPwd = genRandomPwd()
      const reqdata = { bean: { upwd: newPwd }, where: { uid: row.uid } }
      const { data: res } = await userApi.update(reqdata)
      if (res.resp_code == 0) {
        ElMessage.success('密码已重置，新密码：' + newPwd)
      } else {
        ElMessage.error('重置失败')
      }
    } catch (e) {
      if (!isConfirmCancelled(e)) {
        debug.error('重置密码失败', e)
        ElMessage.error('重置失败，请稍后重试')
      }
    }
  }

  const showDelete = async (row: any) => {
    try {
      await ElMessageBox.confirm('您确定要删除吗?', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
      })
      const reqdata = { bean: { uid: row.uid } }
      const { data: res } = await userApi.delete(reqdata)
      if (res.resp_code == 0) {
        getDataList()
        ElMessage.success('操作成功')
      } else {
        ElMessage.error('删除失败')
      }
    } catch (e) {
      if (!isConfirmCancelled(e)) {
        debug.error('删除用户失败', e)
        ElMessage.error('删除失败，请稍后重试')
      }
    }
  }

  const onSave = () => {
    ruleFormRef.value?.validate(async (valid) => {
      if (valid) {
        // 无论成功 / 失败 / 校验异常，finally 都关掉遮罩，避免永久转圈
        let loading: ReturnType<typeof ElLoading.service> | null = null
        try {
          loading = ElLoading.service({
            lock: true,
            text: '正在保存，请稍后',
            spinner: 'el-icon-loading',
            background: 'rgba(0, 0, 0, 0.7)',
          })
          if (state.params.type == 'add') {
            const reqdata = { bean: { ...state.params.form } }
            const { data: res } = await userApi.create(reqdata)
            if (res.resp_code == 0) {
              state.params.dialogVisible = false
              getDataList()
              // 初始密码是随机生成的，创建成功时一次性告知管理员
              ElMessage.success('用户创建成功，初始密码：' + state.params.form.upwd)
            } else {
              ElMessage.error('保存失败，请稍后重试')
            }
          } else {
            const reqdata = {
              bean: { ...state.params.form },
              where: { uid: state.params.form.uid },
            }
            delete reqdata.bean.uid
            delete reqdata.bean.uaccess
            delete reqdata.bean.upwd
            const { data: res } = await userApi.update(reqdata)
            if (res.resp_code == 0) {
              state.params.dialogVisible = false
              getDataList()
              ElMessage.success('保存成功')
            } else {
              ElMessage.error('保存失败，请稍后重试')
            }
          }
        } catch (err) {
          debug.error('保存用户失败', err)
          ElMessage.error('保存失败，请稍后重试')
        } finally {
          loading?.close()
        }
      } else {
        ElMessage({ message: '请填写完整的信息', type: 'error' })
      }
    })
  }

  const filterNodeMethod = (value: string, data: any) => {
    return data.unitname.indexOf(value) !== -1
  }

  const resetForm = (formEl: FormInstance | undefined) => {
    if (!formEl) return
    formEl.resetFields()
  }

  return {
    ruleFormRef, state, onQuery, showAdd, showEdit, showDelete,
    onSave, updatePwd, handleSizeChange, handleCurrentChange, filterNodeMethod,
  }
}
