import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { ElLoading, ElMessage, ElMessageBox } from "element-plus";

const env = vi.hoisted(() => ({
  rid: "2",
  uid: "u-1",
  gateway: false,
  fileConfigured: true,
}));
const routerPush = vi.hoisted(() => vi.fn());

vi.mock("js-cookie", () => ({
  default: {
    get: vi.fn((key: string) =>
      key === "rid" ? env.rid : key === "uid" ? env.uid : undefined
    ),
  },
}));
vi.mock("vue-router", () => ({
  useRouter: () => ({ push: routerPush }),
}));
vi.mock("@/stores/difyConfig", () => ({
  isGatewayMode: vi.fn(() => env.gateway),
  isGatewayModuleConfigured: vi.fn(async () => env.fileConfigured),
}));
vi.mock("@/api/dataset", () => ({
  datasetApi: {
    getDatasets: vi.fn(),
    createDataset: vi.fn(),
    updateDataset: vi.fn(),
    deleteDataset: vi.fn(),
    getEmbeddingModels: vi.fn(),
  },
}));
vi.mock("@/api/knowledgeBaseMng", () => ({
  knowledgeBaseMngApi: {
    query: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    filterVisible: vi.fn(),
  },
}));
vi.mock("@/api/knowledgeBaseMember", () => ({
  knowledgeBaseMemberApi: {
    joinedIds: vi.fn(),
    members: vi.fn(),
    join: vi.fn(),
    leave: vi.fn(),
    remove: vi.fn(),
  },
}));
vi.mock("@/api/unit", () => ({ unitApi: { getDataList: vi.fn() } }));
vi.mock("@/api/role", () => ({ roleApi: { getDataList: vi.fn() } }));
vi.mock("@/api/tags", () => ({ tagsApi: { query: vi.fn() } }));
vi.mock("@/api/tagsbind", () => ({
  tagsBindApi: { query: vi.fn(), create: vi.fn(), delete: vi.fn() },
  default: { query: vi.fn(), create: vi.fn(), delete: vi.fn() },
}));
vi.mock("@/api/knowledgeChat", () => ({
  knowledgeChatStreaming: vi.fn(async () => ({})),
  retrieveFromDataset: vi.fn(async () => []),
  getAppParameters: vi.fn(async () => ({})),
  getConversations: vi.fn(async () => ({ data: [] })),
  getConversationHistory: vi.fn(async () => ({ data: [] })),
  deleteConversation: vi.fn(async () => ({})),
  getSuggestedMessages: vi.fn(async () => []),
}));
vi.mock("@/api/modelsConfig", () => ({
  modelsConfigApi: { getList: vi.fn() },
}));
vi.mock("@/api/request", () => ({ uploadFileToDify: vi.fn() }));
vi.mock("@/api/document", () => ({
  documentApi: {
    getDocuments: vi.fn(),
    deleteDocument: vi.fn(),
    createByFile: vi.fn(),
  },
}));
vi.mock("@/api/chunk", () => ({ chunkApi: { getChunks: vi.fn() } }));

import KnowledgeBaseView from "../index.vue";
import { datasetApi } from "@/api/dataset";
import { knowledgeBaseMngApi } from "@/api/knowledgeBaseMng";
import { knowledgeBaseMemberApi } from "@/api/knowledgeBaseMember";
import { unitApi } from "@/api/unit";
import { roleApi } from "@/api/role";
import { tagsApi } from "@/api/tags";
import { tagsBindApi } from "@/api/tagsbind";
import { modelsConfigApi } from "@/api/modelsConfig";
import { documentApi } from "@/api/document";
import { getAppParameters, getConversations, retrieveFromDataset } from "@/api/knowledgeChat";

/**
 * 知识库中心：三组列表加载、可见性过滤、管理弹窗校验与权限记录、成员与订阅操作。
 */

enableAutoUnmount(afterEach);

