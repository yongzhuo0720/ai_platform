/**
 * Qwen Paw Agent（智能体助手）API 封装
 * 对接 Qwen Paw 的 RESTful 接口：POST /api/console/chat（SSE 流式响应）
 *
 * 参考：QwenPaw 官方 RESTful API 教程与源码（app/routers/console.py、runtime/envelope.py）
 *
 * SSE 事件结构（每个 data: 行一个 JSON 事件）：
 * - response 事件：顶层状态（created / in_progress / completed / failed），completed 时 output 为全部消息快照
 * - message 事件：消息信封，type 区分：
 *   - "reasoning"：思考块（Thinking）
 *   - "message"：正文消息
 *   - "plugin_call" / "function_call" / "mcp_tool_call"：工具调用（执行 skill）
 *   - "plugin_call_output" / "function_call_output" / "mcp_tool_call_output"：工具输出
 * - content 事件：
 *   - type="text"：文本增量，msg_id 关联所属 message
 *   - type="data"：工具调用元数据（data 内含 call_id / name / arguments / output）
 *
 * 说明：
 * - 请求 Header 需携带 X-Agent-Id（指定 Agent）与 Authorization（Bearer Token）
 * - 多轮对话：input 中只放最新一条消息，服务端按 session_id 自动加载历史上下文
 */
import { getCurrentUserId } from "@/utils/user";
import { debug } from '@/utils/debug'
import { gatewayAuthHeaders, isGatewayMode } from "@/stores/difyConfig";
import Cookies from "js-cookie";

// ================= 智能体助手接入配置（集中在此处，便于修改） =================

/**
 * 读取本地 env 覆盖变量（与 stores/difyConfig.ts 的 VITE_DIFY_* 同一套约定）。
 *
 * 安全说明：此前这里硬编码了一个 sub=admin 的 Bearer Token，
 * `npm run build` 后可直接在 dist/assets/*.js 里 grep 出来，
 * 等于把智能体服务的管理员凭据随前端包一起公开发布。
 * 现在改为「构建期环境变量注入」，仓库内只保留占位符。
 */
const envVars = import.meta.env as unknown as Record<string, string | undefined>;
const envValue = (name: string): string => (envVars[name] ?? "").trim();

/**
 * 网关模式（默认）：请求 /ai/api/aiGateway/agent/{助手id|default}，
 * 由后端注入 Token 与 X-Agent-Id，浏览器不再需要智能体凭据。
 * 直连模式（VITE_AI_GATEWAY=0）：沿用 VITE_AGENT_BASE_URL 代理。
 */
const GATEWAY_MODE = isGatewayMode();
const GATEWAY_AGENT_BASE = "/ai/api/aiGateway/agent";
const gatewayAgentBase = (iid?: number): string =>
  `${GATEWAY_AGENT_BASE}/${iid ?? "default"}`;

/** 直连模式的请求基础路径（网关模式下不生效） */
const AGENT_BASE_URL = envValue("VITE_AGENT_BASE_URL") || "/qwen";

/** 指定调用的 Agent ID（对应接口文档 Header 中的 X-Agent-Id，目前全局唯一） */
const AGENT_ID = envValue("VITE_AGENT_ID");

/** Web 认证 Token（由部署方通过 VITE_AGENT_AUTH_TOKEN 注入，不再入库/入仓） */
const AUTH_TOKEN = envValue("VITE_AGENT_AUTH_TOKEN");

/** 调用渠道 */
const AGENT_CHANNEL = envValue("VITE_AGENT_CHANNEL") || "console";

/** 获取技能列表的斜杠命令（Qwen Paw 内置 Slash Command，兜底方案） */
const SKILLS_COMMAND = "/skills";

/**
 * 智能体接入配置（多智能体统一管理）。
 * 不同智能体服务通过不同的接口参数接入，当前支持 Qwen Paw，
 * 后续可扩展类型（如 wanwu）接入其它智能体服务。
 */
