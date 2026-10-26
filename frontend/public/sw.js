/* 极简 Service Worker：只做安装壳缓存，不拦截业务请求。
 * PWA 可安装性（加桌面图标/独立窗口）由 manifest 提供；
 * 离线能力刻意不做——AI 对话依赖后端，离线壳没有意义。 */
self.addEventListener('install', (event) => {
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim())
})
