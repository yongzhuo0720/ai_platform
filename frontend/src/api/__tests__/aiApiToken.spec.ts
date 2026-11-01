import { describe, expect, it, vi } from "vitest";

// vi.mock 会被提到文件最顶部，普通 const 那时还没初始化，必须用 hoisted
const { post } = vi.hoisted(() => ({ post: vi.fn() }));

vi.mock("../axios", () => ({
  default: { post },
}));

import aiApiTokenApi from "../aiApiToken";

/**
 * 密钥管理接口的请求体形状。
 *
 * <p>HNSP 的 MultiRequestBodyArgumentResolver 按「方法参数名」从 JSON 里取字段，
 * 裸传 { name: '...' } 到后端会解析成 null。这条契约在本项目翻过两次车
 * （一次后端报错看不懂、一次前端联调），所以直接盯着 axios 的入参，
 * 而不是在组件测试里 mock 掉 api 层。</p>
 */

describe("aiApiTokenApi", () => {
  it("写操作一律包一层 data，键名与后端方法参数名对齐", () => {
    aiApiTokenApi.create({ name: "本地脚本", allowModels: "", expireAt: 1800000000000 });

    expect(post).toHaveBeenCalledWith("/ai/api/aiApiToken/create", {
      data: { name: "本地脚本", allowModels: "", expireAt: 1800000000000 },
    });

    aiApiTokenApi.update({ iid: 7, enable: false });
    expect(post).toHaveBeenLastCalledWith("/ai/api/aiApiToken/update", {
      data: { iid: 7, enable: false },
    });

    aiApiTokenApi.remove(7);
    expect(post).toHaveBeenLastCalledWith("/ai/api/aiApiToken/delete", { data: { iid: 7 } });
  });

  it("列表只需登录态，不发明细参数", () => {
    aiApiTokenApi.list();

    expect(post).toHaveBeenCalledWith("/ai/api/aiApiToken/list");
  });
});
