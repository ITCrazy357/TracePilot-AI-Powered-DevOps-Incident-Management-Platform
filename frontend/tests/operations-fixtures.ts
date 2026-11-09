import type { Page } from "@playwright/test";
import type { Analysis, Incident, WorkflowRun } from "../src/lib/contracts";
import { mockSession, repositories, user, workspace } from "./fixtures";

export const incidentId = "88888888-8888-4888-8888-888888888888";
export const runId = "99999999-9999-4999-8999-999999999999";
export const analysisId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
export const notificationId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
export const evidence = "error: invalid target release: 21";
export const incident: Incident = {
  id: incidentId,
  workspaceId: workspace.id,
  repositoryId: repositories[0].id,
  workflowRunId: runId,
  failedJobId: "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
  assignedTo: user.id,
  title: "Workflow run #482 thất bại — build-backend",
  status: "INVESTIGATING",
  severity: "MEDIUM",
  version: 7,
  detectedAt: "2026-11-08T08:42:00Z",
  updatedAt: "2026-11-08T08:44:00Z",
  resolvedAt: null,
};
export const run: WorkflowRun = {
  id: runId,
  repositoryId: repositories[0].id,
  githubRunId: 482,
  runAttempt: 2,
  workflowName: "CI / Build & Test",
  headBranch: "main",
  headSha: "9f3a2bd1234567890",
  status: "completed",
  conclusion: "failure",
  htmlUrl: "https://github.com/tracepilot/backend/actions/runs/482",
  startedAt: "2026-11-08T08:39:00Z",
  completedAt: "2026-11-08T08:42:00Z",
};
export const analysis: Analysis = {
  id: analysisId,
  incidentId,
  provider: "openai",
  model: "fixture-model",
  promptVersion: "phase05-v1",
  status: "SUCCEEDED",
  summary: "Bước build dừng khi biên dịch mã nguồn Java.",
  suspectedRootCause:
    "Log cho thấy target release 21 không được trình biên dịch hiện tại hỗ trợ. Cần kiểm tra phiên bản JDK thực tế của runner.",
  suggestedFix:
    "Đối chiếu cấu hình setup-java với maven.compiler.release, rồi chạy lại kiểm thử trên JDK phù hợp.",
  evidence: [evidence],
  errorCode: null,
  startedAt: "2026-11-08T08:43:00Z",
  completedAt: "2026-11-08T08:44:00Z",
  createdAt: "2026-11-08T08:43:00Z",
};

export async function mockOperations(
  page: Page,
  options: { role?: string; unassigned?: boolean; conflict?: boolean } = {},
) {
  await mockSession(page, { role: options.role });
  let current = { ...incident, assignedTo: options.unassigned ? null : incident.assignedTo };
  let readAt: string | null = null;
  const updates: Record<string, unknown>[] = [];
  await page.route("**/api/backend/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    if (path.endsWith("/notifications/stream")) return route.abort();
    if (path.endsWith("/notifications"))
      return route.fulfill({
        json: {
          items: [
            {
              id: notificationId,
              incidentId,
              type: "AI_ANALYSIS_SUCCEEDED",
              title: "Phân tích AI đã hoàn tất",
              readAt,
              createdAt: "2026-11-08T08:44:00Z",
            },
          ],
          page: 0,
          size: 20,
          total: 1,
        },
      });
    if (path.endsWith(`/notifications/${notificationId}/read`)) {
      readAt = "2026-11-08T09:00:00Z";
      return route.fulfill({ status: 204 });
    }
    if (path.endsWith("/incidents"))
      return route.fulfill({ json: { items: [current], page: 0, size: 20, total: 1 } });
    if (path.endsWith(`/incidents/${incidentId}`)) return route.fulfill({ json: current });
    if (path.endsWith("/status") || path.endsWith("/assignee")) {
      const body = request.postDataJSON();
      updates.push(body);
      if (options.conflict) {
        current = { ...current, version: 8, status: "RESOLVED" };
        return route.fulfill({
          status: 409,
          json: { detail: "Incident changed. Reload before updating." },
        });
      }
      current = { ...current, ...body, version: current.version + 1 };
      return route.fulfill({ json: current });
    }
    if (path.endsWith("/logs"))
      return route.fulfill({
        json: {
          status: "READY",
          errorCode: null,
          snippets: [
            {
              chunkIndex: 0,
              content: "[INFO] Build started\n[ERROR] error: invalid ",
              contentSha256: "a".repeat(64),
              capturedAt: "2026-11-08T08:42:30Z",
            },
            {
              chunkIndex: 1,
              content:
                "target release: 21\n<script>window.tracepilotInjected=true</script>\nTOKEN=[REDACTED]",
              contentSha256: "b".repeat(64),
              capturedAt: "2026-11-08T08:42:30Z",
            },
          ],
        },
      });
    if (path.endsWith("/analyses") && method === "GET")
      return route.fulfill({ json: { items: [analysis], page: 0, size: 20, total: 1 } });
    if (path.endsWith(`/analyses/${analysisId}`)) return route.fulfill({ json: analysis });
    if (path.endsWith("/evidence")) return route.fulfill({ json: [] });
    if (path.endsWith("/usage"))
      return route.fulfill({
        json: [
          {
            id: "usage1",
            analysisId,
            attempt: 1,
            operation: "CHAT",
            provider: "openai",
            model: "fixture-model",
            status: "UNKNOWN",
            inputTokens: null,
            outputTokens: null,
            estimatedCostUsd: null,
            durationMs: null,
            errorCode: null,
            createdAt: analysis.createdAt,
          },
        ],
      });
    if (path.endsWith("/workflow-runs"))
      return route.fulfill({ json: { items: [run], page: 0, size: 20, totalElements: 1 } });
    if (path.endsWith(`/workflow-runs/${runId}`)) return route.fulfill({ json: run });
    if (path.endsWith("/jobs"))
      return route.fulfill({
        json: [
          {
            id: incident.failedJobId,
            githubJobId: 4412,
            name: "build-backend",
            status: "completed",
            conclusion: "failure",
            htmlUrl: run.htmlUrl,
            startedAt: run.startedAt,
            completedAt: run.completedAt,
          },
        ],
      });
    if (path.endsWith("/sync-requests"))
      return route.fulfill({
        json: {
          items: [
            {
              id: "sync1",
              repositoryId: run.repositoryId,
              githubRunId: 482,
              runAttempt: 2,
              publicationStatus: "PUBLISHED",
              processingStatus: "SUCCEEDED",
              attempts: 1,
              lastErrorCode: null,
              createdAt: run.startedAt,
              processedAt: run.completedAt,
            },
          ],
          page: 0,
          size: 5,
          totalElements: 1,
        },
      });
    if (path.endsWith("/workflow-runs/sync"))
      return route.fulfill({ status: 202, json: { id: "sync2", processingStatus: "PENDING" } });
    return route.fallback();
  });
  return { updates };
}
