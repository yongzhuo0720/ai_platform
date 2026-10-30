import { computed, ref } from "vue";
import { ElMessage } from "element-plus";
import { assistantApi } from "@/api/assistant";
import type { AssistantForm, ChatModel } from "./chatTypes";
import {
  getActiveAssistantStorageKey,
  getHiddenAssistantStorageKey,
  readStorage,
  writeStorage,
} from "./chatStorage";
import {
  currentUserId,
  isAdmin,
  promptInputDefaultValue,
  promptInputVariable,
  toBuiltinAssistants,
  userInput,
} from "./chatState";
import { runStartNewConversationHandler } from "./chatRuntime";

/** 侧栏展示的助手全集：可见内置推荐 + 自定义助手，按创建顺序合并 */
export const difyModels = ref<ChatModel[]>([]);
export const builtinAssistants = ref<ChatModel[]>([]);
export const customAssistants = ref<ChatModel[]>([]);
export const hiddenAssistantIds = ref<string[]>(
  readStorage<string[]>(getHiddenAssistantStorageKey(), [])
);
export const activeAssistantId = ref(
  readStorage<string>(getActiveAssistantStorageKey(), "")
);

export const canEditAssistant = (assistant: ChatModel) => {
  if (assistant.builtin) return false;
  return isAdmin.value || assistant.userId === currentUserId.value;
};
export const canDeleteAssistant = (assistant: ChatModel) => {
  if (assistant.builtin) return false;
  return isAdmin.value || assistant.userId === currentUserId.value;
};

// ============== 添加助手弹窗状态（仅 UI，不联后端） ==============
export const assistantDialogVisible = ref(false);
export const assistantForm = ref<AssistantForm>({ name: "", description: "" });
export const assistantEditingId = ref<string | null>(null);
export const isAssistantEditing = computed(() => assistantEditingId.value !== null);
export const assistantTemplates: { name: string; description: string; intro: string }[] = [
  {
    name: "商业数据分析",
    intro: "指标拆解、图表解读，先给结论再给行动建议",
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
    intro: "通知、请示、报告、纪要，六要素完整的正式文体",
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
    intro: "中英日互译，术语对照、两种译法对比",
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
    intro: "拆解目标到周/天，带检验节点与补强建议",
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
/**
 * 内置推荐助手：由上方模板生成，侧栏「内置推荐」分组直接可选。
 * id 用稳定前缀（不落在直连模型 direct- 命名空间内），createdAt 用负数排在自定义助手之前。
 */
export const buildDefaultAssistants = (): ChatModel[] =>
  assistantTemplates.map((t, i) => ({
    id: `builtin-${i}-${t.name}`,
    name: t.name,
    provider: "内置",
    description: t.description,
    intro: t.intro,
  }));
// 模块加载即填充内置推荐助手（loadModels 里的重置只是兜底刷新）
builtinAssistants.value = toBuiltinAssistants(buildDefaultAssistants());
export const canConfirmAssistant = computed(
  () => assistantForm.value.name.trim().length > 0
);

export function openAssistantDialog() {
  assistantEditingId.value = null;
  assistantForm.value = { name: "", description: "" };
  assistantDialogVisible.value = true;
}

export function openAssistantEditDialog(assistant: ChatModel) {
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

export function closeAssistantDialog() {
  assistantDialogVisible.value = false;
  assistantEditingId.value = null;
}

export function applyAssistantTemplate(template: { name: string; description: string }) {
  assistantForm.value = { ...template };
}

// ============== 助手更多操作（删除） ==============
export const assistantMenuOpenId = ref<string | null>(null);
export const assistantDeleteConfirm = ref<{ id: string; name: string } | null>(null);

export function extractAssistantIid(id: string): number | null {
  const match = id.match(/^custom-assistant-(\d+)$/);
  return match ? Number(match[1]) : null;
}

export function toggleAssistantMenu(id: string) {
  assistantMenuOpenId.value = assistantMenuOpenId.value === id ? null : id;
}

export function closeAssistantMenu() {
  assistantMenuOpenId.value = null;
}

export async function loadCustomAssistants() {
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

export function persistHiddenAssistantIds() {
  writeStorage(getHiddenAssistantStorageKey(), hiddenAssistantIds.value);
}

export function syncAssistantList() {
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

export async function initCustomAssistants() {
  await loadCustomAssistants();
  syncAssistantList();
  syncAssistantPromptInput(activeAssistantId.value);
}

export function isSuccessResponse(res: any): boolean {
  const data = res?.data;
  return data?.resp_code === 0 || data?.resp_code === 200 || data?.code === 200;
}

export function getResponseMessage(res: any, fallback: string): string {
  return res?.data?.resp_msg || res?.data?.message || fallback;
}

export function requestDeleteAssistant(assistant: { id: string; name: string }) {
  closeAssistantMenu();
  assistantDeleteConfirm.value = { id: assistant.id, name: assistant.name };
}

export function cancelDeleteAssistant() {
  assistantDeleteConfirm.value = null;
}

export async function confirmDeleteAssistant() {
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
    await loadCustomAssistants();
  } else if (!hiddenAssistantIds.value.includes(target.id)) {
    hiddenAssistantIds.value = [...hiddenAssistantIds.value, target.id];
    persistHiddenAssistantIds();
  }

  syncAssistantList();
  assistantDeleteConfirm.value = null;
  if (isActiveAssistant) {
    // startNewConversation 定义在 useChat() 作用域，这里通过注入的回调清空对话
    runStartNewConversationHandler();
  }
  ElMessage.success("助手已删除");
}

const getAssistantPromptValue = (assistantId: string): string => {
  const assistant = difyModels.value.find((item) => item.id === assistantId);
  return assistant?.description?.trim() || promptInputDefaultValue.value;
};

export const syncAssistantPromptInput = (assistantId: string) => {
  if (!promptInputVariable.value) {
    return;
  }

  const nextUserInput = { ...userInput.value };

  nextUserInput[promptInputVariable.value] = getAssistantPromptValue(assistantId);

  userInput.value = {
    ...nextUserInput,
  };
};
