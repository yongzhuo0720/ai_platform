import { defineConfig, devices } from "@playwright/test";

const PORT = 4317;
const baseURL = `http://127.0.0.1:${PORT}`;

/**
 * 浏览器冒烟：真实 Chromium 渲染 + 真实路由，接口层用 page.route 拦截。
 *
 * 与组件测试互补：组件测试跑在 jsdom 里，看不到自动导入、全局图标、路由守卫
 * 和真实浏览器解析 CSS 的问题；这里在真实浏览器里把页面完整启动一遍。
 *
 * 本地默认复用已安装的 Edge（无需下载浏览器）；CI 上先执行
 * `npx playwright install --with-deps chromium`。
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 45_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : [["list"]],
  use: {
    baseURL,
    trace: "retain-on-failure",
    locale: "zh-CN",
    channel: process.env.PLAYWRIGHT_CHANNEL ?? (process.env.CI ? undefined : "msedge"),
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: `npm run dev -- --port ${PORT} --strictPort`,
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
