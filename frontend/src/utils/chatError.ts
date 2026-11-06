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
 * 从 "请求失败 (429): {"code":...,"message":...}" 这类错误文本里取出 JSON 的 message。
 * 兼容尚未经过 request.ts 清洗的调用方。
 */
function extractServerMessage(text: string): string | null {
  const start = text.indexOf('{')
  if (start === -1) {
    return null
  }
  try {
    const parsed = JSON.parse(text.slice(start))
    return parsed && typeof parsed.message === 'string' && parsed.message.trim()
      ? parsed.message.trim()
      : null
  } catch {
    return null
  }
}

/** 已被 request.ts 清洗过的纯文案直接展示；仍带 HTTP 前缀的交给兜底文案 */
function plainMessage(text: string): string | null {
  const trimmed = text.trim()
  if (!trimmed || trimmed.includes('请求失败 (') || trimmed.includes('文件上传失败 (')) {
    return null
  }
  return trimmed
}

const REQUEST_ID_SUFFIX = /\s*（请求 ID：([^）]*)）\s*$/

/** 分类前先剥掉请求 ID 后缀：UUID 里可能出现 400/500 这类数字，不能参与状态码判断 */
function stripRequestId(text: string): string {
  return text.replace(REQUEST_ID_SUFFIX, '').trim()
}

/** 从已清洗文案或原始 JSON 错误体里取请求 ID */
function extractRequestId(text: string): string {
  const suffix = text.match(REQUEST_ID_SUFFIX)
  if (suffix?.[1]) {
    return suffix[1]
  }
  const start = text.indexOf('{')
  if (start === -1) {
    return ''
  }
  try {
    const parsed = JSON.parse(text.slice(start))
    if (typeof parsed?.requestId === 'string') {
      return parsed.requestId
    }
    return typeof parsed?.error?.request_id === 'string' ? parsed.error.request_id : ''
  } catch {
    return ''
  }
}

/**
 * 解析对话流式请求中产生的错误，返回用户友好的提示
 * @param error - fetch/SSE 抛出的 Error 对象
 */
export function parseChatError(error: Error): ParsedChatError {
  const rawText = error.message || String(error)
  const parsed = classifyChatError(stripRequestId(rawText))
  const requestId = extractRequestId(rawText)
  if (!requestId || parsed.message.includes('（请求 ID：')) {
    return parsed
  }
  // 兜底文案同样带上请求 ID，用户报障时能直接定位到这次调用
  return { ...parsed, message: `${parsed.message}（请求 ID：${requestId}）` }
}

function classifyChatError(errorText: string): ParsedChatError {
  const serverMessage = extractServerMessage(errorText)

  /*
   * 幂等冲突：上一次相同请求已被网关接受，这次没有产生任何效果。
   * 重试只会继续撞同一个键，必须等用户确认后作为新操作重新发起。
   */
  if (errorText.includes('idempotency_conflict') || errorText.includes('请勿重复提交')) {
    return {
      message: serverMessage || plainMessage(errorText)
        || '相同请求已提交，请勿重复提交。',
      retryable: false,
      suggestSwitchModel: false,
      suggestCheckConfig: false,
    }
  }

  /*
   * 幂等服务不可用：占位失败，网关拒绝执行请求（没有计费也没有建数据）。
   * 这种情况可以稍后重试。
   */
  if (errorText.includes('idempotency_unavailable') || errorText.includes('幂等服务暂不可用')) {
    return {
      message: serverMessage || plainMessage(errorText)
        || '幂等服务暂不可用，请稍后重试。',
      retryable: true,
      suggestSwitchModel: false,
      suggestCheckConfig: false,
    }
  }

  /*
   * 网关并发闸门：整个服务的在途请求已满，请求没有发到上游。
   * 这不是「我的额度用完」，等几秒后重试是有效的。
   */
  if (errorText.includes('gateway_busy') || errorText.includes('AI 请求过多')) {
    return {
      message: serverMessage || plainMessage(errorText) || '当前 AI 请求较多，请稍后重试。',
      retryable: true,
      suggestSwitchModel: false,
      suggestCheckConfig: false,
    }
  }

  /*
   * 网关总时长护栏：回答生成超时被主动断开。
   * 已经收到的部分内容仍保留在界面上，用户可以重新发起。
   */
  if (errorText.includes('stream_timeout') || errorText.includes('超过总时长')) {
    return {
      message: serverMessage || plainMessage(errorText) || '回答生成时间过长，已中断，请重新发送。',
      retryable: true,
      suggestSwitchModel: false,
      suggestCheckConfig: false,
    }
  }

  /*
   * 上游熔断：网关已经确认同一上游连续失败，冷却期内不再发请求。
   * 请求没有到达上游，等冷却结束重试是安全的。
   */
  if (errorText.includes('circuit_open') || errorText.includes('AI 服务连续失败')) {
    return {
      message: serverMessage || plainMessage(errorText) || 'AI 服务暂时不可用，请稍后重试。',
      retryable: true,
      suggestSwitchModel: false,
      suggestCheckConfig: false,
    }
  }

  /*
   * 每日配额用完：这是「今天不能再用了」，重试或换模型都无济于事，
   * 必须如实展示服务端原因。
   *
   * 这段要放在下面的 model / token 分支之前判定：配额文案里含
   * "tokens" 与 "quota"，先前的顺序会把它误报成「API 密钥失效」。
   */
  if (errorText.includes('quota_exceeded') || errorText.includes('额度已用完')) {
    return {
      message: serverMessage || plainMessage(errorText)
        || '今日 AI 额度已用完，请联系管理员调整配额，或明天再试。',
      retryable: false,
      suggestSwitchModel: false,
      suggestCheckConfig: false,
    }
  }

  // 每分钟限流：稍后确实可以重试
  if (errorText.includes('rate_limited') || errorText.includes('请求过于频繁')) {
    return {
      message: serverMessage || plainMessage(errorText) || '请求过于频繁，请稍等片刻后再试。',
      retryable: true,
      suggestSwitchModel: false,
      suggestCheckConfig: false,
    }
  }

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
