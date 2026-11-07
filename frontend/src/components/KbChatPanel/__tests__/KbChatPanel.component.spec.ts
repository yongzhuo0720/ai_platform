import { afterEach, describe, expect, it } from "vitest";
import { enableAutoUnmount, mount } from "@vue/test-utils";
import KbChatPanel from "../index.vue";

enableAutoUnmount(afterEach);

const messages = () => [
  { id: "m1", role: "user" as const, content: "报销标准是什么？" },
  { id: "m2", role: "assistant" as const, content: "按差旅制度执行。" },
];

const mountPanel = (props: Record<string, unknown> = {}) =>
  mount(KbChatPanel, {
    props: { messages: [], loading: false, ...props },
  });

describe("KbChatPanel 核心问答路径", () => {
  it("没有消息时展示引导语，不出现新对话入口", () => {
    const wrapper = mountPanel({ kbName: "制度库" });

    expect(wrapper.text()).toContain("你好，我是制度库");
    expect(wrapper.find(".kb-chat-new-btn").exists()).toBe(false);
  });

  it("有消息时显示新对话按钮，点击后向外层发出事件", async () => {
    const wrapper = mountPanel({ messages: messages(), kbName: "制度库" });

    const button = wrapper.get(".kb-chat-new-btn");
    expect(button.text()).toContain("新对话");
    await button.trigger("click");

    expect(wrapper.emitted("new-conversation")).toHaveLength(1);
  });

  it("生成中禁用新对话，避免把进行中的回答切走", () => {
    const wrapper = mountPanel({ messages: messages(), loading: true });

    expect(wrapper.get(".kb-chat-new-btn").attributes("disabled")).toBeDefined();
  });

  it("输入内容后点击发送向外层发出 submit，空白内容不发", async () => {
    const wrapper = mountPanel({ messages: messages() });
    const send = () => wrapper.get('button[aria-label="发送消息"]');

    await wrapper.get("textarea").setValue("   ");
    expect(send().attributes("disabled")).toBeDefined();

    await wrapper.get("textarea").setValue("差旅报销上限是多少？");
    expect(send().attributes("disabled")).toBeUndefined();
    await send().trigger("click");

    expect(wrapper.emitted("submit")).toHaveLength(1);
  });

  it("推荐问题仅在回答结束后展示，点击带上原问题", async () => {
    const wrapper = mountPanel({
      messages: messages(),
      suggestedQuestions: ["怎么申请？", "需要哪些材料？"],
    });

    const buttons = wrapper.findAll(".kb-chat-suggestion-btn");
    expect(buttons.map((button) => button.text())).toEqual([
      "怎么申请？",
      "需要哪些材料？",
    ]);
    await buttons[1].trigger("click");
    expect(wrapper.emitted("suggest-click")).toEqual([["需要哪些材料？"]]);
  });

  it("回答结束后可复制或重新生成，生成中不显示操作", async () => {
    const wrapper = mountPanel({ messages: messages() });
    const actions = wrapper.findAll(".kb-chat-message-action");

    expect(actions).toHaveLength(2);
    await actions[0].trigger("click");
    await actions[1].trigger("click");
    expect(wrapper.emitted("copy")).toEqual([["按差旅制度执行。"]]);
    expect(wrapper.emitted("regenerate")).toEqual([["m2"]]);

    await wrapper.setProps({ loading: true });
    expect(wrapper.findAll(".kb-chat-message-action")).toHaveLength(0);
  });
});
