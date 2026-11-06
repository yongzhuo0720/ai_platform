import { beforeEach, describe, expect, it } from "vitest";
import {
  isKbConversation,
  markKbConversation,
  unmarkKbConversation,
} from "@/utils/conversationSource";

const KEY = "kb-conversation-ids";

describe("conversationSource", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("标记后可以识别出知识库会话", () => {
    markKbConversation("kb-1");
    expect(isKbConversation("kb-1")).toBe(true);
    expect(isKbConversation("other")).toBe(false);
  });

  it("重复标记不会写入重复 id", () => {
    markKbConversation("kb-1");
    markKbConversation("kb-1");
    expect(JSON.parse(localStorage.getItem(KEY) || "[]")).toEqual(["kb-1"]);
  });

  it("删除会话后清理标记，未标记的其它会话不受影响", () => {
    markKbConversation("kb-1");
    markKbConversation("kb-2");
    unmarkKbConversation("kb-1");

    expect(isKbConversation("kb-1")).toBe(false);
    expect(isKbConversation("kb-2")).toBe(true);
    expect(JSON.parse(localStorage.getItem(KEY) || "[]")).toEqual(["kb-2"]);
  });

  it("删除后再复用同一个 id，不会被旧标记误判归属", () => {
    markKbConversation("kb-1");
    unmarkKbConversation("kb-1");
    // 模拟 Dify 复用了同一个会话 id，但没有重新走知识库中心
    expect(isKbConversation("kb-1")).toBe(false);
  });

  it("清理不存在的 id 不改动集合，也不会抛异常", () => {
    markKbConversation("kb-1");
    unmarkKbConversation("not-exist");
    unmarkKbConversation("");
    expect(JSON.parse(localStorage.getItem(KEY) || "[]")).toEqual(["kb-1"]);
  });

  it("localStorage 内容损坏时按空集合容错", () => {
    localStorage.setItem(KEY, "{not-json");
    expect(isKbConversation("kb-1")).toBe(false);
    expect(() => unmarkKbConversation("kb-1")).not.toThrow();
  });
});
