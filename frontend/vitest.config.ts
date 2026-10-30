import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vitest/config";

/**
 * 单元测试配置。
 *
 * <p>与 vite.config.ts 分开：测试只跑纯逻辑模块，不需要 Vue 插件、
 * 自动导入与样式处理，保持启动快、依赖少。</p>
 */
export default defineConfig({
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
      // 逻辑层模块只用到 element-plus 的 ElMessage，真实包会拉起整棵组件树；
      // 单测不验证 UI，统一指向轻量桩，避免无谓的转换开销与副作用。
      "element-plus": fileURLToPath(
        new URL("./src/test/element-plus-stub.ts", import.meta.url)
      ),
    },
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.spec.ts"],
    // 组件级用例走 vitest.component.config.ts（需要 Vue 插件与真实 Element Plus）
    exclude: ["**/node_modules/**", "**/dist/**", "src/**/*.component.spec.ts"],
    clearMocks: true,
    restoreMocks: true,
  },
});
