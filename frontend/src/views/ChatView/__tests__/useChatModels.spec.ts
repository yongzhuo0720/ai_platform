import { beforeEach, describe, expect, it, vi } from "vitest";
import { chatApi } from "@/api/chat";
import {
  activeModelId,
  appParameters,
  baseInputFields,
  modelNameVariable,
  parametersLoading,
  promptInputDefaultValue,
  promptInputVariable,
  suggestedAfterAnswerEnabled,
  suggestedQuestions,
  userInput,
} from "../chatState";
import { activeAssistantId } from "../chatAssistantState";
import { loadAppParameters } from "../useChatModels";

vi.mock("@/api/chat", () => ({
  chatApi: {
    getAppParameters: vi.fn(),
  },
}));

const parameters = (overrides: Record<string, unknown> = {}) =>
  ({
    user_input_form: [],
    suggested_questions: [],
    suggested_questions_after_answer: { enabled: false },
    ...overrides,
  } as never);

describe("useChatModels", () => {
  beforeEach(() => {
    appParameters.value = null;
    baseInputFields.value = [];
    suggestedQuestions.value = [];
    suggestedAfterAnswerEnabled.value = false;
    promptInputVariable.value = "";
    promptInputDefaultValue.value = "";
    modelNameVariable.value = "";
    userInput.value = {};
    activeModelId.value = "";
    activeAssistantId.value = "";
    vi.mocked(chatApi.getAppParameters).mockReset();
  });

  describe("loadAppParameters", () => {
    it("文件变量不会被当成提示词变量（避免把助手提示词写进 file 输入）", async () => {
      vi.mocked(chatApi.getAppParameters).mockResolvedValue(
        parameters({
          user_input_form: [
            {
              "file-upload": {
                variable: "input_file",
                label: "资料",
                required: true,
              },
            },
            {
              "text-input": {
                variable: "topic",
                label: "主题",
                default: "默认主题",
              },
            },
          ],
          suggested_questions: ["问题一", "问题二"],
          suggested_questions_after_answer: { enabled: true },
        })
      );

      await loadAppParameters();

      expect(promptInputVariable.value).toBe("topic");
      expect(promptInputDefaultValue.value).toBe("默认主题");
      expect(userInput.value.input_file).toBe("");
      expect(userInput.value.topic).toBe("默认主题");
      expect(suggestedQuestions.value).toEqual(["问题一", "问题二"]);
      expect(suggestedAfterAnswerEnabled.value).toBe(true);
      expect(parametersLoading.value).toBe(false);
    });

    it("显式 prompt 变量优先于普通文本变量", async () => {
      vi.mocked(chatApi.getAppParameters).mockResolvedValue(
        parameters({
          user_input_form: [
            {
              "text-input": {
                variable: "system_prompt",
                label: "提示词",
                default: "你是助手",
              },
            },
            { "text-input": { variable: "topic", label: "主题" } },
          ],
        })
      );

      await loadAppParameters();

      expect(promptInputVariable.value).toBe("system_prompt");
      expect(promptInputDefaultValue.value).toBe("你是助手");
      expect(userInput.value.topic).toBe("");
    });

    it("参数加载失败时降级为空表单，不阻塞页面", async () => {
      vi.mocked(chatApi.getAppParameters).mockRejectedValue(new Error("503"));
      baseInputFields.value = [
        {
          type: "text-input",
          variable: "keep",
          label: "保留",
          required: false,
          defaultValue: "",
        },
      ];

      await loadAppParameters();

      expect(parametersLoading.value).toBe(false);
      expect(appParameters.value).toBeNull();
      expect(baseInputFields.value.map((field) => field.variable)).toEqual([
        "keep",
      ]);
    });
  });
});
