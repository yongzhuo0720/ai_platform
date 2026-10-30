import { debug } from "@/utils/debug";
import { chatApi } from "@/api/chat";
import { modelsConfigApi } from "@/api/modelsConfig";
import {
  activeModelId,
  appParameters,
  baseInputFields,
  defaultModels,
  modelNameVariable,
  modelsLoading,
  parametersLoading,
  promptInputDefaultValue,
  promptInputVariable,
  suggestedAfterAnswerEnabled,
  suggestedQuestions,
  toBuiltinAssistants,
  userInput,
} from "./chatState";
import {
  activeAssistantId,
  buildDefaultAssistants,
  builtinAssistants,
  initCustomAssistants,
  syncAssistantPromptInput,
} from "./chatAssistantState";
import {
  isModelNameVariable,
  isPromptVariable,
  normalizeUserInputFields,
  syncModelBoundInput,
} from "./chatComposerState";

export const loadModels = async () => {
  modelsLoading.value = true;
  try {
    const { data: res } = await modelsConfigApi.getList({
      data: {},
      pageRequest: { pageSize: 100, pageNum: 1 },
    });
    if (res.resp_code === 0 && res.datas?.length > 0) {
      defaultModels.value = res.datas.map(
        (item: { id: string; name: string; provider: string; iid?: number; protocol?: string; upstreamModel?: string; enable?: boolean }) => ({
          id: item.id,
          name: item.name,
          provider: item.provider,
          iid: item.iid,
          protocol: item.protocol,
          // 直连模型判断依赖 upstreamModel：丢了它，所有 OpenAI 兼容配置都会被过滤出对话下拉
          upstreamModel: item.upstreamModel,
          // 停用状态同样要保留，否则已停用的模型仍会出现在对话下拉里
          enable: item.enable,
        })
      );
    }
    builtinAssistants.value = toBuiltinAssistants(buildDefaultAssistants());
  } catch (error) {
    console.warn("加载模型列表失败:", error);
    defaultModels.value = [];
    builtinAssistants.value = toBuiltinAssistants(buildDefaultAssistants());
  } finally {
    await initCustomAssistants();
    syncModelBoundInput(activeModelId.value);
    modelsLoading.value = false;
  }
};

export const loadAppParameters = async () => {
  parametersLoading.value = true;
  try {
    const params = await chatApi.getAppParameters();
    appParameters.value = params;
    suggestedQuestions.value = params.suggested_questions || [];
    suggestedAfterAnswerEnabled.value = params.suggested_questions_after_answer?.enabled === true;

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
