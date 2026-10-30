import { computed, ref } from "vue";
import type { Conversation } from "@/types/chat";
import { isKbConversation } from "@/utils/conversationSource";
import type { ChatModel } from "./chatTypes";
import {
  activeDirectModel,
  activeModelId,
  attachedFiles,
  conversations,
  currentUserId,
  defaultModels,
  directConversations,
  inputValue,
  isDirectMode,
  loading,
  userInput,
} from "./chatState";
import { activeAssistantId, builtinAssistants, customAssistants, difyModels } from "./chatAssistantState";
import { composerInputFields } from "./chatComposerState";
import { readStorage, writeStorage } from "./chatStorage";

export const hasRequiredInputs = computed(() =>
  composerInputFields.value.every((field) => {
    if (!field.required) {
      return true;
    }
    return String(userInput.value[field.variable] ?? "").trim().length > 0;
  })
);

export const canSubmit = computed(
  () =>
    (inputValue.value.trim().length > 0 || attachedFiles.value.length > 0) &&
    !loading.value &&
    hasRequiredInputs.value
);

export const activeModel = computed<ChatModel>(() => {
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

export const activeAssistant = computed<ChatModel>(() => {
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
export const assistantGreetingName = computed(() => {
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
 *
 * 另外叠加侧栏搜索关键词与置顶排序：置顶优先，其余按更新时间
 */
export const conversationSearch = ref("");
/** Dify 会话的置顶标记没有专用接口，按用户存本地（直连会话存在 DirectConversation.pinned） */
const DIFY_PINNED_KEY = (uid: string) => `chat-pinned-conversations-${uid}`;
export const readDifyPinned = (): Set<string> =>
  new Set(readStorage<string[]>(DIFY_PINNED_KEY(currentUserId.value), []));
export const writeDifyPinned = (ids: Set<string>) =>
  writeStorage(DIFY_PINNED_KEY(currentUserId.value), [...ids]);

export const filteredConversations = computed<Conversation[]>(() => {
  const keyword = conversationSearch.value.trim().toLowerCase();
  const matchKeyword = (name: string) =>
    !keyword || name.toLowerCase().includes(keyword);
  const sortByPinned = (a: Conversation, b: Conversation) => {
    if (!!a.pinned !== !!b.pinned) return a.pinned ? -1 : 1;
    return b.updated_at - a.updated_at;
  };

  // 直连模式：会话保存在本地，按当前直连模型过滤
  if (isDirectMode.value) {
    const modelIid = activeDirectModel.value?.iid != null ? String(activeDirectModel.value.iid) : "";
    return directConversations.value
      .filter((c) => c.modelIid === modelIid)
      .filter((c) => matchKeyword(c.name))
      .sort((a, b) => {
        if (!!a.pinned !== !!b.pinned) return a.pinned ? -1 : 1;
        return b.updatedAt - a.updatedAt;
      })
      .map((c) => ({
        id: c.id,
        name: c.name,
        inputs: {},
        status: "",
        introduction: "",
        created_at: Math.floor(c.updatedAt / 1000),
        updated_at: Math.floor(c.updatedAt / 1000),
        pinned: c.pinned,
      }));
  }
  const pinnedIds = readDifyPinned();
  return conversations.value
    .filter((c: any) => {
      if (isKbConversation(c.id)) return false;
      return matchKeyword(c.name || "");
    })
    .map((c: any) => ({ ...c, pinned: pinnedIds.has(c.id) }))
    .sort(sortByPinned);
});

// ============== 侧栏「助手」分组与简介 ==============

/** 助手搜索（名称或提示词关键词） */
export const assistantSearch = ref("");

/** 助手卡片的一行简介：内置用定制 intro，自定义从提示词截取首句 */
export const assistantIntro = (model: ChatModel): string => {
  if (model.intro?.trim()) return model.intro.trim();
  const desc = (model.description || "").replace(/\s+/g, " ").trim();
  if (!desc) return "";
  const first = desc.split(/[。；;!！?？\n]/)[0];
  return first.length > 42 ? `${first.slice(0, 42)}…` : first || "自定义提示词助手";
};

/** 助手两个分组：内置推荐 + 我的助手，各自应用搜索过滤 */
export const assistantGroups = computed(() => {
  const kw = assistantSearch.value.trim().toLowerCase();
  const match = (m: ChatModel) =>
    !kw ||
    m.name.toLowerCase().includes(kw) ||
    (m.description || "").toLowerCase().includes(kw);
  return [
    { key: "builtin", label: "内置推荐", items: builtinAssistants.value.filter(match) },
    { key: "custom", label: "我的助手", items: customAssistants.value.filter(match) },
  ].filter((g) => g.items.length > 0);
});

// ============== 侧栏「会话」时间分组与摘要 ==============

/** 会话按 置顶 / 今天 / 近 7 天 / 更早 分组（输入已在 filteredConversations 过滤） */
export const topicGroups = computed(() => {
  // updated_at 是 Unix 秒（Dify 惯例，直连映射时同样除以 1000），比较前统一转毫秒
  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const dayMs = 86_400_000;
  const pinned: Conversation[] = [];
  const today: Conversation[] = [];
  const week: Conversation[] = [];
  const earlier: Conversation[] = [];
  for (const c of filteredConversations.value) {
    const tsMs = c.updated_at * 1000;
    if (c.pinned) {
      pinned.push(c);
    } else if (tsMs >= startOfToday) {
      today.push(c);
    } else if (tsMs >= startOfToday - 6 * dayMs) {
      week.push(c);
    } else {
      earlier.push(c);
    }
  }
  return [
    { key: "pinned", label: "置顶", items: pinned },
    { key: "today", label: "今天", items: today },
    { key: "week", label: "近 7 天", items: week },
    { key: "earlier", label: "更早", items: earlier },
  ].filter((g) => g.items.length > 0);
});

/** 会话摘要：直连会话取最后一条消息，其余（Dify 会话本地无消息体）返回空 */
export const topicPreview = (conversationId: string): string => {
  const conv = directConversations.value.find((c) => c.id === conversationId);
  if (!conv || conv.messages.length === 0) return "";
  const last = conv.messages[conv.messages.length - 1];
  const text = (last.content || "").replace(/\s+/g, " ").trim();
  return text.length > 40 ? `${text.slice(0, 40)}…` : text;
};

/** 会话时间：一小时内相对时间，更早显示日期。入参是 Unix 秒 */
export const formatTopicTime = (ts: number): string => {
  if (!ts) return "";
  const tsMs = ts * 1000;
  const diff = Date.now() - tsMs;
  if (diff < 60_000) return "刚刚";
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`;
  const d = new Date(tsMs);
  const now = new Date();
  if (d.getFullYear() === now.getFullYear()) return `${d.getMonth() + 1}-${d.getDate()}`;
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;
};
