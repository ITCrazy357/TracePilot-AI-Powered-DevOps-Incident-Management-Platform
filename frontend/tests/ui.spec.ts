import { expect, test, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { memberId, mockSession, otherWorkspace, repositories, user, workspace } from "./fixtures";

const evidenceDir = path.resolve(process.cwd(), "../.verification/frontend");

async function mockGitHubConnection(
  page: Page,
  options: { noGrant?: boolean; conflict?: boolean } = {},
) {
  await mockSession(page);
  let selected: number[] = [];
  const writes: Record<string, unknown>[] = [];
  let version: number | null = null;
  await page.route(`**/workspaces/${otherWorkspace.id}/repositories?**`, (route) =>
    route.fulfill({
      json: {
        items: selected.includes(10001)
          ? [{ ...repositories[0], fullName: "acme/OfficeFlow" }]
          : [],
        page: 0,
        size: 20,
        totalElements: selected.length,
      },
    }),
  );
  await page.route("**/github/**", (route) => {
    if (route.request().method() === "POST") {
      const body = route.request().postDataJSON();
      writes.push(body);
      if (options.conflict && writes.length === 1) {
        version = 2;
        return route.fulfill({ status: 409, json: {} });
      }
      selected = body.repositoryIds;
      version = 3;
      return route.fulfill({
        json: {
          id: "connection",
          installationId: 501,
          accountLogin: "acme",
          status: "ACTIVE",
          repositoryCount: selected.length,
        },
      });
    }
    if (route.request().url().includes("/installations/"))
      return route.fulfill({
        json: {
          status: "ACTIVE",
          repositories: [
            { id: 10000, fullName: "acme/Roomora", defaultBranch: "main" },
            { id: 10001, fullName: "acme/OfficeFlow", defaultBranch: "main" },
          ],
        },
      });
    return route.fulfill({
      json: {
        connection: {
          installationId: version === null ? null : 501,
          accountLogin: "acme",
          version,
          repositoryIds: selected,
        },
        installations: options.noGrant ? [] : [{ installationId: 501, accountLogin: "acme" }],
      },
    });
  });
  await page.goto("/repositories");
  await expect(page.getByRole("button", { name: "Chọn repository", exact: true })).toBeVisible();
  if (await page.getByRole("button", { name: "Mở điều hướng", exact: true }).isVisible()) {
    await page.getByRole("button", { name: "Mở điều hướng", exact: true }).click();
  }
  await page.getByLabel("Workspace hiện tại", { exact: true }).selectOption(otherWorkspace.id);
  if (await page.getByRole("button", { name: "Đóng điều hướng", exact: true }).isVisible()) {
    await page.getByRole("button", { name: "Đóng điều hướng", exact: true }).click();
  }
  await page.getByRole("button", { name: "Chọn repository", exact: true }).click();
  return { writes };
}

test("owner chooses a project for a new workspace without changing the previous workspace", async ({
  page,
}) => {
  const state = await mockGitHubConnection(page);
  await page.getByRole("checkbox", { name: "acme/OfficeFlow", exact: true }).check();
  await page.getByRole("button", { name: "Lưu repository theo dõi" }).click();
  await expect(page.getByRole("link", { name: /acme\/OfficeFlow/ })).toBeVisible();
  expect(state.writes).toEqual([{ installationId: 501, version: null, repositoryIds: [10001] }]);
  await page.getByLabel("Workspace hiện tại", { exact: true }).selectOption(workspace.id);
  await expect(page.getByRole("link", { name: /tracepilot\/backend/ })).toBeVisible();
  await expect(page.getByRole("link", { name: /acme\/OfficeFlow/ })).toHaveCount(0);
});

test("GitHub selection conflicts require reloading the captured version", async ({ page }) => {
  const state = await mockGitHubConnection(page, { conflict: true });
  await page.getByRole("checkbox", { name: "acme/OfficeFlow", exact: true }).check();
  await page.getByRole("button", { name: "Lưu repository theo dõi" }).click();
  await expect(
    page.getByRole("alert").filter({ hasText: "Liên kết hoặc quyền repository" }),
  ).toBeVisible();
  await expect(page.getByRole("checkbox", { name: "acme/OfficeFlow", exact: true })).toBeChecked();
  await expect(page.getByRole("button", { name: "Lưu repository theo dõi" })).toBeDisabled();
  await page.getByRole("button", { name: "Tải lại lựa chọn" }).click();
  await expect(
    page.getByRole("checkbox", { name: "acme/OfficeFlow", exact: true }),
  ).not.toBeChecked();
  await page.getByRole("checkbox", { name: "acme/OfficeFlow", exact: true }).check();
  await page.getByRole("button", { name: "Lưu repository theo dõi" }).click();
  await expect.poll(() => state.writes.length).toBe(2);
  expect(state.writes[1].version).toBe(2);
});

test("a workspace owner with no approved GitHub grant gets a setup explanation", async ({
  page,
}) => {
  await mockGitHubConnection(page, { noGrant: true });
  await expect(page.getByRole("dialog")).toContainText("Chưa có GitHub App được cấp quyền cho bạn");
  await expect(page.getByRole("button", { name: "Lưu repository theo dõi" })).toBeDisabled();
  await expect(page.getByRole("checkbox")).toHaveCount(0);
});

for (const width of [1440, 390, 320]) {
  test(`GitHub repository chooser is accessible and fits at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: width === 1440 ? 900 : width === 390 ? 844 : 812 });
    await mockGitHubConnection(page);
    await expect(
      page.getByRole("checkbox", { name: "acme/OfficeFlow", exact: true }),
    ).toBeVisible();
    expect(
      (await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze())
        .violations,
    ).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await mkdir(evidenceDir, { recursive: true });
    await page.screenshot({
      path: path.join(evidenceDir, `github-selection-${width}.png`),
      fullPage: true,
    });
    await page.keyboard.press("Escape");
    await expect(page.getByRole("button", { name: "Chọn repository", exact: true })).toBeFocused();
  });
}

test("GitHub connection BFF accepts only scoped paths and same-origin writes", async ({
  request,
}) => {
  const base = `/api/backend/workspaces/${workspace.id}/github`;
  const origin = process.env.FRONTEND_URL ?? "http://127.0.0.1:3000";
  expect((await request.get(`${base}/connection`)).status()).toBe(401);
  expect((await request.get(`${base}/installations/501/repositories`)).status()).toBe(401);
  expect((await request.get(`${base}/installations/0/repositories`)).status()).toBe(404);
  expect(
    (
      await request.post(`${base}/connection`, {
        headers: { Origin: "https://foreign.invalid" },
        data: {},
      })
    ).status(),
  ).toBe(403);
  expect(
    (await request.post(`${base}/connection`, { headers: { Origin: origin }, data: {} })).status(),
  ).toBe(401);
  expect(
    (await request.delete(`${base}/connection`, { headers: { Origin: origin } })).status(),
  ).toBe(404);
});

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
