import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ElMessage } from "element-plus";
import { uploadFileToDify } from "@/api/request";
import {
  activeModelId,
  appParameters,
  attachedFiles,
  defaultModels,
  loading,
  modelNameVariable,
  userInput,
} from "../chatState";
import {
  handleDrop,
  removeAttachedFile,
  selectModel,
  stopGenerating,
} from "../useChatFiles";
import { chatRuntime } from "../chatRuntime";

vi.mock("@/api/request", () => ({
  uploadFileToDify: vi.fn(),
}));

const DIRECT_MODEL = {
  id: "direct-5",
  name: "DeepSeek Chat",
  provider: "直连",
  iid: 5,
  protocol: "openai",
  upstreamModel: "deepseek-chat",
};

const enableDirectMode = () => {
  defaultModels.value = [{ ...DIRECT_MODEL }];
  activeModelId.value = "direct-5";
};

const dropEvent = (files: File[]) =>
  ({
    preventDefault: vi.fn(),
    stopPropagation: vi.fn(),
    dataTransfer: { files },
  }) as unknown as DragEvent;

const imageFile = (name = "猫.png") =>
  new File(["img"], name, { type: "image/png" });

/** 直连模式压缩图片会用到 FileReader / Image / Canvas，jsdom 都没有实现 */
class FakeFileReader {
  result: string | ArrayBuffer | null = "data:image/png;base64,raw";
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  readAsDataURL() {
    queueMicrotask(() => this.onload?.());
  }
}

class FakeImage {
  width = 2000;
  height = 1000;
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  set src(_value: string) {
    queueMicrotask(() => this.onload?.());
  }
}

const stubImagePipeline = () => {
  vi.stubGlobal("FileReader", FakeFileReader);
  vi.stubGlobal("Image", FakeImage);
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({
    drawImage: vi.fn(),
  } as unknown as CanvasRenderingContext2D);
  vi.spyOn(HTMLCanvasElement.prototype, "toDataURL").mockReturnValue(
    "data:image/jpeg;base64,compressed"
  );
};

describe("useChatFiles", () => {
  beforeEach(() => {
    localStorage.clear();
    attachedFiles.value = [];
    loading.value = false;
    appParameters.value = null;
    defaultModels.value = [];
    activeModelId.value = "";
    modelNameVariable.value = "";
    userInput.value = {};
    chatRuntime.currentPartialSaveFn = null;
    chatRuntime.currentStopFn = null;
    vi.mocked(uploadFileToDify).mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  describe("handleDrop", () => {
    it("Dify 模式：拖入文件走上传接口并加入附件列表", async () => {
      vi.mocked(uploadFileToDify).mockResolvedValue({
        id: "f1",
        name: "报告.pdf",
        size: 10,
        mime_type: "application/pdf",
      });

      await handleDrop(dropEvent([new File(["x"], "报告.pdf", { type: "application/pdf" })]));

      expect(vi.mocked(uploadFileToDify)).toHaveBeenCalledTimes(1);
      expect(attachedFiles.value).toEqual([
        { id: "f1", name: "报告.pdf", type: "application/pdf", size: 10 },
      ]);
    });

    it("Dify 模式：超过上限只上传剩余数量并提示", async () => {
      const warning = vi.spyOn(ElMessage, "warning");
      appParameters.value = {
        file_upload: { image: { enabled: true, number_limits: 2 } },
      } as never;
      attachedFiles.value = [
        { id: "f0", name: "已有.pdf", type: "application/pdf", size: 1 },
      ];
      vi.mocked(uploadFileToDify).mockResolvedValue({
        id: "f1",
        name: "新.pdf",
        size: 2,
        mime_type: "application/pdf",
      });

      await handleDrop(
        dropEvent([
          new File(["x"], "新.pdf", { type: "application/pdf" }),
          new File(["y"], "多出来的.pdf", { type: "application/pdf" }),
        ])
      );

      expect(vi.mocked(uploadFileToDify)).toHaveBeenCalledTimes(1);
      expect(warning).toHaveBeenCalledWith("最多可上传 2 个文件，已忽略超出部分");
      expect(attachedFiles.value).toHaveLength(2);
    });

    it("直连模式：拖入图片就地压缩成 dataURL，不走 Dify 上传", async () => {
      stubImagePipeline();
      enableDirectMode();

      await handleDrop(dropEvent([imageFile()]));

      expect(vi.mocked(uploadFileToDify)).not.toHaveBeenCalled();
      expect(attachedFiles.value).toHaveLength(1);
      expect(attachedFiles.value[0]).toMatchObject({
        name: "猫.png",
        type: "image/png",
        dataUrl: "data:image/jpeg;base64,compressed",
      });
    });

    it("直连模式：非图片附件被拒绝", async () => {
      const warning = vi.spyOn(ElMessage, "warning");
      enableDirectMode();

      await handleDrop(dropEvent([new File(["x"], "说明.pdf", { type: "application/pdf" })]));

      expect(warning).toHaveBeenCalledWith("直连模型当前仅支持图片附件");
      expect(vi.mocked(uploadFileToDify)).not.toHaveBeenCalled();
      expect(attachedFiles.value).toEqual([]);
    });

    it("直连模式：已有两张图片时不再接收", async () => {
      const warning = vi.spyOn(ElMessage, "warning");
      enableDirectMode();
      attachedFiles.value = [
        { id: "local-1", name: "1.png", type: "image/png", size: 1, dataUrl: "data:image/png;base64,1" },
        { id: "local-2", name: "2.png", type: "image/png", size: 1, dataUrl: "data:image/png;base64,2" },
      ];

      await handleDrop(dropEvent([imageFile("3.png")]));

      expect(warning).toHaveBeenCalledWith("直连模式最多携带 2 张图片");
      expect(attachedFiles.value).toHaveLength(2);
    });
  });

  describe("removeAttachedFile", () => {
    it("只移除指定附件", () => {
      attachedFiles.value = [
        { id: "a", name: "a.png", type: "image/png", size: 1 },
        { id: "b", name: "b.png", type: "image/png", size: 1 },
      ];

      removeAttachedFile("a");

      expect(attachedFiles.value.map((file) => file.id)).toEqual(["b"]);
    });
  });

  describe("stopGenerating", () => {
    it("生成中点击停止：取消请求并提示", () => {
      const info = vi.spyOn(ElMessage, "info");
      loading.value = true;

      stopGenerating();

      expect(loading.value).toBe(false);
      expect(info).toHaveBeenCalledWith("已停止生成");
    });

    it("没有进行中的请求时不做任何事", () => {
      const info = vi.spyOn(ElMessage, "info");

      stopGenerating();

      expect(info).not.toHaveBeenCalled();
    });
  });

  describe("selectModel", () => {
    it("选择直连模型只切换路由，不写入 Dify 应用变量", () => {
      enableDirectMode();
      modelNameVariable.value = "model_name";
      userInput.value = { model_name: "旧模型" };

      selectModel("direct-5");

      expect(activeModelId.value).toBe("direct-5");
      expect(userInput.value).toEqual({ model_name: "旧模型" });
    });

    it("选择 Dify 模型写入应用变量", () => {
      selectModel("dify-model-a");

      expect(userInput.value).toEqual({ model_name: "dify-model-a" });
    });
  });
});
