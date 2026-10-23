import { computed, ref, nextTick, onMounted, onUnmounted, watch, type ComputedRef } from "vue";
import { debug } from '@/utils/debug'
import { ElMessageBox, ElMessage } from "element-plus";
import { chatApi } from "@/api/chat";
import { assistantApi } from "@/api/assistant";
import { modelsConfigApi } from "@/api/modelsConfig";
import { streamDirectChat } from "@/api/directModel";
import { kbRagApi } from "@/api/kbRag";
import { uploadFileToDify } from "@/api/request";
import type {
  Conversation,
  ChatMessage,
  AppParametersResponse,
  NormalizedUserInputField,
  UserInputFormItem,
} from "@/types/chat";
import { getCurrentUserId } from "@/utils/user";
import { renderMarkdown, renderUserContent } from "@/utils/markdown";
import { isKbConversation } from "@/utils/conversationSource";
import { parseChatError } from "@/utils/chatError";
import { useIsAdmin } from '@/composables/useIsAdmin'
import type ChatComposer from "./ChatComposer.vue";

export type MessageRole = "user" | "assistant";

interface UploadedChatFile {
  id: string;
  name: string;
  type: string;
  size: number;
}

export interface MessageItem {
  id: string;
  role: MessageRole;
  content: string;
  thinking?: string;
  requestQueryContent?: string;
  requestDisplayContent?: string;
  requestFiles?: UploadedChatFile[];
  sourceUserMessageId?: string;
}

export interface ChatModel {
  id: string;
  name: string;
  provider: string;
  description?: string;
  builtin?: boolean;
  createdAt?: number;
  userId?: string;
  /** modelsconfigbean 主键；直连模型经网关按它路由 */
  iid?: number;
  /** 直连协议：openai = OpenAI 兼容直连，空 = 仅作为 Dify 应用变量 */
  protocol?: string;
}

export interface ChatSelectOption {
  value: string;
  label: string;
  title?: string;
  subtitle?: string;
}

export interface UseChatReturn {
  activeModelId: typeof activeModelId;
  activeAssistantId: typeof activeAssistantId;
  historyCollapsed: typeof historyCollapsed;
  inputValue: typeof inputValue;
  messages: typeof messages;
  composerRef: typeof composerRef;
  chatMessagesRef: typeof chatMessagesRef;
  conversations: typeof conversations;
  filteredConversations: ComputedRef<Conversation[]>;
  activeConversationId: typeof activeConversationId;
  loading: typeof loading;
  thinking: typeof thinking;
  difyModels: typeof difyModels;
  modelsLoading: typeof modelsLoading;
  parametersLoading: typeof parametersLoading;
  appParameters: typeof appParameters;
  chatInputFields: typeof composerInputFields;
  chatSelectOptionsMap: typeof composerSelectOptionsMap;
  modelOptions: typeof modelOptions;
  suggestedQuestions: typeof suggestedQuestions;
  postAnswerSuggestions: typeof postAnswerSuggestions;
  lastMessageId: typeof lastMessageId;
  userInput: typeof userInput;
  allowFileUpload: typeof allowFileUpload;
  /** 当前是否处于直连模型模式（OpenAI 兼容协议，经网关路由） */
  isDirectMode: ComputedRef<boolean>;
  /** 直连模式选中的知识库名（空 = 不引用知识库，服务端 RAG） */
  directKbName: typeof directKbName;
  /** 直连模式可选的知识库名列表 */
  directKbOptions: typeof directKbOptions;
  canSubmit: ComputedRef<boolean>;
  activeModel: ComputedRef<ChatModel>;
  activeAssistant: ComputedRef<ChatModel>;
  /** 助手问候语中显示的助手名称 */
  assistantGreetingName: ComputedRef<string>;
  currentUserId: typeof currentUserId;
  isAdmin: typeof isAdmin;
  canEditAssistant: (assistant: ChatModel) => boolean;
  canDeleteAssistant: (assistant: ChatModel) => boolean;
  handleQuickAction: (title: string) => void;
  handleSuggestedClick: (question: string) => Promise<void>;
  submitMessage: () => Promise<void>;
  toggleHistorySidebar: () => void;
  handleSelectConversation: (conversationId: string) => void;
  handleDeleteHistory: (itemId: string) => Promise<void>;
  startNewConversation: () => void;
  /** 已上传的文件列表，包含文件ID、名称、类型和大小 */
  attachedFiles: typeof attachedFiles;
  /** 是否正在拖拽文件，用于显示拖拽覆盖层 */
  isDragging: typeof isDragging;
  /** 处理拖拽悬停事件 */
  handleDragOver: (e: DragEvent) => void;
  /** 处理拖拽离开事件 */
  handleDragLeave: (e: DragEvent) => void;
  /** 处理文件放下事件，上传文件到 Dify */
  handleDrop: (e: DragEvent) => Promise<void>;
  /** 移除已上传的文件 */
  removeAttachedFile: (fileId: string) => void;
  /** 处理点击附件按钮选择的文件 */
  handleFileSelected: (file: File) => Promise<void>;
  /** 复制消息内容到剪贴板 */
  copyMessage: (content: string) => Promise<void>;
  /** 停止当前正在进行的流式生成 */
  stopGenerating: () => void;
  /** 把指定助手回答导出为 Markdown 文件 */
  exportMessageMarkdown: (messageId: string) => void;
  /** 当前消息是否允许重新生成 */
  canRegenerateMessage: (messageId: string) => boolean;
  /** 重新生成当前最新一条助手消息 */
  handleRegenerateMessage: (messageId: string) => Promise<void>;
  /** 渲染助手消息的 Markdown 内容 */
  formatContent: (content: string) => string;
  /** 渲染用户消息内容（XSS 安全 + 保留换行） */
  formatUserContent: (content: string) => string;
  /** 侧栏 Tab：助手 / 话题 */
  sideTab: typeof sideTab;
  /** 添加助手弹窗：是否可见 */
  assistantDialogVisible: typeof assistantDialogVisible;
  /** 添加助手弹窗：表单（仅 UI 状态，不联后端） */
  assistantForm: typeof assistantForm;
  isAssistantEditing: typeof isAssistantEditing;
  /** 添加助手弹窗：模板 */
  assistantTemplates: typeof assistantTemplates;
  /** 添加助手弹窗：是否可以确认 */
  canConfirmAssistant: typeof canConfirmAssistant;
  /** 打开添加助手弹窗 */
  openAssistantDialog: () => void;
  openAssistantEditDialog: (assistant: ChatModel) => void;
  /** 关闭添加助手弹窗 */
  closeAssistantDialog: () => void;
  /** 确认添加助手并按助手配置启动新对话 */
  confirmAssistantDialog: () => Promise<void>;
  /** 应用助手模板到表单 */
  applyAssistantTemplate: (template: { name: string; description: string }) => void;
  updateInputField: (payload: { variable: string; value: string }) => void;
  selectModel: (value: string) => void;
  /** 切换助手项的「更多操作」菜单 */
  toggleAssistantMenu: (id: string) => void;
  /** 选择助手并按助手配置启动新对话 */
  selectAssistant: (id: string) => Promise<void>;
  /** 关闭助手项的「更多操作」菜单 */
  closeAssistantMenu: () => void;
  /** 当前展开「更多操作」菜单的助手 id */
  assistantMenuOpenId: typeof assistantMenuOpenId;
  /** 请求删除助手（弹出二次确认） */
  requestDeleteAssistant: (assistant: { id: string; name: string }) => void;
  /** 二次确认：取消 */
  cancelDeleteAssistant: () => void;
  /** 二次确认：确认 */
  confirmDeleteAssistant: () => void;
  /** 二次确认弹窗状态 */
  assistantDeleteConfirm: typeof assistantDeleteConfirm;
}

/**
 * 获取当前用户标识
 * @description 从 Cookie 中动态获取用户 UID，实现会话隔离
 * 每个用户只能看到自己的会话历史
 */
const getCurrentUser = (): string => {
  return getCurrentUserId();
};

const getHiddenAssistantStorageKey = () =>
  `chat-hidden-assistants-${getCurrentUser()}`;
const getActiveAssistantStorageKey = () =>
  `chat-active-assistant-${getCurrentUser()}`;
const getActiveModelStorageKey = () => `chat-active-model-${getCurrentUser()}`;

