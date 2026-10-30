import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";

vi.mock("js-cookie", () => ({
  default: { get: vi.fn(() => "2") },
}));
vi.mock("@/api/dataset", () => ({
  datasetApi: { getDatasets: vi.fn() },
  default: { getDatasets: vi.fn() },
}));
vi.mock("@/api/knowledgeBaseMng", () => ({
  knowledgeBaseMngApi: { filterVisible: vi.fn() },
}));
vi.mock("@/api/tags", () => ({
  tagsApi: { query: vi.fn() },
}));
vi.mock("@/api/tagsbind", () => ({
  tagsBindApi: { query: vi.fn(), create: vi.fn(), delete: vi.fn() },
  default: { query: vi.fn(), create: vi.fn(), delete: vi.fn() },
}));

import KnowledgeBaseSquareView from "../index.vue";
import { datasetApi } from "@/api/dataset";
import { knowledgeBaseMngApi } from "@/api/knowledgeBaseMng";
import { tagsApi } from "@/api/tags";
import { tagsBindApi } from "@/api/tagsbind";

/**
 * 知识库广场：可见性过滤、推荐区块、搜索/标签筛选、标签编辑权限与卡片跳转。
 */

enableAutoUnmount(afterEach);

const getDatasets = datasetApi.getDatasets as Mock;
const filterVisible = knowledgeBaseMngApi.filterVisible as Mock;
const tagsQuery = tagsApi.query as Mock;
const bindQuery = tagsBindApi.query as Mock;

const ok = (datas: unknown, resp_msg = "ok") => ({
  data: { resp_code: 0, resp_msg, datas },
});
const fail = (resp_msg: string) => ({ data: { resp_code: 500, resp_msg } });

const knowledgeBases = [
  {
    id: "kb-1",
    name: "产品手册",
    description: "产品使用说明",
    coverImage: "https://cdn.example.com/kb-1.png",
    isRecommended: true,
    member_count: 5,
    document_count: 12,
    created_by: "admin",
  },
  {
    id: "kb-2",
    name: "客服问答",
    description: "常见问题",
    isRecommended: false,
    partial_member_list: ["u1", "u2"],
    documents: ["d1", "d2", "d3"],
    creator: "运营",
  },
  {
    id: "kb-3",
    name: "研发规范",
    description: "编码规范",
    isRecommended: false,
    member_count: 1,
    document_count: 2,
    creator: "研发",
  },
];

const tags = [
  { iid: 10, name: "产品" },
  { iid: 11, name: "客服" },
];

const bindings = [{ hash: "kb-1", lableBeans: [{ iid: 10, name: "产品" }] }];

const mountView = (props: Record<string, unknown> = {}) =>
  mount(KnowledgeBaseSquareView, {
    props: { isAdmin: false, myKbIds: new Set<string>(), ...props },
  });

// 「全部」区块自带 tabs，推荐区块没有，按此定位避免推荐区隐藏时索引错位
const allSection = (wrapper: ReturnType<typeof mountView>) =>
  wrapper.findAll(".kb-square-section").find((section) => section.find(".kb-square-tabs").exists())!;

const allCards = (wrapper: ReturnType<typeof mountView>) =>
  allSection(wrapper).findAll(".kb-square-card");

const titles = (cards: ReturnType<typeof allCards>) =>
  cards.map((card) => card.find(".kb-square-card-name").text());

beforeEach(() => {
  getDatasets.mockResolvedValue({ data: [...knowledgeBases], has_more: false, total: 3, page: 1, limit: 100 });
  filterVisible.mockResolvedValue(ok(["kb-1", "kb-2"]));
  tagsQuery.mockResolvedValue(ok(tags));
  bindQuery.mockResolvedValue(ok(bindings));
});

