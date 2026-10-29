import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Lock, Star } from '@element-plus/icons-vue'
import Cookies from 'js-cookie'
import { isSuperAdmin } from '@/config/constants'
import { userApi } from '@/api/user'
import { roleApi } from '@/api/role'
import { ADMIN_ACCESS_KEY } from '@/data/adminPlatform'
import { ensureLoaded } from '@/stores/difyConfig'
import { clearAdminSession } from '@/utils/session'

export function useIndex() {
  const router = useRouter()
  const route = useRoute()
  const loading = ref(false)
  const isAdmin = ref(route.path === '/admin/login')
  const isAnimating = ref(false)

  const form = reactive({
    uaccess: Cookies.get('uaccess') || '',
    upwd: Cookies.get('upwd') || '',
    keepUser: true,
  })

  const switchMode = (admin: boolean) => {
    if (isAnimating.value || admin === isAdmin.value) return
    isAnimating.value = true
    isAdmin.value = admin
    form.uaccess = ''
    form.upwd = ''
    router.replace(admin ? '/admin/login' : '/login')
    setTimeout(() => {
      isAnimating.value = false
    }, 750)
  }

  const validateForm = (): boolean => {
    if (!form.uaccess || form.uaccess.trim() === '') {
      ElMessage.warning('请输入用户名')
      return false
    }
    if (!form.upwd || form.upwd === '') {
      ElMessage.warning('请输入密码')
      return false
    }
    return true
  }

  // 2026-10-29：改用统一实现。原来这里漏删了 admin_access，
  // 而路由守卫正是靠它判断能否进入后台，残留会让守卫误判。
  const clearAdminCookies = () => {
    clearAdminSession()
  }

  /** 统一请求包装：失败时结束 loading 并返回 null，错误提示由拦截器统一处理。 */
  const request = async (promise: Promise<any>): Promise<any | null> => {
    try {
      return (await promise).data
    } catch {
      loading.value = false
      return null
    }
  }

  const handleLogin = async () => {
    if (!validateForm()) return
    loading.value = true

    const res = await request(userApi.logintest(form))
    if (!res || res.resp_code != 0) {
      loading.value = false
      return
    }

    if (isAdmin.value) {
      Cookies.set('admin_loginToken', res.datas?.token, { path: '/' })
      const req = { bean: { uaccess: form.uaccess } }
      const userRes = await request(userApi.getUserList(req))
      loading.value = false
      if (!userRes || userRes.resp_code != 0) return

      const users = userRes.datas?.content ?? []
      if (users.length === 0) {
        ElMessage.error('用户不存在')
        return
      }

      const user = users[0]
      Cookies.set('admin_rid', user.rid, { path: '/' })
      Cookies.set('admin_uaccess', user.uaccess, { path: '/' })
      Cookies.set('admin_uname', user.uname, { path: '/' })
      Cookies.set('admin_uid', user.uid, { path: '/' })

      if (isSuperAdmin(user.rid)) {
        Cookies.set('admin_access', '1', { path: '/' })
        ensureLoaded()
        router.push('/admin/users')
        return
      }

      const roleRes = await request(roleApi.getDataList({
        bean: { iid: user.rid },
        pageRequest: { pageNum: 1, pageSize: 1 },
      }))
      const roles = roleRes?.resp_code == 0 ? (roleRes.datas?.content ?? []) : []
      let viewjson: string[] = []
      if (roles.length > 0) {
        try {
          viewjson = JSON.parse(roles[0].viewjson || '[]')
        } catch {
          viewjson = []
        }
      }

      if (viewjson.includes(ADMIN_ACCESS_KEY)) {
        Cookies.set('admin_access', '1', { path: '/' })
        ensureLoaded()
        router.push('/admin/users')
      } else {
        ElMessage.error('暂无权限')
        clearAdminCookies()
      }
    } else {
      Cookies.set('loginToken', res.datas?.token, { path: '/' })
      const req = { bean: { uaccess: form.uaccess } }
      const userRes = await request(userApi.getUserList(req))
      loading.value = false
      if (!userRes || userRes.resp_code != 0) return

      const users = userRes.datas?.content ?? []
      if (users.length === 0) {
        ElMessage.error('用户不存在')
        return
      }

      const user = users[0]
      Cookies.set('rid', user.rid, { path: '/' })
      Cookies.set('uaccess', user.uaccess, { path: '/' })
      Cookies.set('uname', user.uname, { path: '/' })
      Cookies.set('uid', user.uid, { path: '/' })
      ensureLoaded()
      router.push('/chat')
    }
  }

  return { Lock, Star, loading, isAdmin, isAnimating, form, handleLogin, switchMode }
}
