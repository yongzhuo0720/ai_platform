/**
 * AI 调用配置入口。
 *
 * 默认走「AI 网关」模式：前端只请求同源的 /ai/api/aiGateway/**，
 * 由 Spring Boot 网关在服务端拼接 Dify 地址并注入密钥。
 * 这样浏览器永远拿不到 Dify API Key，同时网关负责限流、配额与调用审计。
 *
 * 如需回退到旧的「浏览器直连 Dify」模式（例如网关尚未部署），
 * 在 .env.local 设置 VITE_AI_GATEWAY=0 即可，此时才会读取数据库配置 / 本地 env 密钥。
 */

import Cookies from 'js-cookie'
import axios from '@/api/axios'
import { debug } from '@/utils/debug'

interface DifyConfigItem {
  iid: number
  moduleName: string
  apiUrl: string
  apiKey: string
  description: string
}

// 模块名称常量，与后端 DifyConfigDataRegister 中注册的对应（仅直连模式使用）
const MODULE_CHAT = '问答聊天对话'
const MODULE_KNOWLEDGE_CHAT = '知识库对话/工作流'
const MODULE_KNOWLEDGE_FILE = '知识库文件'

const GATEWAY_BASE = '/ai/api/aiGateway'
/** 网关模式下并不存在真实密钥；占位符用于让上层「配置是否就绪」的判断保持成立 */
const GATEWAY_KEY = 'server-side'

const env = import.meta.env as unknown as Record<string, string | undefined>
const GATEWAY_MODE = (env.VITE_AI_GATEWAY ?? '1') !== '0'

interface ConfigCache {
  chat: { apiUrl: string; apiKey: string } | null
  knowledgeChat: { apiUrl: string; apiKey: string } | null
  knowledgeFile: { apiUrl: string; apiKey: string } | null
}

const cache: ConfigCache = {
  chat: null,
  knowledgeChat: null,
  knowledgeFile: null,
}

/** 当前是否走服务端 AI 网关 */
export function isGatewayMode(): boolean {
  return GATEWAY_MODE
}

/**
 * 网关模式下附加到原生 fetch 的鉴权头。
 * axios 请求由拦截器统一处理，但知识库模块用了多处原生 fetch，需要手动补上。
 * 管理后台与业务页面使用不同的登录 Cookie，先取当前场景的，再兜底另一场景。
 */
export function gatewayAuthHeaders(): Record<string, string> {
  if (!GATEWAY_MODE) {
    return {}
  }
  const admin = window.location.hash.startsWith('#/admin')
  const token =
    Cookies.get(admin ? 'admin_loginToken' : 'loginToken') ||
    Cookies.get(admin ? 'loginToken' : 'admin_loginToken')
  return token ? { 'x-access-token': token } : {}
}

/**
 * 读取一组本地 env 覆盖变量，两个都配了才生效。
 */
function localOverride(
  urlVar: string,
  keyVar: string
): { apiUrl: string; apiKey: string } | null {
  const apiUrl = (env[urlVar] ?? '').trim()
  const apiKey = (env[keyVar] ?? '').trim()
  if (!apiUrl || !apiKey) {
    return null
  }
  return { apiUrl, apiKey }
}

/** 通用兜底：VITE_DIFY_API_URL + VITE_DIFY_API_KEY */
const localFallback = localOverride('VITE_DIFY_API_URL', 'VITE_DIFY_API_KEY')

/** 直连模式下的本地 env 覆盖表（未配置则为 null，走后端数据库） */
const LOCAL: ConfigCache = {
  chat: localOverride('VITE_DIFY_CHAT_URL', 'VITE_DIFY_CHAT_KEY') ?? localFallback,
  knowledgeChat:
    localOverride('VITE_DIFY_KB_CHAT_URL', 'VITE_DIFY_KB_CHAT_KEY') ?? localFallback,
  knowledgeFile:
    localOverride('VITE_DIFY_KB_FILE_URL', 'VITE_DIFY_KB_FILE_KEY') ?? localFallback,
}

