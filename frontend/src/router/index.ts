import { createRouter, createWebHashHistory } from "vue-router";
import { ElMessage } from "element-plus";
import Cookies from "js-cookie";
import MainLayout from "@/layouts/MainLayout.vue";

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: "/admin/login",
      name: "admin-login",
      component: () => import("@/views/LoginView/index.vue"),
      meta: { title: "管理后台登录" },
    },
    {
      path: "/login",
      name: "login",
      component: () => import("@/views/LoginView/index.vue"),
      meta: { title: "登录" },
    },
    {
      path: "/preview",
      name: "preview",
      component: () => import("@/views/PreviewView/index.vue"),
      meta: { title: "预览" },
    },
    {
      path: "/",
      component: MainLayout,
      children: [
        { path: "", redirect: "/chat" },
        {
          path: "chat",
          name: "chat",
          component: () => import("@/views/ChatView/index.vue"),
          meta: { title: "智能问答", group: "问答交互引擎" },
        },
        {
          path: "agent-assistant",
          name: "agent-assistant",
          component: () => import("@/views/AgentAssistantView/index.vue"),
          meta: { title: "智能体助手", group: "智能体助手" },
        },
        {
          path: "capabilities",
          name: "capabilities",
          component: () => import("@/views/CapabilitiesView/index.vue"),
          meta: { title: "能力广场", group: "能力广场" },
        },
        {
          path: "applications",
          name: "applications",
          component: () => import("@/views/ApplicationsView/index.vue"),
          meta: { title: "场景应用", group: "智能开发工具" },
        },
        {
          path: "models",
          name: "models",
          component: () => import("@/views/ModelsView/index.vue"),
          meta: { title: "模型库", group: "仓库" },
        },
        {
          path: "datasets",
          name: "datasets",
          component: () => import("@/views/DatasetView/index.vue"),
          meta: { title: "数据集市场", group: "仓库" },
        },
        {
          path: "knowledge-bases",
          name: "knowledge-bases",
          component: () => import("@/views/KnowledgeBaseView/index.vue"),
          meta: { title: "知识库", group: "知识库", activeNavRouteName: "knowledge-bases" },
        },
        {
          path: "kb-search",
          name: "kb-search",
          component: () => import("@/views/KbSearchView/index.vue"),
          meta: { title: "知识检索", group: "知识库" },
        },
        {
          path: "my-usage",
          name: "my-usage",
          component: () => import("@/views/MyUsageView/index.vue"),
          meta: { title: "我的用量", group: "用量中心" },
        },
      ],
    },
    {
      path: "/admin",
      component: () => import("@/layouts/AdminLayout.vue"),
      redirect: "/admin/users",
      children: [
        {
          path: "users",
          name: "admin-users",
          component: () => import("@/views/Admin/UserView/index.vue"),
          meta: { title: "用户管理" },
        },
        {
          path: "roles",
          name: "admin-roles",
          component: () => import("@/views/Admin/RoleView/index.vue"),
          meta: { title: "角色管理" },
        },
        {
          path: "units",
          name: "admin-units",
          component: () => import("@/views/Admin/UnitView/index.vue"),
          meta: { title: "部门管理" },
        },
        {
          path: "knowledge-bases",
          name: "admin-knowledge-bases",
          component: () => import("@/views/Admin/KnowledgeBaseMngView/index.vue"),
          meta: { title: "知识库管理" },
        },
        {
          path: "dify-config",
          name: "admin-dify-config",
          component: () => import("@/views/Admin/DifyConfigView/index.vue"),
          meta: { title: "Dify配置管理" },
        },
        {
          path: "models-config",
          name: "admin-models-config",
          component: () => import("@/views/Admin/ModelsConfigView/index.vue"),
          meta: { title: "模型配置" },
        },
        {
          path: "ai-gateway",
          name: "admin-ai-gateway",
          component: () => import("@/views/Admin/AiGatewayView/index.vue"),
          meta: { title: "AI调用治理" },
        },
        {
          path: "logs",
          name: "admin-logs",
          component: () => import("@/views/Admin/LogView/index.vue"),
          meta: { title: "系统日志" },
        },
      ],
    },
  ],
});

router.beforeEach((to, _from, next) => {
  if (to.path === '/login') return next()
  if (to.path === '/admin/login') return next()

  if (to.path.startsWith('/admin')) {
    const adminToken = Cookies.get('admin_loginToken')
    if (!adminToken) return next('/admin/login')
    const adminAccess = Cookies.get('admin_access')
    if (adminAccess !== '1') {
      ElMessage.error('暂无权限访问后台管理')
      return next('/admin/login')
    }
    return next()
  }

  const loginToken = Cookies.get('loginToken')
  if (!loginToken) return next('/login')
  next()
})

export default router;