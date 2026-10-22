import { computed, ref, nextTick, onMounted, onUnmounted, watch, type ComputedRef } from "vue";
import { ElMessageBox, ElMessage, ElLoading, type ElTree } from "element-plus";
import {
  sendAgentMessageStreaming,
  fetchSkillList,
  uploadAgentFile,
  getAgentUserId,
  setActiveAgentConfig,
  getActiveAgentConfig,
  type AgentContentPart,
  type AgentSkillItem,
} from "@/api/agentAssistant";
import { assistantApi, type AssistantData } from "@/api/assistant";
import { unitApi } from "@/api/unit";
import { roleApi } from "@/api/role";
import { getCurrentUserId } from "@/utils/user";
import Cookies from "js-cookie";
import { isSuperAdmin } from '@/config/constants'
import { SUPER_ADMIN_RID } from '@/config/constants'
import { renderMarkdown, renderUserContent } from "@/utils/markdown";
import type AgentComposer from "./AgentComposer.vue";

export type MessageRole = "user" | "assistant";

/** 已上传的附件：本地展示信息 + 服务端文件引用 */
export interface AttachedFileItem {
  id: string;
  name: string;
  type: string;
  size: number;
  /** 服务端返回的文件引用，发送时通过 file_url 传给 Agent */
  url: string;
  fileName: string;
}

export interface MessageItem {
  id: string;
  role: MessageRole;
  content: string;
  /** 思考过程（Thinking）文本（旧版消息兼容字段，新版使用 steps） */
  thinking?: string;
  /** Agent 执行步骤时间线（Thinking / 工具调用 / 工具输出，仿 QwenPaw Desktop） */
  steps?: AgentStepItem[];
  /** 发送给 Agent 的原始请求内容（重新生成时使用） */
  requestContent?: string;
  /** 该消息携带的附件（发送与重新生成时使用） */
  requestFiles?: AttachedFileItem[];
  /** 来源用户消息 id（重新生成时定位） */
  sourceUserMessageId?: string;
}

/** Agent 执行步骤（时间线中的一项） */
export interface AgentStepItem {
  id: string;
  kind: "thinking" | "tool" | "tool-output";
  /** 工具名称（kind 为 tool 时） */
  name?: string;
  /** 步骤文本内容（thinking / tool-output） */
  content: string;
  status: "running" | "done";
}

/** 本地话题：每个话题对应一个 Qwen Paw session_id，用于多轮上下文 */
export interface AgentTopic {
  id: string;
  /** 话题所属智能体助手 id（切换助手后按此恢复各自的聊天记录） */
  assistantId: string;
  /** Qwen Paw session_id，同一话题内保持不变以维持上下文 */
  sessionId: string;
  name: string;
  messages: MessageItem[];
  createdAt: number;
  updatedAt: number;
}

/** 智能体助手侧栏 Tab：助手 / 话题 */
export type AgentSideTab = "assistant" | "topic";

/** 助手列表项（内置助手 + 后端配置的自定义助手） */
export interface AgentAssistantItem {
  id: string;
  name: string;
  /** 是否为内置助手（内置助手不可编辑/删除） */
  builtin: boolean;
  /** 智能体服务类型 */
  type?: string;
  /** 后端助手记录 id（网关模式按此选择助手） */
  iid?: number;
  agentId?: string;
  apiUrl?: string;
  authToken?: string;
  channel?: string;
  userId?: string;
  prompt?: string;
  /** 权限类型：public=所有人可见 / partial_invisible=部分不可见 */
  permissionType?: string;
  /** 不可见部门编号（JSON 数组字符串） */
  unitsnos?: string;
  /** 不可见角色 id（JSON 数组字符串） */
  roleiids?: string;
}

/** 添加/编辑助手弹窗表单 */
export interface AgentAssistantForm {
  name: string;
  type: string;
  agentId: string;
  apiUrl: string;
  authToken: string;
  channel: string;
  prompt: string;
}