/**
 * 三个模块是否被本地 env 完整覆盖。
 * 全覆盖时无需请求后端（请求结果会被本地覆盖掉）。
 */
function hasFullLocalOverride(): boolean {
  return !!(LOCAL.chat && LOCAL.knowledgeChat && LOCAL.knowledgeFile)
}

let loaded = false
let loadingPromise: Promise<void> | null = null

async function fetchConfigs(): Promise<DifyConfigItem[]> {
  const { data: res } = await axios.post('/ai/api/difyConfigBean/query', { data: {} })
  if (res.resp_code === 0 && res.datas) {
    return res.datas as DifyConfigItem[]
  }
  return []
}

/**
 * 直连模式：从数据库 / 本地 env 准备 Dify 地址与密钥。
 * 网关模式：无需任何准备，服务端才是配置的唯一来源。
 */
export async function ensureLoaded(): Promise<void> {
  if (GATEWAY_MODE) {
    loaded = true
    return
  }
  if (hasFullLocalOverride()) {
    loaded = true
    return
  }
  if (loaded) return
  if (loadingPromise) return loadingPromise

  loadingPromise = (async () => {
    try {
      const configs = await fetchConfigs()
      for (const c of configs) {
        const entry = { apiUrl: c.apiUrl || '', apiKey: c.apiKey || '' }
        if (c.moduleName === MODULE_CHAT) {
          cache.chat = entry
        } else if (c.moduleName === MODULE_KNOWLEDGE_CHAT) {
          cache.knowledgeChat = entry
        } else if (c.moduleName === MODULE_KNOWLEDGE_FILE) {
          cache.knowledgeFile = entry
        }
      }
      /*
       * 无论后端是否返回数据，都标记为已加载，避免空数组时被反复请求。
       */
      loaded = true
    } catch (err) {
      debug.error('[difyConfig] 加载失败:', err)
    } finally {
      loadingPromise = null
    }
  })()

  return loadingPromise
}

/**
 * 直连模式：本地 env 覆盖优先，其次后端数据库缓存。
 */
function getConfig(
  key: keyof ConfigCache,
  entry: ConfigCache[keyof ConfigCache]
): { apiUrl: string; apiKey: string } {
  const local = LOCAL[key]
  if (local && local.apiUrl && local.apiKey) {
    return local
  }
  if (entry && entry.apiUrl && entry.apiKey) {
    return entry
  }
  return { apiUrl: '', apiKey: '' }
}

/**
 * 清空直连模式的配置缓存。网关模式下无缓存，调用无副作用。
 */
export function invalidate(): void {
  loaded = false
  cache.chat = null
  cache.knowledgeChat = null
  cache.knowledgeFile = null
}

/** 问答聊天对话 配置 */
export function getChatConfig(): { apiUrl: string; apiKey: string } {
  if (GATEWAY_MODE) {
    return { apiUrl: `${GATEWAY_BASE}/chat`, apiKey: GATEWAY_KEY }
  }
  return getConfig('chat', cache.chat)
}

/** 知识库对话/工作流 配置 */
export function getKnowledgeChatConfig(): { apiUrl: string; apiKey: string } {
  if (GATEWAY_MODE) {
    return { apiUrl: `${GATEWAY_BASE}/knowledgeChat`, apiKey: GATEWAY_KEY }
  }
  return getConfig('knowledgeChat', cache.knowledgeChat)
}

/** 知识库文件 配置 (datasets/documents/segments) */
export function getKnowledgeFileConfig(): { apiUrl: string; apiKey: string } {
  if (GATEWAY_MODE) {
    return { apiUrl: `${GATEWAY_BASE}/file`, apiKey: GATEWAY_KEY }
  }
  return getConfig('knowledgeFile', cache.knowledgeFile)
}
