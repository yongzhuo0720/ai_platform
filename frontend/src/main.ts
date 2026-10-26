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

// 代码块复制按钮的点击委托：全局一次，聊天页与知识库聊天的 v-html 均生效
installCodeCopy()
import { ensureLoaded } from './stores/difyConfig'

import * as ElementPlusIconsVue from '@element-plus/icons-vue'

// 预加载 Dify 配置。
// 这里刻意不 await：应用启动不该被这个请求阻塞（各 api 层已有 ensureLoaded 门禁兜底）。
// 但必须接住 rejection —— 否则接口挂掉时会产生 unhandled promise rejection。
ensureLoaded().catch((err) => {
  console.error('[difyConfig] 预加载失败，AI 功能可能不可用:', err)
})

const app = createApp(App).use(router).use(createPinia())
useThemeStore().apply()
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}
app.mount('#app')
// PWA：注册极简 Service Worker（仅满足可安装性；生产 https/localhost 环境）
if (import.meta.env.PROD && "serviceWorker" in navigator) {
  navigator.serviceWorker.register("./sw.js").catch(() => undefined)
}