export interface UseAgentAssistantReturn {
  historyCollapsed: typeof historyCollapsed;
  inputValue: typeof inputValue;
  messages: typeof messages;
  chatMessagesRef: typeof chatMessagesRef;
  composerRef: typeof composerRef;
  topics: typeof topics;
  topicsForActiveAssistant: typeof topicsForActiveAssistant;
  activeTopicId: typeof activeTopicId;
  loading: typeof loading;
  sideTab: typeof sideTab;
  assistantGreetingName: ComputedRef<string>;
  assistants: typeof assistants;
  activeAssistantId: typeof activeAssistantId;
  assistantDialogVisible: typeof assistantDialogVisible;
  assistantEditingId: typeof assistantEditingId;
  assistantForm: typeof assistantForm;
  assistantDeleteConfirm: typeof assistantDeleteConfirm;
  activeAssistant: typeof activeAssistant;
  canEditAssistant: (assistant: AgentAssistantItem) => boolean;
  canDeleteAssistant: (assistant: AgentAssistantItem) => boolean;
  canManagePermission: (assistant: AgentAssistantItem) => boolean;
  permissionDialogVisible: typeof permissionDialogVisible;
  permissionEditingAssistant: typeof permissionEditingAssistant;
  permissionForm: typeof permissionForm;
  deptTree: typeof deptTree;
  roleList: typeof roleList;
  permissionTreeRef: typeof permissionTreeRef;
  permissionTreeProps: typeof permissionTreeProps;
  openPermissionDialog: (assistant: AgentAssistantItem) => Promise<void>;
  closePermissionDialog: () => void;
  savePermission: () => Promise<void>;
  handlePermissionDeptCheckChange: (data: any, checked: boolean) => void;
  isEditingAssistant: typeof isEditingAssistant;
  isAdmin: typeof isAdmin;
  showAdvancedApiUrl: typeof showAdvancedApiUrl;
  maskToken: (token: string | undefined) => string;
  toggleAdvancedApiUrl: () => void;
  openAssistantDialog: () => void;
  openAssistantEditDialog: (assistant: AgentAssistantItem) => void;
  closeAssistantDialog: () => void;
  confirmAssistantDialog: () => Promise<void>;
  requestDeleteAssistant: (assistant: AgentAssistantItem) => void;
  cancelDeleteAssistant: () => void;
  confirmDeleteAssistant: () => Promise<void>;
  selectAssistant: (assistantId: string) => void;
  assistantMenuOpenId: typeof assistantMenuOpenId;
  toggleAssistantMenu: (assistantId: string) => void;
  closeAssistantMenu: () => void;
  skills: typeof skills;
  skillsLoading: typeof skillsLoading;
  skillMenuVisible: typeof skillMenuVisible;
  attachedFiles: typeof attachedFiles;
  isDragging: typeof isDragging;
  submitMessage: () => Promise<void>;
  toggleHistorySidebar: () => void;
  handleSelectTopic: (topicId: string) => void;
  handleDeleteTopic: (topicId: string) => Promise<void>;
  startNewTopic: () => void;
  copyMessage: (content: string) => Promise<void>;
  canRegenerateMessage: (messageId: string) => boolean;
  handleRegenerateMessage: (messageId: string) => Promise<void>;
  formatContent: (content: string) => string;
  formatUserContent: (content: string) => string;
  /** 输入框内容变化：检测 "/" 触发技能弹层 */
  handleInputChange: (value: string) => void;
  /** 选择技能：把输入框中的 /xxx 替换为 "/技能名 " */
  selectSkill: (skillName: string) => void;
  /** 关闭技能弹层 */
  closeSkillMenu: () => void;
  /** 点击附件按钮选择文件后上传 */
  handleFileSelected: (file: File) => Promise<void>;
  /** 移除已上传的附件 */
  removeAttachedFile: (fileId: string) => void;
  /** 拖拽文件悬停 */
  handleDragOver: (e: DragEvent) => void;
  /** 拖拽文件离开 */
  handleDragLeave: (e: DragEvent) => void;
  /** 拖拽文件放下并上传 */
  handleDrop: (e: DragEvent) => Promise<void>;
}

const currentUserId = ref(getCurrentUserId());

const getTopicsStorageKey = () => `agent-topics-${currentUserId.value}`;

const readTopics = (): AgentTopic[] => {
  if (typeof window === "undefined") {
    return [];
  }
  try {
    const raw = window.localStorage.getItem(getTopicsStorageKey());
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    // 兼容历史数据：旧话题没有 assistantId 字段，统一归属到内置助手
    return (parsed as AgentTopic[]).map((item) =>
      item.assistantId ? item : { ...item, assistantId: "builtin" }
    );
  } catch (error) {
    console.warn("[智能体助手] 读取本地话题失败:", error);
    return [];
  }
};

const writeTopics = (topics: AgentTopic[]) => {
  if (typeof window === "undefined") {
    return;
  }
  try {
    window.localStorage.setItem(getTopicsStorageKey(), JSON.stringify(topics));
  } catch (error) {
    console.warn("[智能体助手] 写入本地话题失败:", error);
  }
};

/** 生成会话 ID（Qwen Paw session_id），优先使用 crypto.randomUUID */
const createSessionId = (): string => {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `session-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
};

const createTopicId = (): string => {
  return `topic-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
};

// ================= 模块级共享状态（与 ChatView 的组织方式一致） =================

const historyCollapsed = ref(false);
const sideTab = ref<AgentSideTab>("assistant");
const inputValue = ref("");
const messages = ref<MessageItem[]>([]);
const chatMessagesRef = ref<HTMLDivElement | null>(null);
const composerRef = ref<InstanceType<typeof AgentComposer> | null>(null);
const topics = ref<AgentTopic[]>(readTopics());
const activeTopicId = ref<string>("");
const loading = ref(false);

/** 当前活跃话题 */
const activeTopic = computed<AgentTopic | null>(
  () => topics.value.find((item) => item.id === activeTopicId.value) ?? null
);

// ================= 助手（多智能体）管理 =================

/** 内置默认助手（Qwen Paw） */
const BUILTIN_ASSISTANT: AgentAssistantItem = {
  id: "builtin",
  name: "智能体助手",
  builtin: true,
};

/** 助手列表：内置助手 + 后端配置的自定义助手 */
const assistants = ref<AgentAssistantItem[]>([BUILTIN_ASSISTANT]);
/** 当前激活助手 id */
const activeAssistantId = ref<string>("builtin");

/** 当前助手维度下的话题列表（按 assistantId 过滤，切换助手后各看各的记录） */
const topicsForActiveAssistant = computed<AgentTopic[]>(() =>
  topics.value.filter((item) => item.assistantId === activeAssistantId.value)
);

/**
 * 当前助手最近更新的话题（用于切换助手后自动恢复最近一次会话）
 */
const latestTopicForActiveAssistant = computed<AgentTopic | null>(() => {
  const list = topicsForActiveAssistant.value;
  if (list.length === 0) return null;
  return [...list].sort((a, b) => b.updatedAt - a.updatedAt)[0];
});

/** 添加/编辑助手弹窗可见性 */
const assistantDialogVisible = ref(false);
/** 编辑中的助手 id（null 表示新增） */
const assistantEditingId = ref<string | null>(null);
/** 助手弹窗表单 */
const assistantForm = ref<AgentAssistantForm>({
  name: "",
  type: "qwen_paw",
  agentId: "",
  apiUrl: "",
  authToken: "",
  channel: "console",
  prompt: "",
});
/** 删除助手二次确认 */
const assistantDeleteConfirm = ref<AgentAssistantItem | null>(null);

