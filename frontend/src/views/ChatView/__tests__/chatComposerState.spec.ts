import { beforeEach, describe, expect, it } from "vitest";
import type { AppParametersResponse } from "@/types/chat";
import {
  activeModelId,
  appParameters,
  baseInputFields,
  defaultModels,
  modelNameVariable,
  suggestedQuestions,
  userInput,
} from "../chatState";
import { difyModels } from "../chatAssistantState";
import {
  allowFileUpload,
  chatSelectOptionsMap,
  composerInputFields,
  dedupeSelectOptions,
  displaySuggestedQuestions,
  getFileInputTargets,
  isModelNameVariable,
  isPromptVariable,
  maxFileUploadCount,
  modelOptions,
  normalizeUserInputFields,
  syncModelBoundInput,
} from "../chatComposerState";

const DIRECT_MODEL = {
  id: "direct-5",
  name: "DeepSeek Chat",
  provider: "直连",
  iid: 5,
  protocol: "openai",
  upstreamModel: "deepseek-chat",
};

const makeAppParameters = (
  overrides: Partial<AppParametersResponse> = {}
): AppParametersResponse =>
  ({
    opening_statement: "",
    suggested_questions: [],
    suggested_questions_after_answer: { enabled: false },
    speech_to_text: { enabled: false },
    text_to_speech: { enabled: false, voice: "", language: "", autoPlay: "false" },
    retriever_resource: { enabled: false },
    annotation_reply: { enabled: false },
    more_like_this: { enabled: false },
    sensitive_word_avoidance: { enabled: false },
    user_input_form: [],
    file_upload: {},
    system_parameters: {
      file_size_limit: 0,
      image_file_size_limit: 0,
      audio_file_size_limit: 0,
      video_file_size_limit: 0,
      workflow_file_upload_limit: 0,
    },
    ...overrides,
  } as AppParametersResponse);

