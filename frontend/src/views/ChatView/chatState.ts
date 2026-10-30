import { computed, ref } from "vue";
import { chatConversationApi, type CloudConversation } from "@/api/chatConversation";
import { kbRagApi } from "@/api/kbRag";
import { useIsAdmin } from "@/composables/useIsAdmin";
import { getCurrentUserId } from "@/utils/user";
import type { Conversation, AppParametersResponse, NormalizedUserInputField } from "@/types/chat";
import type ChatComposer from "./ChatComposer.vue";
import type { ChatModel, DirectConversation, MessageItem, UploadedChatFile } from "./chatTypes";
import { getActiveModelStorageKey, getCurrentUser, readDeletedConversationIds, readStorage, removeDeletedConversationId, writeStorage } from "./chatStorage";
import { chatRuntime } from "./chatRuntime";

export const defaultModels = ref<ChatModel[]>([]);

export const toBuiltinAssistants = (models: ChatModel[]): ChatModel[] =>
  models.map((model, index) => ({
    ...model,
    description: model.description ?? "",
    builtin: true,
    createdAt: index,
  }));

export const activeModelId = ref(
  readStorage<string>(getActiveModelStorageKey(), defaultModels.value[0]?.id ?? "")
);

// ============== 直连模型（OpenAI 兼容协议，经 AI 网关路由） ==============
export const directConversationsKey = (uid: string) => `ai-direct-conversations-${uid}`;
const DIRECT_MODEL_KEY_PREFIX = "direct-";
export const directConversations = ref<DirectConversation[]>(
  readStorage<DirectConversation[]>(directConversationsKey(chatRuntime.ownerUserId), [])
);
export const activeDirectConversationId = ref("");
export const persistDirectConversations = () =>
  writeStorage(
    directConversationsKey(chatRuntime.ownerUserId),
    directConversations.value
  );

/**
 * 云端同步（直连会话多端持久化）。
 *
 * <p>本地 localStorage 仍是第一写入位（离线可用）；这里在每次本地持久化后
 * 异步 upsert 云端，登录后拉取合并。网络失败静默——同步是增强能力，
 * 绝不能阻塞对话本身。</p>
 */
export const cloudSyncTimers = new Map<string, number>();
export const cloudSyncRequests = new Map<string, Promise<unknown>>();
export const cancelConversationSync = (conversationId: string) => {
  const timer = cloudSyncTimers.get(conversationId);
  if (timer !== undefined) window.clearTimeout(timer);
  cloudSyncTimers.delete(conversationId);
};
export const syncConversationToCloud = (conv: DirectConversation) => {
  const owner = chatRuntime.ownerUserId;
  if (owner !== getCurrentUser() || owner === "guest") return;
  cancelConversationSync(conv.id);
  const timer = window.setTimeout(() => {
    cloudSyncTimers.delete(conv.id);
    if (owner !== chatRuntime.ownerUserId || owner !== getCurrentUser() ||
        !directConversations.value.includes(conv)) return;
    const payload: CloudConversation = {
      convId: conv.id,
      title: conv.name,
      modelName: conv.modelName,
      pinned: conv.pinned ?? false,
      clientUpdatedAt: conv.updatedAt,
      payload: JSON.stringify(conv),
    };
    const request = chatConversationApi.save(payload).catch((e) =>
      console.warn('[云端会话] 同步失败（不影响本地）:', (e as Error)?.message || e)
    );
    cloudSyncRequests.set(conv.id, request);
    void request.finally(() => {
      if (cloudSyncRequests.get(conv.id) === request) cloudSyncRequests.delete(conv.id);
    });
  }, 800);
  cloudSyncTimers.set(conv.id, timer);
};

/** 登录后从云端拉取并与本地合并：同 id 取 clientUpdatedAt 较新者 */
export const mergeCloudConversations = async () => {
  const owner = chatRuntime.ownerUserId;
  if (owner !== getCurrentUser() || owner === "guest") return;
  try {
    const { data: res } = await chatConversationApi.list();
    if (owner !== chatRuntime.ownerUserId || owner !== getCurrentUser()) return;
    if (res.resp_code !== 0 || !Array.isArray(res.datas)) return;
    const localById = new Map(directConversations.value.map((c) => [c.id, c]));
    const deletedIds = new Set(readDeletedConversationIds(owner));
    let changed = false;
    for (const row of res.datas) {
      if (!row?.convId) continue;
      if (deletedIds.has(row.convId)) {
        // 本地已删除但云端还在（删除时离线）：继续清理，且不要合并回本地
        void chatConversationApi
          .delete(row.convId)
          .then(() => {
            if (chatRuntime.ownerUserId === owner) {
              removeDeletedConversationId(owner, row.convId);
            }
          })
          .catch(() => undefined);
        continue;
      }
      if (!row.payload) continue;
      const local = localById.get(row.convId);
      const cloudUpdatedAt = row.clientUpdatedAt ?? 0;
      if (!local || (local.updatedAt ?? 0) < cloudUpdatedAt) {
        try {
          const parsed = JSON.parse(row.payload) as DirectConversation;
          if (parsed && parsed.id === row.convId && Array.isArray(parsed.messages)) {
            localById.set(row.convId, parsed);
            changed = true;
          }
        } catch {
          // 云端快照损坏：跳过该条，本地为准
        }
      }
    }
    if (changed) {
      directConversations.value = [...localById.values()];
      persistDirectConversations();
    }
  } catch (e) {
    console.warn('[云端会话] 拉取失败:', (e as Error)?.message || e);
  }
};

