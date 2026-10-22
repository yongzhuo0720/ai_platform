/**
 * 会话来源追踪工具
 *
 * 由于 Dify 后端不存储未在 App 输入变量中定义的字段，
 * 无法通过 inputs.source 区分会话来源。
 * 因此使用 localStorage 在客户端追踪知识库中心创建的会话 ID。
 *
 * 规则：
 * - 知识库中心创建的会话：记录在 KB_CONV_IDS 集合中
 * - 问答交互引擎创建的会话：不记录（默认归属）
 * - 旧会话（无记录）：默认归属问答交互引擎
 */

const KB_CONV_IDS_KEY = 'kb-conversation-ids'

function getKbConvIds(): string[] {
  try {
    const raw = localStorage.getItem(KB_CONV_IDS_KEY)
    return raw ? JSON.parse(raw) : []
  } catch {
    return []
  }
}

function setKbConvIds(ids: string[]) {
  try {
    localStorage.setItem(KB_CONV_IDS_KEY, JSON.stringify(ids))
  } catch {
    // 忽略存储满等异常
  }
}

/** 将指定会话标记为知识库中心的会话 */
export function markKbConversation(conversationId: string) {
  const ids = getKbConvIds()
  if (!ids.includes(conversationId)) {
    ids.push(conversationId)
    setKbConvIds(ids)
  }
}

/** 从知识库中心会话集合中移除指定会话（删除时清理） */
export function unmarkKbConversation(conversationId: string) {
  const ids = getKbConvIds().filter(id => id !== conversationId)
  setKbConvIds(ids)
}

/** 判断指定会话是否为知识库中心的会话 */
export function isKbConversation(conversationId: string): boolean {
  return getKbConvIds().includes(conversationId)
}
