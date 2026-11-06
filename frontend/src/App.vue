<template>
  <el-config-provider :locale="zhCn">
    <router-view></router-view>
    <GlobalErrorBanner />
  </el-config-provider>
</template>
<script setup lang="ts">
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import GlobalErrorBanner from './components/GlobalErrorBanner/index.vue'
</script>