import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import 'element-plus/dist/index.css'
import './style.css'
import './styles/markdown.css'
import './styles/element-override.css'

import { useThemeStore } from './stores/theme'
import { installCodeCopy } from './utils/markdown'
import { installPreloadRecovery } from './utils/preloadRecovery'

// 代码块复制按钮的点击委托：全局一次，聊天页与知识库聊天的 v-html 均生效
installCodeCopy()

// 发布新版本后，旧页面里懒加载路由指向的旧 chunk 会 404；重载一次换新清单
installPreloadRecovery()
import { ensureLoaded } from './stores/difyConfig'

// 只注册模板里没有直接 import 的图标：
// - data/*.ts 用字符串名引用，靠 <component :is> 解析
// - 部分页面在模板里直接用图标、由组合式函数返回值带进作用域
// 其余图标都由组件自行 import。此前的 import * 会把 293 个图标全部打进首屏。
// 新增图标优先在组件里 import；确需全局时加到下面的 globalIcons。
import {
  ArrowLeft, Avatar, Box, ChatDotRound, CirclePlusFilled, Collection, Cpu,
  DataLine, Delete, Document, Download, Edit, Grid, OfficeBuilding, Plus,
  PriceTag, Promotion, Refresh, Search, Tools, Upload, User, View,
} from '@element-plus/icons-vue'

const globalIcons = {
  ArrowLeft, Avatar, Box, ChatDotRound, CirclePlusFilled, Collection, Cpu,
  DataLine, Delete, Document, Download, Edit, Grid, OfficeBuilding, Plus,
  PriceTag, Promotion, Refresh, Search, Tools, Upload, User, View,
}

// 预加载 Dify 配置。
// 这里刻意不 await：应用启动不该被这个请求阻塞（各 api 层已有 ensureLoaded 门禁兜底）。
// 但必须接住 rejection —— 否则接口挂掉时会产生 unhandled promise rejection。
ensureLoaded().catch((err) => {
  console.error('[difyConfig] 预加载失败，AI 功能可能不可用:', err)
})

const app = createApp(App).use(router).use(createPinia())
useThemeStore().apply()
for (const [key, component] of Object.entries(globalIcons)) {
  app.component(key, component)
}
app.mount('#app')
// PWA：注册极简 Service Worker（仅满足可安装性；生产 https/localhost 环境）
if (import.meta.env.PROD && "serviceWorker" in navigator) {
  navigator.serviceWorker.register("./sw.js").catch(() => undefined)
}
