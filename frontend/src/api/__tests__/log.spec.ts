import { describe, expect, it, vi } from "vitest";

// vi.mock 会被提到文件最顶部，普通 const 那时还没初始化，必须用 hoisted
const { post } = vi.hoisted(() => ({ post: vi.fn() }));

vi.mock("../axios", () => ({
  default: { post },
}));

import { logApi } from "../log";

/**
 * 操作日志查询的请求形状。
 *
 * 和 aiApiToken.spec.ts 同一个理由：HNSP 的 MultiRequestBodyArgumentResolver 按
 * 「方法形参名」从 JSON 里取字段，后端方法签名是 query(ControllerLogQuery query)，
 * 所以裸传 { pageNum: 1 } 会解析成 null —— 接口不报错，只是每次都返回「全量第一页」，
 * 用户以为筛选生效了。这种失败在组件测试里看不见（api 层被 mock 掉了），
 * 只能直接盯 axios 的入参。
 */

describe("logApi", () => {
  it("查的是本项目的 /api/controllerLog/query，不是框架那个不存在的接口", async () => {
    await logApi.getLogList();

    expect(post).toHaveBeenCalledWith("/ai/api/controllerLog/query", { query: {} });
  });

  it("分页与日期区间都留在 query 这一层里，键名与后端 ControllerLogQuery 对齐", async () => {
    await logApi.getLogList({ pageNum: 3, pageSize: 50, startDate: "2026-10-01", endDate: "2026-10-05" });

    const [url, body] = post.mock.calls[post.mock.calls.length - 1];
    expect(url).toBe("/ai/api/controllerLog/query");
    expect(Object.keys(body)).toEqual(["query"]);
    expect(body.query).toEqual({ pageNum: 3, pageSize: 50, startDate: "2026-10-01", endDate: "2026-10-05" });
  });
});