/** 是否编辑态（编辑助手弹窗，仅名称可编辑） */
const isEditingAssistant = computed(() => assistantEditingId.value !== null);
/** 是否管理员（rid=99999 超管，可查看完整 Token） */
const isAdmin = computed(() => isSuperAdmin(Cookies.get('rid')));
/** 高级设置展开：添加助手时点击「高级」显示接口地址 */
const showAdvancedApiUrl = ref(false);
/** 编辑前正在编辑的助手原始数据（保存时原样回传未被编辑的字段，避免 Token 等被清空） */
const editingOriginalAssistant = ref<AgentAssistantItem | null>(null);

// ================= 权限管理（管理员设置哪些助手对部分用户不可见） =================

/** 权限弹窗可见性 */
const permissionDialogVisible = ref(false);
/** 正在编辑权限的助手 */
const permissionEditingAssistant = ref<AgentAssistantItem | null>(null);
/** 权限表单：permissionType（public / partial_invisible）+ 不可见部门/角色 */
const permissionForm = ref<{
  permissionType: string;
  selectedDepts: string[];
  selectedRoles: number[];
}>({
  permissionType: "public",
  selectedDepts: [],
  selectedRoles: [],
});
/** 部门树与角色列表 */
const deptTree = ref<any[]>([]);
const roleList = ref<any[]>([]);
const permissionTreeRef = ref<InstanceType<typeof ElTree>>();
/** el-tree 数据字段映射 */
const permissionTreeProps = {
  children: "childList",
  label: "unitname",
};
/** 部门树/角色列表是否已异步加载过（惰性加载标志） */
let permissionsLoadedOnce = false;

/** Token 脱敏显示：保留前 8 位，其余以星号隐藏 */
const maskToken = (token: string | undefined): string => {
  if (!token) return "";
  return token.length <= 8 ? "********" : `${token.slice(0, 8)}****`;
};

/** 切换「高级」设置展开状态（显示/隐藏接口地址） */
const toggleAdvancedApiUrl = () => {
  showAdvancedApiUrl.value = !showAdvancedApiUrl.value;
};

/** 当前激活的助手 */
const activeAssistant = computed<AgentAssistantItem>(
  () =>
    assistants.value.find((item) => item.id === activeAssistantId.value) ??
    BUILTIN_ASSISTANT
);

/** 对话框空状态问候语名称（跟随当前助手） */
const assistantGreetingName = computed(() => activeAssistant.value.name);

/** 从助手 id 解析后端 iid（仅自定义助手） */
const extractAssistantIid = (id: string): number | null => {
  const match = id.match(/^custom-assistant-(\d+)$/);
  return match ? Number(match[1]) : null;
};

/** 助手是否可编辑（内置不可编辑） */
const canEditAssistant = (assistant: AgentAssistantItem): boolean => {
  return !assistant.builtin;
};

/** 助手是否可删除（内置不可删除） */
const canDeleteAssistant = (assistant: AgentAssistantItem): boolean => {
  return !assistant.builtin;
};

/** 助手是否可做权限管理（仅管理员，内置助手除外） */
const canManagePermission = (assistant: AgentAssistantItem): boolean => {
  return !assistant.builtin && isAdmin.value;
};

/** 加载部门树 */
const loadDeptTree = async () => {
  try {
    const { data: res } = await unitApi.getDataList({ data: {} });
    if (res.resp_code == 0) {
      deptTree.value = res.datas || [];
    }
  } catch (err) {
    console.error("[智能体助手] 获取部门树失败:", err);
  }
};

/** 加载角色列表（排除超管 99999） */
const loadRoleList = async () => {
  try {
    const { data: res } = await roleApi.getDataList({
      bean: {},
      pageRequest: { pageSize: 1000, pageNum: 1, order: "iid", desc: "desc" },
    });
    if (res.resp_code == 0) {
      roleList.value = (res.datas.content || []).filter((item: any) => item.iid !== SUPER_ADMIN_RID);
    }
  } catch (err) {
    console.error("[智能体助手] 获取角色列表失败:", err);
  }
};

/** 部门树勾选变化：维护 selectedDepts */
const handlePermissionDeptCheckChange = (data: any, checked: boolean) => {
  const deptId = data.unitsno;
  if (checked) {
    if (!permissionForm.value.selectedDepts.includes(deptId)) {
      permissionForm.value.selectedDepts.push(deptId);
    }
  } else {
    const index = permissionForm.value.selectedDepts.indexOf(deptId);
    if (index > -1) {
      permissionForm.value.selectedDepts.splice(index, 1);
    }
  }
};

/** 打开权限管理弹窗（数据懒加载） */
const openPermissionDialog = async (assistant: AgentAssistantItem) => {
  if (!canManagePermission(assistant)) return;
  permissionEditingAssistant.value = assistant;
  permissionForm.value = {
    permissionType: assistant.permissionType || "public",
    selectedDepts: (() => {
      try { return JSON.parse(assistant.unitsnos || "[]"); } catch { return []; }
    })(),
    selectedRoles: (() => {
      try { return JSON.parse(assistant.roleiids || "[]"); } catch { return []; }
    })(),
  };
  permissionDialogVisible.value = true;

  // 惰性加载部门树/角色列表（首次打开时）
  if (!permissionsLoadedOnce) {
    permissionsLoadedOnce = true;
    loadDeptTree();
    loadRoleList();
  }
  nextTick(() => {
    if (permissionTreeRef.value) {
      permissionTreeRef.value.setCheckedKeys(permissionForm.value.selectedDepts);
    }
  });
};

/** 关闭权限管理弹窗 */
const closePermissionDialog = () => {
  permissionDialogVisible.value = false;
  permissionEditingAssistant.value = null;
};

