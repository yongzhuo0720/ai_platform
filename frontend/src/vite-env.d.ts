/// <reference types="vite/client" />

declare module 'element-plus/dist/locale/zh-cn.mjs' {
  const locale: Record<string, unknown>;
  export default locale;
}

interface ImportMetaEnv {
  /**
   * Dify 通用兜底配置。
   * 在 frontend/.env.local 里配置后，可覆盖后端数据库中的 Dify 配置，
   * 用于本机连不上已配置的 Dify 服务时，改用自己申请的 Dify（云端 dify.ai 或本地 docker）。
   * 三个模块未单独配置时都回退到这一组。
   */
  readonly VITE_DIFY_API_URL?: string;
  readonly VITE_DIFY_API_KEY?: string;

  /** 问答聊天对话模块（chat-messages / conversations 等） */
  readonly VITE_DIFY_CHAT_URL?: string;
  readonly VITE_DIFY_CHAT_KEY?: string;

  /** 知识库对话 / 工作流模块 */
  readonly VITE_DIFY_KB_CHAT_URL?: string;
  readonly VITE_DIFY_KB_CHAT_KEY?: string;

  /** 知识库文件模块（datasets / documents / segments，需 dataset 级密钥） */
  readonly VITE_DIFY_KB_FILE_URL?: string;
  readonly VITE_DIFY_KB_FILE_KEY?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
