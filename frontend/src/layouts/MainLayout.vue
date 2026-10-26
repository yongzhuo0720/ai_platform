<script setup lang="ts">
import { type FormInstance, type FormRules } from "element-plus";
import { ElMenu, ElSubMenu, ElMenuItem, ElIcon } from "element-plus";
import {
  Brush,
  Sunny,
  Moon,
  Lock,
  SwitchButton,
  Menu,
  Expand,
  Fold,
  Setting,
} from "@element-plus/icons-vue";
import { userApi } from "@/api/user";
import { roleApi } from "@/api/role";
import { navigationSections } from "@/data/platform";
import { useThemeStore } from "@/stores/theme";
import Cookies from "js-cookie";
import { SUPER_ADMIN_RID } from '@/config/constants'

const themeStore = useThemeStore();

const route = useRoute();
const router = useRouter();
const activeRouteName = computed(() =>
  String(route.meta.activeNavRouteName ?? route.name ?? ""),
);

const state = reactive({
  uname: Cookies.get("uname") || "",
  rid: parseInt(Cookies.get("rid") || "0"),
  viewjson: [] as any[],
  menuSections: [] as any[],
});

/** 超管才显示「管理后台」入口，避免普通用户看到点进去被弹回登录页 */
const isAdminUser = computed(() => state.rid === SUPER_ADMIN_RID);

const goAdminPortal = () => {
  showAccountMenu.value = false;
  router.push("/admin");
};

const isSubmenuItemActive = (item: any) => {
  const currentRouteName = String(route.name ?? "");
  const currentPath = route.path;
  return currentRouteName === item.routeName || currentPath === item.path;
};

const pageTitle = computed(() => String(route.meta.title ?? "智汇平台"));
const pageSubtitle = computed(() =>
  String(route.meta.group ?? "企业级 AI 能力门户"),
);

// 账户菜单
const showAccountMenu = ref(false);
const accountMenuRef = ref<HTMLElement | null>(null);

// 修改密码弹框
const passwordDialogVisible = ref(false);
const passwordFormRef = ref<FormInstance>();
const passwordForm = reactive({
  newPassword: "",
  confirmPassword: "",
});

const passwordRules: FormRules = {
  newPassword: [
    { required: true, message: "请输入新密码", trigger: "blur" },
    { min: 6, message: "密码长度不能少于6位", trigger: "blur" },
  ],
  confirmPassword: [
    { required: true, message: "请确认密码", trigger: "blur" },
    {
      validator: (_rule: any, value: string, callback: Function) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error("两次输入的密码不一致"));
        } else {
          callback();
        }
      },
      trigger: "blur",
    },
  ],
};

const handleChangePassword = () => {
  showAccountMenu.value = false;
  passwordDialogVisible.value = true;
  passwordForm.newPassword = "";
  passwordForm.confirmPassword = "";
};

const handlePasswordSubmit = async () => {
  if (!passwordFormRef.value) return;

  await passwordFormRef.value.validate(async (valid) => {
    if (valid) {
      try {
        const uid = Cookies.get("uid");
        if (!uid) {
          ElMessage.error("获取用户信息失败");
          return;
        }

        const reqdata = {
          bean: {
            upwd: passwordForm.newPassword,
          },
          where: {
            uid: uid,
          },
        };

        const { data: res } = await userApi.update(reqdata);

        if (res.resp_code === 0) {
          ElMessage.success("密码修改成功，请重新登录");
          passwordDialogVisible.value = false;
          handleLogout();
        } else {
          ElMessage.error(res.resp_msg || "密码修改失败");
        }
      } catch (error) {
        ElMessage.error("密码修改失败");
      }
    }
  });
};

const handlePasswordCancel = () => {
  passwordDialogVisible.value = false;
  passwordFormRef.value?.resetFields();
};

const handleLogout = () => {
  showAccountMenu.value = false;
  // 清除当前用户的聊天本地状态，避免切换账号后仍看到上一个用户的记录
  const uid = Cookies.get("uid");
  if (uid) {
    localStorage.removeItem(`chat-hidden-assistants-${uid}`);
    localStorage.removeItem(`chat-active-assistant-${uid}`);
    localStorage.removeItem(`chat-active-model-${uid}`);
  }
  Cookies.remove("uname");
  Cookies.remove("rid");
  Cookies.remove("uaccess");
  Cookies.remove("loginToken");
  Cookies.remove("uid");
  // 直接刷新到首页，由路由守卫根据登录状态重定向到登录页，同时清空应用内存状态
  window.location.href = "./";
};

const handleClickOutside = (event: MouseEvent) => {
  if (
    accountMenuRef.value &&
    !accountMenuRef.value.contains(event.target as Node)
  ) {
    showAccountMenu.value = false;
  }
};

const getRoleMenu = async () => {
  const reqdata = {
    bean: {
      iid: state.rid,
    },
    pageRequest: {
      pageNum: 1,
      pageSize: 1,
    },
  };
  const { data: res } = await roleApi.getDataList(reqdata);
  if (res.resp_code === 0) {
    state.viewjson = JSON.parse(res.datas.content[0].viewjson || "[]");
    state.menuSections.forEach((section) => {
      section.items = section.items.filter((item: any) =>
        state.viewjson.includes(item.routeName),
      );
    });
    for (let i = 0; i < state.menuSections.length; i++) {
      const section = state.menuSections[i];
      if (section.items.length === 0) {
        state.menuSections.splice(i, 1);
        i--;
      }
    }
  }
};