const getDatasets = datasetApi.getDatasets as Mock;
const createDataset = datasetApi.createDataset as Mock;
const updateDataset = datasetApi.updateDataset as Mock;
const deleteDataset = datasetApi.deleteDataset as Mock;
const getEmbeddingModels = datasetApi.getEmbeddingModels as Mock;
const kbQuery = knowledgeBaseMngApi.query as Mock;
const kbCreate = knowledgeBaseMngApi.create as Mock;
const kbUpdate = knowledgeBaseMngApi.update as Mock;
const kbDelete = knowledgeBaseMngApi.delete as Mock;
const filterVisible = knowledgeBaseMngApi.filterVisible as Mock;
const joinedIds = knowledgeBaseMemberApi.joinedIds as Mock;
const members = knowledgeBaseMemberApi.members as Mock;
const join = knowledgeBaseMemberApi.join as Mock;
const leave = knowledgeBaseMemberApi.leave as Mock;
const remove = knowledgeBaseMemberApi.remove as Mock;
const unitList = unitApi.getDataList as Mock;
const roleList = roleApi.getDataList as Mock;
const tagsQuery = tagsApi.query as Mock;
const bindQuery = tagsBindApi.query as Mock;
const modelsList = modelsConfigApi.getList as Mock;
const getDocuments = documentApi.getDocuments as Mock;

const ok = (datas: unknown, resp_msg = "ok") => ({
  data: { resp_code: 0, resp_msg, datas },
});
const fail = (resp_msg: string) => ({ data: { resp_code: 500, resp_msg } });

const allKbs = [
  {
    id: "kb-1",
    name: "我的知识库",
    description: "个人沉淀",
    word_count: 2048,
    updated_at: 1694502400,
    coverImage: "https://placeholder.com/kb-1.png",
    embedding_model: "text-embedding-3-small",
  },
  {
    id: "kb-2",
    name: "共享知识库",
    description: "团队共享",
    word_count: 0,
    coverImage: "https://cdn.example.com/kb-2.png",
    author_name: "张三",
  },
  {
    id: "kb-3",
    name: "已加入知识库",
    description: "订阅内容",
    partial_member_list: ["a", "b"],
    documents: [{ id: "d1" }],
  },
];

const visibleByContext: Record<string, string[]> = {
  my: ["kb-1"],
  shared: ["kb-2", "kb-3"],
  joined: ["kb-3"],
};

const mountView = () =>
  mount(KnowledgeBaseView, {
    global: {
      stubs: {
        KnowledgeBaseSquareView: true,
        KbChatPanel: true,
        TagManageDialog: true,
        DocumentPreviewDialog: true,
      },
    },
  });

const createMessageSpies = () => ({
  success: vi.spyOn(ElMessage, "success").mockReturnValue({ close: () => {} } as never),
  error: vi.spyOn(ElMessage, "error").mockReturnValue({ close: () => {} } as never),
  warning: vi.spyOn(ElMessage, "warning").mockReturnValue({ close: () => {} } as never),
});

const groupNames = (wrapper: ReturnType<typeof mountView>, header: string) => {
  const group = wrapper
    .findAll(".kb-sidebar-group")
    .find((item) => item.find(".kb-sidebar-group-header").text().includes(header));
  return group ? group.findAll(".kb-sidebar-child-name").map((item) => item.text()) : [];
};

const dialogOf = (wrapper: ReturnType<typeof mountView>) => wrapper.find(".el-dialog");

const clickDialogFooter = async (wrapper: ReturnType<typeof mountView>, text: string) => {
  const button = dialogOf(wrapper)
    .findAll(".el-dialog__footer button")
    .find((item) => item.text().includes(text));
  expect(button, `footer ${text}`).toBeTruthy();
  await button!.trigger("click");
  await flushPromises();
};