describe("chatComposerState", () => {
  beforeEach(() => {
    baseInputFields.value = [];
    appParameters.value = null;
    defaultModels.value = [];
    activeModelId.value = "";
    modelNameVariable.value = "";
    suggestedQuestions.value = [];
    userInput.value = {};
    difyModels.value = [];
  });

  describe("normalizeUserInputFields", () => {
    it("归一化四种控件类型", () => {
      const fields = normalizeUserInputFields([
        {
          "text-input": {
            variable: "topic",
            label: "主题",
            required: true,
            default: "默认主题",
          },
        },
        {
          paragraph: {
            variable: "detail",
            label: "",
            required: false,
            default: "",
          },
        },
        {
          select: {
            variable: "tone",
            label: "语气",
            required: true,
            default: "正式",
            options: ["正式", "口语"],
          },
        },
        {
          "file-upload": {
            variable: "input_file",
            label: "附件",
            required: true,
            allowed_file_types: ["image"],
            allowed_file_upload_methods: ["local_file"],
          },
        },
      ]);

      expect(fields).toHaveLength(4);
      expect(fields[0]).toMatchObject({
        type: "text-input",
        label: "主题",
        variable: "topic",
        required: true,
        defaultValue: "默认主题",
      });
      // 缺少 label 时回退为变量名
      expect(fields[1]).toMatchObject({
        type: "paragraph",
        label: "detail",
        required: false,
      });
      expect(fields[2]).toMatchObject({
        type: "select",
        options: ["正式", "口语"],
        defaultValue: "正式",
      });
      expect(fields[3]).toMatchObject({
        type: "file-upload",
        variable: "input_file",
        allowed_file_types: ["image"],
        allowed_file_upload_methods: ["local_file"],
      });
    });

    it("兼容下划线格式的 file_upload", () => {
      const fields = normalizeUserInputFields([
        { file_upload: { variable: "input_file", label: "文件", required: true } },
      ]);
      expect(fields).toHaveLength(1);
      expect(fields[0]).toMatchObject({
        type: "file-upload",
        variable: "input_file",
        required: true,
      });
    });

    it("缺少 variable 的控件被忽略", () => {
      expect(
        normalizeUserInputFields([
          { "text-input": { variable: "", label: "x", required: false, default: "" } },
        ])
      ).toEqual([]);
    });
  });

  describe("变量名判定", () => {
    it("isPromptVariable 识别 prompt / 后缀 / 点号形式", () => {
      expect(isPromptVariable("prompt")).toBe(true);
      expect(isPromptVariable("system_Prompt")).toBe(true);
      expect(isPromptVariable("user.prompt")).toBe(true);
      expect(isPromptVariable("prompts")).toBe(false);
    });

    it("isModelNameVariable 识别模型名变量", () => {
      expect(isModelNameVariable("model_name")).toBe(true);
      expect(isModelNameVariable("chat_model_name")).toBe(true);
      expect(isModelNameVariable("app.model_name")).toBe(true);
      expect(isModelNameVariable("model")).toBe(false);
    });
  });

  describe("dedupeSelectOptions", () => {
    it("丢弃空值并保留首次出现的重复项", () => {
      expect(
        dedupeSelectOptions([
          { value: "", label: "空" },
          { value: "a", label: "A" },
          { value: "a", label: "A 重复" },
          { value: "b", label: "B" },
        ])
      ).toEqual([
        { value: "a", label: "A" },
        { value: "b", label: "B" },
      ]);
    });
  });

  describe("modelOptions", () => {
    it("只保留可对话的直连模型与启用的 Dify 应用模型", () => {
      defaultModels.value = [
        DIRECT_MODEL,
        // 重排模型不能出现在对话模型下拉
        {
          id: "direct-6",
          name: "BGE Reranker",
          provider: "",
          iid: 6,
          protocol: "rerank",
          upstreamModel: "bge-reranker-v2",
        },
        // 纯向量配置（没有 upstreamModel）也不能出现
        {
          id: "direct-7",
          name: "Embedding",
          provider: "",
          iid: 7,
          protocol: "openai",
          upstreamModel: "",
        },
        // 已停用的直连模型
        {
          id: "direct-8",
          name: "停用模型",
          provider: "",
          iid: 8,
          protocol: "openai",
          upstreamModel: "x",
          enable: false,
        },
        { id: "app-1", name: "Dify 应用", provider: "OpenAI" },
        { id: "app-2", name: "停用应用", provider: "", enable: false },
        { id: "vec-1", name: "向量配置", provider: "", protocol: "embed" },
      ];

      expect(modelOptions.value.map((o) => o.value)).toEqual([
        "direct-5",
        "app-1",
      ]);
      expect(modelOptions.value[0].label).toBe("DeepSeek Chat · 直连");
      expect(modelOptions.value[1].label).toBe("Dify 应用 · OpenAI");
    });
  });

  describe("表单字段与选项", () => {
    it("输入区字段排除 prompt 与 model_name 变量", () => {
      baseInputFields.value = [
        {
          type: "text-input",
          variable: "system_prompt",
          label: "提示词",
          required: false,
          defaultValue: "",
        },
        {
          type: "select",
          variable: "model_name",
          label: "模型",
          required: false,
          defaultValue: "",
        },
        {
          type: "text-input",
          variable: "topic",
          label: "主题",
          required: true,
          defaultValue: "",
        },
      ];

      expect(composerInputFields.value.map((f) => f.variable)).toEqual([
        "topic",
      ]);
    });

    it("直连模式隐藏 Dify 应用参数表单（这些变量不会随直连请求发送）", () => {
      baseInputFields.value = [
        {
          type: "text-input",
          variable: "topic",
          label: "主题",
          required: true,
          defaultValue: "",
        },
      ];
      defaultModels.value = [{ ...DIRECT_MODEL }];
      activeModelId.value = "direct-5";

      expect(composerInputFields.value).toEqual([]);
    });

    it("model_name 下拉合并助手模型与自定义选项并去重", () => {
      difyModels.value = [
        { id: "m1", name: "GPT-4o", provider: "OpenAI" },
        { id: "m2", name: "GPT-4o", provider: "OpenAI" },
      ];
      baseInputFields.value = [
        {
          type: "select",
          variable: "model_name",
          label: "模型",
          required: false,
          defaultValue: "",
          options: ["m1", "默认模型"],
        },
      ];

      const options = chatSelectOptionsMap.value.model_name;
      expect(options.map((o) => o.value)).toEqual(["m1", "m2", "默认模型"]);
      expect(options[0].label).toBe("GPT-4o · OpenAI");
      expect(options[1].value).toBe("m2");
      expect(options[2].label).toBe("默认模型");
    });
  });

  describe("文件上传", () => {
    it("优先使用表单里的 file-upload 变量", () => {
      baseInputFields.value = [
        {
          type: "file-upload",
          variable: "input_file",
          label: "附件",
          required: false,
          defaultValue: "",
        },
      ];
      expect(getFileInputTargets({})).toEqual(["input_file"]);
    });

    it("没有声明时从已有 inputs 中匹配文件变量", () => {
      expect(getFileInputTargets({ input_files_0: [], topic: "x" })).toEqual([
        "input_files_0",
      ]);
    });

    it("都没有时回退到 input_files", () => {
      expect(getFileInputTargets({ topic: "x" })).toEqual(["input_files"]);
    });

    it("默认允许上传，且普通应用上限为 10", () => {
      expect(allowFileUpload.value).toBe(true);
      expect(maxFileUploadCount.value).toBe(10);
    });

    it("应用显式关闭图片上传且无工作流文件配额时禁止上传", () => {
      appParameters.value = makeAppParameters({
        file_upload: {
          image: {
            enabled: false,
            number_limits: 0,
            detail: "high",
            transfer_methods: ["local_file"],
          },
        },
      });
      expect(allowFileUpload.value).toBe(false);
    });

    it("开启图片上传或以工作流文件配额放行", () => {
      appParameters.value = makeAppParameters({
        file_upload: {
          image: {
            enabled: true,
            number_limits: 3,
            detail: "high",
            transfer_methods: ["local_file"],
          },
        },
      });
      expect(allowFileUpload.value).toBe(true);
      expect(maxFileUploadCount.value).toBe(3);

      appParameters.value = makeAppParameters({
        system_parameters: {
          file_size_limit: 0,
          image_file_size_limit: 0,
          audio_file_size_limit: 0,
          video_file_size_limit: 0,
          workflow_file_upload_limit: 5,
        },
      });
      expect(allowFileUpload.value).toBe(true);
      expect(maxFileUploadCount.value).toBe(5);
    });

    it("直连模式始终允许上传", () => {
      defaultModels.value = [{ ...DIRECT_MODEL }];
      activeModelId.value = "direct-5";
      appParameters.value = makeAppParameters({
        file_upload: {
          image: {
            enabled: false,
            number_limits: 0,
            detail: "high",
            transfer_methods: ["local_file"],
          },
        },
      });
      expect(allowFileUpload.value).toBe(true);
    });
  });

  describe("displaySuggestedQuestions", () => {
    it("非直连且无建议问题时返回空数组", () => {
      expect(displaySuggestedQuestions.value).toEqual([]);
    });

    it("直连模式补一组通用开场问题", () => {
      defaultModels.value = [{ ...DIRECT_MODEL }];
      activeModelId.value = "direct-5";
      const questions = displaySuggestedQuestions.value;
      expect(questions).toHaveLength(4);
      expect(questions).toContain("用一句话介绍你自己");
    });

    it("应用自带建议问题时优先使用", () => {
      suggestedQuestions.value = ["应用建议 1", "应用建议 2"];
      expect(displaySuggestedQuestions.value).toEqual([
        "应用建议 1",
        "应用建议 2",
      ]);
    });

    it("直连模式始终使用通用开场问题，不混用 Dify 应用建议", () => {
      suggestedQuestions.value = ["应用建议 1"];
      defaultModels.value = [{ ...DIRECT_MODEL }];
      activeModelId.value = "direct-5";

      const questions = displaySuggestedQuestions.value;
      expect(questions).toHaveLength(4);
      expect(questions).not.toContain("应用建议 1");
    });
  });

  describe("syncModelBoundInput", () => {
    it("未声明模型名变量时不写入", () => {
      syncModelBoundInput("m1");
      expect(userInput.value).toEqual({});
    });

    it("写入已声明的模型名变量且保留其它输入", () => {
      modelNameVariable.value = "model_name";
      userInput.value = { topic: "季度总结" };
      syncModelBoundInput("m1");
      expect(userInput.value).toEqual({ topic: "季度总结", model_name: "m1" });
    });
  });
});