// 菜单折叠状态：默认折叠
const isCollapsed = ref(false);
const mobileMenuOpen = ref(false);
const toggleCollapse = () => {
  isCollapsed.value = !isCollapsed.value;
};

const handleMenuClick = (path: string) => {
  mobileMenuOpen.value = false;
  router.push(path);
};

onMounted(() => {
  state.menuSections = JSON.parse(JSON.stringify(navigationSections));
  document.addEventListener("click", handleClickOutside);
  if (state.rid !== SUPER_ADMIN_RID) {
    getRoleMenu();
  }
});

onBeforeUnmount(() => {
  document.removeEventListener("click", handleClickOutside);
});
</script>

<template>
  <div class="shell" :class="{ 'is-collapsed': isCollapsed, 'is-mobile-open': mobileMenuOpen }" @keydown.esc="mobileMenuOpen = false">
    <button v-if="mobileMenuOpen" class="mobile-menu-backdrop" aria-label="关闭菜单" @click="mobileMenuOpen = false" />
    <aside class="shell-sidebar">
      <div class="brand-box">
        <div class="brand-mark">
          <img
            src="@/assets/images/brand-logo.svg"
            alt="logo"
            class="logo-image"
          />
        </div>
        <div v-show="!isCollapsed" class="brand-copy">
          <span>智汇平台</span>
          <span>NexusAI</span>
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
          <template
            v-for="section in state.menuSections"
            :key="section.id"
          >
            <el-sub-menu
              v-if="section.collapsible && section.items && section.items.length > 1"
              :index="section.id"
              :popper-class="'main-menu-popper'"
            >
              <template #title>
                <el-icon><component :is="section.icon" /></el-icon>
                <span>{{ section.title }}</span>
              </template>
              <el-menu-item
                v-for="item in section.items"
                :key="item.routeName"
                :index="item.routeName"
                :class="{ 'is-sub-active': isSubmenuItemActive(item) }"
                @click="handleMenuClick(item.path)"
              >
                <span>{{ item.title }}</span>
              </el-menu-item>
            </el-sub-menu>

            <el-menu-item
              v-else-if="section.items && section.items.length"
              :index="section.items[0].routeName"
              :class="{ 'is-sub-active': isSubmenuItemActive(section.items[0]) }"
              :title="section.items[0].title"
              @click="handleMenuClick(section.items[0].path)"
            >
              <el-icon><component :is="section.icon" /></el-icon>
              <span>{{ section.items[0].title }}</span>
            </el-menu-item>
          </template>
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
          <span v-show="!isCollapsed">{{ isCollapsed ? "展开" : "收起" }}</span>
        </button>
      </div>

      <div ref="accountMenuRef" class="account-box-wrapper">
        <button
          type="button"
          class="account-box"
          @click="showAccountMenu = !showAccountMenu"
        >
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
          <button
            v-if="isAdminUser"
            type="button"
            class="account-dropdown-item"
            @click="goAdminPortal"
          >
            <Setting class="account-dropdown-icon" />
            <span>管理后台</span>
          </button>
          <button
            type="button"
            class="account-dropdown-item"
            @click="handleChangePassword"
          >
            <Lock class="account-dropdown-icon" />
            <span>修改密码</span>
          </button>
          <button
            type="button"
            class="account-dropdown-item"
            @click="handleLogout"
          >
            <SwitchButton class="account-dropdown-icon" />
            <span>退出系统</span>
          </button>
        </div>
      </div>
    </aside>

    <div class="shell-main">
      <header class="shell-header">
        <div class="header-left">
          <button
            type="button"
            class="header-btn header-mobile-toggle"
            aria-label="菜单"
            :aria-expanded="mobileMenuOpen"
            @click="mobileMenuOpen = !mobileMenuOpen"
          >
            <Menu />
          </button>
          <div class="header-title">
            <h1>{{ pageTitle }}</h1>
            <span>{{ pageSubtitle }}</span>
          </div>
        </div>

        <div class="header-actions">
          <div class="header-user">
            <div class="header-user-avatar">{{ state.uname.charAt(0) }}</div>
            <span>{{ state.uname }}</span>
          </div>
        </div>
      </header>

      <main class="shell-content">
        <RouterView v-slot="{ Component }" class="page-content">
          <component :is="Component" :key="route.name" />
        </RouterView>
      </main>
    </div>

    <!-- 修改密码弹框 -->
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

<style scoped>
.shell-content {
  width: 100%;
  height: 100%;
  position: relative;
}
.page-content {
  width: 100%;
  height: 100%;
  position: relative;
}
.page-fade-enter-active,
.page-fade-leave-active {
  transition: opacity 0.15s ease;
}

.page-fade-enter-from,
.page-fade-leave-to {
  opacity: 0;
}
</style>
