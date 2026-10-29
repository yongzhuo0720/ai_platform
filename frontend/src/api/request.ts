/**
 * Dify API 请求拦截器
 * 统一处理请求头、错误处理等
 */

import { getChatConfig, ensureLoaded, gatewayAuthHeaders } from '@/stores/difyConfig'
import { debug } from '@/utils/debug'

export interface RequestConfig {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';
  headers?: Record<string, string>;
  body?: unknown;
  params?: Record<string, string | number | boolean | undefined>;
  /**
   * 外部传入的取消信号。
   * 2026-10-29 新增：此前 request() 完全无法取消，组件卸载后请求仍在飞行，
   * 回调会去写已经销毁的响应式状态。传入 signal 后调用方可主动中止。
   */
  signal?: AbortSignal;
  /**
   * 超时时间（毫秒），默认 30000。
   * 2026-10-29 新增：此前 request() 没有超时，网络悬挂时 Promise 会永久 pending，
   * 页面一直卡在 loading，且没有任何报错。
   */
  timeoutMs?: number;
}

/** 非流式请求的默认超时（与 streamingRequest 的首字节超时保持一致） */
const DEFAULT_REQUEST_TIMEOUT_MS = 30000;

/**
 * 获取默认请求头（包含 Authorization）
 */
export const getDefaultHeaders = (isJson = true): Record<string, string> => {
  const { apiKey } = getChatConfig()
  const headers: Record<string, string> = {
    'Authorization': `Bearer ${apiKey}`,
    ...gatewayAuthHeaders(),
  };
  if (isJson) {
    headers['Content-Type'] = 'application/json';
  }
  return headers;
};

/**
 * Dify 文件上传响应结构
 * @description 上传文件到 Dify 后返回的文件元数据
 */
export interface DifyFileUploadResponse {
  id: string;
  name: string;
  size: number;
  mime_type: string;
}

/**
 * 上传文件到 Dify
 * @param file - 要上传的文件对象
 * @returns Promise<DifyFileUploadResponse> - 包含文件ID、名称、大小、MIME类型的响应
 * @description
 * 此函数用于将文件上传到 Dify 平台，上传后的文件可以附加到聊天消息中。
 * Dify 会返回文件的唯一标识符（ID），用于后续消息发送时引用该文件。
 *
 * 使用场景：
 * - 用户拖拽文件到聊天窗口时调用
 * - 上传后的文件 ID 会附加到消息的 files 字段中
 *
 * @example
 * ```ts
 * const fileInput = document.querySelector('input[type="file"]');
 * if (fileInput.files[0]) {
 *   const result = await uploadFileToDify(fileInput.files[0]);
 *   debug.log(result.id); // 文件ID，用于发送消息
 * }
 * ```
 */
