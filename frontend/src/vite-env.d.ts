/// <reference types="vite/client" />

declare module 'element-plus/dist/locale/zh-cn.mjs' {
  const locale: Record<string, unknown>;
  export default locale;
}

interface ImportMetaEnv {
  /** 网关开关。留空或 1 走服务端 AI 网关；0 为浏览器直连 Dify（仅排障）。 */
  readonly VITE_AI_GATEWAY?: string;

  // 刻意不声明任何 VITE_*_KEY / TOKEN / SECRET：Vite 会把 VITE_* 原样内联进 dist，
  // 在这里声明一次就等于重新打开「密钥被打进前端产物」这条路。
  // Dify 密钥只在服务端持有；直连模式也只能从后端接口取，而该接口不下发 apiKey。
  // 兜底扫描见 frontend/scripts/check-secrets.mjs（npm run check:secrets）。
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
