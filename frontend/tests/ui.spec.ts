import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { memberId, mockSession, otherWorkspace, repositories, user, workspace } from "./fixtures";

const evidenceDir = path.resolve(process.cwd(), "../.verification/frontend");

test("unauthenticated visitor reaches login and receives a useful login error", async ({
  page,
}) => {
  await page.route("**/api/auth/**", (route) =>
    route.fulfill({ status: 401, json: { status: 401 } }),
  );
  await page.goto("/");
  await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel("Email", { exact: true }).fill("minh@example.test");
  await page.getByLabel("Mật khẩu", { exact: true }).fill("wrong-password");
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await expect(
    page.getByRole("alert").filter({ hasText: "Email hoặc mật khẩu không đúng" }),
  ).toBeVisible();
});

test("registration checks UTF-8 byte length and keeps password unchanged", async ({ page }) => {
  await page.route("**/api/auth/me", (route) => route.fulfill({ status: 401 }));
  await page.route("**/api/auth/refresh", (route) => route.fulfill({ status: 401 }));
  await page.goto("/register");
  await page.getByLabel("Tên hiển thị").fill("Nguyễn Minh");
  await page.getByLabel("Email", { exact: true }).fill("minh@example.test");
  await page.getByLabel("Mật khẩu", { exact: true }).fill("ắ".repeat(25));
  await page.getByRole("button", { name: "Tạo tài khoản", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "72 byte UTF-8" })).toBeVisible();
  await page.getByRole("button", { name: "Hiện mật khẩu" }).click();
  await expect(page.getByLabel("Mật khẩu", { exact: true })).toHaveAttribute("type", "text");
  await expect(page.getByLabel("Mật khẩu", { exact: true })).toHaveValue("ắ".repeat(25));
});

test("empty workspace state opens an accessible create dialog", async ({ page }) => {
  await mockSession(page, { empty: true });
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Không gian cho dự án tiếp theo" })).toBeVisible();
  await page.getByRole("button", { name: "Tạo workspace", exact: true }).last().click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(page.getByLabel("Tên workspace")).toBeFocused();
  await page.getByLabel("Tên workspace").fill("Platform");
  await page.getByLabel("Slug", { exact: true }).fill("invalid SLUG");
  expect(
    await page
      .getByLabel("Slug", { exact: true })
      .evaluate((element: HTMLInputElement) => element.checkValidity()),
  ).toBe(false);
  await page.keyboard.press("Escape");
  await expect(dialog).not.toBeVisible();
});

test("loading state is announced before data arrives", async ({ page }) => {
  await mockSession(page, { delay: 500 });
  await page.goto("/");
  await expect(page.getByRole("status").first()).toBeVisible();
  await expect(page.locator(".workspace-context")).toHaveText(workspace.name);
});

test("repository failure leaves workspace and member data visible and can recover", async ({
  page,
}) => {
  await mockSession(page, { repositoriesError: true });
  await page.goto("/");
  await expect(page.locator(".workspace-context")).toHaveText(workspace.name);
  await expect(
    page.getByRole("alert").filter({ hasText: "Không kết nối được dịch vụ" }),
  ).toBeVisible();
  await expect(page.getByText("Sự cố gần đây", { exact: true })).toBeVisible();
  await page.route("**/repositories?*", (route) =>
    route.fulfill({ json: { items: repositories, page: 0, size: 5, totalElements: 3 } }),
  );
  await page.getByRole("button", { name: "Thử lại" }).click();
  await expect(page.getByRole("link", { name: /tracepilot\/backend/ })).toBeVisible();
});

test("member permissions omit management actions and owner alone can sync", async ({ page }) => {
  await mockSession(page, { role: "MEMBER" });
  await page.goto("/members");
  await expect(page.getByText("Bạn có quyền xem danh sách.", { exact: false })).toBeVisible();
  await expect(page.getByRole("button", { name: "Thêm thành viên", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: /^Xóa / })).toHaveCount(0);
  await page.getByRole("link", { name: "Kho mã", exact: true }).click();
  await expect(page.getByRole("link", { name: /tracepilot\/backend/ })).toBeVisible();
  await expect(page.getByRole("button", { name: "Đồng bộ GitHub" })).toHaveCount(0);
});