export interface AgentConfig {
  /** 智能体服务类型（qwen_paw / wanwu 等） */
  type?: string;
  /** 智能体 ID（Header X-Agent-Id） */
  agentId: string;
  /** 后端助手记录 id；网关模式用它选择要使用的助手 */
  iid?: number;
  /** 接口基础路径（网关模式为 /ai/api/aiGateway/agent/{id}，直连模式为 /qwen） */
  baseUrl: string;
  /** 认证 Token（Authorization Bearer，仅直连模式使用） */
  authToken: string;
  /** 调用渠道 */
  channel: string;
}

/** 默认配置：内置 Qwen Paw Agent（作为兜底） */
const DEFAULT_AGENT_CONFIG: AgentConfig = {
  type: "qwen_paw",
  agentId: AGENT_ID,
  baseUrl: GATEWAY_MODE ? gatewayAgentBase() : AGENT_BASE_URL,
  authToken: AUTH_TOKEN,
  channel: AGENT_CHANNEL,
};

/** 当前生效的智能体配置（由视图层在切换助手时更新） */
let activeAgentConfig: AgentConfig = { ...DEFAULT_AGENT_CONFIG };

/** 设置当前使用的智能体配置（切换助手时调用） */
export const setActiveAgentConfig = (config: Partial<AgentConfig> | null): void => {
  if (!config) {
    activeAgentConfig = { ...DEFAULT_AGENT_CONFIG };
    return;
  }
  // 过滤掉值为 undefined 的字段，避免其覆盖默认配置（例如内置助手未提供 agentId/authToken）
  const overrides = Object.fromEntries(
    Object.entries(config).filter(([, value]) => value !== undefined)
  ) as Partial<AgentConfig>;
  activeAgentConfig = {
    ...DEFAULT_AGENT_CONFIG,
    ...overrides,
  };
  if (GATEWAY_MODE) {
    // 网关模式只认助手 id：旧配置里没有 iid 时自动回落到内置助手
    activeAgentConfig.baseUrl = gatewayAgentBase(activeAgentConfig.iid);
  }
};

/** 获取当前智能体配置 */
export const getActiveAgentConfig = (): AgentConfig => {
  return { ...activeAgentConfig };
};

/**
 * 生成 Qwen Paw 的 user_id
 * 规则：角色ID_用户ID（如 3_5），按登录用户的角色区分会话
 * 说明：X-Agent-Id 目前全局唯一（dev-test），会话归属通过 user_id 区分
 */
export const getAgentUserId = (): string => {
  const uid = getCurrentUserId();
  const rid = Cookies.get("rid") || "0";
  return `${rid}_${uid}`;
};

// ================= 请求体类型 =================

export interface AgentChatBody {
  input: Array<{
    role: "user";
    content: Array<AgentContentPart>;
  }>;
  session_id?: string;
  user_id?: string;
  channel: string;
}

/** 消息内容片段：文本或上传的文件引用 */
export type AgentContentPart =
  | {
      type: "text";
      text: string;
    }
  | {
      type: "file";
      file_url: string;
      filename: string;
    };

/**
 * 步骤（时间线中的一项，仿 QwenPaw Desktop）
 * thinking：思考块；tool：工具调用（执行 skill）；tool-output：工具输出
 */
export interface AgentSnapshotStep {
  id: string;
  kind: "thinking" | "tool" | "tool-output";
  /** 工具名称（kind 为 tool 时） */
  name?: string;
  /** 步骤文本内容（thinking / tool-output） */
  content: string;
  /** 是否仍在运行中 */
  running: boolean;
}

/**
 * 全量快照（每次事件处理后回调）
 * 采用与 QwenPaw 官方前端一致的状态树模式：UI 按快照整体替换渲染，
 * 任何事件重复/乱序都不会造成内容重复。
 */
export interface AgentSnapshot {
  /** 正文全文 */
  body: string;
  /** 步骤时间线 */
  steps: AgentSnapshotStep[];
}

export interface AgentStreamingOptions {
  /** 每次事件处理后回调全量快照（UI 整体替换渲染，幂等防重复） */
  onSnapshot: (snapshot: AgentSnapshot) => void;
  /** 流式结束时的回调 */
  onComplete?: () => void;
  /** 出错时的回调 */
  onError?: (error: Error) => void;
}