beforeEach(() => {
  env.rid = "2";
  env.uid = "u-1";
  env.gateway = false;
  env.fileConfigured = true;
  routerPush.mockReset();

  getDatasets.mockResolvedValue({
    data: allKbs.map((kb) => ({ ...kb })),
    has_more: false,
    total: allKbs.length,
    page: 1,
    limit: 100,
  });
  filterVisible.mockImplementation(async ({ data }: any) => ok(visibleByContext[data.context] || []));
  kbQuery.mockResolvedValue(
    ok([
      {
        kbId: "kb-1",
        permissionType: "partial_invisible",
        unitsnos: '["D1"]',
        roleiids: "[2]",
      },
    ])
  );
  kbCreate.mockResolvedValue(ok(true));
  kbUpdate.mockResolvedValue(ok(true));
  kbDelete.mockResolvedValue(ok(true));
  joinedIds.mockResolvedValue(ok(["kb-3"]));
  members.mockResolvedValue(
    ok([
      { userId: "u-1", userName: "我", isOwner: "1" },
      { userId: "u-2", userName: "同事", isOwner: "0" },
    ])
  );
  join.mockResolvedValue(ok(true));
  leave.mockResolvedValue(ok(true));
  remove.mockResolvedValue(ok(true));
  unitList.mockResolvedValue(
    ok([{ unitsno: "D1", unitname: "研发部", childList: [{ unitsno: "D11", unitname: "前端组", childList: [] }] }])
  );
  roleList.mockResolvedValue(ok({ content: [{ iid: 2, name: "普通用户" }, { iid: 99999, name: "超级管理员" }] }));
  tagsQuery.mockResolvedValue(ok([{ iid: 10, name: "产品" }, { iid: 11, name: "客服" }]));
  bindQuery.mockResolvedValue(
    ok([
      { hash: "kb-1", lableBeans: [{ iid: 10, name: "产品" }] },
      { hash: "kb-3", lableBeans: [{ iid: 11, name: "客服" }] },
    ])
  );
  getEmbeddingModels.mockResolvedValue([
    { provider: "openai", model: "text-embedding-3-small", label: "small" },
  ]);
  modelsList.mockResolvedValue({ data: { resp_code: 0, datas: [] } });
  (getAppParameters as Mock).mockResolvedValue({});
  (getConversations as Mock).mockResolvedValue({ data: [] });
  (retrieveFromDataset as Mock).mockResolvedValue([]);
  getDocuments.mockResolvedValue({ data: [] });
  createDataset.mockResolvedValue({ id: "kb-new", name: "新知识库" });
  updateDataset.mockResolvedValue({ id: "kb-1" });
  deleteDataset.mockResolvedValue(undefined);
});

