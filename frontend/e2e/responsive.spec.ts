import { expect, test } from "@playwright/test";
import { installBackendMocks, loginAsUser } from "./mocks";

/**
 * 外壳响应式：960px 断点两侧的侧栏形态。
 *
 * agent.spec 只在页面内断言横向不溢出，测不到「窄屏抽屉能否打开/关闭」。
 * 这里把 375px 的真实交互和桌面恢复常驻各走一遍，避免两条同特异度
 * 的 .shell 规则再次互相覆盖后没人发现。
 */
test.describe("响应式外壳", () => {
  test("窄屏侧栏以抽屉开合，桌面宽度恢复常驻且不横向溢出", async ({ page }) => {
    await installBackendMocks(page);
    await loginAsUser(page);
    await page.setViewportSize({ width: 375, height: 800 });
    await page.goto("/#/chat");

    const toggle = page.getByRole("button", { name: "菜单", exact: true });
    const sidebar = page.locator(".shell-sidebar");
    const noOverflow = () =>
      page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

    await expect(toggle).toBeVisible();
    await expect(sidebar).toBeHidden();
    // 聊天页自己的历史抽屉同样不能默认铺满窄屏，否则会挡住全局菜单
    await expect(page.locator(".chat-sidebar-drawer-overlay")).toHaveCount(0);
    await expect.poll(noOverflow).toBe(true);

    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-expanded", "true");
    await expect(sidebar).toBeVisible();
    await expect(page.getByRole("button", { name: "关闭菜单", exact: true })).toBeVisible();
    await expect.poll(noOverflow).toBe(true);

    // 遮罩左侧被抽屉本体盖住，点右侧可见区域才是真实用户操作
    await page
      .getByRole("button", { name: "关闭菜单", exact: true })
      .click({ position: { x: 360, y: 400 } });
    await expect(sidebar).toBeHidden();
    await expect(toggle).toHaveAttribute("aria-expanded", "false");

    await page.setViewportSize({ width: 1280, height: 800 });
    await expect(sidebar).toBeVisible();
    await expect(toggle).toBeHidden();
    await expect.poll(noOverflow).toBe(true);
  });
});