// ================= 文件上传 =================

/** 上传接口返回结构 */
export interface AgentFileUploadResponse {
  /** 服务端存储的文件引用（本地路径），chat 时通过 file_url 引用 */
  url: string;
  file_name: string;
  size: number;
}

/** 构建带认证的请求头（chat / upload / skills 共用） */
const buildAuthHeaders = (isJson: boolean): Record<string, string> => {
  if (GATEWAY_MODE) {
    // 凭据由服务端注入；浏览器只带平台登录态，供网关鉴权与审计
    const gatewayHeaders: Record<string, string> = { ...gatewayAuthHeaders() };
    if (isJson) {
      gatewayHeaders["Content-Type"] = "application/json";
    }
    return gatewayHeaders;
  }

  const config = getActiveAgentConfig();
  // 缺少 agentId / authToken 时立刻报错，避免发出 `Bearer undefined` 之类的
  // 请求、再拿到一个和「Token 过期」难以区分的服务端响应。
  if (!config.agentId) {
    throw new Error(
      "未配置智能体 Agent ID：请在「智能体助手 → 添加/编辑助手」里填写，" +
        "或用 VITE_AGENT_ID 环境变量提供内置兜底智能体。"
    );
  }
  if (!config.authToken) {
    throw new Error(
      "未配置智能体认证 Token：请在「智能体助手 → 添加/编辑助手」里填写，" +
        "或用 VITE_AGENT_AUTH_TOKEN 环境变量提供内置兜底智能体。"
    );
  }
  const headers: Record<string, string> = {
    "X-Agent-Id": config.agentId,
    Authorization: `Bearer ${config.authToken}`,
  };
  if (isJson) {
    headers["Content-Type"] = "application/json";
  }
  return headers;
};

/**
 * 上传文件到 Qwen Paw Agent
 * 上传成功后服务端返回文件引用（url），在 chat 请求的 content 中
 * 以 {type:"file", file_url, filename} 形式引用，Agent 会自动识别文件
 * 并调用相应 skill 处理。
 * @param file - 要上传的文件对象
 * @returns 上传结果（url / file_name / size）
 */
export const uploadAgentFile = async (file: File): Promise<AgentFileUploadResponse> => {
  const formData = new FormData();
  formData.append("file", file);

  const config = getActiveAgentConfig();
  const response = await fetch(`${config.baseUrl}/api/console/upload`, {
    method: "POST",
    // 注意：不设置 Content-Type，浏览器会自动带上 multipart/form-data 边界
    headers: buildAuthHeaders(false),
    body: formData,
  });

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`文件上传失败 (${response.status}): ${errorText}`);
  }

  const result = await response.json();
  if (!result?.url) {
    throw new Error("文件上传失败：服务端未返回文件引用");
  }
  return result as AgentFileUploadResponse;
};

// ================= SSE 流式请求 =================

/** 工具调用类 message 类型 */
const TOOL_CALL_TYPES = new Set(["plugin_call", "function_call", "mcp_tool_call"]);
/** 工具输出类 message 类型 */
const TOOL_OUTPUT_TYPES = new Set([
  "plugin_call_output",
  "function_call_output",
  "mcp_tool_call_output",
]);

/** 从 message 的 content 数组中提取全部文本 */
const extractTextFromContents = (content: unknown): string => {
  if (!Array.isArray(content)) return "";
  let text = "";
  for (const item of content) {
    if (
      item &&
      typeof item === "object" &&
      (item as Record<string, unknown>).type === "text" &&
      typeof (item as Record<string, unknown>).text === "string"
    ) {
      text += (item as Record<string, unknown>).text as string;
    }
  }
  return text;
};

/** 从工具调用 message 的 content 中提取工具名（data 类型内容块） */
const extractToolNameFromContents = (content: unknown): string => {
  if (!Array.isArray(content)) return "";
  for (const item of content) {
    if (!item || typeof item !== "object") continue;
    const obj = item as Record<string, unknown>;
    if (obj.type !== "data") continue;
    const data = obj.data;
    if (data && typeof data === "object") {
      const name = (data as Record<string, unknown>).name;
      if (typeof name === "string" && name) return name;
    }
  }
  return "";
};

