import { expect, test } from "@playwright/test";
import { installBackendMocks, loginAsUser } from "./mocks";

/**
 * 知识检索页真实浏览器链路：文件选择 → multipart 异步上传 →
 * 轮询 queued → ready → 表格展示分块数。
 *
 * <p>组件测试只能证明状态机与渲染，这里额外验证三件浏览器专属的事：
 * 文件 input 的 change 事件、FormData 的 Content-Type（是否真的带 boundary）、
 * 以及 2 秒定时器在真实事件循环里确实会继续拉取列表。</p>
 */
test.describe("知识检索文档流水线", () => {
  test("上传文本文件后轮询进度并最终就绪", async ({ page }) => {
    const uploads: Array<{ contentType: string; postData: string | null }> = [];
    let docs: Array<Record<string, unknown>> = [];
    let listCalls = 0;

    await installBackendMocks(page, {
      kbUploads: uploads,
      kbUploadResponse: () => {
        docs = [
          {
            iid: 9,
            name: "deploy.txt",
            kbName: "默认知识库",
            chunkCount: 0,
            charCount: 120,
            status: "queued",
            progress: 0,
            createtime: null,
          },
        ];
        return docs[0];
      },
      kbDocs: () => {
        listCalls += 1;
        // 首次上传后的列表仍返回 queued，让页面真的经历一次轮询
        if (docs.length > 0 && docs[0].status === "queued" && listCalls >= 3) {
          docs = [{ ...docs[0], status: "ready", progress: 100, chunkCount: 4 }];
        }
        return docs;
      },
    });
    await loginAsUser(page);
    const pageErrors: string[] = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));

    await page.goto("/#/kb-search");
    await expect(page.getByText("文档库")).toBeVisible();

    await page.getByRole("button", { name: "上传文档" }).click();
    await page.locator('input[type="file"]').setInputFiles({
      name: "deploy.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("差旅报销制度：住宿每晚上限六百元，需部门负责人审批。", "utf-8"),
    });
    await expect(page.getByText(/已选择 deploy\.txt/)).toBeVisible();
    await page.getByRole("button", { name: "上传并向量化" }).click();

    await expect(page.locator(".el-table__row").first()).toContainText("deploy.txt");
    await expect(page.locator(".el-table__row").first()).toContainText("就绪", {
      timeout: 8000,
    });
    await expect(page.locator(".el-table__row").first()).toContainText("4");

    expect(uploads).toHaveLength(1);
    expect(uploads[0].contentType).toContain("multipart/form-data");
    expect(uploads[0].contentType).toContain("boundary=");
    expect(pageErrors).toEqual([]);
  });
});
