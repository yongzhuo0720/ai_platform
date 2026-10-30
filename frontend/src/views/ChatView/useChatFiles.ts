import { ElMessage } from "element-plus";
import { uploadFileToDify } from "@/api/request";
import {
  activeModelId,
  attachedFiles,
  cancelCurrentRequest,
  isDirectMode,
  isDirectModelValue,
  isDragging,
  loading,
  messages,
  modelNameVariable,
  userInput,
} from "./chatState";
import { allowFileUpload, isModelNameVariable, maxFileUploadCount } from "./chatComposerState";
import { assistantGreetingName } from "./useChatSelectors";

/**
 * 处理拖拽悬停事件
 * @param e - 拖拽事件对象
 * @description 当用户拖拽文件进入聊天区域时调用，设置 isDragging 为 true 显示覆盖层
 */
export const handleDragOver = (e: DragEvent) => {
  e.preventDefault();
  e.stopPropagation();
  isDragging.value = true;
};

/**
 * 处理拖拽离开事件
 * @param e - 拖拽事件对象
 * @description 当用户拖拽文件离开聊天区域时调用，设置 isDragging 为 false 隐藏覆盖层
 */
export const handleDragLeave = (e: DragEvent) => {
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
export const handleDrop = async (e: DragEvent) => {
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
/**
 * 图片压缩为 dataURL：Canvas 缩到最长边 1280 / JPEG 82%。
 * 网关请求体上限 2MB，原图直接 base64 很容易超；压缩后单图约 200-400KB。
 */
const compressImageToDataUrl = (file: File, maxSide = 1280): Promise<string> =>
  new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error("读取文件失败"));
    reader.onload = () => {
      const img = new Image();
      img.onerror = () => reject(new Error("解析图片失败"));
      img.onload = () => {
        const scale = Math.min(1, maxSide / Math.max(img.width, img.height));
        const canvas = document.createElement("canvas");
        canvas.width = Math.round(img.width * scale);
        canvas.height = Math.round(img.height * scale);
        const ctx = canvas.getContext("2d");
        if (!ctx) { reject(new Error("画布不可用")); return; }
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
        resolve(canvas.toDataURL("image/jpeg", 0.82));
      };
      img.src = String(reader.result);
    };
    reader.readAsDataURL(file);
  });

export const handleFileSelected = async (file: File) => {
  if (!allowFileUpload.value) {
    ElMessage.warning("当前对话未开启文件上传");
    return;
  }

  if (isDirectMode.value) {
    // 直连模式：图片本地压缩成 dataURL 随消息体走网关（不走 Dify 上传）
    if (!file.type.startsWith("image/")) {
      ElMessage.warning("直连模型当前仅支持图片附件");
      return;
    }
    if (attachedFiles.value.length >= 2) {
      ElMessage.warning("直连模式最多携带 2 张图片");
      return;
    }
    try {
      const dataUrl = await compressImageToDataUrl(file);
      attachedFiles.value.push({
        id: `local-${Date.now()}`,
        name: file.name,
        type: file.type,
        size: file.size,
        dataUrl,
      });
      ElMessage.success(`已附加图片 ${file.name}`);
    } catch (error) {
      console.error("读取图片失败:", error);
      ElMessage.error("读取图片失败，请重试");
    }
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
export const copyMessage = async (content: string) => {
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
export const stopGenerating = () => {
  if (!loading.value) return;
  cancelCurrentRequest();
  ElMessage.info("已停止生成");
};

/**
 * 把指定助手回答导出为 Markdown 文件（含来源助手名与导出时间）。
 */
export const exportMessageMarkdown = (messageId: string) => {
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
export const removeAttachedFile = (fileId: string) => {
  attachedFiles.value = attachedFiles.value.filter(f => f.id !== fileId);
};

export const updateInputField = ({ variable, value }: { variable: string; value: string }) => {
  userInput.value = {
    ...userInput.value,
    [variable]: value,
  };

  if (isModelNameVariable(variable) && value) {
    activeModelId.value = value;
  }
};

export const selectModel = (value: string) => {
  // 直连模型：模型即路由目标，不需要写入 Dify 应用变量
  if (isDirectModelValue(value)) {
    activeModelId.value = value;
    return;
  }
  const variable = modelNameVariable.value || "model_name";
  updateInputField({ variable, value });
};
