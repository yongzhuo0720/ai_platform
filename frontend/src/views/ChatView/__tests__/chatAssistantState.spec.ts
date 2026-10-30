import { beforeEach, describe, expect, it, vi } from "vitest";
import Cookies from "js-cookie";
import { ElMessage } from "element-plus";
import { assistantApi } from "@/api/assistant";
import { currentUserId, promptInputDefaultValue, promptInputVariable, userInput } from "../chatState";
import { setStartNewConversationHandler } from "../chatRuntime";
import {
  activeAssistantId,
  applyAssistantTemplate,
  assistantDeleteConfirm,
  assistantDialogVisible,
  assistantEditingId,
  assistantForm,
  assistantMenuOpenId,
  builtinAssistants,
  canConfirmAssistant,
  canDeleteAssistant,
  canEditAssistant,
  cancelDeleteAssistant,
  closeAssistantDialog,
  confirmDeleteAssistant,
  customAssistants,
  difyModels,
  extractAssistantIid,
  getResponseMessage,
  hiddenAssistantIds,
  isSuccessResponse,
  openAssistantDialog,
  openAssistantEditDialog,
  requestDeleteAssistant,
  syncAssistantList,
  syncAssistantPromptInput,
  toggleAssistantMenu,
} from "../chatAssistantState";

vi.mock("@/api/assistant", () => ({
  assistantApi: {
    query: vi.fn(),
    create: vi.fn(),
    delete: vi.fn(),
    update: vi.fn(),
  },
}));

const ownAssistant = {
  id: "custom-assistant-42",
  name: "我的助手",
  provider: "自定义助手",
  description: "我的提示词",
  userId: "u1",
  createdAt: 100,
};

const otherAssistant = {
  id: "custom-assistant-43",
  name: "别人的助手",
  provider: "自定义助手",
  description: "别人的提示词",
  userId: "u2",
  createdAt: 101,
};