export const uploadFileToDify = async (file: File): Promise<DifyFileUploadResponse> => {
  const formData = new FormData();
  formData.append('file', file);

  const { apiUrl, apiKey } = getChatConfig()
  // 直连模式 apiUrl 以 /v1 结尾，网关模式以 /ai/api/aiGateway/chat 结尾，
  // 统一拼 /files/upload 即可同时适配。
  const uploadUrl = `${apiUrl}/files/upload`;
  debug.log('[文件上传] 开始上传文件:', file.name, 'MIME:', file.type, 'URL:', uploadUrl);

  const response = await fetch(uploadUrl, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${apiKey}`,
      ...gatewayAuthHeaders(),
    },
    body: formData,
  });

  debug.log('[文件上传] 响应状态:', response.status, response.statusText);

  if (!response.ok) {
    const errorText = await response.text();
    console.error('[文件上传] 上传失败:', errorText);
    throw new Error(`文件上传失败 (${response.status}): ${errorText}`);
  }

  const result = await response.json();
  debug.log('[文件上传] 上传成功，返回数据:', result);

  if (!result.id) {
    console.warn('[文件上传] 警告: 返回数据中没有 id 字段，返回值:', result);
  }

  return result;
};

/**
 * 构建 URL（处理查询参数）
 */
export const buildUrl = (
  baseUrl: string,
  path: string,
  params?: Record<string, string | number | boolean | undefined>
): string => {
  const url = `${baseUrl}${path}`;

  if (!params) return url;

  const searchParams = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null) {
      searchParams.append(key, String(value));
    }
  });

  const queryString = searchParams.toString();
  return queryString ? `${url}?${queryString}` : url;
};

/**
 * 统一请求拦截器
 * @param url 请求地址
 * @param config 请求配置
 * @returns Promise<T>
 */
export const request = async <T>(
  url: string,
  config: RequestConfig = {}
): Promise<T> => {
  // 配置门禁：先等 Dify 配置就绪，再构建请求头。
  // 与 streamingRequest 同样的问题——不等待的话会发出空 Bearer 的请求。
  await ensureLoaded();
  const { apiUrl, apiKey } = getChatConfig();
  if (!apiUrl || !apiKey) {
    throw new Error('Dify 配置未加载：请检查后端「Dify 配置」是否已填写，或本地 .env.local 是否配置');
  }

  const { method = 'GET', headers = {}, body, params, signal, timeoutMs } = config;

  // 合并默认请求头和自定义请求头
  const defaultHeaders = getDefaultHeaders(body !== undefined);
  const mergedHeaders = { ...defaultHeaders, ...headers };

  // 构建完整 URL（处理查询参数）
  const fullUrl = params ? buildUrl('', url, params) : url;

  // 2026-10-29：补超时与取消。
  // 自己建一个 controller 负责超时；如果调用方也传了 signal，
  // 就在它 abort 时连带把内部的 controller 也 abort 掉
  // （AbortSignal.any 兼容性还不够，这里手动桥接）。
  const controller = new AbortController();
  const timeout = timeoutMs ?? DEFAULT_REQUEST_TIMEOUT_MS;
  const timer = setTimeout(() => controller.abort(), timeout);
  let onOuterAbort: (() => void) | undefined;
  if (signal) {
    if (signal.aborted) {
      controller.abort();
    } else {
      onOuterAbort = () => controller.abort();
      signal.addEventListener('abort', onOuterAbort);
    }
  }

  // 构建 fetch 配置
  const fetchConfig: RequestInit = {
    method,
    headers: mergedHeaders,
    signal: controller.signal,
  };

  if (body !== undefined) {
    fetchConfig.body = typeof body === 'string' ? body : JSON.stringify(body);
  }

  // 发送请求
  let response: Response;
  try {
    response = await fetch(fullUrl, fetchConfig);
  } catch (e) {
    // 超时或外部取消时，fetch 会以 AbortError 抛出；
    // 区分两者，避免把「用户主动取消」报成「请求超时」
    if (controller.signal.aborted && signal?.aborted) {
      throw new Error('请求已取消');
    }
    if (controller.signal.aborted) {
      throw new Error(`请求超时：${timeout} 毫秒内未收到响应`);
    }
    throw e;
  } finally {
    clearTimeout(timer);
    if (signal && onOuterAbort) {
      signal.removeEventListener('abort', onOuterAbort);
    }
  }

  // 统一错误处理
  if (!response.ok) {
    const errorMessage = await response.text().catch(() => '未知错误');
    if (response.status === 401 || response.status === 403) {
      // 这里只抛明确错误，不做自动跳转：
      // request() 被聊天、知识库等多处复用，贸然 redirect 会打断正常流程，
      // 由调用方决定是提示还是跳登录页（axios.js 那边已经有 401 跳转逻辑）。
      throw new Error(`登录状态已失效，请重新登录 (${response.status})`);
    }
    throw new Error(`请求失败 (${response.status}): ${errorMessage || response.statusText}`);
  }

  // 处理空响应（补括号：原写法 || 与 && 混用，语义虽碰巧正确但极易被误改）
  const contentType = response.headers.get('content-type');
  if (response.status === 204 || (response.status === 200 && !contentType)) {
    return {} as T;
  }
  if (contentType && contentType.includes('application/json')) {
    return response.json() as Promise<T>;
  }

  const text = await response.text();
  return (text || {}) as T;
};

/**
 * GET 请求快捷方法
 */
export const get = <T>(url: string, params?: Record<string, string | number | boolean | undefined>, headers?: Record<string, string>): Promise<T> => {
  return request<T>(url, { method: 'GET', params, headers });
};

/**
 * POST 请求快捷方法
 */
export const post = <T>(url: string, body?: unknown, headers?: Record<string, string>): Promise<T> => {
  return request<T>(url, { method: 'POST', body, headers });
};

/**
 * PUT 请求快捷方法
 */
export const put = <T>(url: string, body?: unknown, headers?: Record<string, string>): Promise<T> => {
  return request<T>(url, { method: 'PUT', body, headers });
};

/**
 * DELETE 请求快捷方法
 */
export const del = <T>(url: string, body?: unknown, headers?: Record<string, string>): Promise<T> => {
  return request<T>(url, { method: 'DELETE', body, headers });
};

/**
 * 知识库检索结果
 */
export interface RetrieverResource {
  dataset_id: string;
  dataset_name: string;
  document_id: string;
  document_name: string;
  segment_id: string;
  score: number;
  content: string;
}

/**
 * 工作流节点类型映射为中文名称
 * 用于在工作流执行过程中显示节点名称
 */
const nodeTypeMap: Record<string, string> = {
  'relevant-search': '检索知识库',
  'retrieve': '检索',
  'embedding': '嵌入',
  'llm': '生成回答',
  'question-classifier': '问题分类',
  'summarize': '摘要',
  'template-transform': '模板转换',
  'http': 'HTTP 请求',
  'variable-aggregator': '变量聚合',
  'variable-assigner': '变量赋值',
  'iterator': '迭代器',
  'list-operator': '列表操作',
  'if-else': '条件判断',
  'variable-catcher': '变量捕获',
  'loop': '循环',
  'code': '代码执行',
  'tool': '工具调用',
  'ai-text-to-speech': '文本转语音',
  'speech-to-text': '语音转文本',
  'image': '图片生成',
};

/**
 * 根据节点类型获取中文名称
 * @param nodeType - 节点类型（如 'llm', 'relevant-search' 等）
 * @returns 中文名称，如果未找到则返回原类型并去掉连字符
 */
const getNodeName = (nodeType: string): string => {
  return nodeTypeMap[nodeType] || nodeType.replace(/-/g, '');
};

/**
 * 流式请求配置选项
 * @param onChunk - 收到文本片段时的回调函数，用于实时更新 UI
 * @param onComplete - 流式结束时的回调函数，传入 conversation_id
 * @param onError - 发生错误时的回调函数
 * @param onRetrieverResources - 知识库检索资源回调（仅在 message_end 或 workflow_finished 事件中触发）
 * @param onThinking - 工作流节点执行时的思考内容回调，用于显示节点执行进度
 * @param headers - 自定义请求头（如需自定义 Authorization）
 */
export interface StreamingOptions {
  onChunk: (text: string) => void;
  onComplete?: (conversationId?: string) => void;
  onError?: (error: Error) => void;
  onRetrieverResources?: (resources: RetrieverResource[]) => void;
  onThinking?: (thinking: string) => void;
  /** 消息 ID 回调，在 message_end / workflow_finished 事件中触发，用于后续调用推荐消息接口 */
  onMessageId?: (messageId: string) => void;
  headers?: Record<string, string>;
}

/**
 * Dify SSE 事件类型说明：
 * - workflow_started: 工作流开始执行
 * - node_started: 某个节点开始执行
 * - node_finished: 某个节点执行完成，包含节点的输出结果
 * - text_chunk: 流式文本片段（用于某些工作流类型）
 * - message: 聊天消息事件（用于对话类应用）
 * - message_end: 消息结束，包含会话 ID 和检索资源
 * - workflow_finished: 工作流执行完成，包含最终输出结果
 * - ping: 心跳保活事件（应忽略）
 */

/**
 * 发送流式 POST 请求（SSE 支持）
 * 用于 Dify 流式对话场景，支持普通对话和工作流两种模式
 *
 * 工作流模式事件流程：
 * 1. workflow_started - 工作流启动
 * 2. node_started / node_finished - 按顺序执行各个节点
 * 3. workflow_finished - 工作流完成，包含最终结果
 *
 * @param url - 请求地址（如 /v1/workflows/run 或 /v1/chat-messages）
 * @param body - 请求体（包含 inputs、response_mode、user 等）
 * @param options - 流式配置选项
 * @returns 取消请求的函数，调用后可中止请求
 */
/**
 * 从一段模型输出中剥离 <think> 思考块。
 *
 * 结束标记有两种，必须都兼容，否则会漏：
 *   1) 标准推理模型输出：    <think>...</think>
 *   2) Dify 对推理内容的包装：<think><!--dify-deepseek-reasoning-->思考-->
 *      —— 注意注释头 <!--...--> 本身也含 -->，所以必须先剥掉注释头，
 *         再往后找真正的结束标记，否则会把注释头的结尾误判成思考块结束。
 *
 * @param raw - 模型输出的原始文本
 * @returns thinking 思考内容；answer 剥离后的正文；closed 是否已读到结束标记
 *          （false 表示思考块尚未闭合，需要继续拼接后续分片）
 */
export const splitThinking = (
  raw: string
): { thinking: string; answer: string; closed: boolean } => {
  const OPEN = "<think>";
  const openIdx = raw.indexOf(OPEN);
  if (openIdx === -1) {
    // 没有思考块，整段都是正文
    return { thinking: "", answer: raw, closed: true };
  }

  // 去掉 <think> 开头，再去掉 Dify 的推理注释头（如 <!--dify-deepseek-reasoning-->）
  const rest = raw
    .slice(openIdx + OPEN.length)
    .replace(/^\s*<!--[^>]*-->\s*/, "");

  /** 两种结束标记，取最先出现的那个 */
  const CLOSES = ["</think>", "-->"];
  let closeIdx = -1;
  let closeLen = 0;
  for (const close of CLOSES) {
    const idx = rest.indexOf(close);
    if (idx !== -1 && (closeIdx === -1 || idx < closeIdx)) {
      closeIdx = idx;
      closeLen = close.length;
    }
  }

  if (closeIdx === -1) {
    // 思考块还没闭合，把已有内容先当作思考内容
    return { thinking: rest, answer: "", closed: false };
  }

  return {
    thinking: rest.slice(0, closeIdx).trim(),
    answer: rest.slice(closeIdx + closeLen),
    closed: true,
  };
};

export const streamingRequest = (
  url: string,
  body: unknown,
  options: StreamingOptions
): (() => void) => {
  debug.log('[streamingRequest called]', url, body);
  const controller = new AbortController();
  const { onChunk, onComplete, onError, onRetrieverResources, onThinking, onMessageId, headers } = options;

  // 判断是否为工作流模式
  const isWorkflowMode = url.includes('/workflows/run');
  // 标志位：工作流模式下避免 node_finished 和 workflow_finished 重复触发 onChunk
  let workflowChunkTriggered = false;
  // 深度思考缓冲区：用于暂存分片发送的<think>...<\/think>内容
  let thinkingBuffer = '';
  // 防止 onComplete 被 SSE 事件和流结束重复触发
  let completeCalled = false;
  // 已触发错误回调：之后不再触发 onComplete，避免与 onError 状态冲突
  let errorCalled = false;
  // 跨 chunk 保留的事件类型（SSE 规范：event: 行可能早于 data: 行到达不同 TCP chunk）
  let eventType = '';
  // SSE 超时控制（30s 无首字节则中止，避免代理悬挂）
  let firstByteTimer: ReturnType<typeof setTimeout> | null = null;
  let idleTimer: ReturnType<typeof setTimeout> | null = null;
  const clearStreamTimers = () => {
    if (firstByteTimer) {
      clearTimeout(firstByteTimer);
      firstByteTimer = null;
    }
    if (idleTimer) {
      clearTimeout(idleTimer);
      idleTimer = null;
    }
  };
  const resetIdleTimer = () => {
    if (idleTimer) clearTimeout(idleTimer);
    idleTimer = setTimeout(() => {
      controller.abort();
      safeError(new Error('响应流空闲超时：90 秒内无新数据'));
    }, 90000);
  };
  const safeComplete = (conversationId?: string) => {
    if (completeCalled || errorCalled) return;
    completeCalled = true;
    clearStreamTimers();
    onComplete?.(conversationId);
  };
  const safeError = (err: Error) => {
    if (errorCalled || completeCalled) return;
    errorCalled = true;
    clearStreamTimers();
    onError?.(err);
  };
  // 30 秒内未收到响应首字节则中止并报错（服务不可达时常悬挂）
  firstByteTimer = setTimeout(() => {
    controller.abort();
    safeError(new Error('请求超时：30 秒内未收到首个响应字节'));
  }, 30000);

  /**
   * 配置门禁：等 Dify 配置就绪后再发请求，并且请求头也移到里面构建。
   *
   * 修复前这里是「先构建 headers，再直接 fetch」：
   *   const defaultHeaders = getDefaultHeaders(true);
   *   const requestHeaders = headers ? { ...defaultHeaders, ...headers } : defaultHeaders;
   *   fetch(url, { ... })
   * 而 main.ts 的 ensureLoaded() 是 fire-and-forget，登录成功后 LoginView 不等待就跳转，
   * 于是用户立刻发消息时 getChatConfig() 还是空的 —— 发出空 Bearer + 相对路径的请求，
   * 打到前端自己的 origin，返回 404 HTML，报一个「请求失败 (404): <html...>」无法定位的错误。
   *
   * 现在把 headers 构建和 fetch 一起挪到 ensureLoaded() 之后，
   * 并且配置仍然为空时抛出明确错误（走链尾 catch → onError，用户能看到原因）。
   */
  ensureLoaded()
    .then(() => {
      const { apiUrl, apiKey } = getChatConfig();
      if (!apiUrl || !apiKey) {
        throw new Error('Dify 配置未加载：请检查后端「Dify 配置」是否已填写，或本地 .env.local 是否配置');
      }
      const defaultHeaders = getDefaultHeaders(true);
      return fetch(url, {
        method: 'POST',
        headers: headers ? { ...defaultHeaders, ...headers } : defaultHeaders,
        body: JSON.stringify(body),
        signal: controller.signal,
      });
    })
    .then(async (response) => {
      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`请求失败 (${response.status}): ${errorText}`);
      }

      const reader = response.body?.getReader();
      if (!reader) {
        throw new Error('无法读取响应流');
      }

      const decoder = new TextDecoder();
      let buffer = '';

      // 持续读取 SSE 流
      while (!completeCalled && !errorCalled) {
        const { done, value } = await reader.read();
        if (done) break;
        if (!value?.byteLength) continue;
        if (firstByteTimer) {
          clearTimeout(firstByteTimer);
          firstByteTimer = null;
        }

        // 每次收到数据都重置空闲超时（流停滞时触发中止，避免 UI 卡在"生成中"）
        resetIdleTimer();

        // 解码数据块并处理 SSE 格式（每行以 data: 开头，event: 行声明事件类型）
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split(/\r\n|\r|\n/);
        // 保留未完整的行到 buffer 中下次处理
        buffer = lines.pop() || '';

        // 当前 data 行所属的事件类型（由紧邻的 event: 行声明，已提升到函数作用域跨 chunk 保留）

        for (const line of lines) {
          if (errorCalled) break;
          if (line.startsWith('event: ')) {
            eventType = line.slice(7).trim();
            continue;
          }
          if (line.startsWith('data: ')) {
            const data = line.slice(6);
            // SSE 结束标记
            if (data === '[DONE]') {
              // Dify 在 error 事件后也会发送 [DONE]，此时不触发完成回调
              if (errorCalled) {
                eventType = '';
                continue;
              }
              continue;
            }
            try {
              const parsed = JSON.parse(data);
              debug.log('[Dify SSE Event]', eventType || parsed.event, parsed);

              // error 事件：模型调用失败（模型不可用/API Key 无效/配额不足/上下文超长等）时
              // Dify 返回 HTTP 200，但 SSE 流中发送 error 事件，必须显式处理并提示用户
              const isErrorEvent =
                eventType === 'error' ||
                parsed.event === 'error' ||
                (parsed.code && !parsed.event && (parsed.message || parsed.error));
              if (isErrorEvent) {
                const errMsg = parsed.message || parsed.error || '模型调用失败，请稍后重试';
                const errCode = parsed.code ? ` (${parsed.code})` : '';
                console.error('[Dify SSE error event]', parsed);
                safeError(new Error(`${errMsg}${errCode}`));
                continue;
              }

              // message 事件：对话类应用的流式输出，包含完整的answer字段
              // 注意：answer字段中可能包含<think>...<\/think>标签，需要提取并过滤
              if (parsed.event === 'message' && parsed.answer) {
                debug.log('[onChunk trigger] message event');
                // 从 message 事件捕获 message_id（用于后续推荐消息接口）
                const msgId = parsed.message_id || parsed.id;
                if (msgId) {
                  onMessageId?.(msgId);
                }
                // 思考块剥离必须走与 text_chunk 相同的缓冲逻辑：
                // <think> 标签可能被 SSE 分片从中间切开（如 "…<th" + "ink>"），
                // 逐 chunk 直接 splitThinking 会漏判开头，把思考内容和
                // </think> 字面量一起泄漏进正文。
                const answer = parsed.answer;
                if (answer.includes('<think>') || answer.includes('</think>') || thinkingBuffer.length > 0) {
                  thinkingBuffer += answer;
                  const result = splitThinking(thinkingBuffer);
                  if (result.closed) {
                    if (result.thinking) {
                      onThinking?.(result.thinking);
                    }
                    if (result.answer) {
                      onChunk(result.answer);
                    }
                    thinkingBuffer = '';
                  } else if (result.thinking) {
                    // 思考块未闭合：把已累积的思考增量推给 UI 实时展示
                    onThinking?.(result.thinking);
                  }
                } else {
                  // 正常文本，直接输出
                  onChunk(answer);
                }
              }
              // text / text_chunk 事件：工作流的流式输出事件
              // 每个chunk要么是thinking内容，要么是正常文本，不会混合
              else if ((parsed.event === 'text' || parsed.event === 'text_chunk') && parsed.data?.text) {
                debug.log('[onChunk trigger] text_chunk event');
                if (isWorkflowMode) {
                  workflowChunkTriggered = true;
                }
                const text = parsed.data.text;

                /**
                 * 进入思考块，或仍在思考块内（还没读到结束标记）：
                 * 先累积到 buffer，等闭合后再拆成「思考 + 正文」。
                 * 结束标记兼容两种：标准的 </think>，以及 Dify 推理包装的 -->。
                 */
                if (text.includes('<think>') || thinkingBuffer.length > 0) {
                  thinkingBuffer += text;
                  const result = splitThinking(thinkingBuffer);
                  if (result.closed) {
                    if (result.thinking) {
                      onThinking?.(result.thinking);
                    }
                    if (result.answer) {
                      onChunk(result.answer);
                    }
                    thinkingBuffer = '';
                  }
                } else {
                  // 正常文本，直接输出
                  onChunk(text);
                }
              }
              // assistant 事件：另一种助手消息事件格式
              // 重要：知识库对话（工作流模式）不在此处获取答案，只在 workflow_finished 获取
              else if (parsed.event === 'assistant' && parsed.answer && !isWorkflowMode) {
                debug.log('[onChunk trigger] assistant event');
                onChunk(parsed.answer);
              }
              // message_end 事件：消息结束，包含会话 ID 和检索资源
              // 通常在所有流式输出完成后触发
              else if (parsed.event === 'message_end') {
                // flush：缓冲里可能还有未闭合的思考块（流在 </think> 前被截断），
                // 不 flush 的话这段内容会永远留在 buffer 里丢掉
                if (thinkingBuffer.length > 0) {
                  const flushed = splitThinking(thinkingBuffer);
                  if (flushed.thinking) {
                    onThinking?.(flushed.thinking);
                  }
                  if (flushed.answer) {
                    onChunk(flushed.answer);
                  }
                  thinkingBuffer = '';
                }
                debug.log('[message_end full]', parsed);
                const msgId = parsed.message_id || parsed.id;
                debug.log('[message_end] captured message_id:', msgId);
                if (msgId) {
                  onMessageId?.(msgId);
                }
                if (parsed.conversation_id) {
                  safeComplete(parsed.conversation_id);
                }
                /*
                 * 检索引用（知识库命中片段）。
                 *
                 * 修复前只读 parsed.retriever_resources。但不同 Dify 版本
                 * 放的位置不一样：有的在事件根级，有的包在 metadata 里
                 * （parsed.metadata.retriever_resources）。只读一处会导致
                 * 另一种版本的引用完全不展示——用户看不到答案依据，
                 * 而这正是知识库问答最需要的能力。
                 *
                 * 三个位置都试，取到第一个非空的。
                 */
                const retrieverResources = parsed.retriever_resources
                  || parsed.metadata?.retriever_resources
                  || parsed.data?.retriever_resources;
                if (retrieverResources) {
                  onRetrieverResources?.(retrieverResources);
                }
              }
              // workflow_started 事件：工作流开始执行
              // 注意：这不是深度思考内容，只是工作流状态
              else if (parsed.event === 'workflow_started') {
                debug.log('[workflow_started full]', parsed);
              }
              // node_started 事件：工作流中的某个节点开始执行
              // 注意：这不是深度思考内容，只是工作流状态
              else if (parsed.event === 'node_started') {
                debug.log('[node_started full]', parsed);
                const nodeType = parsed.data?.node_type || parsed.node_type;
                const nodeName = getNodeName(nodeType);
                debug.log('[node_started] node_type:', nodeType, '-> mapped name:', nodeName);
              }
              // node_finished 事件：工作流中的某个节点执行完成
              // 如果是 LLM 节点，需要从 outputs.text 中提取thinking内容
              else if (parsed.event === 'node_finished') {
                debug.log('[node_finished full]', parsed);
                const nodeType = parsed.data?.node_type || parsed.node_type;
                const nodeOutputs = parsed.data?.outputs || parsed.outputs;
                debug.log('[node_finished] node_type:', nodeType, 'outputs:', nodeOutputs);
                // 如果是 LLM 节点，从 text 字段提取thinking
                if (nodeType?.includes('llm') && nodeOutputs?.text) {
                  const { thinking } = splitThinking(nodeOutputs.text);
                  if (thinking) {
                    onThinking?.(thinking);
                  }
                }
              }
              // workflow_finished 事件：工作流执行完成
              // 这是工作流的最终事件，包含完整的输出结果
              // 注意：outputs 在 parsed.data.outputs 中
              else if (parsed.event === 'workflow_finished') {
                debug.log('[workflow_finished FULL]', parsed);
                const dataOutputs = parsed.data?.outputs || parsed.outputs;
                debug.log('[workflow_finished outputs]', dataOutputs);

                /*
                 * 工作流终态判定。
                 *
                 * 修复前这里完全不看 data.status / data.error，一律走 onComplete。
                 * 于是工作流失败（模型不可用、节点超时、被中止）时，用户看到的是
                 * 一个空回答加上「生成完毕」——比直接报错更难排查。
                 *
                 * Dify 的 workflow_finished 约定：
                 *   data.status = 'succeeded' | 'failed' | 'stopped'
                 *   data.error  = 失败时的错误信息（字符串或对象）
                 * 只有明确 succeeded（或老版本不返回 status）时才允许走成功分支。
                 */
                const wfStatus = String(parsed.data?.status || parsed.status || '').toLowerCase();
                const wfError = parsed.data?.error || parsed.error;
                if (wfStatus === 'failed' || wfStatus === 'stopped' || (wfStatus === '' && wfError)) {
                  const msg = (typeof wfError === 'string' && wfError)
                    || wfError?.message
                    || (wfStatus === 'stopped' ? '工作流已停止' : '工作流执行失败');
                  debug.warn('[workflow_finished] 工作流未成功完成, status=%s, error=%o', wfStatus, wfError);
                  safeError(new Error(msg));
                  continue;
                }

                // 捕获 message_id 用于推荐消息
                // 注意：parsed.data?.id 是 workflow run ID，不能作为 message_id 回退
                const msgId = parsed.data?.message_id || parsed.message_id;
                debug.log('[workflow_finished] captured message_id:', msgId, '| data.id(workflow run):', parsed.data?.id);
                if (msgId) {
                  onMessageId?.(msgId);
                }

                // 工作流模式：如果 text_chunk 已经输出了内容，此处不再重复输出
                if (isWorkflowMode) {
                  if (!workflowChunkTriggered) {
                    let answer = dataOutputs?.answer || dataOutputs?.text || dataOutputs?.result || dataOutputs?.text_chunk || dataOutputs?.content || dataOutputs?.output || '';
                    // 从最终回答中过滤掉thinking标签
                    // （结束标记兼容标准的 </think> 与 Dify 推理包装的 -->）
                    const { answer: cleanAnswer } = splitThinking(answer);
                    answer = cleanAnswer;
                    debug.log('[onChunk trigger] workflow_finished with answer');
                    workflowChunkTriggered = true;
                    onChunk(answer);
                  }
                }

                // 处理会话 ID（如果有）
                const conversationId = parsed.data?.conversation_id || parsed.conversation_id;
                if (conversationId) {
                  debug.log('[workflow_finished] has conversation_id:', conversationId);
                  safeComplete(conversationId);
                } else {
                  debug.log('[workflow_finished] calling onComplete without conversation_id');
                  safeComplete();
                }
                // 处理检索资源（如果有）
                const retrieverResources = parsed.data?.retriever_resources || dataOutputs?.retriever_resources;
                if (retrieverResources) {
                  debug.log('[workflow_finished] has retriever_resources:', retrieverResources);
                  onRetrieverResources?.(retrieverResources);
                }
              }
            } catch {
              // 解析 SSE 数据行失败，忽略该行
            } finally {
              // event: 行只对紧邻的 data: 行有效，处理完后重置
              eventType = '';
            }
          }
        }
      }

      // 流读取完毕，调用完成回调（仅在 SSE 事件未触发时作为兜底）
      safeComplete();
    })
    .catch((error) => {
      clearStreamTimers();
      // 主动取消（AbortError）不触发错误回调；其它错误统一走 safeError（已做重复防护）
      if (error.name !== 'AbortError') {
        safeError(error instanceof Error ? error : new Error(String(error)));
      }
    });

  // 返回取消函数，供调用者中止请求
  return () => {
    controller.abort();
    clearStreamTimers();
  };
};

export default request;
