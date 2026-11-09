import type { Page } from "@playwright/test";

export const user = {
  id: "11111111-1111-4111-8111-111111111111",
  email: "minh@example.test",
  displayName: "Nguyễn Minh",
};
export const workspace = {
  id: "22222222-2222-4222-8222-222222222222",
  name: "Engineering",
  slug: "engineering",
  createdBy: user.id,
};
export const otherWorkspace = {
  id: "33333333-3333-4333-8333-333333333333",
  name: "Nền tảng hạ tầng và các dịch vụ nội bộ",
  slug: "platform-infrastructure",
  createdBy: user.id,
};
export const memberId = "44444444-4444-4444-8444-444444444444";
export const repositories = [
  {
    id: "55555555-5555-4555-8555-555555555555",
    githubRepositoryId: 101,
    fullName: "tracepilot/backend",
    defaultBranch: "main",
    active: true,
  },
  {
    id: "66666666-6666-4666-8666-666666666666",
    githubRepositoryId: 102,
    fullName: "tracepilot/dashboard",
    defaultBranch: "develop",
    active: true,
  },
  {
    id: "77777777-7777-4777-8777-777777777777",
    githubRepositoryId: 103,
    fullName: "tracepilot/infrastructure",
    defaultBranch: null,
    active: false,
  },
];

export async function mockSession(
  page: Page,
  options: {
    role?: string;
    empty?: boolean;
    repositoriesError?: boolean;
    delay?: number;
    long?: boolean;
  } = {},
) {
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({
      json: {
        ...user,
        displayName: options.long
          ? "Nguyễn Minh và tên hiển thị dài để kiểm tra bố cục"
          : user.displayName,
      },
    }),
  );
  await page.route("**/api/backend/**", async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    if (options.delay) await new Promise((resolve) => setTimeout(resolve, options.delay));
    if (path.endsWith("/notifications/stream")) return route.abort();
    if (path.endsWith("/notifications"))
      return route.fulfill({ json: { items: [], page: 0, size: 5, total: 0 } });
    if (path.endsWith("/incidents"))
      return route.fulfill({ json: { items: [], page: 0, size: 5, total: 0 } });
    if (path.endsWith("/workspaces"))
      return route.fulfill({ json: options.empty ? [] : [workspace, otherWorkspace] });
    if (path.endsWith("/members"))
      return route.fulfill({
        json: [
          { userId: user.id, role: options.role ?? "OWNER", status: "ACTIVE" },
          { userId: memberId, role: "MEMBER", status: "ACTIVE" },
        ],
      });
    if (path.endsWith("/repositories")) {
      if (options.repositoriesError)
        return route.fulfill({ status: 502, json: { detail: "Unavailable" } });
      return route.fulfill({ json: { items: repositories, page: 0, size: 20, totalElements: 3 } });
    }
    if (path.endsWith("/github/sync"))
      return route.fulfill({ status: 503, json: { detail: "GitHub integration disabled" } });
    return route.fulfill({ json: path.includes(otherWorkspace.id) ? otherWorkspace : workspace });
  });
}