describe("KnowledgeBaseSquareView", () => {
  it("首屏按可见性过滤知识库，经 tagsbind 回填标签并补齐统计字段", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(getDatasets).toHaveBeenCalledWith(1, 100);
    expect(filterVisible).toHaveBeenCalledWith({
      data: { context: "square", kbIds: ["kb-1", "kb-2", "kb-3"] },
    });
    expect(tagsQuery).toHaveBeenCalledWith({ data: { type: "knowledge_base" } });
    expect(bindQuery).toHaveBeenCalledWith({ data: { type: "knowledge_base" } });

    // kb-3 不在可见列表里；kb-2 用 partial_member_list/documents 兜底统计
    expect(titles(allCards(wrapper))).toEqual(["产品手册", "客服问答"]);
    const cards = allCards(wrapper);
    expect(cards[0].text()).toContain("5人已加入 | 12个内容");
    expect(cards[1].text()).toContain("2人已加入 | 3个内容");
    expect(cards[0].find(".ima-card-tag").text()).toContain("产品");
    expect(cards[1].find(".ima-card-tag").exists()).toBe(false);
  });

  it("推荐知识库单独成区并展示创建者，未推荐的不进入", async () => {
    const wrapper = mountView();
    await flushPromises();

    const sections = wrapper.findAll(".kb-square-section");
    expect(sections).toHaveLength(2);
    expect(titles(sections[0].findAll(".kb-square-card"))).toEqual(["产品手册"]);
    expect(sections[0].find(".kb-square-card-meta").text()).toContain("5人已加入 | 12个内容 | admin");
  });

  it("搜索按名称/描述过滤，标签下拉按 iid 单选过滤", async () => {
    const wrapper = mountView();
    await flushPromises();

    const input = wrapper.find('input[placeholder="搜索知识库..."]');
    await input.setValue("客服");
    expect(titles(allCards(wrapper))).toEqual(["客服问答"]);
    // 推荐区无命中时整块隐藏
    expect(wrapper.findAll(".kb-square-section")).toHaveLength(1);

    await input.setValue("产品使用");
    expect(titles(allCards(wrapper))).toEqual(["产品手册"]);

    await input.setValue("");
    (wrapper.vm as any).selectedCategories = "10";
    await flushPromises();
    expect(titles(allCards(wrapper))).toEqual(["产品手册"]);
  });

  it("搜索无命中时展示空态并保留提示文案", async () => {
    const wrapper = mountView();
    await flushPromises();

    expect(wrapper.find(".kb-square-no-more").text()).toBe("没有更多数据了");

    await wrapper.find('input[placeholder="搜索知识库..."]').setValue("不存在的知识库");
    expect(wrapper.findAll(".kb-square-card")).toHaveLength(0);
    expect(wrapper.find(".kb-square-no-more").text()).toBe("暂无可展示的知识库");
  });

  it("无任何可见知识库时只显示空态，不再请求可见性过滤", async () => {
    getDatasets.mockResolvedValue({ data: [], has_more: false, total: 0, page: 1, limit: 100 });
    const wrapper = mountView();
    await flushPromises();

    expect(filterVisible).not.toHaveBeenCalled();
    expect(wrapper.findAll(".kb-square-card")).toHaveLength(0);
    expect(wrapper.find(".kb-square-no-more").text()).toBe("暂无可展示的知识库");
  });

  it("可见性过滤异常或返回错误码时降级展示全部，不阻断广场", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    filterVisible.mockRejectedValueOnce(new Error("network"));
    const wrapper = mountView();
    await flushPromises();

    expect(warn).toHaveBeenCalled();
    expect(titles(allCards(wrapper))).toEqual(["产品手册", "客服问答", "研发规范"]);

    filterVisible.mockResolvedValueOnce(fail("权限服务不可用"));
    const second = mountView();
    await flushPromises();
    expect(titles(allCards(second))).toEqual(["产品手册", "客服问答", "研发规范"]);
    warn.mockRestore();
  });

  it("标签编辑权限：管理员全量可编辑，归属者仅自己的知识库可编辑", async () => {
    const adminWrapper = mountView({ isAdmin: true });
    await flushPromises();
    const adminCards = allCards(adminWrapper);
    // kb-1 已有标签走编辑入口，kb-2 无标签显示添加按钮
    expect(adminCards[0].find(".tag-dropdown").exists()).toBe(true);
    expect(adminCards[1].find(".tag-dropdown").exists()).toBe(true);
    expect(adminCards[1].text()).toContain("添加标签");

    const ownerWrapper = mountView({ myKbIds: new Set(["kb-1"]) });
    await flushPromises();
    const ownerCards = allCards(ownerWrapper);
    expect(ownerCards[0].find(".tag-dropdown").exists()).toBe(true);
    expect(ownerCards[1].find(".tag-dropdown").exists()).toBe(false);

    const guestWrapper = mountView();
    await flushPromises();
    expect(guestWrapper.find(".tag-dropdown").exists()).toBe(false);
    // 只读态仍展示已有标签，只是不可点击编辑
    expect(allCards(guestWrapper)[0].find(".ima-card-tag").text()).toContain("产品");
  });

  it("点击卡片抛出 select-kb，封面缺省时使用默认图", async () => {
    const wrapper = mountView();
    await flushPromises();

    const covers = allCards(wrapper).map((card) => card.find("img").attributes("src"));
    expect(covers[0]).toBe("https://cdn.example.com/kb-1.png");
    expect(covers[1]).toContain("brand-cover-default");

    await allCards(wrapper)[0].trigger("click");
    await allCards(wrapper)[1].trigger("click");
    const emitted = wrapper.emitted("select-kb");
    expect(emitted?.[0]?.[0]).toMatchObject({ id: "kb-1", name: "产品手册" });
    expect(emitted?.[1]?.[0]).toMatchObject({ id: "kb-2", name: "客服问答" });
  });

  it("知识库列表加载异常时清空列表并给出空态", async () => {
    const error = vi.spyOn(console, "error").mockImplementation(() => {});
    getDatasets.mockRejectedValueOnce(new Error("network"));
    const wrapper = mountView();
    await flushPromises();

    expect(error).toHaveBeenCalled();
    expect(wrapper.findAll(".kb-square-card")).toHaveLength(0);
    expect(wrapper.find(".kb-square-no-more").text()).toBe("暂无可展示的知识库");
    error.mockRestore();
  });
});
