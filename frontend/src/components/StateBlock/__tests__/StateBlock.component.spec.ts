import { afterEach, describe, expect, it, vi } from "vitest";
import { enableAutoUnmount, mount } from "@vue/test-utils";

import StateBlock from "../index.vue";

/**
 * 三态占位组件：loading > error > empty > 默认插槽。
 * 优先级是这类组件最容易写错的地方——列表请求失败时后端往往返回空数组，
 * 若不判 error，页面会显示「暂无数据」，把服务故障说成「这里本来就没东西」。
 */

enableAutoUnmount(afterEach);

const mountBlock = (props: Record<string, unknown> = {}, slots: Record<string, unknown> = {}) =>
  mount(StateBlock, { props, slots: { default: '<p class="the-list">真实内容</p>', ...slots } });

describe("StateBlock", () => {
  it("默认渲染插槽内容", () => {
    const wrapper = mountBlock();

    expect(wrapper.find(".the-list").text()).toBe("真实内容");
    expect(wrapper.find(".state-block__skeleton").exists()).toBe(false);
    expect(wrapper.find(".state-block__error").exists()).toBe(false);
    expect(wrapper.find(".state-block__empty").exists()).toBe(false);
  });

  it("loading 优先于 error 与 empty，按 count 渲染骨架并标注 aria-busy", () => {
    const wrapper = mountBlock({
      loading: true,
      error: "获取列表失败",
      empty: true,
      count: 3,
    });

    expect(wrapper.findAll(".state-block__sk")).toHaveLength(3);
    expect(wrapper.find(".state-block__skeleton").attributes("aria-busy")).toBe("true");
    expect(wrapper.find(".state-block__error").exists()).toBe(false);
    expect(wrapper.find(".state-block__empty").exists()).toBe(false);
    expect(wrapper.find(".the-list").exists()).toBe(false);
  });

  it("骨架默认走 rows，variant=cards 时换成卡片栅格", () => {
    const rows = mountBlock({ loading: true });
    expect(rows.find(".state-block__skeleton").classes()).toContain("is-rows");
    expect(rows.find(".state-block__sk").classes()).toContain("state-block__sk--row");

    const cards = mountBlock({ loading: true, variant: "cards" });
    expect(cards.find(".state-block__skeleton").classes()).toContain("is-cards");
    expect(cards.find(".state-block__sk").classes()).toContain("state-block__sk--card");
  });

  it("error 优先于 empty，暴露原因与重试入口", () => {
    const wrapper = mountBlock({ error: "获取模型列表失败", empty: true, emptyText: "暂无模型" });

    expect(wrapper.find(".state-block__error").attributes("role")).toBe("alert");
    expect(wrapper.find(".state-block__error-text").text()).toBe("获取模型列表失败");
    expect(wrapper.find(".state-block__empty").exists()).toBe(false);
    expect(wrapper.find(".the-list").exists()).toBe(false);
  });

  it("点重试向上抛 retry，供页面重新拉数据", async () => {
    const onRetry = vi.fn();
    const wrapper = mount(StateBlock, {
      props: { error: "获取模型列表失败", retryText: "重新加载", onRetry },
    });

    const button = wrapper.find(".state-block__retry");
    expect(button.text()).toBe("重新加载");
    await button.trigger("click");

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("empty 用传入文案，默认文案为「暂无数据」，并留出 empty-action 插槽", () => {
    const fallback = mountBlock({ empty: true });
    expect(fallback.find(".state-block__empty-text").text()).toBe("暂无数据");

    const wrapper = mountBlock({ empty: true, emptyText: "没有符合当前搜索与筛选条件的模型" }, {
      "empty-action": '<button class="clear-filter">清空筛选</button>',
    });

    expect(wrapper.find(".state-block__empty-text").text()).toBe("没有符合当前搜索与筛选条件的模型");
    expect(wrapper.find(".clear-filter").exists()).toBe(true);
  });
});
