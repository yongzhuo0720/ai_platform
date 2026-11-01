import { afterEach, describe, expect, it, vi, type Mock } from "vitest";
import { enableAutoUnmount, flushPromises, mount } from "@vue/test-utils";
import { ElMessage, ElMessageBox } from "element-plus";

vi.mock("@/api/aiApiToken", () => {
  // 视图走的是默认导出，两个名字必须指向同一个桩对象，否则调用直接抛 TypeError
  const api = {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
  };
  return { aiApiTokenApi: api, default: api };
});

import ApiKeysView from "../index.vue";
import aiApiTokenApi from "@/api/aiApiToken";

/**
 * API 密钥页：列表口径、明文只出现一次、开关失败回滚、删除确认。
 * 请求体必须包一层 data（HNSP 的 MultiRequestBodyArgumentResolver 按方法参数名取字段，
 * 裸传会变 null）是 api 层的契约，由 src/api/__tests__/aiApiToken.spec.ts 直接盯着 axios。
 */

enableAutoUnmount(afterEach);

const list = aiApiTokenApi.list as Mock;
const create = aiApiTokenApi.create as Mock;
const update = aiApiTokenApi.update as Mock;
const remove = aiApiTokenApi.remove as Mock;

const DAY_MS = 24 * 60 * 60 * 1000;

const ok = (datas: unknown) => ({ data: { resp_code: 0, resp_msg: "ok", datas } });
const fail = (resp_msg: string) => ({ data: { resp_code: -1, resp_msg } });

const row = (overrides: Record<string, unknown> = {}) => ({
  iid: 1,
  name: "本地脚本",
  tokenPrefix: "sk-1a2b3c4d",
  userId: "u1",
  allowModels: null,
  expireAt: null,
  lastUsedAt: null,
  enable: true,
  createtime: "2026/11/01 10:00:00",
  ...overrides,
});

const mountView = () => mount(ApiKeysView);

const loadView = async () => {
  const wrapper = mountView();
  await flushPromises();
  return wrapper;
};

const dialogInput = (wrapper: ReturnType<typeof mountView>, placeholder: string) =>
  wrapper.find(`.el-dialog input[placeholder="${placeholder}"]`);

const dialogButton = (wrapper: ReturnType<typeof mountView>, text: string) =>
  wrapper
    .findAll(".el-dialog__footer button")
    .find((item) => item.text().includes(text));

const lastDialogSelect = (wrapper: ReturnType<typeof mountView>) => {
  const selects = wrapper.findAll(".el-dialog .el-select__wrapper");
  return selects[selects.length - 1];
};

const issuedKeyValue = (wrapper: ReturnType<typeof mountView>) =>
  wrapper.find(".ak-issued-key textarea").element as HTMLTextAreaElement;