/** 保存权限配置（复用 assistantBean，仅更新权限字段） */
const savePermission = async () => {
  const assistant = permissionEditingAssistant.value;
  if (!assistant) return;
  const iid = extractAssistantIid(assistant.id);
  if (iid == null) {
    ElMessage.error("助手信息异常，请刷新后重试");
    return;
  }
  const loading = ElLoading.service({
    lock: true,
    text: "正在保存，请稍后",
    spinner: "el-icon-loading",
    background: "rgba(0, 0, 0, 0.7)",
  });
  const reqdata = {
    data: {
      permissionType: permissionForm.value.permissionType,
      unitsnos: JSON.stringify(permissionForm.value.selectedDepts),
      roleiids: JSON.stringify(permissionForm.value.selectedRoles),
    },
    where: { iid },
  };
  try {
    const { data: res } = await assistantApi.update(reqdata.data as any, reqdata.where);
    if (res.resp_code == 0) {
      ElMessage.success("权限配置保存成功");
      permissionDialogVisible.value = false;
      permissionEditingAssistant.value = null;
      await loadCustomAssistants();
    } else {
      ElMessage.error("保存失败，请重试");
    }
  } catch (err) {
    console.error("[智能体助手] 保存权限失败:", err);
    ElMessage.error("保存失败，请重试");
  } finally {
    loading.close();
  }
};

/** 根据助手配置，更新底层 agentAssistant 的活跃配置 */
const applyActiveAgentConfig = (assistant: AgentAssistantItem) => {
  setActiveAgentConfig({
    type: assistant.type,
    iid: assistant.iid,
    agentId: assistant.agentId,
    // 直连模式走 /qwen 代理；网关模式由 setActiveAgentConfig 按 iid 重写 baseUrl
    baseUrl: "/qwen",
    authToken: assistant.authToken,
    channel: assistant.channel,
  });
};

/** 加载后端配置的自定义助手列表 */
const loadCustomAssistants = async () => {
  try {
    const res = await assistantApi.query();
    const list: any[] = (res as any)?.data?.datas ?? [];
    const custom: AgentAssistantItem[] = (Array.isArray(list) ? list : []).map(
      (item: any) => ({
        id: `custom-assistant-${item.iid}`,
        name: item.name || "未命名助手",
        builtin: false,
        type: item.type || "qwen_paw",
        iid: item.iid,
        agentId: item.agentId || "",
        apiUrl: item.apiUrl || "",
        authToken: item.authToken || "",
        channel: item.channel || "console",
        userId: item.userId,
        prompt: item.prompt || "",
        permissionType: item.permissionType || "public",
        unitsnos: item.unitsnos || "[]",
        roleiids: item.roleiids || "[]",
      })
    );
    assistants.value = [BUILTIN_ASSISTANT, ...custom];
    // 若当前激活助手已不存在，回退到内置助手
    if (!assistants.value.some((item) => item.id === activeAssistantId.value)) {
      activeAssistantId.value = "builtin";
    }
    applyActiveAgentConfig(activeAssistant.value);
  } catch (error) {
    console.warn("[智能体助手] 加载自定义助手失败:", error);
    assistants.value = [BUILTIN_ASSISTANT];
  }
};

/** 打开新增助手弹窗 */
const openAssistantDialog = () => {
  assistantEditingId.value = null;
  assistantForm.value = {
    name: "",
    type: "qwen_paw",
    agentId: "",
    apiUrl: "",
    authToken: "",
    channel: "console",
    prompt: "",
  };
  showAdvancedApiUrl.value = false;
  editingOriginalAssistant.value = null;
  assistantDialogVisible.value = true;
};

/** 打开编辑助手弹窗 */
const openAssistantEditDialog = (assistant: AgentAssistantItem) => {
  if (assistant.builtin || !canEditAssistant(assistant)) return;
  assistantEditingId.value = assistant.id;
  assistantForm.value = {
    name: assistant.name,
    type: assistant.type || "qwen_paw",
    agentId: assistant.agentId || "",
    apiUrl: assistant.apiUrl || "",
    authToken: assistant.authToken || "",
    channel: assistant.channel || "console",
    prompt: assistant.prompt || "",
  };
  // 记录编辑前原始数据：保存时原样回传 Token 等未编辑字段，避免被清空
  editingOriginalAssistant.value = { ...assistant };
  showAdvancedApiUrl.value = false;
  assistantDialogVisible.value = true;
};

/** 关闭助手弹窗 */
const closeAssistantDialog = () => {
  assistantDialogVisible.value = false;
  assistantEditingId.value = null;
  editingOriginalAssistant.value = null;
  showAdvancedApiUrl.value = false;
};

/** 确认新增/编辑助手 */
const confirmAssistantDialog = async () => {
  const editingId = assistantEditingId.value;
  const form = assistantForm.value;
  const name = form.name.trim();
  if (!name) {
    ElMessage.warning("请输入助手名称");
    return;
  }

  // 编辑态：仅名称可改，其余字段（agentId/apiUrl/authToken/channel/prompt/type）原样回传，
  // 绕过表单里的脱敏/只读字段，避免 Token 被清空或破坏。
  const original = editingOriginalAssistant.value;
  const payload: AssistantData = editingId
    ? {
        name,
        prompt: original?.prompt ?? "",
        type: original?.type || "qwen_paw",
        agentId: original?.agentId || "",
        apiUrl: original?.apiUrl || "",
        authToken: original?.authToken || "",
        channel: original?.channel || "console",
      }
    : {
        name,
        prompt: "",
        type: form.type.trim() || "qwen_paw",
        agentId: form.agentId.trim(),
        apiUrl: form.apiUrl.trim(),
        authToken: form.authToken.trim(),
        channel: "console",
      };

  // 新增态校验必填项
  if (!editingId) {
    if (!payload.agentId?.trim()) {
      ElMessage.warning("请输入智能体 ID（X-Agent-Id）");
      return;
    }
    if (!payload.authToken?.trim()) {
      ElMessage.warning("请输入认证 Token");
      return;
    }
  }

  try {
    if (editingId) {
      const iid = extractAssistantIid(editingId);
      if (iid == null) {
        ElMessage.error("助手信息异常，请刷新后重试");
        return;
      }
      await assistantApi.update(payload, { iid });
    } else {
      await assistantApi.create(payload);
    }
    ElMessage.success(editingId ? "助手已更新" : "助手已添加");
    assistantDialogVisible.value = false;
    assistantEditingId.value = null;
    await loadCustomAssistants();
    // 新增后切换到最后添加的助手
    if (!editingId) {
      const created = assistants.value.find(
        (item) => !item.builtin && item.name === name
      );
      if (created) {
        selectAssistant(created.id);
      }
    }
  } catch (error) {
    console.error("[智能体助手] 保存助手失败:", error);
    ElMessage.error("保存失败，请重试");
  }
};

