<template>
  <div class="not-found">
    <el-result icon="warning" title="404" :sub-title="subTitle">
      <template #extra>
        <el-button type="primary" @click="goHome">回到首页</el-button>
        <el-button @click="goBack">返回上一页</el-button>
      </template>
    </el-result>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Cookies from 'js-cookie'

const route = useRoute()
const router = useRouter()

/**
 * 2026-10-29 新增。
 *
 * 修复前项目没有 404 兜底路由：访问任意不存在的路径时，
 * router 匹配不到任何记录，页面中间一片空白，控制台也没有报错，
 * 用户完全不知道发生了什么。
 *
 * 这里给出一个明确的 404 页，并按当前是否处于管理后台决定「首页」跳哪。
 */
const subTitle = computed(() => `找不到页面：${route.fullPath}`)

const goHome = () => {
  const isAdmin = window.location.hash.startsWith('#/admin')
  const hasAdminToken = Boolean(Cookies.get('admin_loginToken'))
  if (isAdmin && hasAdminToken) {
    router.replace('/admin')
    return
  }
  router.replace('/chat')
}

const goBack = () => {
  // 没有历史记录时（直接输入网址打开）回退无效，直接回首页
  if (window.history.length > 1) {
    router.back()
  } else {
    goHome()
  }
}
</script>

<style scoped>
.not-found {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  padding: 24px;
}
</style>