/**
 * 发送流式消息到 Qwen Paw Agent
 *
 * 事件解析策略（对照 QwenPaw 源码 runtime/envelope.py 的事件流）：
 * 1. message 事件（object="message"）：type="reasoning" 为思考块；工具调用/输出类型
 *    对应执行 skill 的步骤；其余为正文消息
 * 2. content 事件（object="content"）：
 *    - type="text"：增量文本，msg_id 归属思考块 / 工具输出块 / 正文
 *    - type="data"：工具调用元数据（call_id / name / arguments / output）
 * 3. response 事件（object="response"）：status="failed" 触发错误；output 为全量消息快照，
 *    做快照自适应（增量已收时只补差量）；status="completed" 触发完成
 *
 * 快照自适应：服务端部分事件携带全量文本（如块结束的 final 事件、output 快照），
 * 按块（message id）比对去重，保证回调始终为增量且不重复。
 *
 * @param body - 请求体（input/session_id/user_id/channel）
 * @param options - 流式回调配置
 * @returns 取消函数，调用后中止请求
 */
export const sendAgentMessageStreaming = (
  body: AgentChatBody,
  options: AgentStreamingOptions
): (() => void) => {
  const controller = new AbortController();
  const { onSnapshot, onComplete, onError } = options;

  // 是否打印每个 SSE 事件（调试用：排查重复/丢失问题，排查后可置为 false）
  const DEBUG_SSE_EVENTS = true;

  // ============ 状态树（与 QwenPaw 官方前端 @agentscope-ai/chat 的 Response Builder 一致） ============
  // 所有事件按 message id upsert 到 outputMessages；UI 渲染始终读取状态树快照（整体替换），
  // 事件重复 / 快照重复 / 乱序都不会造成内容重复（幂等）。
  type OutputMessage = Record<string, unknown> & {
    id: string;
    type?: string;
    status?: string;
    content?: Array<Record<string, unknown>>;
  };
  const outputMessages: OutputMessage[] = [];

  // 防止 onComplete 被 SSE 事件和流结束重复触发
  let completeCalled = false;
  // 已触发错误回调：之后不再触发 onComplete
  let errorCalled = false;
  // 是否为调用者主动取消（区别于超时 abort）
  let cancelledByUser = false;
  // 请求已开始返回（收到响应头），用于清理首字节超时计时器
  let headerReceived = false;

  const fail = (error: Error) => {
    if (errorCalled) return;
    errorCalled = true;
    onError?.(error);
  };

  const safeComplete = () => {
    if (completeCalled || errorCalled) return;
    completeCalled = true;
    onComplete?.();
  };

  /** 从状态树构建全量快照并回调（UI 整体替换渲染） */
  const emitSnapshot = () => {
    let body = "";
    const steps: AgentSnapshotStep[] = [];

    for (const msg of outputMessages) {
      const type = String(msg.type || "");
      if (type === "reasoning") {
        steps.push({
          id: msg.id,
          kind: "thinking",
          content: extractTextFromContents(msg.content),
          running: msg.status !== "completed",
        });
      } else if (TOOL_CALL_TYPES.has(type)) {
        steps.push({
          id: msg.id,
          kind: "tool",
          name: extractToolNameFromContents(msg.content) || "工具调用",
          content: "",
          running: msg.status !== "completed",
        });
      } else if (TOOL_OUTPUT_TYPES.has(type)) {
        steps.push({
          id: msg.id,
          kind: "tool-output",
          content: extractTextFromContents(msg.content),
          running: msg.status !== "completed",
        });
      } else {
        // 正文消息（type="message" 或未知类型）
        body += extractTextFromContents(msg.content);
      }
    }

    onSnapshot({ body, steps });
  };

  /**
   * response 事件：output 与状态树按 id 合并
   * 官方逻辑：incoming 无内容时保留已累积内容（partial 快照不覆盖流式数据）；
   * incoming 中没有的已有消息追加保留。
   */
  const handleResponse = (data: Record<string, unknown>) => {
    const incomingOutput = Array.isArray(data.output) ? (data.output as OutputMessage[]) : [];
    if (incomingOutput.length === 0) {
      // 心跳/空快照：保留已累积的 output
      emitSnapshot();
      return;
    }
    const existingMap = new Map(outputMessages.map((m) => [m.id, m]));
    const incomingIds = new Set(incomingOutput.map((m) => m.id));
    const merged = incomingOutput.map((incoming) => {
      const existing = existingMap.get(incoming.id);
      if (!existing) return incoming;
      const incomingHasContent = ((incoming.content || []) as unknown[]).length > 0;
      const existingHasContent = ((existing.content || []) as unknown[]).length > 0;
      if (existingHasContent && !incomingHasContent) {
        // 优先保留有内容的版本
        return { ...incoming, content: existing.content };
      }
      return incoming;
    });
    for (const existing of outputMessages) {
      if (!incomingIds.has(existing.id)) {
        merged.push(existing);
      }
    }
    outputMessages.length = 0;
    outputMessages.push(...merged);
    emitSnapshot();
  };

  /** message 信封事件：按 id upsert，信封 content 为空时不覆盖已累积内容 */
  const handleMessage = (data: OutputMessage) => {
    const idx = outputMessages.findIndex((m) => m.id === data.id);
    if (idx >= 0) {
      const existingContent = outputMessages[idx].content;
      outputMessages[idx] = { ...data };
      if (!data.content || data.content.length === 0) {
        outputMessages[idx].content = existingContent;
      }
    } else {
      outputMessages.push(data);
    }
    emitSnapshot();
  };

  /** content 事件：delta 与块内最后一项合并；非 delta（全量）替换最后一项 */
  const handleContent = (data: Record<string, unknown>) => {
    const msg = outputMessages.find((m) => m.id === data.msg_id);
    if (!msg) {
      // 官方同款处理：找不到归属消息时忽略
      return;
    }
    if (!msg.content) {
      msg.content = [];
    }
    const contentList = msg.content as Array<Record<string, unknown>>;

    if (data.delta) {
      const lastContent = contentList[contentList.length - 1];
      if (lastContent && lastContent.delta) {
        if (data.type === "text" && lastContent.type === "text") {
          // 文本增量合并
          lastContent.text = String(lastContent.text || "") + String(data.text || "");
        } else if (data.type === "data") {
          const msgType = String(msg.type || "");
          const isStreamingToolInput = [
            "plugin_call",
            "function_call",
            "mcp_tool_call",
          ].includes(msgType);
          if (isStreamingToolInput) {
            // 工具调用参数：字符串字段增量拼接（官方同款）
            const oldData = (lastContent.data || {}) as Record<string, unknown>;
            const newData = (data.data || {}) as Record<string, unknown>;
            const mergedData: Record<string, unknown> = { ...oldData };
            for (const [key, value] of Object.entries(newData)) {
              if (typeof value === "string" && typeof mergedData[key] === "string") {
                mergedData[key] = String(mergedData[key]) + value;
              } else {
                mergedData[key] = value;
              }
            }
            lastContent.data = mergedData;
          } else {
            lastContent.data = data.data;
          }
        }
      } else {
        contentList.push(data);
      }
    } else {
      // 全量快照：替换最后一项（官方同款）
      if (contentList.length > 0) {
        Object.assign(contentList[contentList.length - 1], data);
      } else {
        contentList.push(data);
      }
    }
    emitSnapshot();
  };

  // 首字节超时：服务不可达时代理可能长时间悬挂，30 秒无响应则中止并报错
  const headerTimer = window.setTimeout(() => {
    if (headerReceived) return;
    controller.abort();
  }, 30000);

  const config = getActiveAgentConfig();
  // 配置缺失时通过 onError 上报，而不是在这里同步抛出：
  // 同步抛出会让上面那个 30 秒首字节超时定时器一直挂着没人清理，
  // 且调用方拿到的是异常而非统一的错误回调。
  let requestHeaders: Record<string, string>;
  try {
    requestHeaders = buildAuthHeaders(true);
  } catch (configError) {
    window.clearTimeout(headerTimer);
    const err = configError instanceof Error ? configError : new Error(String(configError));
    onError?.(err);
    return () => controller.abort();
  }

  fetch(`${config.baseUrl}/api/console/chat`, {
    method: "POST",
    headers: requestHeaders,
    body: JSON.stringify(body),
    signal: controller.signal,
  })
    .then(async (response) => {
      headerReceived = true;
      window.clearTimeout(headerTimer);

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`请求失败 (${response.status}): ${errorText}`);
      }

      const reader = response.body?.getReader();
      if (!reader) {
        throw new Error("无法读取响应流");
      }

      const decoder = new TextDecoder();
      let buffer = "";

      // 持续读取 SSE 流
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split("\n");
        // 保留不完整的行到 buffer 中下次处理
        buffer = lines.pop() || "";

        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed.startsWith("data:")) {
            continue;
          }

          const data = trimmed.slice(5).trim();
          if (!data || data === "[DONE]") {
            continue;
          }

          try {
            const parsed = JSON.parse(data);
            if (DEBUG_SSE_EVENTS) {
              // 调试日志：排查重复/丢失问题用
              debug.log(
                "[AgentSSE]",
                parsed.object,
                parsed.type || "-",
                parsed.status || "-",
                parsed.delta ? "delta" : "-",
                parsed.msg_id ? `msg=${parsed.msg_id}` : "",
                parsed.id ? `id=${parsed.id}` : "",
                typeof parsed.text === "string" ? `text=${parsed.text.slice(0, 60)}` : "",
                parsed.data?.call_id ? `call=${parsed.data.call_id}` : "",
                parsed.data?.name ? `name=${parsed.data.name}` : ""
              );
            }
            const obj = parsed.object;

            // 失败事件
            if (obj === "response" && (parsed.status === "failed" || parsed.error)) {
              const message =
                parsed.error?.message || parsed.error || "Agent 调用失败，请稍后重试";
              fail(new Error(String(message)));
              continue;
            }

            // 事件分发到状态树（与官方前端 Builder 一致）
            if (obj === "response") {
              handleResponse(parsed);
              if (parsed.status === "completed") {
                safeComplete();
              }
            } else if (obj === "message") {
              handleMessage(parsed as Parameters<typeof handleMessage>[0]);
            } else if (obj === "content") {
              handleContent(parsed);
            }
          } catch {
            // 非 JSON 行，忽略
          }
        }
      }

      // 流读取完毕，兜底触发完成回调
      safeComplete();
    })
    .catch((error) => {
      if (error.name === "AbortError") {
        // 调用者主动取消：静默处理
        if (cancelledByUser) {
          return;
        }
        // 首字节超时中止：提示超时错误
        fail(new Error("请求超时，请检查网络后重试"));
        return;
      }
      fail(error);
    });

  // 返回取消函数，供调用者中止请求
  return () => {
    cancelledByUser = true;
    controller.abort();
  };
};

