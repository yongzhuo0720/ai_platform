import { computed } from "vue";
import { debug } from "@/utils/debug";
import type { NormalizedUserInputField, UserInputFormItem } from "@/types/chat";
import type { ChatSelectOption } from "./chatTypes";
import {
  appParameters,
  baseInputFields,
  defaultModels,
  directModelKey,
  directModels,
  isDirectMode,
  modelNameVariable,
  suggestedQuestions,
  userInput,
} from "./chatState";
import { difyModels } from "./chatAssistantState";

/**
 * 直连模式的空状态建议问题。
 *
 * <p>Dify 模式的建议来自应用参数（suggested_questions）；直连模型没有这套
 * 参数，空状态会只剩一行问候语。这里补一组通用开场问题，点击即发送，
 * 让空状态有可点的引导。</p>
 */
const DIRECT_MODEL_SUGGESTIONS = [
  "用一句话介绍你自己",
  "帮我把一段中文总结成三条要点",
  "解释一下什么是 HTTP 302 重定向",
  "帮我写一封请假邮件",
];

/** 空状态最终展示的建议问题：Dify 用应用参数，直连用通用开场 */
export const displaySuggestedQuestions = computed(() => {
  if (isDirectMode.value) return DIRECT_MODEL_SUGGESTIONS;
  return suggestedQuestions.value;
});

export const normalizeUserInputFields = (
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

export const isPromptVariable = (variable: string) => {
  const normalized = variable.trim().toLowerCase();
  return (
    normalized === "prompt" ||
    normalized.endsWith("_prompt") ||
    normalized.endsWith(".prompt")
  );
};

export const isModelNameVariable = (variable: string) => {
  const normalized = variable.trim().toLowerCase();
  return (
    normalized === "model_name" ||
    normalized.endsWith("_model_name") ||
    normalized.endsWith(".model_name")
  );
};

export const dedupeSelectOptions = (options: ChatSelectOption[]): ChatSelectOption[] => {
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

export const chatInputFields = computed<NormalizedUserInputField[]>(() =>
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

export const chatSelectOptionsMap = computed<Record<string, ChatSelectOption[]>>(() => {
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

export const modelOptions = computed<ChatSelectOption[]>(() =>
  dedupeSelectOptions([
    // 直连模型：选中后对话走网关 OpenAI 协议，不再依赖 Dify
    ...directModels.value.map((model) => ({
      title: model.name,
      subtitle: `直连模型 · ${model.provider || "OpenAI 兼容"}`,
      label: `${model.name} · 直连`,
      value: directModelKey(model),
    })),
    // 仅保留绑定 Dify 应用的模型：带 protocol 的行是直连/向量/重排配置，
    // 直连已在上面按能力过滤，向量与重排（rerank 等）不能作为对话模型。
    ...defaultModels.value
      .filter((m) => !m.protocol && m.enable !== false)
      .map((model) => ({
        title: model.name,
        subtitle: model.provider || "系统模型",
        label: model.provider ? `${model.name} · ${model.provider}` : model.name,
        value: model.id,
      })),
  ])
);

export const composerInputFields = computed<NormalizedUserInputField[]>(() => {
  /*
   * 直连模式不发送 Dify 的 inputs：这些应用变量（含必填项）与直连请求无关，
   * 展示出来还会挡住发送，所以整组隐藏。
   */
  if (isDirectMode.value) {
    return [];
  }
  return chatInputFields.value.filter(
    (field) =>
      !isModelNameVariable(field.variable) &&
      !isPromptVariable(field.variable) &&
      // 文件变量由附件按钮负责；渲染成文本框既无意义，必填时还会永远拦住发送
      field.type !== "file-upload"
  );
});

/** Dify 工作流里必填的文件变量：是否满足看附件区，而不是输入框 */
export const requiredFileInputFields = computed<NormalizedUserInputField[]>(() => {
  if (isDirectMode.value) {
    return [];
  }
  return chatInputFields.value.filter(
    (field) => field.type === "file-upload" && field.required
  );
});

export const composerSelectOptionsMap = computed<Record<string, ChatSelectOption[]>>(() => {
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
export const fileInputVariables = computed<string[]>(() =>
  baseInputFields.value
    .filter((field) => field.type === "file-upload" && field.variable)
    .map((field) => field.variable)
);

/**
 * 获取实际要注入文件的目标变量名列表
 * 优先使用 user_input_form 中的 file-upload 变量，
 * 如果未检测到则回退到常见变量名
 */
export const getFileInputTargets = (existingInputs: Record<string, unknown>): string[] => {
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

export const allowFileUpload = computed(() => {
  // 直连模式：OpenAI 兼容协议支持 image_url 多模态，放开图片（仅图片）
  if (isDirectMode.value) {
    return true;
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

export const maxFileUploadCount = computed(() => {
  return (
    appParameters.value?.file_upload?.image?.number_limits ||
    appParameters.value?.system_parameters?.workflow_file_upload_limit ||
    10
  );
});

export const syncModelBoundInput = (modelId: string) => {
  if (!modelNameVariable.value) {
    return;
  }

  userInput.value = {
    ...userInput.value,
    [modelNameVariable.value]: modelId,
  };
};