describe("chatAssistantState", () => {
  beforeEach(() => {
    localStorage.clear();
    // 隐藏列表按 Cookie 中的 uid 分键，保持与 currentUserId 一致
    Cookies.set("uid", "u1");
    currentUserId.value = "u1";
    builtinAssistants.value = [
      {
        id: "b1",
        name: "内置一",
        provider: "内置",
        description: "内置提示词",
        builtin: true,
        createdAt: 0,
      },
      {
        id: "b2",
        name: "内置二",
        provider: "内置",
        description: "",
        builtin: true,
        createdAt: 1,
      },
    ];
    customAssistants.value = [];
    difyModels.value = [];
    hiddenAssistantIds.value = [];
    activeAssistantId.value = "";
    assistantDialogVisible.value = false;
    assistantEditingId.value = null;
    assistantForm.value = { name: "", description: "" };
    assistantMenuOpenId.value = null;
    assistantDeleteConfirm.value = null;
    promptInputVariable.value = "";
    promptInputDefaultValue.value = "";
    userInput.value = {};
    setStartNewConversationHandler(null);
  });

  describe("extractAssistantIid", () => {
    it("从自定义助手 id 中解析 iid", () => {
      expect(extractAssistantIid("custom-assistant-42")).toBe(42);
    });

    it("非法格式返回 null", () => {
      expect(extractAssistantIid("custom-assistant-x")).toBeNull();
      expect(extractAssistantIid("builtin-0-翻译助理")).toBeNull();
    });
  });

  describe("接口结果判定", () => {
    it("兼容 resp_code / code 两种成功标记", () => {
      expect(isSuccessResponse({ data: { resp_code: 0 } })).toBe(true);
      expect(isSuccessResponse({ data: { resp_code: 200 } })).toBe(true);
      expect(isSuccessResponse({ data: { code: 200 } })).toBe(true);
      expect(isSuccessResponse({ data: { resp_code: 1 } })).toBe(false);
      expect(isSuccessResponse(undefined)).toBe(false);
    });

    it("优先取 resp_msg，其次 message，最后兜底", () => {
      expect(
        getResponseMessage({ data: { resp_msg: "删除失败" } }, "fallback")
      ).toBe("删除失败");
      expect(getResponseMessage({ data: { message: "提示" } }, "fallback")).toBe(
        "提示"
      );
      expect(getResponseMessage({ data: {} }, "fallback")).toBe("fallback");
    });
  });

  describe("编辑 / 删除权限", () => {
    it("内置助手不可编辑、不可删除", () => {
      const builtin = { ...builtinAssistants.value[0] };
      expect(canEditAssistant(builtin)).toBe(false);
      expect(canDeleteAssistant(builtin)).toBe(false);
    });

    it("创建者可以编辑、删除自己的助手", () => {
      expect(canEditAssistant(ownAssistant)).toBe(true);
      expect(canDeleteAssistant(ownAssistant)).toBe(true);
    });

    it("普通用户不能操作他人的助手", () => {
      expect(canEditAssistant(otherAssistant)).toBe(false);
      expect(canDeleteAssistant(otherAssistant)).toBe(false);
    });
  });

  describe("助手弹窗", () => {
    it("打开新增弹窗时清空编辑态与表单", () => {
      assistantEditingId.value = "custom-assistant-42";
      assistantForm.value = { name: "旧名称", description: "旧描述" };

      openAssistantDialog();

      expect(assistantDialogVisible.value).toBe(true);
      expect(assistantEditingId.value).toBeNull();
      expect(assistantForm.value).toEqual({ name: "", description: "" });
    });

    it("应用模板后表单被填充，名称为空时不可确认", () => {
      const template = { name: "翻译助理", description: "翻译提示词" };
      applyAssistantTemplate(template);
      expect(assistantForm.value).toEqual(template);
      expect(canConfirmAssistant.value).toBe(true);

      assistantForm.value = { name: "   ", description: "" };
      expect(canConfirmAssistant.value).toBe(false);
    });

    it("内置助手不允许打开编辑弹窗", () => {
      const warning = vi.spyOn(ElMessage, "warning").mockImplementation(() => ({ close: () => undefined }));
      openAssistantEditDialog(builtinAssistants.value[0]);
      expect(warning).toHaveBeenCalled();
      expect(assistantDialogVisible.value).toBe(false);
    });

    it("他人的助手不允许打开编辑弹窗", () => {
      const warning = vi.spyOn(ElMessage, "warning").mockImplementation(() => ({ close: () => undefined }));
      openAssistantEditDialog(otherAssistant);
      expect(warning).toHaveBeenCalled();
      expect(assistantDialogVisible.value).toBe(false);
    });

    it("编辑自己的助手时回填表单并关闭更多菜单", () => {
      assistantMenuOpenId.value = ownAssistant.id;
      openAssistantEditDialog(ownAssistant);
      expect(assistantDialogVisible.value).toBe(true);
      expect(assistantEditingId.value).toBe(ownAssistant.id);
      expect(assistantForm.value).toEqual({
        name: ownAssistant.name,
        description: ownAssistant.description,
      });
      expect(assistantMenuOpenId.value).toBeNull();
    });

    it("关闭弹窗清空编辑态", () => {
      assistantDialogVisible.value = true;
      assistantEditingId.value = "custom-assistant-42";
      closeAssistantDialog();
      expect(assistantDialogVisible.value).toBe(false);
      expect(assistantEditingId.value).toBeNull();
    });
  });

  describe("菜单与助手列表", () => {
    it("同一个助手再次点击更多按钮时收起菜单", () => {
      toggleAssistantMenu("b1");
      expect(assistantMenuOpenId.value).toBe("b1");
      toggleAssistantMenu("b1");
      expect(assistantMenuOpenId.value).toBeNull();
    });

    it("列表隐藏被删除的内置助手并保持排序", () => {
      hiddenAssistantIds.value = ["b1"];
      customAssistants.value = [{ ...ownAssistant }];

      syncAssistantList();

      expect(difyModels.value.map((m) => m.id)).toEqual([
        "b2",
        "custom-assistant-42",
      ]);
    });

    it("当前选中的助手被隐藏后自动清空选择", () => {
      hiddenAssistantIds.value = ["b1"];
      activeAssistantId.value = "b1";
      syncAssistantList();
      expect(activeAssistantId.value).toBe("");
    });

    it("当前选中的助手仍存在时保留选择", () => {
      activeAssistantId.value = "b2";
      syncAssistantList();
      expect(activeAssistantId.value).toBe("b2");
    });

    it("切换助手时同步提示词输入变量", () => {
      promptInputVariable.value = "prompt";
      promptInputDefaultValue.value = "默认提示词";
      difyModels.value = [{ ...builtinAssistants.value[0] }];

      syncAssistantPromptInput("b1");
      expect(userInput.value.prompt).toBe("内置提示词");

      syncAssistantPromptInput("missing");
      expect(userInput.value.prompt).toBe("默认提示词");
    });
  });

  describe("删除助手", () => {
    it("请求删除先关闭菜单并记录待确认目标", () => {
      assistantMenuOpenId.value = "b1";
      requestDeleteAssistant({ id: "b1", name: "内置一" });
      expect(assistantMenuOpenId.value).toBeNull();
      expect(assistantDeleteConfirm.value).toEqual({
        id: "b1",
        name: "内置一",
      });

      cancelDeleteAssistant();
      expect(assistantDeleteConfirm.value).toBeNull();
    });

    it("无权限删除时给出提示并清除确认态", async () => {
      const warning = vi.spyOn(ElMessage, "warning").mockImplementation(() => ({ close: () => undefined }));
      difyModels.value = [{ ...otherAssistant }];
      assistantDeleteConfirm.value = { id: otherAssistant.id, name: otherAssistant.name };

      await confirmDeleteAssistant();

      expect(warning).toHaveBeenCalled();
      expect(assistantDeleteConfirm.value).toBeNull();
      expect(vi.mocked(assistantApi.delete)).not.toHaveBeenCalled();
    });

    it("内置助手不可删除", async () => {
      const warning = vi.spyOn(ElMessage, "warning").mockImplementation(() => ({ close: () => undefined }));
      difyModels.value = [{ ...builtinAssistants.value[0] }];
      assistantDeleteConfirm.value = { id: "b1", name: "内置一" };

      await confirmDeleteAssistant();

      expect(warning).toHaveBeenCalled();
      expect(hiddenAssistantIds.value).toEqual([]);
      expect(assistantDeleteConfirm.value).toBeNull();
      expect(vi.mocked(assistantApi.delete)).not.toHaveBeenCalled();
    });

    it("删除非自定义助手时仅本地隐藏并持久化", async () => {
      const success = vi.spyOn(ElMessage, "success").mockImplementation(() => ({ close: () => undefined }));
      const onDeleted = vi.fn();
      setStartNewConversationHandler(onDeleted);
      // 既非内置、也不在自定义助手列表里：走本地隐藏分支
      const appAssistant = {
        id: "dify-model-1",
        name: "应用助手",
        provider: "系统模型",
        userId: "u1",
      };
      difyModels.value = [{ ...appAssistant }];
      activeAssistantId.value = appAssistant.id;
      assistantDeleteConfirm.value = {
        id: appAssistant.id,
        name: appAssistant.name,
      };

      await confirmDeleteAssistant();

      expect(hiddenAssistantIds.value).toEqual(["dify-model-1"]);
      expect(localStorage.getItem("chat-hidden-assistants-u1")).toBe(
        JSON.stringify(["dify-model-1"])
      );
      // 列表按「可见内置 + 自定义」重建，被隐藏的非自定义项不再出现
      expect(difyModels.value.map((m) => m.id)).toEqual(["b1", "b2"]);
      expect(onDeleted).toHaveBeenCalled();
      expect(success).toHaveBeenCalled();
      expect(vi.mocked(assistantApi.delete)).not.toHaveBeenCalled();
    });

    it("删除自定义助手时调用接口并刷新列表", async () => {
      vi.mocked(assistantApi.delete).mockResolvedValue({
        data: { resp_code: 0 },
      } as never);
      vi.mocked(assistantApi.query).mockResolvedValue({
        data: { datas: [{ iid: 9, name: "剩余助手", prompt: "p", userId: "u1" }] },
      } as never);

      customAssistants.value = [{ ...ownAssistant }];
      difyModels.value = [{ ...ownAssistant }];
      activeAssistantId.value = ownAssistant.id;
      assistantDeleteConfirm.value = {
        id: ownAssistant.id,
        name: ownAssistant.name,
      };

      await confirmDeleteAssistant();

      expect(vi.mocked(assistantApi.delete)).toHaveBeenCalledWith({ iid: 42 });
      expect(customAssistants.value.map((m) => m.id)).toEqual([
        "custom-assistant-9",
      ]);
      expect(activeAssistantId.value).toBe("");
      expect(assistantDeleteConfirm.value).toBeNull();
    });

    it("接口返回失败时保留助手并提示", async () => {
      const error = vi.spyOn(ElMessage, "error").mockImplementation(() => ({ close: () => undefined }));
      vi.mocked(assistantApi.delete).mockResolvedValue({
        data: { resp_code: 1, resp_msg: "仅创建者可删除" },
      } as never);

      customAssistants.value = [{ ...ownAssistant }];
      difyModels.value = [{ ...ownAssistant }];
      assistantDeleteConfirm.value = {
        id: ownAssistant.id,
        name: ownAssistant.name,
      };

      await confirmDeleteAssistant();

      expect(error).toHaveBeenCalledWith("仅创建者可删除");
      expect(difyModels.value.map((m) => m.id)).toEqual([ownAssistant.id]);
    });

    it("自定义助手 id 异常时不发请求", async () => {
      const error = vi.spyOn(ElMessage, "error").mockImplementation(() => ({ close: () => undefined }));
      const broken = { ...ownAssistant, id: "custom-assistant-x" };
      customAssistants.value = [{ ...broken }];
      difyModels.value = [{ ...broken }];
      assistantDeleteConfirm.value = { id: broken.id, name: broken.name };

      await confirmDeleteAssistant();

      expect(error).toHaveBeenCalled();
      expect(vi.mocked(assistantApi.delete)).not.toHaveBeenCalled();
    });
  });
});
