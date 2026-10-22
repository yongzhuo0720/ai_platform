import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
export default defineConfig({
  base:"./",
  // 产物目录统一为 dist，Nginx 部署时 root 指向该目录即可
  build: {
    outDir: 'dist',
  },
  plugins: [
    vue(),
   AutoImport({
      imports: ['vue', 'vue-router', 'vuex'], // 自动引入vue、vue-router、vuex
      resolvers: [ElementPlusResolver()],
    }),
    Components({
      resolvers: [ElementPlusResolver()],
    })
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    host: '0.0.0.0',
    proxy: {
      /**
       * Dify。默认指向云端；用自建 Dify（docker）时改成对应地址。
       *
       * 注意：前端实际请求的 Dify 地址由 VITE_DIFY_API_URL 决定（见 .env.local）。
       * 只有那里填相对路径（如 /v1）时才会走本代理；填绝对地址
       * （如 https://api.dify.ai/v1）则是浏览器直连，不经过这里。
       */
      '/v1': {
        target: 'https://api.dify.ai',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/v1/, '/v1'),
        secure: false
      },
      /**
       * 智能体助手（Qwen Paw）—— 上游服务已下线。
       * 该模块计划改造为接入自己的 Dify Agent 应用，改造完成前此规则保持注释；
       * 改造时把 target 换成 Dify 地址，并去掉 rewrite 里的 /qwen 前缀即可。
       */
      // '/qwen': {
      //   target: 'http://127.0.0.1:15088',
      //   changeOrigin: true,
      //   rewrite: (path) => path.replace(/^\/qwen/, ''),
      //   secure: false
      // },
      '/ai': {
        target: 'http://127.0.0.1:8507/ai',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/ai/, ''),
        secure: false
      }
    }
  }
})