describe("KnowledgeBaseView", () => {
  it("首屏加载个人/共享/订阅三组列表，按 context 过滤并自动选中首项", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(getDatasets).toHaveBeenCalledWith(1, 100, true);
    expect(filterVisible).toHaveBeenCalledWith({
      data: { context: "my", kbIds: ["kb-1", "kb-2", "kb-3"] },
    });
    expect(filterVisible).toHaveBeenCalledWith({
      data: { context: "shared", kbIds: ["kb-1", "kb-2", "kb-3"] },
    });
    expect(filterVisible).toHaveBeenCalledWith({
      data: { context: "joined", kbIds: ["kb-1", "kb-2", "kb-3"] },
    });

    expect(groupNames(wrapper, "个人知识库")).toEqual(["我的知识库"]);
    expect(groupNames(wrapper, "共享知识库")).toEqual(["共享知识库", "已加入知识库"]);
    expect(groupNames(wrapper, "订阅知识库")).toEqual(["已加入知识库"]);

    const vm = wrapper.vm as any;
    expect(vm.selectedKbId).toBe("kb-1");
    // 个人列表已排除共享列表中的库
    expect(vm.filteredKnowledgeBases.map((kb: any) => kb.id)).toEqual(["kb-1"]);
    expect(vm.filteredKnowledgeBases[0].tagList).toEqual([{ iid: 10, name: "产品" }]);
    expect(vm.filteredKnowledgeBases[0].size).toBe("2.0K字");
    expect(vm.filteredKnowledgeBases[0].coverImage).toContain("brand-cover.svg");
    expect(vm.roleList).toEqual([{ iid: 2, name: "普通用户" }]);
    expect(vm.embeddingModelOptions).toHaveLength(1);
    expect(getDocuments).toHaveBeenCalledWith("kb-1", 1, 100);
  });

  it("侧边栏搜索与标签筛选分别作用于个人、共享、订阅列表", async () => {
    const wrapper = mountView();
    await flushPromises();
    const vm = wrapper.vm as any;

    vm.sidebarSearchQuery = "共享";
    await flushPromises();
    expect(groupNames(wrapper, "个人知识库")).toEqual([]);
    expect(wrapper.text()).toContain("未找到匹配的知识库");

    vm.sidebarSearchQuery = "";
    vm.sidebarSelectedTag = ["10"];
    await flushPromises();
    expect(groupNames(wrapper, "个人知识库")).toEqual(["我的知识库"]);
    expect(groupNames(wrapper, "共享知识库")).toEqual([]);
    expect(wrapper.text()).toContain("没有符合标签的共享知识库");
  });

  it("网关模式未配置知识库文件模块时整页引导，不请求任何数据集", async () => {
    env.gateway = true;
    env.fileConfigured = false;
    const wrapper = mountView();
    await flushPromises();

    expect(getDatasets).not.toHaveBeenCalled();
    expect(wrapper.find(".kb-config-alert").exists()).toBe(true);
    expect(wrapper.find(".kb-add-btn").exists()).toBe(false);
    expect((wrapper.vm as any).knowledgeFileUnavailable).toBe(true);
  });

  it("个人列表加载失败时给出错误状态，共享与订阅列表继续可用", async () => {
    getDatasets.mockRejectedValueOnce(new Error("network"));
    const wrapper = mountView();
    await flushPromises();

    expect(groupNames(wrapper, "个人知识库")).toEqual([]);
    expect(groupNames(wrapper, "共享知识库")).toEqual(["共享知识库", "已加入知识库"]);
  });

  it("新建弹窗：空名称被表单校验拦截；补全后按 Dify → 权限记录顺序创建", async () => {
    const spies = createMessageSpies();
    const close = vi.fn();
    vi.spyOn(ElLoading, "service").mockReturnValue({ close } as never);
    const wrapper = mountView();
    await flushPromises();

    await wrapper.find(".kb-add-btn").trigger("click");
    await flushPromises();
    expect(dialogOf(wrapper).text()).toContain("新建知识库");

    // 默认向量模型已带入
    expect((wrapper.vm as any).mngFormData.embedding_model).toBe("text-embedding-3-small");
    await clickDialogFooter(wrapper, "确定");
    expect(createDataset).not.toHaveBeenCalled();
    expect(spies.error).toHaveBeenCalledWith("请填写完整的信息");
    expect(dialogOf(wrapper).find(".el-form-item.is-error").exists()).toBe(true);

    await dialogOf(wrapper).find('input[placeholder="请输入知识库名称"]').setValue("新知识库");
    await dialogOf(wrapper).find("textarea").setValue("新库描述");
    await clickDialogFooter(wrapper, "确定");

    expect(createDataset).toHaveBeenCalledWith({
      name: "新知识库",
      description: "新库描述",
      indexing_technique: "high_quality",
      embedding_model: "text-embedding-3-small",
      embedding_model_provider: "openai",
      permission: "only_me",
    });
    expect(kbCreate).toHaveBeenCalledWith({
      data: {
        kbId: "kb-new",
        ownerUid: "u-1",
        permissionType: "public",
        unitsnos: "[]",
        roleiids: "[]",
      },
    });
    expect(spies.success).toHaveBeenCalledWith("知识库创建成功");
    expect((wrapper.vm as any).mngDialogVisible).toBe(false);
    expect(close).toHaveBeenCalled();
    // 创建成功后刷新三组列表
    expect(getDatasets).toHaveBeenCalledTimes(6);
  });

  it("编辑弹窗：权限记录回填，损坏 JSON 回退为空，存在记录走 update、不存在走 create", async () => {
    createMessageSpies();
    vi.spyOn(ElLoading, "service").mockReturnValue({ close: vi.fn() } as never);
    const wrapper = mountView();
    await flushPromises();
    const vm = wrapper.vm as any;

    vm.openMngEditDialog(vm.filteredKnowledgeBases[0]);
    await flushPromises();
    expect(vm.mngFormData.name).toBe("我的知识库");
    expect(vm.mngFormData.permissionType).toBe("partial_invisible");
    expect(vm.mngFormData.selectedDepts).toEqual(["D1"]);
    expect(vm.mngFormData.selectedRoles).toEqual([2]);
    expect(vm.mngFormData.embedding_model).toBe("text-embedding-3-small");

    await clickDialogFooter(wrapper, "确定");
    expect(updateDataset).toHaveBeenCalledWith("kb-1", {
      name: "我的知识库",
      description: "个人沉淀",
      indexing_technique: "high_quality",
      embedding_model: "text-embedding-3-small",
      embedding_model_provider: undefined,
      permission: "only_me",
    });
    expect(kbUpdate).toHaveBeenCalledWith({
      data: {
        permissionType: "partial_invisible",
        unitsnos: '["D1"]',
        roleiids: "[2]",
      },
      where: { kbId: "kb-1" },
    });
    expect(kbCreate).not.toHaveBeenCalled();

    // 权限记录里的 JSON 损坏时不应抛错
    const broken = { ...vm.sharedKnowledgeBases[0], id: "kb-broken", name: "损坏记录库" };
    kbQuery.mockResolvedValueOnce(ok([{ kbId: "kb-broken", unitsnos: "not-json", roleiids: "{" }]));
    expect(() => vm.openMngEditDialog(broken)).not.toThrow();
    await flushPromises();
    expect(vm.mngFormData.selectedDepts).toEqual([]);
    expect(vm.mngFormData.selectedRoles).toEqual([]);

    // 没有权限记录的共享库：保存时补建一条权限记录
    vm.openMngEditDialog(vm.sharedKnowledgeBases[0]);
    await flushPromises();
    await clickDialogFooter(wrapper, "确定");
    expect(kbCreate).toHaveBeenCalledWith({
      data: {
        kbId: "kb-2",
        ownerUid: "u-1",
        permissionType: "public",
        unitsnos: "[]",
        roleiids: "[]",
      },
    });
  });

  it("删除知识库：取消不请求；权限记录删除失败仍提示成功；数据集删除失败提示重试", async () => {
    const spies = createMessageSpies();
    const close = vi.fn();
    vi.spyOn(ElLoading, "service").mockReturnValue({ close } as never);
    const wrapper = mountView();
    await flushPromises();
    const kb = (wrapper.vm as any).filteredKnowledgeBases[0];

    const confirm = vi.spyOn(ElMessageBox, "confirm").mockRejectedValueOnce("cancel" as never);
    await (wrapper.vm as any).handleMngDelete(kb);
    await flushPromises();
    expect(confirm).toHaveBeenCalled();
    expect(deleteDataset).not.toHaveBeenCalled();

    confirm.mockResolvedValueOnce("confirm" as never);
    kbDelete.mockRejectedValueOnce(new Error("no record"));
    await (wrapper.vm as any).handleMngDelete(kb);
    await flushPromises();
    expect(deleteDataset).toHaveBeenCalledWith("kb-1");
    expect(spies.success).toHaveBeenCalledWith("知识库删除成功");

    confirm.mockResolvedValueOnce("confirm" as never);
    deleteDataset.mockRejectedValueOnce(new Error("network"));
    await (wrapper.vm as any).handleMngDelete(kb);
    await flushPromises();
    expect(spies.error).toHaveBeenCalledWith("删除失败，请重试");
    expect(close).toHaveBeenCalledTimes(2);
  });

  it("成员：拉取列表同步加入人数；加入/移除的错误码与异常分支", async () => {
    const spies = createMessageSpies();
    const wrapper = mountView();
    await flushPromises();
    const vm = wrapper.vm as any;

    await vm.fetchMembers("kb-1");
    expect(members).toHaveBeenCalledWith({ data: { kbId: "kb-1" } });
    expect(vm.memberList).toHaveLength(2);
    expect(vm.filteredKnowledgeBases[0].member_count).toBe(2);

    await vm.joinKb("kb-2");
    expect(join).toHaveBeenCalledWith({ data: { kbId: "kb-2" } });
    expect(spies.success).toHaveBeenCalledWith("加入成功");

    join.mockResolvedValueOnce(fail("已加入"));
    await vm.joinKb("kb-2");
    expect(spies.error).toHaveBeenCalledWith("已加入");

    join.mockRejectedValueOnce(new Error("network"));
    await vm.joinKb("kb-2");
    expect(spies.error).toHaveBeenCalledWith("加入失败");

    const confirm = vi.spyOn(ElMessageBox, "confirm").mockRejectedValueOnce("cancel" as never);
    await vm.removeMember("kb-1", "u-2");
    expect(confirm).toHaveBeenCalled();
    expect(remove).not.toHaveBeenCalled();

    confirm.mockResolvedValueOnce("confirm" as never);
    remove.mockResolvedValueOnce(fail("无权移除"));
    await vm.removeMember("kb-1", "u-2");
    expect(spies.error).toHaveBeenCalledWith("无权移除");

    confirm.mockResolvedValueOnce("confirm" as never);
    remove.mockResolvedValueOnce(ok(true));
    const membersBefore = members.mock.calls.length;
    await vm.removeMember("kb-1", "u-2");
    expect(members.mock.calls.length).toBe(membersBefore + 1);
    expect(spies.success).toHaveBeenCalledWith("已移除");
  });

  it("退出知识库：取消不请求；成功后从订阅列表移除；错误码与异常分别提示", async () => {
    const spies = createMessageSpies();
    const wrapper = mountView();
    await flushPromises();
    const vm = wrapper.vm as any;
    expect(groupNames(wrapper, "订阅知识库")).toEqual(["已加入知识库"]);

    const confirm = vi.spyOn(ElMessageBox, "confirm").mockRejectedValueOnce("cancel" as never);
    await vm.leaveKb("kb-3");
    expect(confirm).toHaveBeenCalled();
    expect(leave).not.toHaveBeenCalled();

    confirm.mockResolvedValueOnce("confirm" as never);
    leave.mockResolvedValueOnce(fail("不在该知识库"));
    await vm.leaveKb("kb-3");
    expect(spies.error).toHaveBeenCalledWith("不在该知识库");

    confirm.mockResolvedValueOnce("confirm" as never);
    leave.mockRejectedValueOnce(new Error("network"));
    await vm.leaveKb("kb-3");
    expect(spies.error).toHaveBeenCalledWith("退出失败");

    confirm.mockResolvedValueOnce("confirm" as never);
    leave.mockResolvedValueOnce(ok(true));
    await vm.leaveKb("kb-3");
    await flushPromises();
    expect(spies.success).toHaveBeenCalledWith("已退出");
    expect(groupNames(wrapper, "订阅知识库")).toEqual([]);
  });

  it("选择订阅库与广场入口：切换到个人菜单并刷新成员/标签", async () => {
    const wrapper = mountView();
    await flushPromises();
    const vm = wrapper.vm as any;
    const membersBefore = members.mock.calls.length;

    vm.handleSelectJoinedKb(vm.joinedKnowledgeBases[0]);
    await flushPromises();
    expect(vm.selectedKbId).toBe("kb-3");
    expect(vm.activeMenu).toBe("personal");
    expect(members.mock.calls.length).toBe(membersBefore + 1);
    expect(members).toHaveBeenLastCalledWith({ data: { kbId: "kb-3" } });
    expect(bindQuery.mock.calls.length).toBeGreaterThan(3);

    vm.goToSquare();
    await flushPromises();
    expect(vm.showSquareView).toBe(true);
    vm.hideSquareView();
    expect(vm.showSquareView).toBe(false);
  });
});