describe("ApiKeysView", () => {
  it("首屏列出我的密钥：前缀带省略号、空白名单显示全部模型", async () => {
    list.mockResolvedValue(ok([row(), row({ iid: 2, name: "CI", allowModels: "gpt-4o-mini,7" })]));

    const wrapper = await loadView();

    expect(list).toHaveBeenCalledTimes(1);
    expect(wrapper.find(".ak-toolbar-text").text()).toContain("共 2 把，可用 2 把");
    const cells = wrapper.findAll(".ak-prefix").map((item) => item.text());
    expect(cells).toEqual(["sk-1a2b3c4d…", "sk-1a2b3c4d…"]);
    expect(wrapper.text()).toContain("全部模型");
    expect(wrapper.text()).toContain("gpt-4o-mini,7");
    expect(wrapper.text()).toContain("不过期");
  });

  it("过期与停用都算不可用，过期行的开关被禁用", async () => {
    list.mockResolvedValue(
      ok([
        row({ iid: 3, expireAt: Date.now() - DAY_MS }),
        row({ iid: 4, enable: false }),
      ])
    );

    const wrapper = await loadView();

    expect(wrapper.find(".ak-toolbar-text").text()).toContain("可用 0 把");
    expect(wrapper.text()).toContain("已过期");
    expect(wrapper.text()).toContain("已停用");
    expect(wrapper.findAll(".el-switch")[0].classes()).toContain("is-disabled");
    expect(wrapper.findAll(".el-switch")[1].classes()).not.toContain("is-disabled");
  });

  it("列表接口失败给提示而不是白屏", async () => {
    const error = vi.spyOn(ElMessage, "error");
    list.mockResolvedValue(fail("请先登录"));

    const wrapper = await loadView();

    expect(error).toHaveBeenCalledWith("请先登录");
    expect(wrapper.findAll("tbody tr")).toHaveLength(0);
    expect(wrapper.find(".ak-empty").exists()).toBe(true);
  });

  it("名称为空时不发请求", async () => {
    list.mockResolvedValue(ok([]));
    const warning = vi.spyOn(ElMessage, "warning");
    const wrapper = await loadView();

    await wrapper.find(".ak-toolbar button").trigger("click");
    await dialogButton(wrapper, "确定")!.trigger("click");

    expect(create).not.toHaveBeenCalled();
    expect(warning).toHaveBeenCalledWith("请填写密钥名称");
  });

  it("新建：有效期按天数换算，明文只在弹窗出现一次", async () => {
    list.mockResolvedValue(ok([]));
    create.mockResolvedValue(
      ok({ token: row({ allowModels: "" }), plaintext: "sk-plain-once-123456" })
    );

    const wrapper = await loadView();
    await wrapper.find(".ak-toolbar button").trigger("click");
    await dialogInput(wrapper, "例如：本地脚本 / CI").setValue("本地脚本");
    const select = lastDialogSelect(wrapper);
    expect(select).toBeTruthy();
    await select!.trigger("click");
    const option = Array.from(document.body.querySelectorAll<HTMLElement>(".el-select-dropdown__item"))
      .find((item) => item.textContent?.includes("30 天"));
    expect(option).toBeTruthy();
    option!.click();
    await flushPromises();

    await dialogButton(wrapper, "确定")!.trigger("click");
    await flushPromises();

    // 这里看视图交给 api 层的入参；「包一层 data」是 api 层的契约，见 api/__tests__/aiApiToken.spec.ts
    const payload = create.mock.calls[0][0];
    expect(payload.name).toBe("本地脚本");
    expect(payload.allowModels).toBe("");
    const before = Date.now();
    expect(payload.expireAt).toBeGreaterThan(before + 30 * DAY_MS - 5000);
    expect(payload.expireAt).toBeLessThanOrEqual(Date.now() + 30 * DAY_MS + 5000);

    // 创建成功后弹出唯一一次可见的明文
    expect(wrapper.text()).toContain("密钥已生成");
    expect(issuedKeyValue(wrapper).value).toBe("sk-plain-once-123456");
    expect(wrapper.find(".ak-usage").text()).toContain("Bearer sk-plain-once-123456");
    // 列表刷新过一次
    expect(list).toHaveBeenCalledTimes(2);
  });

  it("关闭明文窗后状态里不再留明文", async () => {
    list.mockResolvedValue(ok([]));
    create.mockResolvedValue(ok({ token: row(), plaintext: "sk-plain-once-999" }));

    const wrapper = await loadView();
    await wrapper.find(".ak-toolbar button").trigger("click");
    await dialogInput(wrapper, "例如：本地脚本 / CI").setValue("脚本");
    await dialogButton(wrapper, "确定")!.trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("sk-plain-once-999");

    await dialogButton(wrapper, "我已保存，关闭")!.trigger("click");
    await flushPromises();

    expect(wrapper.text()).not.toContain("sk-plain-once-999");
  });

  it("编辑：不选有效期就不下发 expireAt（框架 update 只写非 null 字段，清空做不到）", async () => {
    list.mockResolvedValue(ok([row({ allowModels: "gpt-4o-mini" })]));
    update.mockResolvedValue(ok(true));

    const wrapper = await loadView();
    await wrapper.findAll("tbody .el-button")[0].trigger("click");
    await flushPromises();

    expect(wrapper.find(".el-dialog").text()).toContain("编辑密钥");
    expect(wrapper.find(".ak-hint").text()).toContain("不会清空");
    await dialogButton(wrapper, "确定")!.trigger("click");
    await flushPromises();

    const payload = update.mock.calls[0][0];
    expect(payload).toEqual({ iid: 1, name: "本地脚本", allowModels: "gpt-4o-mini", enable: true });
    expect("expireAt" in payload).toBe(false);
  });

  it("停用成功：受控开关要跟着落下，状态列与计数同步", async () => {
    list.mockResolvedValue(ok([row()]));
    update.mockResolvedValue(ok(true));

    const wrapper = await loadView();
    await wrapper.findAll(".el-switch")[0].trigger("click");
    await flushPromises();

    expect(update).toHaveBeenCalledWith({ iid: 1, enable: false });
    expect(wrapper.findAll(".el-switch")[0].classes()).not.toContain("is-checked");
    expect(wrapper.text()).toContain("已停用");
    expect(wrapper.find(".ak-toolbar-text").text()).toContain("可用 0 把");
  });

  it("停用失败要把开关拨回去，界面不能和库里不一致", async () => {
    list.mockResolvedValue(ok([row()]));
    update.mockResolvedValue(fail("令牌不存在"));
    const error = vi.spyOn(ElMessage, "error");

    const wrapper = await loadView();
    await wrapper.findAll(".el-switch")[0].trigger("click");
    await flushPromises();

    expect(update).toHaveBeenCalledWith({ iid: 1, enable: false });
    expect(error).toHaveBeenCalledWith("令牌不存在");
    // 回滚后仍是启用态：开关、状态列、计数三处口径必须一起回来
    expect(wrapper.findAll(".el-switch")[0].classes()).toContain("is-checked");
    expect(wrapper.find(".ak-toolbar-text").text()).toContain("可用 1 把");
    expect(wrapper.text()).not.toContain("已停用");
  });

  it("删除：取消确认不发请求，确认后带上 iid 并重载", async () => {
    list.mockResolvedValue(ok([row({ iid: 9 })]));
    remove.mockResolvedValue(ok(true));
    const confirm = vi.spyOn(ElMessageBox, "confirm").mockRejectedValue(new Error("cancel"));

    const wrapper = await loadView();
    await wrapper.findAll("tbody .el-button")[1].trigger("click");
    await flushPromises();

    expect(remove).not.toHaveBeenCalled();

    confirm.mockResolvedValue("confirm" as never);
    await wrapper.findAll("tbody .el-button")[1].trigger("click");
    await flushPromises();

    expect(remove).toHaveBeenCalledWith(9);
    expect(list).toHaveBeenCalledTimes(2);
  });

  it("接口抛异常时提示重试并结束 loading", async () => {
    const error = vi.spyOn(ElMessage, "error");
    list.mockRejectedValue(new Error("network down"));

    const wrapper = await loadView();

    expect(error).toHaveBeenCalledWith("获取密钥列表失败，请稍后重试");
    // loading 必须收掉，否则刷新按钮永久禁用
    expect(wrapper.find(".ak-toolbar button:nth-child(2)").attributes("disabled")).toBeUndefined();
  });
});
