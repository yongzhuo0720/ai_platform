/**
 * AI 调用配置入口。
 *
 * 默认走「AI 网关」模式：前端只请求同源的 /ai/api/aiGateway/**，
 * 由 Spring Boot 网关在服务端拼接 Dify 地址并注入密钥。
 * 这样浏览器永远拿不到 Dify API Key，同时网关负责限流、配额与调用审计。
 *
 * 直连模式（VITE_AI_GATEWAY=0）只作为排障回退保留，且密钥一律来自后端接口。
 * 禁止再从构建期 env 读密钥：Vite 会把 VITE_* 原样内联进 dist，「.env.local 不提交」
 * 只挡住 git、挡不住构建内联——旧写法已实测把 app-/dataset- 真密钥打进产物。
 * 后端 query 本身也不下发 apiKey（只给 apiKeyConfigured），所以这条 env 兜底
 * 是该链路唯一能拿到密钥的口子，删掉不影响任何在用的功能。扫描见 npm run check:secrets。
 */

import Cookies from 'js-cookie'
import axios from '@/api/axios'
import { newIdempotencyKey, newRequestId } from '@/api/requestId'
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

export type GatewayModule = 'chat' | 'knowledgeChat' | 'file' | 'models'

let moduleStatusPromise: Promise<Set<GatewayModule> | null> | null = null

/**
 * 网关模式下各模块是否已配置。
 * <p>状态接口失败时返回 null，调用方按「未知」处理，不阻断功能。</p>
 */
export function getGatewayModuleStatus(): Promise<Set<GatewayModule> | null> {
  if (!GATEWAY_MODE) {
    return Promise.resolve(null)
  }
  if (!moduleStatusPromise) {
    moduleStatusPromise = axios
      .post('/ai/api/aiGatewayStatus/config')
      .then(({ data: res }) => {
        if (res.resp_code !== 0 || !Array.isArray(res.datas?.modules)) {
          return null
        }
        return new Set<GatewayModule>(
          res.datas.modules
            .filter((item: { configured?: boolean }) => item.configured)
            .map((item: { module: GatewayModule }) => item.module)
        )
      })
      .catch((err) => {
        debug.error('[difyConfig] 获取网关模块状态失败:', err)
        moduleStatusPromise = null
        return null
      })
  }
  return moduleStatusPromise
}

/** 模块未配置返回 false；状态未知返回 null（调用方不应因此阻断功能） */
export async function isGatewayModuleConfigured(
  module: GatewayModule
): Promise<boolean | null> {
  const status = await getGatewayModuleStatus()
  return status ? status.has(module) : null
}

/**
 * 网关请求头：鉴权 + 请求追踪 + 幂等键。
 *
 * axios 请求由拦截器统一处理，但知识库模块用了多处原生 fetch，需要手动补上。
 * 管理后台与业务页面使用不同的登录 Cookie，先取当前场景的，再兜底另一场景。
 *
 * 每个请求生成一个 `X-Request-Id`：后端会原样回显并写进审计与错误响应，
 * 用户报障时把这个 ID 给管理员，就能直接捞出整条调用链。
 *
 * 同时带上 `Idempotency-Key`。后端对 GET/HEAD/OPTIONS 直接忽略，因此这里无需区分方法；
 * 浏览器、代理层把同一个 POST 重放两次时请求头会一起重发，第二次会被网关以 409 挡住，
 * 避免对话重复计费与数据集重复创建。用户主动重发属于新意图，会拿到新键。
 */
export function gatewayRequestHeaders(): Record<string, string> {
  if (!GATEWAY_MODE) {
    return {}
  }
  const admin = window.location.hash.startsWith('#/admin')
  const token =
    Cookies.get(admin ? 'admin_loginToken' : 'loginToken') ||
    Cookies.get(admin ? 'loginToken' : 'admin_loginToken')
  const headers: Record<string, string> = {
    'X-Request-Id': newRequestId(),
    'Idempotency-Key': newIdempotencyKey(),
  }
  if (token) {
    headers['x-access-token'] = token
  }
  return headers
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
 * 直连模式：从后端数据库准备 Dify 地址与密钥。
 * 网关模式：无需任何准备，服务端才是配置的唯一来源。
 */
export async function ensureLoaded(): Promise<void> {
  if (GATEWAY_MODE) {
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
 * 直连模式：配置只来自后端。
 */
function getConfig(
  entry: { apiUrl: string; apiKey: string } | null
): { apiUrl: string; apiKey: string } {
  return entry && entry.apiUrl && entry.apiKey ? entry : { apiUrl: '', apiKey: '' }
}

/**
 * 清空直连模式的配置缓存。网关模式下无缓存，调用无副作用。
 */
export function invalidate(): void {
  loaded = false
  cache.chat = null
  cache.knowledgeChat = null
  cache.knowledgeFile = null
  // 管理员保存配置后重新探测模块状态，否则页面会继续按旧的「未配置」处理
  moduleStatusPromise = null
}

/** 问答聊天对话 配置 */
export function getChatConfig(): { apiUrl: string; apiKey: string } {
  if (GATEWAY_MODE) {
    return { apiUrl: `${GATEWAY_BASE}/chat`, apiKey: GATEWAY_KEY }
  }
  return getConfig(cache.chat)
}

/** 知识库对话/工作流 配置 */
export function getKnowledgeChatConfig(): { apiUrl: string; apiKey: string } {
  if (GATEWAY_MODE) {
    return { apiUrl: `${GATEWAY_BASE}/knowledgeChat`, apiKey: GATEWAY_KEY }
  }
  return getConfig(cache.knowledgeChat)
}

/** 知识库文件 配置 (datasets/documents/segments) */
export function getKnowledgeFileConfig(): { apiUrl: string; apiKey: string } {
  if (GATEWAY_MODE) {
    return { apiUrl: `${GATEWAY_BASE}/file`, apiKey: GATEWAY_KEY }
  }
  return getConfig(cache.knowledgeFile)
}

/**
 * 模型直连网关基址：/ai/api/aiGateway/models/{模型iid}/chat/completions。
 * 与 Dify 配置无关（服务端按 modelsconfigbean 的直连配置路由），两种模式下都可用。
 */
export function getModelsGatewayBase(): string {
  return `${GATEWAY_BASE}/models`
}