const readStorage = <T>(key: string, fallback: T): T => {
  if (typeof window === "undefined") {
    return fallback;
  }
  try {
    const raw = window.localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch (error) {
    console.warn(`读取本地存储失败: ${key}`, error);
    return fallback;
  }
};

const writeStorage = (key: string, value: unknown) => {
  if (typeof window === "undefined") {
    return;
  }
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch (error) {
    console.warn(`写入本地存储失败: ${key}`, error);
  }
};

const defaultModels = ref<ChatModel[]>([]);

const defaultAssistants: ChatModel[] = [];

const toBuiltinAssistants = (models: ChatModel[]): ChatModel[] =>
  models.map((model, index) => ({
    ...model,
    description: model.description ?? "",
    builtin: true,
    createdAt: index,
  }));

const builtinAssistants = ref<ChatModel[]>(toBuiltinAssistants(defaultAssistants));
const customAssistants = ref<ChatModel[]>([]);
const hiddenAssistantIds = ref<string[]>(
  readStorage<string[]>(getHiddenAssistantStorageKey(), [])
);
const activeAssistantId = ref(
  readStorage<string>(getActiveAssistantStorageKey(), "")
);
const activeModelId = ref(
  readStorage<string>(getActiveModelStorageKey(), defaultModels.value[0]?.id ?? "")
);

// ============== 直连模型（OpenAI 兼容协议，经 AI 网关路由） ==============
/**
 * 与 Dify 的有状态会话不同，OpenAI 协议无状态：多轮上下文由前端携带，
 * 因此直连会话保存在本地（刷新不丢，换设备不同步）。
 */
interface DirectChatTurn {
  role: "user" | "assistant";
  content: string;
}
interface DirectConversation {
  id: string;
  name: string;
  modelIid: string;
  modelName: string;
  messages: DirectChatTurn[];
  updatedAt: number;
}
const DIRECT_CONVERSATIONS_KEY = "ai-direct-conversations";
const DIRECT_MODEL_KEY_PREFIX = "direct-";
const directConversations = ref<DirectConversation[]>(
  readStorage<DirectConversation[]>(DIRECT_CONVERSATIONS_KEY, [])
);
const activeDirectConversationId = ref("");
const persistDirectConversations = () => writeStorage(DIRECT_CONVERSATIONS_KEY, directConversations.value);

// ============== 直连模式的知识库增强（服务端 RAG） ==============
/** 当前选中的知识库名；空串 = 不引用知识库 */
const directKbName = ref("");
/** 可选知识库名列表（按文档的 kbName 去重），仅直连模式展示 */
const directKbOptions = ref<string[]>([]);
const loadDirectKbOptions = async () => {
  try {
    const res = await kbRagApi.listDocs();
    if (res?.data?.resp_code !== 0) {
      directKbOptions.value = [];
      return;
    }
    const docs = (res.data?.datas ?? []) as Array<{ kbName?: string }>;
    const names: string[] = [];
    for (const doc of docs) {
      const name = (doc.kbName || "").trim();
      if (name && !names.includes(name)) {
        names.push(name);
      }
    }
    directKbOptions.value = names;
    // 选中的知识库被删光后自动回落到「不引用」
    if (directKbName.value && !names.includes(directKbName.value)) {
      directKbName.value = "";
    }
  } catch (error) {
    console.warn("加载知识库列表失败:", error);
    directKbOptions.value = [];
  }
};
const directModels = computed<ChatModel[]>(() =>
  defaultModels.value.filter((m) => m.protocol === "openai" && m.iid != null)
);
const directModelKey = (model: ChatModel) => `${DIRECT_MODEL_KEY_PREFIX}${model.iid}`;
const isDirectModelValue = (value: string) =>
  value.startsWith(DIRECT_MODEL_KEY_PREFIX) &&
  directModels.value.some((m) => directModelKey(m) === value);
const isDirectMode = computed(() => isDirectModelValue(activeModelId.value));
const activeDirectModel = computed<ChatModel | null>(() =>
  directModels.value.find((m) => directModelKey(m) === activeModelId.value) ?? null
);
/** 直连模型标识变化（进入/离开/切换直连模型）时开新对话：上下文不能跨模型混用 */
const activeDirectKey = computed(() => (isDirectMode.value ? activeModelId.value : ""));
const currentUserId = ref(getCurrentUserId());
const isAdmin = useIsAdmin();
const canEditAssistant = (assistant: ChatModel) => {
  if (assistant.builtin) return false;
  return isAdmin.value || assistant.userId === currentUserId.value;
};
const canDeleteAssistant = (assistant: ChatModel) => {
  if (assistant.builtin) return false;
  return isAdmin.value || assistant.userId === currentUserId.value;
};
const historyCollapsed = ref(false);
const sideTab = ref<"assistant" | "topic">("assistant");

// ============== 添加助手弹窗状态（仅 UI，不联后端） ==============
interface AssistantForm {
  name: string;
  description: string;
}
const assistantDialogVisible = ref(false);
const assistantForm = ref<AssistantForm>({ name: "", description: "" });
const assistantEditingId = ref<string | null>(null);
const isAssistantEditing = computed(() => assistantEditingId.value !== null);
const assistantTemplates: { name: string; description: string }[] = [
  {
    name: "商业数据分析",
    description:
      "你是一名资深的商业数据分析师，专长于业务指标解读与图表分析。\n" +
      "当用户提出问题时，请遵循以下流程：\n" +
      "1. 先明确问题对应的核心业务指标（如 GMV、DAU、转化率、留存率、客单价等）；\n" +
      "2. 若用户未提供数据，请先列出需要哪些字段、维度、时间窗口再请求补充；\n" +
      "3. 基于数据先给出关键结论，再用结构化方式（指标 → 解读 → 行动建议）展开；\n" +
      "4. 涉及图表描述时，给出可读性强的文字版图表摘要；\n" +
      "5. 结论必须可被业务验证，避免主观臆断；对异常值主动给出可能原因。\n" +
      "输出语言：中文，简洁专业，避免空话。",
  },
  {
    name: "公文写作助手",
    description:
      "你是一名公文写作助理，熟悉通知、请示、报告、纪要等常用文体的写作规范。\n" +
      "工作要求：\n" +
      "1. 用户给出背景信息后，先复述要点确认理解，再开始撰写；\n" +
      "2. 确保「时间、地点、人物、事件、原因、结果」六要素完整；\n" +
      "3. 用语规范、用词准确、客观中立，避免主观评价与情感色彩；\n" +
      "4. 涉及个人信息时使用「某某」占位，提醒用户在最终文档中替换为真实信息；\n" +
      "5. 撰写完成后提供「待补充信息」清单供用户复核。\n" +
      "输出语言：中文，符合正式公文表达习惯。",
  },
  {
    name: "翻译助理",
    description:
      "你是一名精通中英日多语种互译的翻译助理，熟悉法律、商务、技术等领域的专业表达。\n" +
      "工作要求：\n" +
      "1. 准确识别源语言，未指定时主动确认目标语言；\n" +
      "2. 保持原文的语气、正式程度与专业领域一致，避免口语化或过度文学化；\n" +
      "3. 遇到专业术语、专有名词、品牌名时，保留原文并以括号简注；\n" +
      "4. 输出格式为：原文摘要 → 译文 → 关键术语表 → 译注（必要时给出两种译法对比）；\n" +
      "5. 对于歧义、可能误读的句子，主动标注并给出建议译法。\n" +
      "输出语言：默认中文译注 + 用户指定的目标语言译文。",
  },
  {
    name: "学习计划",
    description:
      "你是一名善于拆解目标、制定学习节奏的私人学习教练。\n" +
      "工作要求：\n" +
      "1. 收到学习目标后，先与用户确认：基础水平、可投入时间、最终达成标准、deadline；\n" +
      "2. 将大目标拆解为周计划，再细化为每日 1-3 个可执行任务；\n" +
      "3. 每个任务包含：预计耗时、前置知识、产出物（例如「做完 5 道题并整理错因」）；\n" +
      "4. 主动设计「检验节点」（如周末小测、阶段复盘），防止学完即忘；\n" +
      "5. 跟进用户进度时，根据完成情况动态调整后续计划，给出具体补强建议。\n" +
      "输出语言：中文，鼓励式表达，避免制造焦虑。",
  },
];
const canConfirmAssistant = computed(
  () => assistantForm.value.name.trim().length > 0
);

function openAssistantDialog() {
  assistantEditingId.value = null;
  assistantForm.value = { name: "", description: "" };
  assistantDialogVisible.value = true;
}

function openAssistantEditDialog(assistant: ChatModel) {
  if (assistant.builtin) {
    ElMessage.warning("内置助手暂不支持编辑");
    return;
  }
  if (!canEditAssistant(assistant)) {
    ElMessage.warning("只有创建者或超管才能编辑该助手");
    return;
  }
  assistantEditingId.value = assistant.id;
  assistantForm.value = {
    name: assistant.name,
    description: assistant.description ?? "",
  };
  closeAssistantMenu();
  assistantDialogVisible.value = true;
}

function closeAssistantDialog() {
  assistantDialogVisible.value = false;
  assistantEditingId.value = null;
}

function applyAssistantTemplate(template: { name: string; description: string }) {
  assistantForm.value = { ...template };
}

// ============== 助手更多操作（删除） ==============
const assistantMenuOpenId = ref<string | null>(null);
const assistantDeleteConfirm = ref<{ id: string; name: string } | null>(null);

function extractAssistantIid(id: string): number | null {
  const match = id.match(/^custom-assistant-(\d+)$/);
  return match ? Number(match[1]) : null;
}

function toggleAssistantMenu(id: string) {
  assistantMenuOpenId.value = assistantMenuOpenId.value === id ? null : id;
}

function closeAssistantMenu() {
  assistantMenuOpenId.value = null;
}

async function loadCustomAssistants() {
  try {
    const res = await assistantApi.query();
    const list = res?.data?.datas ?? [];
    customAssistants.value = (Array.isArray(list) ? list : []).map(
      (item: { iid: number; name: string; prompt: string; userId: string; createtime?: string }) => ({
        id: `custom-assistant-${item.iid}`,
        name: item.name,
        provider: "自定义助手",
        description: item.prompt ?? "",
        builtin: false,
        userId: item.userId,
        createdAt: item.iid,
      })
    );
  } catch (error) {
    console.warn("加载自定义助手失败:", error);
    customAssistants.value = [];
  }
}

function persistHiddenAssistantIds() {
  writeStorage(getHiddenAssistantStorageKey(), hiddenAssistantIds.value);
}

function syncAssistantList() {
  const hiddenIds = new Set(hiddenAssistantIds.value);
  const visibleBuiltinAssistants = builtinAssistants.value.filter(
    (item) => !hiddenIds.has(item.id)
  );
  difyModels.value = [...visibleBuiltinAssistants, ...customAssistants.value].sort(
    (a, b) => (a.createdAt ?? 0) - (b.createdAt ?? 0)
  );

  if (!difyModels.value.some((item) => item.id === activeAssistantId.value)) {
    activeAssistantId.value = "";
  }
}

async function initCustomAssistants() {
  await loadCustomAssistants();
  syncAssistantList();
  syncAssistantPromptInput(activeAssistantId.value);
}

function isSuccessResponse(res: any): boolean {
  const data = res?.data;
  return data?.resp_code === 0 || data?.resp_code === 200 || data?.code === 200;
}

function getResponseMessage(res: any, fallback: string): string {
  return res?.data?.resp_msg || res?.data?.message || fallback;
}

/**
 * 由 useChat() 注入的「新建对话」回调。
 *
 * startNewConversation 定义在 useChat() 函数体内部，而 confirmDeleteAssistant
 * 是模块顶层函数，访问不到 useChat() 的局部声明 —— TypeScript 报 TS2304，
 * 运行时同样会抛 ReferenceError：删除「当前正在使用」的助手时会走到
 * confirmDeleteAssistant 的这个分支，助手已删掉但清空对话那一步崩溃。
 * 这里用注入的方式解耦；未注入时安全跳过。
 */
let startNewConversationHandler: (() => void) | null = null;

function requestDeleteAssistant(assistant: { id: string; name: string }) {
  closeAssistantMenu();
  assistantDeleteConfirm.value = { id: assistant.id, name: assistant.name };
}

function cancelDeleteAssistant() {
  assistantDeleteConfirm.value = null;
}

async function confirmDeleteAssistant() {
  const target = assistantDeleteConfirm.value;
  if (!target) return;
  const targetAssistant = difyModels.value.find((item) => item.id === target.id);
  if (!targetAssistant || !canDeleteAssistant(targetAssistant)) {
    ElMessage.warning("只有创建者或超管才能删除该助手");
    assistantDeleteConfirm.value = null;
    return;
  }
  const isActiveAssistant = activeAssistantId.value === target.id;
  const isCustomAssistant = customAssistants.value.some((item) => item.id === target.id);

  if (isCustomAssistant) {
    const iid = extractAssistantIid(target.id);
    if (iid === null) {
      ElMessage.error("助手数据异常，无法删除");
      return;
    }
    if (iid !== null) {
      try {
        const res = await assistantApi.delete({ iid });
        if (!isSuccessResponse(res)) {
          ElMessage.error(getResponseMessage(res, "删除助手失败"));
          return;
        }
      } catch (error) {
        console.warn("删除助手失败:", error);
        ElMessage.error("删除助手失败，请稍后重试");
        return;
      }
    }
    await loadCustomAssistants();
  } else if (!hiddenAssistantIds.value.includes(target.id)) {
    hiddenAssistantIds.value = [...hiddenAssistantIds.value, target.id];
    persistHiddenAssistantIds();
  }

  syncAssistantList();
  assistantDeleteConfirm.value = null;
  if (isActiveAssistant) {
    // 见上方 startNewConversationHandler 的说明：
    // 这里不能直接调 startNewConversation（它在 useChat() 内部，顶层访问不到）
    startNewConversationHandler?.();
  }
  ElMessage.success("助手已删除");
}
const inputValue = ref("");
const messages = ref<MessageItem[]>([]);
const chatMessagesRef = ref<HTMLDivElement | null>(null);
const composerRef = ref<InstanceType<typeof ChatComposer> | null>(null);
const conversations = ref<Conversation[]>([]);
const activeConversationId = ref<string>("");
const loading = ref(false);
const thinking = ref<string>('');
const difyModels = ref<ChatModel[]>([]);
const modelsLoading = ref(false);
const parametersLoading = ref(false);
const appParameters = ref<AppParametersResponse | null>(null);
const baseInputFields = ref<NormalizedUserInputField[]>([]);
const suggestedQuestions = ref<string[]>([]);
/** 消息回答完成后推荐的后续问题 */
const postAnswerSuggestions = ref<string[]>([]);
/** 当前消息的 message_id，用于调用推荐消息接口 */
const lastMessageId = ref('');
const userInput = ref<Record<string, unknown>>({});
const promptInputVariable = ref("");
const promptInputDefaultValue = ref("");
const modelNameVariable = ref("");

/**
 * 已上传文件列表
 * @description 存储用户拖拽上传到 Dify 的文件信息，包含文件ID、名称、类型和大小
 * 文件ID用于后续发送消息时附加到聊天内容中
 */
const attachedFiles = ref<UploadedChatFile[]>([]);

/**
 * 是否正在拖拽文件
 * @description 用于控制拖拽覆盖层的显示，当用户拖拽文件进入聊天区域时为 true
 */
const isDragging = ref(false);
let currentStopFn: (() => void) | null = null;
let currentRequestToken = 0;
syncAssistantList();

const normalizeUserInputFields = (
  formItems: UserInputFormItem[] = []
): NormalizedUserInputField[] => {
  const normalizedFields: NormalizedUserInputField[] = [];

  formItems.forEach((item) => {
    const textInput = item["text-input"];
    const paragraph = item.paragraph;
    const select = item.select;
    const fileUpload = item["file-upload"];

    if (textInput?.variable) {
      normalizedFields.push({
        type: "text-input",
        label: textInput.label || textInput.variable,
        variable: textInput.variable,
        required: Boolean(textInput.required),
        defaultValue: textInput.default || "",
      });
      return;
    }

    if (paragraph?.variable) {
      normalizedFields.push({
        type: "paragraph",
        label: paragraph.label || paragraph.variable,
        variable: paragraph.variable,
        required: Boolean(paragraph.required),
        defaultValue: paragraph.default || "",
      });
      return;
    }

    if (select?.variable) {
      normalizedFields.push({
        type: "select",
        label: select.label || select.variable,
        variable: select.variable,
        required: Boolean(select.required),
        defaultValue: select.default || "",
        options: select.options || [],
      });
      return;
    }

    // 兼容 'file-upload' (连字符) 和 'file_upload' (下划线) 两种 key 格式
    const fileUploadItem = fileUpload || item["file_upload"];
    if (fileUploadItem?.variable) {
      normalizedFields.push({
        type: "file-upload",
        label: fileUploadItem.label || fileUploadItem.variable,
        variable: fileUploadItem.variable,
        required: Boolean((fileUploadItem as any).required),
        defaultValue: "",
        allowed_file_types: (fileUploadItem as any).allowed_file_types || [],
        allowed_file_upload_methods: (fileUploadItem as any).allowed_file_upload_methods || [],
      });
    }
  });

  return normalizedFields;
};

const isPromptVariable = (variable: string) => {
  const normalized = variable.trim().toLowerCase();
  return (
    normalized === "prompt" ||
    normalized.endsWith("_prompt") ||
    normalized.endsWith(".prompt")
  );
};

const isModelNameVariable = (variable: string) => {
  const normalized = variable.trim().toLowerCase();
  return (
    normalized === "model_name" ||
    normalized.endsWith("_model_name") ||
    normalized.endsWith(".model_name")
  );
};

const dedupeSelectOptions = (options: ChatSelectOption[]): ChatSelectOption[] => {
  const uniqueOptions = new Map<string, ChatSelectOption>();
  options.forEach((option) => {
    if (!option.value) {
      return;
    }
    if (!uniqueOptions.has(option.value)) {
      uniqueOptions.set(option.value, option);
    }
  });
  return Array.from(uniqueOptions.values());
};

const chatInputFields = computed<NormalizedUserInputField[]>(() =>
  baseInputFields.value.map((field) => {
    if (isModelNameVariable(field.variable)) {
      return {
        ...field,
        type: "select",
      };
    }
    return field;
  })
);

const chatSelectOptionsMap = computed<Record<string, ChatSelectOption[]>>(() => {
  const optionMap: Record<string, ChatSelectOption[]> = {};

  chatInputFields.value.forEach((field) => {
    if (field.type !== "select") {
      return;
    }

    const baseOptions = (field.options || []).map((option) => ({
      label: option,
      value: option,
    }));

    if (isModelNameVariable(field.variable)) {
      optionMap[field.variable] = dedupeSelectOptions([
        ...difyModels.value.map((model) => ({
          title: model.name,
          subtitle: model.provider || "系统模型",
          label: model.provider ? `${model.name} · ${model.provider}` : model.name,
          value: model.id,
        })),
        ...baseOptions,
      ]);
      return;
    }

    optionMap[field.variable] = dedupeSelectOptions(baseOptions);
  });

  return optionMap;
});

const modelOptions = computed<ChatSelectOption[]>(() =>
  dedupeSelectOptions([
    // 直连模型：选中后对话走网关 OpenAI 协议，不再依赖 Dify
    ...directModels.value.map((model) => ({
      title: model.name,
      subtitle: `直连模型 · ${model.provider || "OpenAI 兼容"}`,
      label: `${model.name} · 直连`,
      value: directModelKey(model),
    })),
    ...defaultModels.value
      .filter((m) => !(m.protocol === "openai" && m.iid != null))
      .map((model) => ({
        title: model.name,
        subtitle: model.provider || "系统模型",
        label: model.provider ? `${model.name} · ${model.provider}` : model.name,
        value: model.id,
      })),
  ])
);

const composerInputFields = computed<NormalizedUserInputField[]>(() =>
  chatInputFields.value.filter(
    (field) => !isModelNameVariable(field.variable) && !isPromptVariable(field.variable)
  )
);

const composerSelectOptionsMap = computed<Record<string, ChatSelectOption[]>>(() => {
  const optionMap: Record<string, ChatSelectOption[]> = {};

  composerInputFields.value.forEach((field) => {
    if (field.type !== "select") {
      return;
    }
    optionMap[field.variable] = chatSelectOptionsMap.value[field.variable] || [];
  });

  return optionMap;
});

/** Chatflow 中的文件类型输入变量名列表（如 input_file） */
const fileInputVariables = computed<string[]>(() =>
  baseInputFields.value
    .filter((field) => field.type === "file-upload" && field.variable)
    .map((field) => field.variable)
);

/**
 * 获取实际要注入文件的目标变量名列表
 * 优先使用 user_input_form 中的 file-upload 变量，
 * 如果未检测到则回退到常见变量名
 */
const getFileInputTargets = (existingInputs: Record<string, unknown>): string[] => {
  const detected = fileInputVariables.value;
  if (detected.length > 0) {
    debug.log('[文件注入] 从 user_input_form 检测到文件变量:', detected);
    return detected;
  }

  // 未从 user_input_form 中检测到 file-upload 变量，尝试从已有 inputs key 中匹配
  const fromInputs = Object.keys(existingInputs).filter(
    (key) => key.toLowerCase().includes('file')
  );
  if (fromInputs.length > 0) {
    debug.log('[文件注入] 从 inputs key 匹配到文件变量:', fromInputs);
    return fromInputs;
  }

  // 兜底：尝试常见的文件变量名
  debug.log('[文件注入] 未检测到文件变量，使用兜底变量名: input_files');
  return ['input_files'];
};

const allowFileUpload = computed(() => {
  // 直连模型走 OpenAI 协议，暂未实现文件/图片消息内容
  if (isDirectMode.value) {
    return false;
  }
  const fileUpload = appParameters.value?.file_upload;
  if (!fileUpload) {
    return true;
  }
  if (fileUpload.image?.enabled) {
    return true;
  }
  return (appParameters.value?.system_parameters?.workflow_file_upload_limit || 0) > 0;
});

const maxFileUploadCount = computed(() => {
  return (
    appParameters.value?.file_upload?.image?.number_limits ||
    appParameters.value?.system_parameters?.workflow_file_upload_limit ||
    10
  );
});

const getAssistantPromptValue = (assistantId: string): string => {
  const assistant = difyModels.value.find((item) => item.id === assistantId);
  return assistant?.description?.trim() || promptInputDefaultValue.value;
};

const syncAssistantPromptInput = (assistantId: string) => {
  if (!promptInputVariable.value) {
    return;
  }

  const nextUserInput = { ...userInput.value };

  nextUserInput[promptInputVariable.value] = getAssistantPromptValue(assistantId);

  userInput.value = {
    ...nextUserInput,
  };
};

const syncModelBoundInput = (modelId: string) => {
  if (!modelNameVariable.value) {
    return;
  }

  userInput.value = {
    ...userInput.value,
    [modelNameVariable.value]: modelId,
  };
};

export function useChat(): UseChatReturn {
  const focusEmptyTextarea = () => {
    nextTick(() => {
      if (messages.value.length > 0 || parametersLoading.value || loading.value) {
        return;
      }
      composerRef.value?.focus({ preventScroll: true });
    });
  };

  const hasRequiredInputs = computed(() =>
    composerInputFields.value.every((field) => {
      if (!field.required) {
        return true;
      }
      return String(userInput.value[field.variable] ?? "").trim().length > 0;
    })
  );

  const canSubmit = computed(
    () =>
      (inputValue.value.trim().length > 0 || attachedFiles.value.length > 0) &&
      !loading.value &&
      hasRequiredInputs.value
  );

  const activeModel = computed(() => {
    const model = defaultModels.value.find((item) => item.id === activeModelId.value);
    return (
      model ?? {
        id: activeModelId.value,
        name: activeModelId.value || "选择模型",
        provider: "",
        description: "",
      }
    );
  });

  const activeAssistant = computed(() => {
    const assistant = difyModels.value.find((item) => item.id === activeAssistantId.value);
    return (
      assistant ?? {
        id: "",
        name: "AI 助手",
        provider: "",
        description: "",
      }
    );
  });

  /** 问候语中使用的名称：去除尾部"助手"后统一拼接，避免出现 "XXX助手助手" */
  const assistantGreetingName = computed(() => {
    // 直连模式下问候当前模型
    if (isDirectMode.value && activeDirectModel.value) {
      return activeDirectModel.value.name;
    }
    const raw = activeAssistant.value?.name || "AI";
    const trimmed = raw.replace(/助手$/, "");
    return `${trimmed}助手`;
  });

  /**
   * 当前用户的全部会话列表
   * 点击助手后不再按 prompt 过滤，直接展示该用户所有会话
   */
  /**
   * 过滤会话，排除知识库中心创建的会话
   * 由于 Dify 不存储未定义的 inputs 字段，改用 localStorage 追踪
   * 旧会话（无记录）默认归属问答交互引擎
   */
  const filteredConversations = computed<Conversation[]>(() => {
    // 直连模式：会话保存在本地，按当前直连模型过滤
    if (isDirectMode.value) {
      const modelIid = activeDirectModel.value?.iid != null ? String(activeDirectModel.value.iid) : "";
      return directConversations.value
        .filter((c) => c.modelIid === modelIid)
        .map((c) => ({
          id: c.id,
          name: c.name,
          inputs: {},
          status: "",
          introduction: "",
          created_at: Math.floor(c.updatedAt / 1000),
          updated_at: Math.floor(c.updatedAt / 1000),
        }));
    }
    return conversations.value.filter((c: any) => {
      if (isKbConversation(c.id)) return false;
      return true;
    });
  });

  const handleQuickAction = (title: string) => {
    inputValue.value = `请帮我${title}`;
  };

  /** 点击推荐问题：设置输入内容并自动发送 */
  const handleSuggestedClick = async (question: string) => {
    inputValue.value = question;
    await nextTick();
    submitMessage();
  };

  const loadModels = async () => {
    modelsLoading.value = true;
    try {
      const { data: res } = await modelsConfigApi.getList({
        data: {},
        pageRequest: { pageSize: 100, pageNum: 1 },
      });
      if (res.resp_code === 0 && res.datas?.length > 0) {
        defaultModels.value = res.datas.map(
          (item: { id: string; name: string; provider: string; iid?: number; protocol?: string }) => ({
            id: item.id,
            name: item.name,
            provider: item.provider,
            iid: item.iid,
            protocol: item.protocol,
          })
        );
      }
      builtinAssistants.value = toBuiltinAssistants(defaultAssistants);
    } catch (error) {
      console.warn("加载模型列表失败:", error);
      defaultModels.value = [];
      builtinAssistants.value = toBuiltinAssistants(defaultAssistants);
    } finally {
      await initCustomAssistants();
      syncModelBoundInput(activeModelId.value);
      modelsLoading.value = false;
    }
  };

  const loadAppParameters = async () => {
    parametersLoading.value = true;
    try {
      const params = await chatApi.getAppParameters();
      appParameters.value = params;
      suggestedQuestions.value = params.suggested_questions || [];

      debug.log('[加载参数] user_input_form 原始数据:', JSON.stringify(params.user_input_form));

      const normalizedFields = normalizeUserInputFields(params.user_input_form || []);

      debug.log('[加载参数] 规范化后的字段:', normalizedFields.map(f => ({
        type: f.type,
        variable: f.variable,
      })));
      const nextUserInput: Record<string, unknown> = {};
      normalizedFields.forEach((field) => {
        nextUserInput[field.variable] = field.defaultValue || "";
      });

      const promptField =
        normalizedFields.find((field) => isPromptVariable(field.variable)) ||
        normalizedFields.find((field) => field.type !== "select");
      const modelField = normalizedFields.find((field) =>
        isModelNameVariable(field.variable)
      );

      baseInputFields.value = normalizedFields;
      promptInputVariable.value = promptField?.variable || "";
      promptInputDefaultValue.value = promptField?.defaultValue || "";
      modelNameVariable.value = modelField?.variable || "";
      userInput.value = nextUserInput;

      syncAssistantPromptInput(activeAssistantId.value);
      syncModelBoundInput(activeModelId.value);
    } catch (error) {
      // 参数加载失败（如 Dify 未配置时的 503）按降级处理：无推荐问题/表单仍可正常使用，
      // 真正发消息时才会有明确的错误提示，页面加载阶段不弹 toast。
      console.warn("获取应用参数失败:", error);
    } finally {
      parametersLoading.value = false;
    }
  };

  const loadConversations = async () => {
    try {
      const response = await chatApi.getConversations({
        user: getCurrentUser(),
        limit: 100,
      });
      const nextConversations = response.data || [];
      conversations.value = nextConversations;
    } catch (error) {
      console.warn("获取会话列表失败:", error);
      conversations.value = [];
    }
  };

  const loadConversationMessages = async (conversationId: string) => {
    try {
      const response = await chatApi.getConversationHistory(
        conversationId,
        getCurrentUser(),
        undefined,
        100
      );
      const messageList: MessageItem[] = [];
      (response.data || []).forEach((msg: ChatMessage) => {
        if (msg.query) {
          messageList.push({
            id: `${msg.id}-user`,
            role: "user",
            content: msg.query,
          });
        }
        if (msg.answer) {
          messageList.push({
            id: `${msg.id}-assistant`,
            role: "assistant",
            content: msg.answer,
          });
        }
      });
      messages.value = messageList;
      activeConversationId.value = conversationId;
    } catch (error) {
      console.warn("获取会话历史失败:", error);
      ElMessage.error("获取会话历史失败");
    }
  };

  const cancelCurrentRequest = () => {
    if (currentStopFn) {
      currentStopFn();
      currentStopFn = null;
    }
    currentRequestToken += 1;
    loading.value = false;
    thinking.value = "";
  };

  const sendMessageInternal = async (options: {
    queryContent: string;
    displayContent: string;
    files?: UploadedChatFile[];
    switchToTopic?: boolean;
    renameConversationTo?: string;
    appendUserMessage?: boolean;
    resetComposerState?: boolean;
    targetAssistantMessageId?: string;
    sourceUserMessageId?: string;
  }) => {
    const {
      queryContent,
      displayContent,
      files = [],
      switchToTopic = false,
      renameConversationTo,
      appendUserMessage = true,
      resetComposerState = true,
      targetAssistantMessageId,
      sourceUserMessageId,
    } = options;

    if (!queryContent.trim() && files.length === 0) {
      return;
    }

    cancelCurrentRequest();
    const requestToken = currentRequestToken;

    thinking.value = "";
    if (switchToTopic) {
      sideTab.value = "topic";
    }

    const userMessageId = sourceUserMessageId ?? `user-${Date.now()}`;

    /**
     * 将附件文件转换为 Dify 消息格式
     * @description
     * - type: 根据文件 MIME 类型判断是 'image' 还是 'document'
     * - transfer_method: 使用 'local_file' 表示本地上传方式
     * - upload_file_id: 上传文件后获得的 Dify 文件 ID
     */
    const fileList = files.map((file) => ({
      type: file.type.startsWith("image/") ? "image" : "document",
      transfer_method: "local_file" as const,
      upload_file_id: file.id,
    }));

    // 发送新消息时清空上一次的推荐问题
    postAnswerSuggestions.value = [];
    lastMessageId.value = '';

    if (appendUserMessage) {
      messages.value.push({
        id: userMessageId,
        role: "user",
        content: displayContent,
        requestQueryContent: queryContent,
        requestDisplayContent: displayContent,
        requestFiles: [...files],
      });
    }

    if (resetComposerState) {
      inputValue.value = "";
      /**
       * 发送消息后清空附件列表
       */
      attachedFiles.value = [];
    }
    loading.value = true;

    const assistantMessageId = targetAssistantMessageId ?? `assistant-${Date.now()}`;
    let assistantContent = "";
    let receivedConversationId = activeConversationId.value || undefined;
    const existingAssistantMessage = messages.value.find((item) => item.id === assistantMessageId);

    if (existingAssistantMessage) {
      existingAssistantMessage.content = "";
      delete existingAssistantMessage.thinking;
      existingAssistantMessage.requestQueryContent = queryContent;
      existingAssistantMessage.requestDisplayContent = displayContent;
      existingAssistantMessage.requestFiles = [...files];
      existingAssistantMessage.sourceUserMessageId = userMessageId;
    } else {
      messages.value.push({
        id: assistantMessageId,
        role: "assistant",
        content: assistantContent,
        requestQueryContent: queryContent,
        requestDisplayContent: displayContent,
        requestFiles: [...files],
        sourceUserMessageId: userMessageId,
      });
    }

    /**
     * 发送流式消息到 Dify
     * @param queryContent - 使用处理后的查询内容（确保不为空）
     * @param files - 附件文件列表，如果有文件则附加到消息中
     * Dify 会自动处理文件并将其用于增强回答（如 RAG 检索）
     *
     * 文件传递策略：
     * 1. files 参数：始终传递，文件可通过 sys.files 访问
     * 2. inputs[fileVariable]：当 Chatflow 有 file-upload 类型输入变量时，
     *    将文件信息也注入到 inputs 中，确保 LLM 节点能通过 {{#START.input_file#}} 引用
     */
    const requestInputs: Record<string, unknown> = { ...userInput.value, source: 'chat' };

    // 将文件信息注入到对应输入变量中
    if (fileList.length > 0) {
      const targetVars = getFileInputTargets(requestInputs);
      debug.log('[文件注入] 目标变量:', targetVars, 'fileList:', fileList);
      targetVars.forEach((variable) => {
        requestInputs[variable] = fileList;
      });
    }

    debug.log("[发送消息] 准备发送消息:", {
      query: queryContent,
      user: getCurrentUser(),
      conversation_id: receivedConversationId,
      inputsKeys: Object.keys(requestInputs),
      files: fileList.length > 0 ? fileList : undefined,
      detectedFileVars: fileInputVariables.value,
    });

    currentStopFn = chatApi.sendMessageStreaming(
      {
        query: queryContent,
        user: getCurrentUser(),
        conversation_id: receivedConversationId,
        inputs: requestInputs,
        files: fileList.length > 0 ? fileList : undefined,
      },
      {
        onChunk: (text) => {
          if (requestToken !== currentRequestToken) {
            return;
          }
          assistantContent += text;
          thinking.value = "";
          const msg = messages.value.find((m) => m.id === assistantMessageId);
          if (msg) {
            msg.content = assistantContent;
          }
        },
        onMessageId: (messageId) => {
          lastMessageId.value = messageId;
        },
        onThinking: (thought) => {
          if (requestToken !== currentRequestToken) {
            return;
          }
          thinking.value = thought;
          const msg = messages.value.find((m) => m.id === assistantMessageId);
          if (msg) {
            msg.thinking = thought;
          }
        },
        onComplete: async (conversationId) => {
          if (requestToken !== currentRequestToken) {
            return;
          }
          loading.value = false;
          thinking.value = "";
          const msg = messages.value.find((m) => m.id === assistantMessageId);
          if (msg) {
            delete msg.thinking;
            // 兜底：流结束后内容仍为空（模型未返回有效内容），给出提示而非空白
            if (!msg.content || !msg.content.trim()) {
              msg.content = "模型未返回有效内容，请重试或尝试切换其他模型。";
            }
          }
          currentStopFn = null;
          if (conversationId) {
            receivedConversationId = conversationId;
            activeConversationId.value = conversationId;
            if (renameConversationTo) {
              try {
                await chatApi.renameConversation(conversationId, {
                  name: renameConversationTo,
                  user: getCurrentUser(),
                });
              } catch (error) {
                console.warn("重命名话题失败:", error);
              }
            }
            await loadConversations();
            if (switchToTopic) {
              sideTab.value = "topic";
            }
          }
          // 消息完成后获取推荐问题
          try {
            let msgId = lastMessageId.value;
            // 兜底：如果 SSE 事件没有 message_id，从会话历史获取最新消息
            if (!msgId && receivedConversationId) {
              debug.log('[推荐消息] SSE 未捕获 message_id，从会话历史兜底获取');
              const historyRes = await chatApi.getConversationHistory(receivedConversationId, getCurrentUser(), undefined, 1);
              const msgs = historyRes?.data || [];
              if (msgs.length > 0) {
                msgId = msgs[0].id;
              }
            }
            debug.log('[推荐消息] message_id:', msgId, 'conversation_id:', receivedConversationId);
            if (msgId) {
              const suggestions = await chatApi.getSuggestedMessages(msgId, getCurrentUser());
              debug.log('[推荐消息] 获取成功:', suggestions);
              postAnswerSuggestions.value = suggestions;
            } else {
              console.warn('[推荐消息] 无法获取 message_id，跳过推荐问题');
            }
          } catch (e: any) {
            console.warn('[推荐消息] 获取失败:', e?.message || e);
          }
        },
        onError: (error) => {
          if (requestToken !== currentRequestToken) {
            return;
          }
          console.error("发送消息失败:", error);
          const parsed = parseChatError(error);
          thinking.value = "";
          const msg = messages.value.find((m) => m.id === assistantMessageId);
          if (msg) {
            msg.content = parsed.message;
            delete msg.thinking;
          }
          loading.value = false;
          currentStopFn = null;
        },
      }
    );
  };

  const submitMessage = async () => {
    debug.log("[提交消息] 输入值:", inputValue.value);
    const content = inputValue.value.trim();

    const missingFields = composerInputFields.value.filter((field) => {
      if (!field.required) {
        return false;
      }
      return String(userInput.value[field.variable] ?? "").trim().length === 0;
    });

    if (missingFields.length > 0) {
      ElMessage.warning(`请先填写：${missingFields.map((field) => field.label).join("、")}`);
      return;
    }

    /**
     * 检查是否可以发送消息：有文本内容或有附件文件时才能发送
     * 注意：当只有附件文件没有文字时，需要设置默认 query
     */
    if (!content && attachedFiles.value.length === 0) {
      return;
    }

    /**
     * 如果只有附件没有文字，设置默认 query
     * Dify API 要求 query 字段不能为空
     */
    const queryContent = content || "请分析上传的文件";
    const files = [...attachedFiles.value];
    inputValue.value = "";
    /**
     * 发送消息后清空附件列表
     */
    attachedFiles.value = [];

    // 构建用户消息的显示内容：有文字则显示文字，否则显示文件名列表
    const displayContent = content || (files.length > 0
      ? files.map((f) => f.name).join('、')
      : '[文件]');

    if (isDirectMode.value) {
      sendDirectMessage(queryContent);
      return;
    }

    await sendMessageInternal({
      queryContent,
      displayContent,
      files,
      renameConversationTo:
        !activeConversationId.value && activeAssistant.value.id ? activeAssistant.value.name : undefined,
    });
  };

  const toggleHistorySidebar = () => {
    historyCollapsed.value = !historyCollapsed.value;
  };

  const handleSelectConversation = (conversationId: string) => {
    if (isDirectMode.value) {
      loadDirectConversation(conversationId);
      return;
    }
    loadConversationMessages(conversationId);
  };

  const handleDeleteHistory = async (itemId: string) => {
    if (isDirectMode.value) {
      try {
        await ElMessageBox.confirm("确定要删除此对话吗？", "删除对话", {
          confirmButtonText: "确定",
          cancelButtonText: "取消",
          type: "warning",
        });
        directConversations.value = directConversations.value.filter((c) => c.id !== itemId);
        persistDirectConversations();
        if (activeDirectConversationId.value === itemId) {
          activeDirectConversationId.value = "";
          messages.value = [];
        }
        ElMessage.success("删除成功");
      } catch (error) {
        if (error !== "cancel") {
          console.error("删除直连会话失败:", error);
          ElMessage.error("删除会话失败");
        }
      }
      return;
    }
    try {
      await ElMessageBox.confirm(
        "确定要删除此对话吗？",
        "删除对话",
        {
          confirmButtonText: "确定",
          cancelButtonText: "取消",
          type: "warning",
        }
      );
      await chatApi.deleteConversation(itemId, getCurrentUser());
      conversations.value = conversations.value.filter((c) => c.id !== itemId);
      if (activeConversationId.value === itemId) {
        activeConversationId.value = "";
        messages.value = [];
      }
      ElMessage.success("删除成功");
    } catch (error) {
      if (error !== "cancel") {
        console.error("删除会话失败:", error);
        ElMessage.error("删除会话失败");
      }
    }
  };

  // ============== 直连模型（OpenAI 兼容协议）对话流 ==============

  const getOrCreateDirectConversation = (firstQuery: string): DirectConversation => {
    const existing = directConversations.value.find(
      (c) => c.id === activeDirectConversationId.value
    );
    if (existing) {
      return existing;
    }
    const model = activeDirectModel.value;
    const conversation: DirectConversation = {
      id: `direct-${Date.now()}`,
      name: firstQuery.slice(0, 30) || "新对话",
      modelIid: model?.iid != null ? String(model.iid) : "",
      modelName: model?.name || "",
      messages: [],
      updatedAt: Date.now(),
    };
    directConversations.value = [conversation, ...directConversations.value];
    activeDirectConversationId.value = conversation.id;
    persistDirectConversations();
    return conversation;
  };

  const persistPartialDirectReply = (
    conversation: DirectConversation,
    content: string
  ) => {
    if (!content.trim()) {
      return;
    }
    conversation.messages.push({ role: "assistant", content });
    conversation.updatedAt = Date.now();
    persistDirectConversations();
  };

  /** 发起直连流式请求并把分片写进消息列表与本地会话 */
  const runDirectStream = (
    conversation: DirectConversation,
    assistantMessageId: string,
    userMessageId: string,
    queryContent: string
  ) => {
    const model = activeDirectModel.value;
    if (!model || model.iid == null) {
      ElMessage.error("直连模型不可用，请检查模型直连配置或重新选择模型");
      return;
    }

    cancelCurrentRequest();
    const requestToken = currentRequestToken;
    thinking.value = "";
    postAnswerSuggestions.value = [];
    lastMessageId.value = "";
    loading.value = true;

    let assistantContent = "";
    const existingAssistantMessage = messages.value.find((m) => m.id === assistantMessageId);
    if (existingAssistantMessage) {
      existingAssistantMessage.content = "";
      delete existingAssistantMessage.thinking;
    } else {
      messages.value.push({
        id: assistantMessageId,
        role: "assistant",
        content: "",
        requestQueryContent: queryContent,
        requestDisplayContent: queryContent,
        sourceUserMessageId: userMessageId,
      });
    }

    /**
     * OpenAI 协议无状态：把历史轮次一并带给网关才能多轮对话。
     * 只取最近 40 条，防止上下文超长。
     */
    const context = conversation.messages.slice(-40).map((turn) => ({
      role: turn.role,
      content: turn.content,
    }));

    currentStopFn = streamDirectChat(String(model.iid), context, {
      onChunk: (text) => {
        if (requestToken !== currentRequestToken) {
          return;
        }
        assistantContent += text;
        thinking.value = "";
        const msg = messages.value.find((m) => m.id === assistantMessageId);
        if (msg) {
          msg.content = assistantContent;
        }
      },
      onThinking: (thought) => {
        if (requestToken !== currentRequestToken) {
          return;
        }
        thinking.value = thought;
        const msg = messages.value.find((m) => m.id === assistantMessageId);
        if (msg) {
          msg.thinking = thought;
        }
      },
      onComplete: () => {
        if (requestToken !== currentRequestToken) {
          return;
        }
        loading.value = false;
        thinking.value = "";
        const msg = messages.value.find((m) => m.id === assistantMessageId);
        if (msg) {
          delete msg.thinking;
          if (!msg.content || !msg.content.trim()) {
            msg.content = "模型未返回有效内容，请重试或尝试切换其他模型。";
          }
        }
        if (assistantContent.trim()) {
          conversation.messages.push({ role: "assistant", content: assistantContent });
        }
        conversation.updatedAt = Date.now();
        persistDirectConversations();
        currentStopFn = null;
      },
      onError: (error) => {
        if (requestToken !== currentRequestToken) {
          return;
        }
        console.error("直连模型发送失败:", error);
        thinking.value = "";
        const msg = messages.value.find((m) => m.id === assistantMessageId);
        if (msg) {
          msg.content = error.message || "直连模型调用失败，请稍后重试";
          delete msg.thinking;
        }
        // 已生成的部分内容保留到本地会话，切换/刷新后不丢
        persistPartialDirectReply(conversation, assistantContent);
        loading.value = false;
        currentStopFn = null;
      },
    }, directKbName.value ? { kbName: directKbName.value } : undefined);
  };

  const sendDirectMessage = (queryContent: string) => {
    const model = activeDirectModel.value;
    if (!model || model.iid == null) {
      ElMessage.error("直连模型不可用，请检查模型直连配置或重新选择模型");
      return;
    }
    const conversation = getOrCreateDirectConversation(queryContent);
    const userMessageId = `user-${Date.now()}`;
    messages.value.push({
      id: userMessageId,
      role: "user",
      content: queryContent,
      requestQueryContent: queryContent,
      requestDisplayContent: queryContent,
    });
    conversation.messages.push({ role: "user", content: queryContent });
    runDirectStream(conversation, `assistant-${Date.now()}`, userMessageId, queryContent);
  };

  const loadDirectConversation = (conversationId: string) => {
    cancelCurrentRequest();
    const conversation = directConversations.value.find((c) => c.id === conversationId);
    if (!conversation) {
      return;
    }
    activeDirectConversationId.value = conversationId;
    messages.value = conversation.messages.map((turn, index) => ({
      id: `${conversationId}-${index}`,
      role: turn.role,
      content: turn.content,
      ...(turn.role === "user"
        ? { requestQueryContent: turn.content, requestDisplayContent: turn.content }
        : {}),
    }));
  };

  /**
   * 新建对话。
   * 这里用 function 声明而不是 const 箭头函数：
   * confirmDeleteAssistant（约 514 行）在本函数定义之前就调用了它，
   * 而 const 不会提升，TypeScript 直接报 TS2304 Cannot find name。
   * 函数声明会提升到作用域顶部，顺序问题一并消除。
   */
  function startNewConversation() {
    cancelCurrentRequest();
    activeConversationId.value = "";
    activeDirectConversationId.value = "";
    messages.value = [];
    inputValue.value = "";
    thinking.value = "";
    postAnswerSuggestions.value = [];
    lastMessageId.value = "";
    closeAssistantMenu();
    /**
     * 新建对话时清空附件列表
     */
    attachedFiles.value = [];
  }

  // 注入给模块顶层的 confirmDeleteAssistant：
  // 删除「当前正在使用」的助手后需要清空对话，而它无法直接访问本作用域内的函数
  startNewConversationHandler = startNewConversation;

  /**
   * 确认添加助手：仅 UI 行为
   * 关闭弹窗，触发一次新建对话（沿用现有 startNewConversation 行为）
   */
  const activateAssistant = async (assistantId: string) => {
    const targetAssistant = difyModels.value.find((item) => item.id === assistantId);
    if (!targetAssistant) {
      return;
    }

    const shouldRestartConversation =
      assistantId !== activeAssistantId.value ||
      activeConversationId.value.length > 0 ||
      messages.value.length > 0;

    if (!shouldRestartConversation) {
      closeAssistantMenu();
      return;
    }

    activeAssistantId.value = assistantId;
    startNewConversation();
    sideTab.value = "assistant";
  };

  const confirmAssistantDialog = async () => {
    if (!canConfirmAssistant.value) return;

    const payload = {
      name: assistantForm.value.name.trim(),
      prompt: assistantForm.value.description.trim(),
    };
    const editingId = assistantEditingId.value;
    if (editingId) {
      const iid = extractAssistantIid(editingId);
      if (iid === null) {
        ElMessage.error("助手数据异常，无法编辑");
        return;
      }

      try {
        const res = await assistantApi.update(payload, { iid });
        if (!isSuccessResponse(res)) {
          ElMessage.error(getResponseMessage(res, "更新助手失败"));
          return;
        }
        await loadCustomAssistants();
        syncAssistantList();
        assistantForm.value = { name: "", description: "" };
        assistantDialogVisible.value = false;
        assistantEditingId.value = null;
        if (activeAssistantId.value === editingId) {
          syncAssistantPromptInput(editingId);
        }
        ElMessage.success("助手已更新");
      } catch (error) {
        console.warn("更新助手失败:", error);
        ElMessage.error("更新助手失败，请稍后重试");
      }
      return;
    }

    try {
      const res = await assistantApi.create(payload);
      if (!isSuccessResponse(res)) {
        ElMessage.error(getResponseMessage(res, "创建助手失败"));
        return;
      }
    } catch (error) {
      console.warn("创建助手失败:", error);
      ElMessage.error("创建助手失败，请稍后重试");
      return;
    }

    await loadCustomAssistants();
    syncAssistantList();
    const createdAssistant = customAssistants.value.find(
      (item) => item.name === payload.name && item.description === payload.prompt
    );
    assistantForm.value = { name: "", description: "" };
    assistantDialogVisible.value = false;
    assistantEditingId.value = null;
    if (createdAssistant) {
      await activateAssistant(createdAssistant.id);
    }
    ElMessage.success("助手已添加并应用");
  };

  const selectAssistant = async (assistantId: string) => {
    const targetAssistant = difyModels.value.find((item) => item.id === assistantId);
    if (!targetAssistant) {
      return;
    }

    activeAssistantId.value = assistantId;
    startNewConversation();
    sideTab.value = "topic";
  };

  const getPreviousUserMessage = (assistantMessageId: string): MessageItem | null => {
    const assistantIndex = messages.value.findIndex((item) => item.id === assistantMessageId);
    if (assistantIndex <= 0) {
      return null;
    }

    for (let index = assistantIndex - 1; index >= 0; index -= 1) {
      const item = messages.value[index];
      if (item.role === "user") {
        return item;
      }
    }

    return null;
  };

  const getLatestAssistantMessageId = (): string => {
    for (let index = messages.value.length - 1; index >= 0; index -= 1) {
      if (messages.value[index].role === "assistant") {
        return messages.value[index].id;
      }
    }
    return "";
  };

  const canRegenerateMessage = (messageId: string): boolean => {
    if (loading.value) {
      return false;
    }
    if (!isDirectMode.value && !activeConversationId.value) {
      return false;
    }

    const targetMessage = messages.value.find((item) => item.id === messageId);
    if (!targetMessage || targetMessage.role !== "assistant") {
      return false;
    }

    if (getLatestAssistantMessageId() !== messageId) {
      return false;
    }

    const sourceUserMessage = getPreviousUserMessage(messageId);
    if (!sourceUserMessage) {
      return false;
    }

    const queryContent = sourceUserMessage.requestQueryContent || sourceUserMessage.content;
    const requestFiles = sourceUserMessage.requestFiles || [];
    return queryContent.trim().length > 0 || requestFiles.length > 0;
  };

  const handleRegenerateMessage = async (messageId: string) => {
    if (!canRegenerateMessage(messageId)) {
      ElMessage.info("仅支持重新生成当前最新一条助手回复");
      return;
    }

    const sourceUserMessage = getPreviousUserMessage(messageId);
    if (!sourceUserMessage) {
      ElMessage.error("未找到可重新生成的用户消息");
      return;
    }

    const queryContent = sourceUserMessage.requestQueryContent || sourceUserMessage.content;
    const displayContent = sourceUserMessage.requestDisplayContent || sourceUserMessage.content;
    const requestFiles = sourceUserMessage.requestFiles || [];

    if (!queryContent.trim() && requestFiles.length === 0) {
      ElMessage.warning("当前消息缺少可重新生成的请求内容");
      return;
    }

    if (isDirectMode.value) {
      const conversation = directConversations.value.find(
        (c) => c.id === activeDirectConversationId.value
      );
      if (!conversation) {
        ElMessage.error("会话不存在，无法重新生成");
        return;
      }
      // 移除被重新生成的 assistant 轮次（最后一条），保留用户消息重发
      for (let i = conversation.messages.length - 1; i >= 0; i--) {
        if (conversation.messages[i].role === "assistant") {
          conversation.messages.splice(i, 1);
          break;
        }
      }
      persistDirectConversations();
      runDirectStream(conversation, messageId, sourceUserMessage.id, queryContent);
      return;
    }

    await sendMessageInternal({
      queryContent,
      displayContent,
      files: requestFiles,
      appendUserMessage: false,
      resetComposerState: false,
      targetAssistantMessageId: messageId,
      sourceUserMessageId: sourceUserMessage.id,
    });
  };

  /**
   * 处理拖拽悬停事件
   * @param e - 拖拽事件对象
   * @description 当用户拖拽文件进入聊天区域时调用，设置 isDragging 为 true 显示覆盖层
   */
  const handleDragOver = (e: DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    isDragging.value = true;
  };

  /**
   * 处理拖拽离开事件
   * @param e - 拖拽事件对象
   * @description 当用户拖拽文件离开聊天区域时调用，设置 isDragging 为 false 隐藏覆盖层
   */
  const handleDragLeave = (e: DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    isDragging.value = false;
  };

  /**
   * 处理文件放下事件
   * @param e - 拖拽事件对象
   * @description
   * 当用户释放文件时调用，主要流程：
   * 1. 阻止默认行为和事件冒泡
   * 2. 从事件中获取拖拽的文件列表
   * 3. 遍历文件并上传到 Dify
   * 4. 上传成功后添加到 attachedFiles 列表
   * 5. 上传失败则显示错误提示
   */
  const handleDrop = async (e: DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    isDragging.value = false;

    if (!allowFileUpload.value) {
      ElMessage.warning("当前对话未开启文件上传");
      return;
    }

    const files = e.dataTransfer?.files;
    if (!files || files.length === 0) return;

    const remainingSlots = Math.max(maxFileUploadCount.value - attachedFiles.value.length, 0);
    if (remainingSlots <= 0) {
      ElMessage.warning(`最多可上传 ${maxFileUploadCount.value} 个文件`);
      return;
    }

    const filesToUpload = Array.from(files).slice(0, remainingSlots);
    if (filesToUpload.length < files.length) {
      ElMessage.warning(`最多可上传 ${maxFileUploadCount.value} 个文件，已忽略超出部分`);
    }

    for (const file of filesToUpload) {
      try {
        ElMessage.info(`正在上传文件: ${file.name}`);
        const response = await uploadFileToDify(file);
        attachedFiles.value.push({
          id: response.id,
          name: response.name,
          type: response.mime_type,
          size: response.size,
        });
        ElMessage.success(`文件 ${file.name} 上传成功`);
      } catch (error) {
        console.error(`文件 ${file.name} 上传失败:`, error);
        ElMessage.error(`文件 ${file.name} 上传失败`);
      }
    }
  };

  /**
   * 处理点击附件按钮选择的文件
   * @param file - 用户选择的文件
   * @description 与拖拽上传逻辑一致，上传到 Dify 并加入 attachedFiles
   */
  const handleFileSelected = async (file: File) => {
    if (!allowFileUpload.value) {
      ElMessage.warning("当前对话未开启文件上传");
      return;
    }

    if (attachedFiles.value.length >= maxFileUploadCount.value) {
      ElMessage.warning(`最多可上传 ${maxFileUploadCount.value} 个文件`);
      return;
    }

    try {
      ElMessage.info(`正在上传文件: ${file.name}`);
      const response = await uploadFileToDify(file);
      attachedFiles.value.push({
        id: response.id,
        name: response.name,
        type: response.mime_type,
        size: response.size,
      });
      ElMessage.success(`文件 ${file.name} 上传成功`);
    } catch (error) {
      console.error(`文件 ${file.name} 上传失败:`, error);
      ElMessage.error(`文件 ${file.name} 上传失败`);
    }
  };

  /**
   * 复制消息内容到剪贴板
   * @param content - 要复制的文本内容
   */
  const copyMessage = async (content: string) => {
    try {
      await navigator.clipboard.writeText(content);
      ElMessage.success("已复制到剪贴板");
    } catch {
      // clipboard API 不可用时，回退到 execCommand 方式
      try {
        const textarea = document.createElement('textarea');
        textarea.value = content;
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        document.body.removeChild(textarea);
        ElMessage.success("已复制到剪贴板");
      } catch (fallbackError) {
        console.error("复制失败:", fallbackError);
        ElMessage.error("复制失败");
      }
    }
  };

  /**
   * 停止当前正在进行的流式生成（中止请求并保留已生成的部分内容）。
   */
  const stopGenerating = () => {
    if (!loading.value) return;
    cancelCurrentRequest();
    ElMessage.info("已停止生成");
  };

  /**
   * 把指定助手回答导出为 Markdown 文件（含来源助手名与导出时间）。
   */
  const exportMessageMarkdown = (messageId: string) => {
    const msg = messages.value.find((item) => item.id === messageId);
    if (!msg || msg.role !== "assistant" || !msg.content.trim()) {
      ElMessage.warning("当前回答没有可导出的内容");
      return;
    }
    const pad = (num: number) => String(num).padStart(2, "0");
    const now = new Date();
    const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
    const question = msg.requestQueryContent?.trim();
    const lines: string[] = [];
    if (question) {
      lines.push(`> **提问：** ${question.replace(/\r?\n/g, "\n> ")}`, "");
    }
    lines.push(msg.content, "", "---", "", `导出自 ${assistantGreetingName.value} · ${now.toLocaleString()}`);
    const markdown = lines.join("\n");
    const blob = new Blob([markdown], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `ai-answer-${stamp}.md`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
    ElMessage.success("已导出 Markdown");
  };

  /**
   * 移除已上传的文件
   * @param fileId - 要移除的文件ID
   * @description 从 attachedFiles 列表中移除指定ID的文件，用于用户取消上传的文件
   */
  const removeAttachedFile = (fileId: string) => {
    attachedFiles.value = attachedFiles.value.filter(f => f.id !== fileId);
  };

  const updateInputField = ({ variable, value }: { variable: string; value: string }) => {
    userInput.value = {
      ...userInput.value,
      [variable]: value,
    };

    if (isModelNameVariable(variable) && value) {
      activeModelId.value = value;
    }
  };

  const selectModel = (value: string) => {
    // 直连模型：模型即路由目标，不需要写入 Dify 应用变量
    if (isDirectModelValue(value)) {
      activeModelId.value = value;
      return;
    }
    const variable = modelNameVariable.value || "model_name";
    updateInputField({ variable, value });
  };

  // 直连模型切换/进入/离开直连模式时开新对话，避免上下文跨模型混用
  watch(activeDirectKey, (next, prev) => {
    if (next !== prev && (next || prev)) {
      startNewConversation();
    }
  });

  onMounted(() => {
    loadModels();
    loadAppParameters();
    loadConversations();
    loadDirectKbOptions();
    if (typeof document !== "undefined") {
      document.addEventListener("click", closeAssistantMenu);
    }
  });

  watch(
    [() => messages.value.length, parametersLoading, loading],
    ([messageLength, appParametersLoading, isChatLoading]) => {
      if (messageLength === 0 && !appParametersLoading && !isChatLoading) {
        focusEmptyTextarea();
      }
    },
    { immediate: true }
  );

  // AI 流式输出时自动滚动到底部（双重 rAF 确保 Markdown 内容完成布局）
  watch(
    messages,
    () => {
      nextTick(() => {
        requestAnimationFrame(() => {
          requestAnimationFrame(() => {
            if (chatMessagesRef.value) {
              chatMessagesRef.value.scrollTop = chatMessagesRef.value.scrollHeight;
            }
          });
        });
      });
    },
    { deep: true }
  );

  onUnmounted(() => {
    if (currentStopFn) {
      currentStopFn();
      currentStopFn = null;
    }
    if (typeof document !== "undefined") {
      document.removeEventListener("click", closeAssistantMenu);
    }
  });

  watch(
    activeModelId,
    (value) => {
      writeStorage(getActiveModelStorageKey(), value);
      syncModelBoundInput(value);
    },
    { immediate: true }
  );

  watch(
    activeAssistantId,
    (value) => {
      writeStorage(getActiveAssistantStorageKey(), value);
      syncAssistantPromptInput(value);
    },
    { immediate: true }
  );

  /**
   * 渲染助手消息的 Markdown 内容
   * @param content 助手回复的原始 Markdown 文本
   * @returns 渲染后的 HTML 字符串
   */
  const formatContent = (content: string): string => {
    return renderMarkdown(content);
  };

  /**
   * 渲染用户消息内容（XSS 安全 + 保留换行）
   * @param content 用户输入的原始文本
   * @returns 安全的 HTML 字符串
   */
  const formatUserContent = (content: string): string => {
    return renderUserContent(content);
  };

  return {
    activeModelId,
    activeAssistantId,
    historyCollapsed,
    inputValue,
    messages,
    chatMessagesRef,
    composerRef,
    conversations,
    filteredConversations,
    activeConversationId,
    loading,
    thinking,
    difyModels,
    modelsLoading,
    parametersLoading,
    appParameters,
    chatInputFields: composerInputFields,
    chatSelectOptionsMap: composerSelectOptionsMap,
    modelOptions,
    suggestedQuestions,
    postAnswerSuggestions,
    lastMessageId,
    userInput,
    allowFileUpload,
    isDirectMode,
    directKbName,
    directKbOptions,
    canSubmit,
    activeModel,
    activeAssistant,
    assistantGreetingName,
    currentUserId,
    isAdmin,
    canEditAssistant,
    canDeleteAssistant,
    handleQuickAction,
    handleSuggestedClick,
    submitMessage,
    toggleHistorySidebar,
    handleSelectConversation,
    handleDeleteHistory,
    startNewConversation,
    attachedFiles,
    isDragging,
    handleDragOver,
    handleDragLeave,
    handleDrop,
    removeAttachedFile,
    handleFileSelected,
    copyMessage,
    stopGenerating,
    exportMessageMarkdown,
    canRegenerateMessage,
    handleRegenerateMessage,
    formatContent,
    formatUserContent,
    sideTab,
    assistantDialogVisible,
    assistantForm,
    isAssistantEditing,
    assistantTemplates,
    canConfirmAssistant,
    openAssistantDialog,
    openAssistantEditDialog,
    closeAssistantDialog,
    confirmAssistantDialog,
    applyAssistantTemplate,
    updateInputField,
    selectModel,
    toggleAssistantMenu,
    selectAssistant,
    closeAssistantMenu,
    assistantMenuOpenId,
    requestDeleteAssistant,
    cancelDeleteAssistant,
    confirmDeleteAssistant,
    assistantDeleteConfirm,
  };
}