// ============== 直连模式的知识库增强（服务端 RAG） ==============
/** 当前选中的知识库名；空串 = 不引用知识库 */
export const directKbName = ref("");
/** 可选知识库名列表（按文档的 kbName 去重），仅直连模式展示 */
export const directKbOptions = ref<string[]>([]);
export const loadDirectKbOptions = async () => {
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
/**
 * 可作为「对话模型」出现在下拉里的直连配置。
 *
 * <p>必须同时有 {@code upstreamModel}：只配了向量模型（embedModel）的配置行
 * 是给本地 RAG 做 embedding 用的，不属于对话模型。</p>
 *
 * <p>为什么需要这个判断：DeepSeek 没有 embeddings 接口，RAG 必须用第二家
 * 服务商做向量化，于是会出现「纯向量」的配置行。若不过滤，它会出现在对话
 * 模型下拉里，用户选中后网关拿不到 upstreamModel，直接调用失败。</p>
 */
export const isChatCapableDirectModel = (m: ChatModel) =>
  m.enable !== false &&
  m.protocol === "openai" &&
  m.iid != null &&
  !!m.upstreamModel?.trim();

export const directModels = computed<ChatModel[]>(() =>
  defaultModels.value.filter(isChatCapableDirectModel)
);
export const directModelKey = (model: ChatModel) => `${DIRECT_MODEL_KEY_PREFIX}${model.iid}`;
export const isDirectModelValue = (value: string) =>
  value.startsWith(DIRECT_MODEL_KEY_PREFIX) &&
  directModels.value.some((m) => directModelKey(m) === value);
export const isDirectMode = computed(() => isDirectModelValue(activeModelId.value));
export const activeDirectModel = computed<ChatModel | null>(() =>
  directModels.value.find((m) => directModelKey(m) === activeModelId.value) ?? null
);
/** 直连模型标识变化（进入/离开/切换直连模型）时开新对话：上下文不能跨模型混用 */
export const activeDirectKey = computed(() => (isDirectMode.value ? activeModelId.value : ""));

export const currentUserId = ref(getCurrentUserId());
export const isAdmin = useIsAdmin();
export const historyCollapsed = ref(false);
export const sideTab = ref<"assistant" | "topic">("assistant");

export const inputValue = ref("");
export const messages = ref<MessageItem[]>([]);
export const chatMessagesRef = ref<HTMLDivElement | null>(null);
export const composerRef = ref<InstanceType<typeof ChatComposer> | null>(null);
export const conversations = ref<Conversation[]>([]);
export const activeConversationId = ref<string>("");
export const loading = ref(false);

/** 会话列表分页：Dify 按 updated_at 倒序返回，last_id 取更早的一页 */
export const CONVERSATION_PAGE_SIZE = 30;
export const conversationsHasMore = ref(false);
export const conversationsLoadingMore = ref(false);
export const conversationsCursor = ref("");

/** 历史消息分页：first_id 取更早的一页，Dify 返回按时间升序 */
export const MESSAGE_PAGE_SIZE = 30;
export const messagesHasMore = ref(false);
export const messagesLoadingMore = ref(false);
export const messagesCursor = ref("");

/** 消息区滚动：仅在用户贴近底部时自动跟随，离开底部则显示「回到底部」 */
export const showScrollToBottom = ref(false);
export const thinking = ref<string>('');
export const modelsLoading = ref(false);

/** 消息回答完成后推荐的后续问题 */
export const postAnswerSuggestions = ref<string[]>([]);
/**
 * 应用是否开启了「回答后推荐问题」。
 * <p>Dify 的 /messages/{id}/suggested 在未开启该功能时固定返回 400，
 * 前端若仍然请求，既拿不到数据又会制造失败调用；参数未加载成功时同样跳过。</p>
 */
export const suggestedAfterAnswerEnabled = ref(false);
/** 当前消息的 message_id，用于调用推荐消息接口 */
export const lastMessageId = ref('');

export const parametersLoading = ref(false);
export const appParameters = ref<AppParametersResponse | null>(null);
export const baseInputFields = ref<NormalizedUserInputField[]>([]);
export const suggestedQuestions = ref<string[]>([]);
export const userInput = ref<Record<string, unknown>>({});
export const promptInputVariable = ref("");
export const promptInputDefaultValue = ref("");
export const modelNameVariable = ref("");

/**
 * 已上传文件列表
 * @description 存储用户拖拽上传到 Dify 的文件信息，包含文件ID、名称、类型和大小
 * 文件ID用于后续发送消息时附加到聊天内容中
 */
export const attachedFiles = ref<UploadedChatFile[]>([]);

/**
 * 是否正在拖拽文件
 * @description 用于控制拖拽覆盖层的显示，当用户拖拽文件进入聊天区域时为 true
 */
export const isDragging = ref(false);

/** 中止当前流式生成：作废旧请求令牌、落盘部分内容、调用上游 stop */
export const cancelCurrentRequest = () => {
  chatRuntime.currentRequestToken += 1;
  chatRuntime.currentPartialSaveFn?.();
  chatRuntime.currentPartialSaveFn = null;
  if (chatRuntime.currentStopFn) {
    chatRuntime.currentStopFn();
    chatRuntime.currentStopFn = null;
  }
  loading.value = false;
  thinking.value = "";
};