/** 请求删除助手 */
const requestDeleteAssistant = (assistant: AgentAssistantItem) => {
  if (assistant.builtin || !canDeleteAssistant(assistant)) return;
  assistantDeleteConfirm.value = assistant;
};

/** 取消删除助手 */
const cancelDeleteAssistant = () => {
  assistantDeleteConfirm.value = null;
};

/** 确认删除助手 */
const confirmDeleteAssistant = async () => {
  const target = assistantDeleteConfirm.value;
  if (!target) return;
  const iid = extractAssistantIid(target.id);
  if (iid == null) {
    assistantDeleteConfirm.value = null;
    return;
  }
  try {
    await assistantApi.delete({ iid });
    ElMessage.success("助手已删除");
  } catch (error) {
    console.error("[智能体助手] 删除助手失败:", error);
    ElMessage.error("删除失败，请重试");
  }
  assistantDeleteConfirm.value = null;
  await loadCustomAssistants();
  // 清理被删助手名下的话题
  topics.value = topics.value.filter((item) => item.assistantId !== target.id);
  persistTopics();
  if (activeAssistantId.value === target.id) {
    activeAssistantId.value = "builtin";
    applyActiveAgentConfig(BUILTIN_ASSISTANT);
    // 删除的是当前助手：对话区切回内置助手，并恢复其最近一次会话
    restoreLatestTopicForActiveAssistant();
  }
};

/** 助手条目"更多操作"菜单的展开 id（null 表示全部收起） */
const assistantMenuOpenId = ref<string | null>(null);

/** 切换助手条目的更多操作菜单 */
const toggleAssistantMenu = (assistantId: string) => {
  assistantMenuOpenId.value =
    assistantMenuOpenId.value === assistantId ? null : assistantId;
};

/** 关闭助手更多操作菜单 */
const closeAssistantMenu = () => {
  assistantMenuOpenId.value = null;
};

/** 切换助手 */
const selectAssistant = (assistantId: string) => {
  if (activeAssistantId.value === assistantId) return;

  // 切换前：先把当前助手的对话写回话题，避免丢失
  persistActiveTopic();

  activeAssistantId.value = assistantId;
  const target = assistants.value.find((item) => item.id === assistantId);
  if (!target) return;
  applyActiveAgentConfig(target);

  // 切换助手后清空当前对话，避免上下文串用
  cancelCurrentRequest();
  inputValue.value = "";
  attachedFiles.value = [];
  skills.value = [];
  skillsLoaded = false;
  closeSkillMenu();

  // 自动恢复目标助手最近一次会话的聊天记录
  restoreLatestTopicForActiveAssistant();
};

/** 恢复当前助手最近一次会话（无记录则进入空状态） */
const restoreLatestTopicForActiveAssistant = () => {
  const topic = latestTopicForActiveAssistant.value;
  if (topic) {
    activeTopicId.value = topic.id;
    messages.value = [...topic.messages];
  } else {
    activeTopicId.value = "";
    messages.value = [];
  }
};

// ================= 技能弹层状态 =================

const skills = ref<AgentSkillItem[]>([]);
const skillsLoading = ref(false);
/** 技能列表是否已成功加载过（缓存，避免每次输入 "/" 都请求） */
let skillsLoaded = false;
const skillMenuVisible = ref(false);

/**
 * 加载技能列表
 * 首次触发时通过 GET /api/skills 获取（失败回退 /skills 消息方式），之后使用缓存
 */
const loadSkills = async () => {
  if (skillsLoaded || skillsLoading.value) {
    return;
  }
  skillsLoading.value = true;
  try {
    const list = await fetchSkillList();
    if (list.length > 0) {
      skills.value = list;
      skillsLoaded = true;
    }
  } catch (error) {
    console.warn("[智能体助手] 获取技能列表失败:", error);
  } finally {
    skillsLoading.value = false;
  }
};

/** 关闭技能弹层 */
const closeSkillMenu = () => {
  skillMenuVisible.value = false;
};

/**
 * 输入框内容变化处理
 * 输入以 "/" 开头且未包含空格时（如 "/"、"/sk"），弹出技能列表
 */
const handleInputChange = (value: string) => {
  const trimmed = value.trim();
  const isSlashCommand = trimmed.startsWith("/") && !trimmed.includes(" ");
  if (isSlashCommand) {
    if (!skillsLoading.value && skills.value.length === 0) {
      loadSkills();
    }
    skillMenuVisible.value = true;
  } else {
    skillMenuVisible.value = false;
  }
};

/**
 * 选择技能：把输入框中的 "/xxx" 部分替换为 "/技能名 "
 * 例如输入 "/" 后选择技能"文档分析" → "/文档分析 "，用户继续输入问题即可发送
 */
const selectSkill = (skillName: string) => {
  const current = inputValue.value;
  const slashIndex = current.indexOf("/");
  const beforeSlash = slashIndex >= 0 ? current.slice(0, slashIndex) : "";
  inputValue.value = `${beforeSlash}/${skillName} `;
  closeSkillMenu();
  nextTick(() => {
    composerRef.value?.focus();
  });
};

// ================= 话题管理（本地存储） =================

/** 持久化当前话题列表 */
const persistTopics = () => {
  writeTopics(topics.value);
};