// ================= 技能列表 =================

/** GET /api/skills 返回的技能条目 */
export interface AgentSkillItem {
  name: string;
  description?: string;
  source?: string;
  emoji?: string;
  enabled?: boolean;
  channels?: string[];
  tags?: string[];
  last_updated?: string;
}

/**
 * 从 Agent 回复文本中解析技能列表（兜底方案）
 * 兼容常见格式：markdown 列表（- item / * item / 1. item）、
 * ```json 代码块（数组或 {skills: []}）
 * @param text - Agent 返回的原始文本
 * @returns 技能名列表（去重、去空）
 */
export const parseSkillListFromText = (text: string): string[] => {
  if (!text) return [];

  // 1. 优先尝试解析 JSON 代码块或纯 JSON 文本
  const jsonBlocks = text.match(/```(?:json)?\s*([\s\S]*?)```/gi);
  const jsonCandidates = jsonBlocks
    ? jsonBlocks.map((block) => block.replace(/```(?:json)?/gi, "").trim())
    : [text.trim()];

  for (const candidate of jsonCandidates) {
    try {
      const parsed = JSON.parse(candidate);
      const list = Array.isArray(parsed)
        ? parsed
        : Array.isArray(parsed?.skills)
          ? parsed.skills
          : null;
      if (list) {
        const names = list
          .map((item: unknown) => {
            if (typeof item === "string") return item;
            if (item && typeof item === "object") {
              const obj = item as Record<string, unknown>;
              return String(obj.name ?? obj.skill ?? obj.title ?? obj.command ?? "");
            }
            return "";
          })
          .filter(Boolean);
        if (names.length > 0) return Array.from(new Set(names));
      }
    } catch {
      // JSON 解析失败，继续尝试 markdown 列表解析
    }
  }

  // 2. 按行解析 markdown 列表项（- / * / • / 数字.）
  const skills: string[] = [];
  const listItemPattern = /^\s*(?:[-*•]|\d+[.)])\s+(.+)$/;
  const lines = text.split(/\r?\n/);

  for (const line of lines) {
    const match = line.match(listItemPattern);
    if (!match) continue;

    let name = match[1].trim();
    if (!name) continue;

    // 清理 markdown 标记：加粗、行内代码、链接等
    name = name
      .replace(/\*\*(.+?)\*\*/g, "$1")
      .replace(/__(.+?)__/g, "$1")
      .replace(/`(.+?)`/g, "$1")
      .replace(/\[(.+?)\]\(.+?\)/g, "$1")
      .replace(/^#+\s*/, "")
      // 去掉技能说明部分（如 "name - 描述" 只保留名字）
      .replace(/\s*[-—–:：].*$/, "")
      .trim();

    if (name && !skills.includes(name)) {
      skills.push(name);
    }
  }

  return skills;
};

/**
 * 兜底：通过向独立临时 session 发送 /skills 斜杠命令获取技能列表
 * 结果不影响当前对话的话题上下文。
 * @returns 技能名列表（获取失败时返回空数组）
 */
const fetchSkillListViaChat = async (): Promise<string[]> => {
  const sessionId = `skill-lookup-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

  return new Promise<string[]>((resolve) => {
    let fullText = "";
    let settled = false;
    let cancel: (() => void) | null = null;
    // 兜底超时：30 秒内未完成则中止（在 finish 之前声明，供 finish 清理）
    const timeoutTimer = window.setTimeout(finish, 30000);

    /** 结束收集：中止请求并解析技能列表 */
    function finish() {
      if (settled) return;
      settled = true;
      window.clearTimeout(timeoutTimer);
      try {
        cancel?.();
      } catch {
        // 忽略取消异常
      }
      resolve(parseSkillListFromText(fullText));
    }

    cancel = sendAgentMessageStreaming(
      {
        input: [
          {
            role: "user",
            content: [{ type: "text", text: SKILLS_COMMAND }],
          },
        ],
        session_id: sessionId,
        user_id: getAgentUserId(),
        channel: getActiveAgentConfig().channel,
      },
      {
        onSnapshot: (snapshot) => {
          fullText = snapshot.body;
        },
        onComplete: () => {
          finish();
        },
        onError: () => {
          finish();
        },
      }
    );
  });
};

/**
 * 获取可用技能列表
 * 优先使用官方 GET /api/skills 接口；失败时回退到发送 /skills 消息解析文本
 * @returns 技能条目列表（获取失败时返回空数组）
 */
export const fetchSkillList = async (): Promise<AgentSkillItem[]> => {
  try {
    const config = getActiveAgentConfig();
    const response = await fetch(`${config.baseUrl}/api/skills`, {
      method: "GET",
      headers: buildAuthHeaders(false),
    });
    if (!response.ok) {
      throw new Error(`请求失败 (${response.status})`);
    }
    const data = await response.json();
    if (Array.isArray(data)) {
      const list = data
        .filter((item) => item && typeof item.name === "string" && item.name)
        .map((item) => ({ ...item }) as AgentSkillItem);
      if (list.length > 0) {
        return list;
      }
    }
    throw new Error("技能列表为空");
  } catch (error) {
    console.warn("[智能体助手] GET /api/skills 失败，回退 /skills 消息方式:", error);
    const names = await fetchSkillListViaChat();
    return names.map((name) => ({ name }));
  }
};