test("owner sees disabled integration error and unknown default branch", async ({ page }) => {
  await mockSession(page);
  await page.goto("/repositories");
  await expect(page.getByText("Chưa xác định nhánh mặc định")).toBeVisible();
  await expect(page.getByText("Đã ngừng theo dõi", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Đồng bộ GitHub" }).click();
  await expect(
    page.getByRole("alert").filter({ hasText: "Tích hợp GitHub chưa sẵn sàng" }),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: /tracepilot\/backend/ })).toBeVisible();
});

test("owner adds a member and sees success confirmation", async ({ page }) => {
  await mockSession(page);
  await page.goto("/members");
  await page.getByRole("button", { name: "Thêm thành viên", exact: true }).click();
  const newId = "55555555-5555-4555-8555-555555555555";
  let added = false;
  await page.route(`**/workspaces/${workspace.id}/members`, (route) => {
    if (route.request().method() === "POST") {
      added = true;
      return route.fulfill({
        status: 201,
        json: { userId: newId, role: "MEMBER", status: "ACTIVE" },
      });
    }
    return route.fulfill({
      json: [
        { userId: user.id, role: "OWNER", status: "ACTIVE" },
        { userId: memberId, role: "MEMBER", status: "ACTIVE" },
        ...(added ? [{ userId: newId, role: "MEMBER", status: "ACTIVE" }] : []),
      ],
    });
  });
  await page.getByLabel("UUID tài khoản").fill(newId);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Thêm thành viên", exact: true })
    .click();
  await expect(page.getByRole("status")).toContainText("Đã thêm thành viên");
  await expect(page.getByText(newId, { exact: true })).toBeVisible();
});

test("workspace switching clears the previous repository data", async ({ page }) => {
  await mockSession(page);
  await page.goto("/repositories");
  await expect(page.getByRole("link", { name: /tracepilot\/backend/ })).toBeVisible();
  await page.route(`**/workspaces/${otherWorkspace.id}/repositories?*`, (route) =>
    route.fulfill({ json: { items: [], page: 0, size: 20, totalElements: 0 } }),
  );
  await page.getByLabel("Workspace hiện tại").selectOption(otherWorkspace.id);
  await expect(page.getByRole("heading", { name: "Chưa có kho mã" })).toBeVisible();
  await expect(page.getByRole("link", { name: /tracepilot\/backend/ })).toHaveCount(0);
});

for (const viewport of [
  { width: 1440, height: 900 },
  { width: 390, height: 844 },
  { width: 320, height: 812 },
]) {
  test(`rendered layout and keyboard navigation at ${viewport.width}x${viewport.height}`, async ({
    page,
  }) => {
    await page.setViewportSize(viewport);
    await page.emulateMedia({ reducedMotion: "reduce" });
    await mockSession(page, { long: true });
    await page.goto("/");
    await expect(page.getByRole("link", { name: /tracepilot\/backend/ })).toBeVisible();
    await page.keyboard.press("Tab");
    await expect(page.getByRole("link", { name: "Đến nội dung chính" })).toBeFocused();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    const accessibility = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
      .analyze();
    await mkdir(evidenceDir, { recursive: true });
    await writeFile(
      path.join(evidenceDir, `axe-dashboard-${viewport.width}.json`),
      JSON.stringify(accessibility.violations, null, 2),
    );
    expect(
      accessibility.violations.map((violation) => ({
        id: violation.id,
        targets: violation.nodes.map((node) => node.target),
      })),
    ).toEqual([]);
    await page.evaluate(() => (document.activeElement as HTMLElement)?.blur());
    await page.screenshot({
      path: path.join(evidenceDir, `dashboard-${viewport.width}x${viewport.height}.png`),
      fullPage: true,
    });
    if (viewport.width < 760) {
      await page.getByRole("button", { name: "Mở điều hướng" }).click();
      await expect(page.getByRole("navigation", { name: "Điều hướng chính" })).toBeVisible();
      await page.getByLabel("Workspace hiện tại").selectOption(otherWorkspace.id);
      await page.getByRole("button", { name: "Mở điều hướng" }).click();
      await page.getByRole("link", { name: "Thành viên", exact: true }).click();
    } else await page.getByRole("link", { name: "Thành viên", exact: true }).click();
    await expect(page.getByText(memberId, { exact: true })).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.screenshot({
      path: path.join(evidenceDir, `members-${viewport.width}x${viewport.height}.png`),
      fullPage: true,
    });
  });
}