/** 将当前消息写入活跃话题 */
const persistActiveTopic = () => {
  const topic = activeTopic.value;
  if (!topic) return;
  topic.messages = [...messages.value];
  topic.updatedAt = Date.now();
  persistTopics();
};

/** 新建话题：生成新 sessionId，清空消息区 */
const startNewTopic = () => {
  cancelCurrentRequest();
  activeTopicId.value = "";
  messages.value = [];
  inputValue.value = "";
  attachedFiles.value = [];
  closeSkillMenu();
};

/** 确保存在一个活跃话题（发送消息前调用，首个话题名用消息前 20 字命名） */
const ensureActiveTopic = (firstMessage: string): AgentTopic => {
  let topic = activeTopic.value;
  if (topic) return topic;

  const fallbackName = firstMessage.trim().slice(0, 20) || "新话题";
  topic = {
    id: createTopicId(),
    assistantId: activeAssistantId.value,
    sessionId: createSessionId(),
    name: fallbackName,
    messages: [],
    createdAt: Date.now(),
    updatedAt: Date.now(),
  };
  topics.value = [topic, ...topics.value];
  activeTopicId.value = topic.id;
  persistTopics();
  return topic;
};

/** 切换话题：从本地加载该话题的消息 */
const handleSelectTopic = (topicId: string) => {
  if (activeTopicId.value === topicId) {
    return;
  }
  // 切换前先把当前话题的消息写回，避免流式进行中切换导致丢失
  persistActiveTopic();
  cancelCurrentRequest();
  const topic = topics.value.find((item) => item.id === topicId);
  if (!topic) return;
  activeTopicId.value = topicId;
  messages.value = [...topic.messages];
  inputValue.value = "";
  closeSkillMenu();
};

/** 删除话题（二次确认） */
const handleDeleteTopic = async (topicId: string) => {
  try {
    await ElMessageBox.confirm("确定要删除此话题吗？", "删除话题", {
      confirmButtonText: "确定",
      cancelButtonText: "取消",
      type: "warning",
    });
  } catch (error) {
    if (error !== "cancel") {
      console.error("删除话题失败:", error);
    }
    return;
  }

  cancelCurrentRequest();
  topics.value = topics.value.filter((item) => item.id !== topicId);
  persistTopics();
  if (activeTopicId.value === topicId) {
    activeTopicId.value = "";
    messages.value = [];
    inputValue.value = "";
  }
  ElMessage.success("删除成功");
};

// ================= 附件上传 =================

/** 已上传的附件列表（点击加号或拖拽上传） */
const attachedFiles = ref<AttachedFileItem[]>([]);
/** 是否正在拖拽文件，用于显示拖拽覆盖层 */
const isDragging = ref(false);
/** 最多可上传的附件数量 */
const MAX_FILE_UPLOAD_COUNT = 10;

