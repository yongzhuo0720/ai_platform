import { afterEach, describe, expect, it, vi } from "vitest";
import { streamDirectChat } from "../directModel";
import type { KbSearchItemDto } from "../kbRag";

vi.mock("@/stores/difyConfig", () => ({
  getModelsGatewayBase: () => "/gateway/models",
  gatewayAuthHeaders: () => ({}),
}));

const sseResponse = (chunks: string[]): Response => {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk));
      }
      controller.close();
    },
  });
  return new Response(stream, {
    status: 200,
    headers: { "Content-Type": "text/event-stream" },
  });
};

const run = (chunks: string[], onKbSources?: (items: KbSearchItemDto[]) => void) => {
  vi.stubGlobal("fetch", vi.fn(async () => sseResponse(chunks)));
  const collected: string[] = [];
  const finish = new Promise<void>((resolve, reject) => {
    streamDirectChat(
      "7",
      [{ role: "user", content: "问题" }],
      {
        onKbSources,
        onChunk: (text) => collected.push(text),
        onComplete: resolve,
        onError: reject,
      },
      { kbName: "kb" }
    );
  });
  return { collected, finish };
};

const sources: KbSearchItemDto[] = [
  { docIid: 11, docName: "手册.md", kbName: "kb", chunkIndex: 0, content: "片段内容", score: 0.5 },
];

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("streamDirectChat 知识库来源事件", () => {
  it("解析 kb_sources 事件，并把其后的增量块当作正文", async () => {
    const received: KbSearchItemDto[][] = [];
    const { collected, finish } = run(
      [
        `event: kb_sources\ndata: ${JSON.stringify({ items: sources })}\n\n`,
        'data: {"choices":[{"delta":{"content":"你"}}]}\n\n',
        'data: {"choices":[{"delta":{"content":"好"}}]}\n\n',
        "data: [DONE]\n\n",
      ],
      (items) => received.push(items)
    );

    await finish;

    expect(received).toEqual([sources]);
    expect(collected.join("")).toBe("你好");
  });

  it("来源事件后没有 event 行的增量块不会被误判", async () => {
    const received: KbSearchItemDto[][] = [];
    const { collected, finish } = run(
      [
        `event: kb_sources\ndata: ${JSON.stringify({ items: sources })}\n\n`,
        'data: {"choices":[{"delta":{"content":"正文"}}]}\n\n',
        "data: [DONE]\n\n",
      ],
      (items) => received.push(items)
    );

    await finish;

    expect(received).toHaveLength(1);
    expect(collected.join("")).toBe("正文");
  });

  it("items 为空时不回调来源，正文照常解析", async () => {
    const onKbSources = vi.fn();
    const { collected, finish } = run(
      [
        'event: kb_sources\ndata: {"items":[]}\n\n',
        'data: {"choices":[{"delta":{"content":"回答"}}]}\n\n',
        "data: [DONE]\n\n",
      ],
      onKbSources
    );

    await finish;

    expect(onKbSources).not.toHaveBeenCalled();
    expect(collected.join("")).toBe("回答");
  });

  it("普通流不触发来源回调", async () => {
    const onKbSources = vi.fn();
    const { finish } = run(
      [
        'data: {"choices":[{"delta":{"content":"hi"}}]}\n\n',
        "data: [DONE]\n\n",
      ],
      onKbSources
    );

    await finish;

    expect(onKbSources).not.toHaveBeenCalled();
  });

  it("error 事件仍然抛给 onError", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        sseResponse(['event: error\ndata: {"error":{"message":"上游限流"}}\n\n'])
      )
    );

    const error = await new Promise<Error>((resolve) => {
      streamDirectChat("7", [{ role: "user", content: "问题" }], {
        onChunk: () => undefined,
        onComplete: () => resolve(new Error("不应完成")),
        onError: resolve,
      });
    });

    expect(error.message).toContain("上游限流");
  });
});
