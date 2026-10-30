import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
import Components from "unplugin-vue-components/vite";
import { ElementPlusResolver } from "unplugin-vue-components/resolvers";

/**
 * 组件级测试配置。
 *
 * <p>与 vitest.config.ts 分开：逻辑层单测刻意不加载 Vue 插件、并把 element-plus
 * 指向轻量桩，追求启动快；组件测试则需要编译 SFC、加载真实 Element Plus。
 * 两套用例靠文件名区分（*.component.spec.ts），互不进入对方的 include。</p>
 *
 * <p>这里不引入 importStyle —— 测试不断言样式，省掉 CSS 编译与注入。</p>
 */
export default defineConfig({
  plugins: [
    vue(),
    Components({
      dts: false,
      resolvers: [ElementPlusResolver({ importStyle: false })],
    }),
  ],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  test: {
    environment: "jsdom",
    include: ["src/**/*.component.spec.ts"],
    clearMocks: true,
    restoreMocks: true,
    server: {
      deps: {
        /**
         * element-plus 的表单校验 import 的是 async-validator 的 CJS 入口。
         * Vitest 默认把 node_modules 外部化交给 Node ESM 加载，默认导入
         * 拿到的是 { default: Schema } 而非构造函数，被 FormItem 的 catch
         * 吞掉后表现为每个字段校验静默失败、el-form.validate 恒为 true。
         * 由 Vite 内联转换后套用 CJS 默认导出互操作，校验行为与浏览器一致。
         */
        inline: ["element-plus"],
      },
    },
  },
});