const createAttachmentId = (): string => {
  return `file-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
};

/** 上传文件到 Qwen Paw 并加入附件列表 */
const uploadFileInternal = async (file: File) => {
  if (attachedFiles.value.length >= MAX_FILE_UPLOAD_COUNT) {
    ElMessage.warning(`最多可上传 ${MAX_FILE_UPLOAD_COUNT} 个文件`);
    return;
  }
  try {
    ElMessage.info(`正在上传文件: ${file.name}`);
    const response = await uploadAgentFile(file);
    attachedFiles.value.push({
      id: createAttachmentId(),
      name: file.name,
      type: file.type,
      size: file.size,
      url: response.url,
      fileName: response.file_name || file.name,
    });
    ElMessage.success(`文件 ${file.name} 上传成功`);
  } catch (error) {
    console.error(`文件 ${file.name} 上传失败:`, error);
    ElMessage.error(`文件 ${file.name} 上传失败`);
  }
};

/** 点击附件按钮选择的文件 */
const handleFileSelected = async (file: File) => {
  await uploadFileInternal(file);
};

/** 移除已上传的附件 */
const removeAttachedFile = (fileId: string) => {
  attachedFiles.value = attachedFiles.value.filter((item) => item.id !== fileId);
};

/** 拖拽文件悬停 */
const handleDragOver = (e: DragEvent) => {
  e.preventDefault();
  e.stopPropagation();
  isDragging.value = true;
};

/** 拖拽文件离开 */
const handleDragLeave = (e: DragEvent) => {
  e.preventDefault();
  e.stopPropagation();
  isDragging.value = false;
};

/** 拖拽文件放下：批量上传 */
const handleDrop = async (e: DragEvent) => {
  e.preventDefault();
  e.stopPropagation();
  isDragging.value = false;

  const files = e.dataTransfer?.files;
  if (!files || files.length === 0) return;

  const remainingSlots = Math.max(
    MAX_FILE_UPLOAD_COUNT - attachedFiles.value.length,
    0
  );
  if (remainingSlots <= 0) {
    ElMessage.warning(`最多可上传 ${MAX_FILE_UPLOAD_COUNT} 个文件`);
    return;
  }

  const filesToUpload = Array.from(files).slice(0, remainingSlots);
  if (filesToUpload.length < files.length) {
    ElMessage.warning(`最多可上传 ${MAX_FILE_UPLOAD_COUNT} 个文件，已忽略超出部分`);
  }

  for (const file of filesToUpload) {
    await uploadFileInternal(file);
  }
};

// ================= 消息发送 =================

let currentStopFn: (() => void) | null = null;
let currentRequestToken = 0;

const cancelCurrentRequest = () => {
  if (currentStopFn) {
    currentStopFn();
    currentStopFn = null;
  }
  currentRequestToken += 1;
  loading.value = false;
};

/**
 * 发送消息到 Qwen Paw Agent
 * 只发送最新一条用户消息，多轮上下文由服务端按 session_id 维护。
 * 携带附件时，content 为 [文本, {type:"file", file_url, filename}...]，
 * Agent 收到文件引用后会自动识别文件内容并调用相应 skill 处理。
 */
const sendMessageInternal = async (options: {
  content: string;
  files?: AttachedFileItem[];
  appendUserMessage?: boolean;
  targetAssistantMessageId?: string;
  sourceUserMessageId?: string;
}) => {
  const {
    content,
    files = [],
    appendUserMessage = true,
    targetAssistantMessageId,
    sourceUserMessageId,
  } = options;

  if (!content.trim() && files.length === 0) {
    return;
  }

  cancelCurrentRequest();
  const requestToken = currentRequestToken;

  const topic = ensureActiveTopic(content || (files[0]?.name ?? "新话题"));
  const userMessageId = sourceUserMessageId ?? `user-${Date.now()}`;

  if (appendUserMessage) {
    messages.value.push({
      id: userMessageId,
      role: "user",
      content,
      requestContent: content,
      requestFiles: [...files],
    });
  }

  inputValue.value = "";
  attachedFiles.value = [];
  loading.value = true;

  const assistantMessageId = targetAssistantMessageId ?? `assistant-${Date.now()}`;

  const existingAssistantMessage = messages.value.find(
    (item) => item.id === assistantMessageId
  );
  if (existingAssistantMessage) {
    existingAssistantMessage.content = "";
    existingAssistantMessage.thinking = "";
    existingAssistantMessage.steps = [];
    existingAssistantMessage.requestContent = content;
    existingAssistantMessage.requestFiles = [...files];
    existingAssistantMessage.sourceUserMessageId = userMessageId;
  } else {
    messages.value.push({
      id: assistantMessageId,
      role: "assistant",
      content: "",
      thinking: "",
      steps: [],
      requestContent: content,
      requestFiles: [...files],
      sourceUserMessageId: userMessageId,
    });
  }

  // 构建消息内容：文本在前（便于服务端提取标题），文件引用在后
  const contentParts: AgentContentPart[] = [];
  if (content.trim()) {
    contentParts.push({ type: "text", text: content });
  }
  files.forEach((file) => {
    contentParts.push({
      type: "file",
      file_url: file.url,
      filename: file.fileName,
    });
  });

  currentStopFn = sendAgentMessageStreaming(
    {
      input: [
        {
          role: "user",
          content: contentParts,
        },
      ],
      session_id: topic.sessionId,
      // user_id 按登录角色+用户区分（角色ID_用户ID）
      user_id: getAgentUserId(),
      channel: getActiveAgentConfig().channel,
    },
    {
      onSnapshot: (snapshot) => {
        if (requestToken !== currentRequestToken) {
          return;
        }
        const msg = messages.value.find((item) => item.id === assistantMessageId);
        if (!msg) return;
        // 整体替换渲染（与官方前端一致的状态树模式：事件重复/乱序均幂等）
        msg.content = snapshot.body;
        msg.steps = snapshot.steps.map((step) => ({
          id: step.id,
          kind: step.kind,
          name: step.name,
          content: step.content,
          status: step.running ? "running" : "done",
        }));
      },
      onComplete: () => {
        if (requestToken !== currentRequestToken) {
          return;
        }
        loading.value = false;
        const msg = messages.value.find((item) => item.id === assistantMessageId);
        if (msg) {
          // 兜底：流结束后内容仍为空，给出提示而非空白
          if (!msg.content || !msg.content.trim()) {
            msg.content = "Agent 未返回有效内容，请重试。";
          }
          // 兜底：把仍在运行的步骤标记完成，避免 UI 一直显示运行中
          if (msg.steps) {
            msg.steps.forEach((step) => {
              step.status = "done";
            });
          }
        }
        currentStopFn = null;
        persistActiveTopic();
      },
      onError: (error) => {
        if (requestToken !== currentRequestToken) {
          return;
        }
        console.error("[智能体助手] 发送消息失败:", error);
        const msg = messages.value.find((item) => item.id === assistantMessageId);
        if (msg) {
          msg.content = `调用失败：${error.message || "请稍后重试"}`;
        }
        loading.value = false;
        currentStopFn = null;
        persistActiveTopic();
      },
    }
  );
};

/** 提交输入框内容（支持只有附件没有文字） */
const submitMessage = async () => {
  if (loading.value) {
    return;
  }
  const content = inputValue.value.trim();
  const files = [...attachedFiles.value];

  if (!content && files.length === 0) {
    return;
  }

  closeSkillMenu();
  await sendMessageInternal({ content, files });
};

// ================= 消息操作 =================

/** 复制消息内容到剪贴板 */
const copyMessage = async (content: string) => {
  try {
    await navigator.clipboard.writeText(content);
    ElMessage.success("已复制到剪贴板");
  } catch {
    // clipboard API 不可用时，回退到 execCommand 方式
    try {
      const textarea = document.createElement("textarea");
      textarea.value = content;
      textarea.style.position = "fixed";
      textarea.style.opacity = "0";
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand("copy");
      document.body.removeChild(textarea);
      ElMessage.success("已复制到剪贴板");
    } catch (fallbackError) {
      console.error("复制失败:", fallbackError);
      ElMessage.error("复制失败");
    }
  }
};

/** 查找某条助手消息之前的最近一条用户消息 */
const getPreviousUserMessage = (assistantMessageId: string): MessageItem | null => {
  const assistantIndex = messages.value.findIndex((item) => item.id === assistantMessageId);
  if (assistantIndex <= 0) {
    return null;
  }
  for (let index = assistantIndex - 1; index >= 0; index -= 1) {
    const item = messages.value[index];
    if (item.role === "user") {
      return item;
    }
  }
  return null;
};

const getLatestAssistantMessageId = (): string => {
  for (let index = messages.value.length - 1; index >= 0; index -= 1) {
    if (messages.value[index].role === "assistant") {
      return messages.value[index].id;
    }
  }
  return "";
};

/** 当前消息是否允许重新生成（仅限最新一条助手消息且不在加载中） */
const canRegenerateMessage = (messageId: string): boolean => {
  if (loading.value) {
    return false;
  }
  const targetMessage = messages.value.find((item) => item.id === messageId);
  if (!targetMessage || targetMessage.role !== "assistant") {
    return false;
  }
  if (getLatestAssistantMessageId() !== messageId) {
    return false;
  }
  const sourceUserMessage = getPreviousUserMessage(messageId);
  if (!sourceUserMessage) {
    return false;
  }
  const requestContent = sourceUserMessage.requestContent || sourceUserMessage.content;
  const requestFiles = sourceUserMessage.requestFiles || [];
  return requestContent.trim().length > 0 || requestFiles.length > 0;
};

/**
 * 重新生成最新一条助手回复
 * 移除本地旧回复后重发上一条用户消息（含原附件）。
 * 注意：Qwen Paw 服务端按 session_id 维护上下文，无法撤回旧回复，
 * 重新生成后服务端上下文中仍保留历史回复，此为接口固有限制。
 */
const handleRegenerateMessage = async (messageId: string) => {
  if (!canRegenerateMessage(messageId)) {
    ElMessage.info("仅支持重新生成当前最新一条助手回复");
    return;
  }

  const sourceUserMessage = getPreviousUserMessage(messageId);
  if (!sourceUserMessage) {
    ElMessage.error("未找到可重新生成的用户消息");
    return;
  }

  const requestContent = sourceUserMessage.requestContent || sourceUserMessage.content;
  const requestFiles = sourceUserMessage.requestFiles || [];
  if (!requestContent.trim() && requestFiles.length === 0) {
    ElMessage.warning("当前消息缺少可重新生成的请求内容");
    return;
  }

  await sendMessageInternal({
    content: requestContent,
    files: requestFiles,
    appendUserMessage: false,
    targetAssistantMessageId: messageId,
    sourceUserMessageId: sourceUserMessage.id,
  });
};

// ================= 内容渲染 =================

/** 渲染助手消息的 Markdown 内容 */
const formatContent = (content: string): string => {
  return renderMarkdown(content);
};

/** 渲染用户消息内容（XSS 安全 + 保留换行） */
const formatUserContent = (content: string): string => {
  return renderUserContent(content);
};

// ================= 生命周期与自动滚动 =================

const toggleHistorySidebar = () => {
  historyCollapsed.value = !historyCollapsed.value;
};

export function useAgentAssistant(): UseAgentAssistantReturn {
  // 初始化：加载后端配置的自定义助手列表（异步，不阻塞 UI）
  loadCustomAssistants();

  const focusEmptyTextarea = () => {
    nextTick(() => {
      if (messages.value.length > 0 || loading.value) {
        return;
      }
      composerRef.value?.focus({ preventScroll: true });
    });
  };

  // 空状态时自动聚焦输入框
  watch(
    [() => messages.value.length, loading],
    ([messageLength, isChatLoading]) => {
      if (messageLength === 0 && !isChatLoading) {
        focusEmptyTextarea();
      }
    },
    { immediate: true }
  );

  // AI 流式输出时自动滚动到底部（双重 rAF 确保 Markdown 内容完成布局）
  watch(
    messages,
    () => {
      nextTick(() => {
        requestAnimationFrame(() => {
          requestAnimationFrame(() => {
            if (chatMessagesRef.value) {
              chatMessagesRef.value.scrollTop = chatMessagesRef.value.scrollHeight;
            }
          });
        });
      });
    },
    { deep: true }
  );

  // 组件卸载时中止进行中的请求
  onUnmounted(() => {
    cancelCurrentRequest();
  });

  // 点击页面任意位置关闭助手"更多操作"菜单
  onMounted(() => {
    if (typeof document !== "undefined") {
      document.addEventListener("click", closeAssistantMenu);
    }
  });
  onUnmounted(() => {
    if (typeof document !== "undefined") {
      document.removeEventListener("click", closeAssistantMenu);
    }
  });

  return {
    historyCollapsed,
    inputValue,
    messages,
    chatMessagesRef,
    composerRef,
    topics,
    topicsForActiveAssistant,
    activeTopicId,
    loading,
    sideTab,
    assistantGreetingName,
    assistants,
    activeAssistantId,
    assistantDialogVisible,
    assistantEditingId,
    assistantForm,
    assistantDeleteConfirm,
    activeAssistant,
    canEditAssistant,
    canDeleteAssistant,
    canManagePermission,
    permissionDialogVisible,
    permissionEditingAssistant,
    permissionForm,
    deptTree,
    roleList,
    permissionTreeRef,
    permissionTreeProps,
    openPermissionDialog,
    closePermissionDialog,
    savePermission,
    handlePermissionDeptCheckChange,
    isEditingAssistant,
    isAdmin,
    showAdvancedApiUrl,
    maskToken,
    toggleAdvancedApiUrl,
    openAssistantDialog,
    openAssistantEditDialog,
    closeAssistantDialog,
    confirmAssistantDialog,
    requestDeleteAssistant,
    cancelDeleteAssistant,
    confirmDeleteAssistant,
    selectAssistant,
    assistantMenuOpenId,
    toggleAssistantMenu,
    closeAssistantMenu,
    skills,
    skillsLoading,
    skillMenuVisible,
    submitMessage,
    toggleHistorySidebar,
    handleSelectTopic,
    handleDeleteTopic,
    startNewTopic,
    copyMessage,
    canRegenerateMessage,
    handleRegenerateMessage,
    formatContent,
    formatUserContent,
    handleInputChange,
    selectSkill,
    closeSkillMenu,
    attachedFiles,
    isDragging,
    handleFileSelected,
    removeAttachedFile,
    handleDragOver,
    handleDragLeave,
    handleDrop,
  };
}
