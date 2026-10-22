/**
 * AI 对话错误解析工具
 * 根据错误信息匹配对应的用户可见提示文案
 */

export interface ParsedChatError {
  /** 用户可见的友好提示 */
  message: string
  /** 是否建议重试 */
  retryable: boolean
  /** 是否建议更换模型 */
  suggestSwitchModel: boolean
  /** 是否建议检查配置 */
  suggestCheckConfig: boolean
}

/**
 * 解析对话流式请求中产生的错误，返回用户友好的提示
 * @param error - fetch/SSE 抛出的 Error 对象
 */
export function parseChatError(error: Error): ParsedChatError {
  const errorText = error.message || String(error)

  // 模型相关错误
  if (
    errorText.includes('model') ||
    errorText.includes('llm') ||
    errorText.includes('provider') ||
    errorText.includes('not_found') ||
    errorText.includes('invalid_model')
  ) {
    // 模型不存在或不可用
    if (
      errorText.includes('not_found') ||
      errorText.includes('not found') ||
      errorText.includes('does not exist') ||
      errorText.includes('invalid_model') ||
      errorText.includes('unsupported')
    ) {
      return {
        message: '当前选择的模型不可用，可能已被下线或暂不支持，请切换其他模型后重试。',
        retryable: false,
        suggestSwitchModel: true,
        suggestCheckConfig: false,
      }
    }
    // 模型调用超时/配额不足
    if (
      errorText.includes('quota') ||
      errorText.includes('rate') ||
      errorText.includes('limit') ||
      errorText.includes('exceeded')
    ) {
      return {
        message: '模型调用次数已达上限，请稍后重试或切换其他模型。',
        retryable: true,
        suggestSwitchModel: true,
        suggestCheckConfig: false,
      }
    }
    // 通用模型错误
    return {
      message: '模型调用异常，可能是模型配置有误或服务暂时不可用，建议切换其他模型或稍后重试。',
      retryable: true,
      suggestSwitchModel: true,
      suggestCheckConfig: false,
    }
  }

  // 认证相关错误（401, 403）
  if (
    errorText.includes('401') ||
    errorText.includes('403') ||
    errorText.includes('unauthorized') ||
    errorText.includes('forbidden') ||
    errorText.includes('auth') ||
    errorText.includes('token') ||
    errorText.includes('permission')
  ) {
    if (errorText.includes('401') || errorText.includes('unauthorized') || errorText.includes('token')) {
      return {
        message: '认证失败，API 密钥可能已过期或无效，请联系管理员检查配置。',
        retryable: false,
        suggestSwitchModel: false,
        suggestCheckConfig: true,
      }
    }
    return {
      message: '没有权限访问该资源，请检查账号权限或联系管理员。',
      retryable: false,
      suggestSwitchModel: false,
      suggestCheckConfig: true,
    }
  }

  // 请求参数错误（400）
  if (errorText.includes('400') || errorText.includes('invalid_param') || errorText.includes('bad request')) {
    return {
      message: '请求参数有误，可能是输入内容格式不正确，请检查后重试。',
      retryable: true,
      suggestSwitchModel: false,
      suggestCheckConfig: false,
    }
  }

  // 请求频率限制（429）
  if (errorText.includes('429') || errorText.includes('too many requests')) {
    return {
      message: '请求过于频繁，请稍等片刻后再试。',
      retryable: true,
      suggestSwitchModel: false,
      suggestCheckConfig: false,
    }
  }

  // 服务器错误（500, 502, 503, 504）
  if (
    errorText.includes('500') ||
    errorText.includes('502') ||
    errorText.includes('503') ||
    errorText.includes('504') ||
    errorText.includes('server error') ||
    errorText.includes('internal') ||
    errorText.includes('unavailable')
  ) {
    return {
      message: 'AI 服务暂时不可用，可能是后端服务异常，请稍后重试。',
      retryable: true,
      suggestSwitchModel: false,
      suggestCheckConfig: false,
    }
  }

  // 网络错误
  if (
    errorText.includes('Failed to fetch') ||
    errorText.includes('NetworkError') ||
    errorText.includes('timeout') ||
    errorText.includes('ECONNREFUSED') ||
    errorText.includes('ENOTFOUND')
  ) {
    return {
      message: '网络连接异常，无法连接到 AI 服务，请检查网络后重试。',
      retryable: true,
      suggestSwitchModel: false,
      suggestCheckConfig: true,
    }
  }

  // 用户主动取消
  if (errorText.includes('abort') || errorText.includes('cancel')) {
    return {
      message: '已取消当前对话。',
      retryable: false,
      suggestSwitchModel: false,
      suggestCheckConfig: false,
    }
  }

  // 兜底：未知错误
  return {
    message: '消息发送失败，请稍后重试。如问题持续出现，请尝试更换模型或联系管理员。',
    retryable: true,
    suggestSwitchModel: false,
    suggestCheckConfig: false,
  }
}
