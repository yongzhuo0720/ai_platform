<script setup lang="ts">
import { ElMenu, ElMenuItem, ElIcon } from 'element-plus'
import {
  Brush,
  Sunny,
  Moon,
  Lock,
  SwitchButton,
  Expand,
  Fold,
} from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { userApi } from '@/api/user'
import { adminMenus } from '@/data/adminPlatform'
import { useThemeStore } from '@/stores/theme'
import Cookies from 'js-cookie'
import { clearAdminSession } from '@/utils/session'

const themeStore = useThemeStore()

const route = useRoute()
const router = useRouter()
const activeRouteName = computed(() => String(route.name ?? ''))

// 菜单折叠状态：默认折叠
const isCollapsed = ref(true)
const toggleCollapse = () => {
  isCollapsed.value = !isCollapsed.value
}

const handleMenuClick = (path: string) => {
  router.push(path)
}

const state = reactive({
  uname: Cookies.get('admin_uname') || '',
  rid: parseInt(Cookies.get('admin_rid') || '0'),
})

const showAccountMenu = ref(false)
const accountMenuRef = ref<HTMLElement | null>()

const passwordDialogVisible = ref(false)
const passwordFormRef = ref<FormInstance>()
const passwordForm = reactive({
  newPassword: '',
  confirmPassword: '',
})

const passwordRules: FormRules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于6位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请确认新密码', trigger: 'blur' },
    {
      validator: (_rule: any, value: string, callback: Function) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

const handleChangePassword = () => {
  showAccountMenu.value = false
  passwordDialogVisible.value = true
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
}

const handlePasswordSubmit = async () => {
  if (!passwordFormRef.value) return
  await passwordFormRef.value.validate(async (valid) => {
    if (valid) {
      try {
        const uid = Cookies.get('admin_uid')
        if (!uid) {
          ElMessage.error('获取用户信息失败')
          return
        }
        const reqdata = {
          bean: { upwd: passwordForm.newPassword },
          where: { uid },
        }
        const { data: res } = await userApi.update(reqdata)
        if (res.resp_code === 0) {
          ElMessage.success('密码修改成功，请重新登录')
          passwordDialogVisible.value = false
          handleLogout()
        } else {
          ElMessage.error(res.resp_msg || '密码修改失败')
        }
      } catch {
        ElMessage.error('密码修改失败')
      }
    }
  })
}

const handlePasswordCancel = () => {
  passwordDialogVisible.value = false
  passwordFormRef.value?.resetFields()
}

const handleLogout = () => {
  showAccountMenu.value = false
  // 2026-10-29：改用统一实现，行为不变（这里原本就是唯一完整的那份）
  clearAdminSession()
  // 直接刷新到首页，由路由守卫根据登录状态重定向到登录页，同时清空应用内存状态
  window.location.href = './'
}

const handleClickOutside = (event: MouseEvent) => {
  if (accountMenuRef.value && !accountMenuRef.value.contains(event.target as Node)) {
    showAccountMenu.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleClickOutside)
})
</script>

<template>
  <div class="shell" :class="{ 'is-collapsed': isCollapsed }">
    <aside class="shell-sidebar">
      <div class="brand-box">
        <div class="brand-mark">
          <img src="@/assets/images/brand-logo.svg" alt="logo"  class="logo-image"/>
        </div>
        <div v-show="!isCollapsed" class="brand-copy">
          <span>智汇平台</span>
          <span>系统管理后台</span>
        </div>
      </div>

      <nav class="shell-nav">
        <el-menu
          :default-active="activeRouteName"
          :collapse="isCollapsed"
          :collapse-transition="false"
          :popper-class="'main-menu-popper'"
          class="main-menu"
        >
          <el-menu-item
            v-for="menu in adminMenus"
            :key="menu.routeName"
            :index="menu.routeName"
            :title="menu.title"
            @click="handleMenuClick(menu.path)"
          >
            <el-icon><component :is="menu.icon" /></el-icon>
            <span>{{ menu.title }}</span>
          </el-menu-item>
        </el-menu>
      </nav>

      <div class="sidebar-footer">
        <button
          type="button"
          class="collapse-toggle"
          :title="isCollapsed ? '展开菜单' : '折叠菜单'"
          @click="toggleCollapse"
        >
          <el-icon><component :is="isCollapsed ? Expand : Fold" /></el-icon>
          <span v-show="!isCollapsed">{{ isCollapsed ? '展开' : '收起' }}</span>
        </button>
      </div>

      <div ref="accountMenuRef" class="account-box-wrapper">
        <button type="button" class="account-box" @click="showAccountMenu = !showAccountMenu">
          <div class="account-avatar">{{ state.uname.charAt(0) }}</div>
          <div v-show="!isCollapsed" class="account-info">
            <strong>{{ state.uname }}</strong>
          </div>
        </button>

        <div v-if="showAccountMenu" class="account-dropdown">
          <div class="account-dropdown-item theme-switcher">
            <Brush class="account-dropdown-icon" />
            <span>深浅模式</span>
            <div class="theme-options">
              <button
                type="button"
                class="theme-btn"
                :class="{ 'is-active': themeStore.theme === 'light' }"
                title="浅色模式"
                @click="themeStore.setTheme('light')"
              >
                <Sunny />
              </button>
              <button
                type="button"
                class="theme-btn"
                :class="{ 'is-active': themeStore.theme === 'dark' }"
                title="深色模式"
                @click="themeStore.setTheme('dark')"
              >
                <Moon />
              </button>
            </div>
          </div>
          <button type="button" class="account-dropdown-item" @click="handleChangePassword">
            <Lock class="account-dropdown-icon" />
            <span>修改密码</span>
          </button>
          <button type="button" class="account-dropdown-item" @click="handleLogout">
            <SwitchButton class="account-dropdown-icon" />
            <span>退出系统</span>
          </button>
        </div>
      </div>
    </aside>

    <div class="shell-main">
      <main class="shell-content">
        <RouterView v-slot="{ Component }">
          <component :is="Component" :key="route.name" />
        </RouterView>
      </main>
    </div>

    <el-dialog
      v-model="passwordDialogVisible"
      title="修改密码"
      width="400px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="passwordFormRef"
        :model="passwordForm"
        :rules="passwordRules"
        label-width="100px"
      >
        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="passwordForm.newPassword"
            type="password"
            placeholder="请输入新密码"
            show-password
          />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="passwordForm.confirmPassword"
            type="password"
            placeholder="请再次输入新密码"
            show-password
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="handlePasswordCancel">取消</el-button>
          <el-button type="primary" @click="handlePasswordSubmit">确认</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>
