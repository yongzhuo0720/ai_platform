import { describe, expect, it, afterEach } from "vitest";
import { mount } from "@vue/test-utils";
import { nextTick } from "vue";
import ChatComposer from "../ChatComposer.vue";

/**
 * ChatComposer 组件级测试：直接渲染 SFC，验证模板事件绑定，
 * 与只测逻辑函数的用例互补（逻辑层不知道按钮接没接对回调）。
 */

const mountComposer = (props: Record<string, unknown> = {}) =>
  mount(ChatComposer, {
    props: {
      modelValue: "",
      ...props,
    },
  });

afterEach(() => {
  // 模型菜单 Teleport 到 body，逐个用例自己清理
  document.body.innerHTML = "";
});

describe("ChatComposer", () => {
  it("输入内容后按 Enter 提交，Shift+Enter 只换行", async () => {
    const wrapper = mountComposer({ modelValue: "你好" });
    const textarea = wrapper.find("textarea");
    expect(textarea.exists()).toBe(true);

    await textarea.trigger("keydown", { key: "Enter", shiftKey: true });
    expect(wrapper.emitted("submit")).toBeUndefined();

    await textarea.trigger("keydown", { key: "Enter" });
    expect(wrapper.emitted("submit")).toHaveLength(1);
  });

  it("输入法组词中的 Enter 不会误发消息", async () => {
    const wrapper = mountComposer({ modelValue: "nihao" });
    await wrapper.find("textarea").trigger("keydown", { key: "Enter", isComposing: true });
    expect(wrapper.emitted("submit")).toBeUndefined();
  });

  it("内容为空时发送按钮禁用，点击不提交", async () => {
    const wrapper = mountComposer({ modelValue: "   " });
    const send = wrapper.find(".chat-send-btn:not(.is-stop)");
    expect(send.attributes("disabled")).toBeDefined();

    await send.trigger("click");
    expect(wrapper.emitted("submit")).toBeUndefined();
  });

  it("有内容时点击发送按钮提交", async () => {
    const wrapper = mountComposer({ modelValue: "问题" });
    await wrapper.find(".chat-send-btn:not(.is-stop)").trigger("click");
    expect(wrapper.emitted("submit")).toHaveLength(1);
  });

  it("生成中显示停止按钮，点击发出 stop", async () => {
    const wrapper = mountComposer({ modelValue: "问题", loading: true });
    const stop = wrapper.find(".chat-send-btn.is-stop");
    expect(stop.exists()).toBe(true);
    expect(wrapper.find(".chat-send-btn:not(.is-stop)").exists()).toBe(false);

    await stop.trigger("click");
    expect(wrapper.emitted("stop")).toHaveLength(1);
  });

  it("textarea 输入会同步给父组件", async () => {
    const wrapper = mountComposer();
    await wrapper.find("textarea").setValue("新内容");
    const events = wrapper.emitted("update:modelValue");
    expect(events).toBeTruthy();
    expect(events?.[events.length - 1]).toEqual(["新内容"]);
  });

  it("模型菜单展开后可选择模型并关闭", async () => {
    const wrapper = mountComposer({
      modelValue: "问题",
      activeModelId: "1",
      modelOptions: [
        { label: "模型 A", value: "1" },
        { label: "模型 B", value: "2", subtitle: "备用" },
      ],
    });

    await wrapper.find(".chat-model-switcher-trigger").trigger("click");
    await nextTick();

    const items = Array.from(document.body.querySelectorAll<HTMLButtonElement>(".chat-model-menu-item"));
    expect(items).toHaveLength(2);
    expect(items[0].classList.contains("is-active")).toBe(true);

    items[1].click();
    await nextTick();

    expect(wrapper.emitted("select-model")).toEqual([["2"]]);
    expect(document.body.querySelector(".chat-model-menu")).toBeNull();
  });

  it("知识库下拉切换会通知父组件", async () => {
    const wrapper = mountComposer({
      modelValue: "问题",
      kbOptions: ["默认知识库", "产品手册"],
      activeKbName: "",
    });

    await wrapper.find("select.chat-kb-switcher-select").setValue("产品手册");
    expect(wrapper.emitted("select-kb")).toEqual([["产品手册"]]);
  });

  it("移除附件会带着文件 id 通知父组件", async () => {
    const wrapper = mountComposer({
      modelValue: "",
      attachedFiles: [{ id: "f1", name: "制度.txt", type: "text/plain", size: 12 }],
    });

    const remove = wrapper.find(".attached-file-remove");
    expect(remove.exists()).toBe(true);
    await remove.trigger("click");
    expect(wrapper.emitted("remove-file")).toEqual([["f1"]]);
  });
});
