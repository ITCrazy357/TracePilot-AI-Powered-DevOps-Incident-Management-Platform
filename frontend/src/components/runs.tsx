"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import {
  CirclePlay,
  ExternalLink,
  GitBranch,
  RefreshCw,
  Download,
  ArrowLeft,
  ArrowRight,
} from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import type { RunPage, SyncPage, SyncRequest, WorkflowJob, WorkflowRun } from "@/lib/contracts";
import { useResource } from "@/lib/use-resource";
import { dateTime, duration, githubUrl } from "@/lib/presentation";
import { useWorkspace } from "./workspace-provider";
import { WorkspaceBoundary } from "./workspace-boundary";
import { RepositorySelect } from "./repository-select";
import { Button } from "./ui/button";
import { Dialog } from "./ui/dialog";
import { EmptyState, ErrorNotice, Loading, SuccessNotice } from "./ui/states";
import { PageHeading, Pagination, StatusBadge } from "./ui/operational";

export function Runs({ initialRepository = "" }: { initialRepository?: string }) {
  const { selectedId } = useWorkspace();
  return (
    <>
      <PageHeading
        title="Lần chạy"
        description="Lịch sử GitHub Actions, tách riêng từng lần chạy lại."
      />
      <WorkspaceBoundary>
        <RunList key={`${selectedId}:${initialRepository}`} initialRepository={initialRepository} />
      </WorkspaceBoundary>
    </>
  );
}

function RunList({ initialRepository }: { initialRepository: string }) {
  const { selectedId, role } = useWorkspace();
  const [repositoryId, setRepositoryId] = useState(initialRepository);
  const [page, setPage] = useState(0);
  const base = repositoryId
    ? `/api/backend/workspaces/${selectedId}/repositories/${repositoryId}/workflow-runs`
    : null;
  const runs = useResource<RunPage>(base ? `${base}?page=${page}&size=20` : null);
  const requests = useResource<SyncPage>(base ? `${base}/sync-requests?page=0&size=5` : null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  async function synchronize(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const githubRunId = Number(data.get("githubRunId"));
    const runAttempt = Number(data.get("runAttempt"));
    if (
      !Number.isSafeInteger(githubRunId) ||
      githubRunId < 1 ||
      !Number.isInteger(runAttempt) ||
      runAttempt < 1 ||
      runAttempt > 1_000_000
    ) {
      setError("Nhập GitHub run ID nguyên dương và attempt từ 1 đến 1.000.000.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      await api<SyncRequest>(`${base}/sync`, {
        method: "POST",
        body: JSON.stringify({ githubRunId, runAttempt }),
      });
      setOpen(false);
      setSuccess("Đã xếp hàng yêu cầu đồng bộ. Làm mới để xem tiến độ xử lý.");
      requests.reload();
      runs.reload();
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="filter-toolbar">
        <RepositorySelect
          value={repositoryId}
          onChange={(id) => {
            setRepositoryId(id);
            setPage(0);
            setSuccess("");
          }}
        />
        <div className="toolbar-actions">
          <Button
            variant="outline"
            disabled={!base || runs.loading}
            onClick={() => {
              runs.reload();
              requests.reload();
            }}
          >
            <RefreshCw size={16} />
            Làm mới
          </Button>
          {role === "OWNER" && (
            <Button
              disabled={!base}
              onClick={() => {
                setError("");
                setOpen(true);
              }}
            >
              <Download size={16} />
              Đồng bộ lần chạy
            </Button>
          )}
        </div>
      </div>
      {success && <SuccessNotice>{success}</SuccessNotice>}
      {!repositoryId ? (
        <section className="panel">
          <EmptyState
            icon={GitBranch}
            title="Chọn kho mã để xem lần chạy"
            description="Nếu danh sách trống, OWNER cần đồng bộ kho mã GitHub cho workspace."
            action={
              <Button asChild variant="outline">
                <Link href="/repositories">
                  Đến kho mã
                  <ArrowRight size={16} />
                </Link>
              </Button>
            }
          />
        </section>
      ) : (
        <section className="panel">
          <div className="panel-heading">
            <h2>Lịch sử lần chạy</h2>
            <span className="muted">Mỗi attempt là một bản ghi riêng</span>
          </div>
          {runs.loading && !runs.data ? (
            <Loading label="Đang tải lần chạy…" />
          ) : runs.error ? (
            <div className="panel-error">
              <ErrorNotice message={runs.error} retry={runs.reload} />
            </div>
          ) : !runs.data?.items.length ? (
            <EmptyState
              icon={CirclePlay}
              title="Chưa có lần chạy"
              description="Lần chạy xuất hiện sau khi backend nhận webhook, hoặc OWNER đồng bộ một GitHub run ID cụ thể."
            />
          ) : (
            <div className="run-list">
              {runs.data.items.map((run) => (
                <Link key={run.id} href={`/runs/${repositoryId}/${run.id}`} className="run-row">
                  <CirclePlay className="row-icon" size={19} />
                  <div className="run-identity">
                    <strong>{run.workflowName}</strong>
                    <span className="run-meta">
                      <code>#{run.githubRunId}</code>
                      <span>Attempt {run.runAttempt}</span>
                      <span>
                        <GitBranch size={13} />
                        {run.headBranch ?? "Chưa có nhánh"}
                      </span>
                      {run.headSha && <code>{run.headSha.slice(0, 7)}</code>}
                    </span>
                  </div>
                  <div className="run-result">
                    <StatusBadge status={run.conclusion ?? run.status} />
                    <span className="muted">
                      {dateTime(run.startedAt)} · {duration(run.startedAt, run.completedAt)}
                    </span>
                  </div>
                  <ArrowRight size={16} />
                </Link>
              ))}
            </div>
          )}
          {runs.data && (
            <Pagination
              page={page}
              size={20}
              total={runs.data.totalElements}
              loading={runs.loading}
              onPage={setPage}
            />
          )}
        </section>
      )}
      {base && (
        <details className="panel sync-history">
          <summary>
            Yêu cầu đồng bộ gần đây
            <span className="muted">Phân biệt lỗi xử lý dữ liệu với kết quả CI</span>
          </summary>
          {requests.error ? (
            <div className="panel-error">
              <ErrorNotice message={requests.error} retry={requests.reload} />
            </div>
          ) : requests.loading && !requests.data ? (
            <Loading />
          ) : !requests.data?.items.length ? (
            <div className="quiet-empty">Chưa có yêu cầu đồng bộ nào.</div>
          ) : (
            <div className="sync-list">
              {requests.data.items.map((request) => (
                <div key={request.id}>
                  <div>
                    <code>#{request.githubRunId}</code> · Attempt {request.runAttempt}
                    <p className="muted">
                      {dateTime(request.createdAt)} · Đã thử {request.attempts} lần
                    </p>
                  </div>
                  <StatusBadge status={request.processingStatus} />
                  <span className="muted">Broker: {request.publicationStatus}</span>
                  {request.lastErrorCode && (
                    <code className="error-code">{request.lastErrorCode}</code>
                  )}
                </div>
              ))}
            </div>
          )}
        </details>
      )}
      <Dialog
        title="Đồng bộ lần chạy"
        description="Nhập run ID và attempt từ GitHub Actions. Đây là yêu cầu lấy dữ liệu, không khởi chạy workflow trên GitHub."
        open={open}
        onOpenChange={(value) => {
          if (!busy) setOpen(value);
        }}
      >
        {error && <ErrorNotice message={error} />}
        <form onSubmit={synchronize} className="form-stack">
          <label>
            GitHub run ID
            <input
              name="githubRunId"
              type="number"
              min={1}
              max={Number.MAX_SAFE_INTEGER}
              required
              disabled={busy}
            />
          </label>
          <label>
            Run attempt
            <input
              name="runAttempt"
              type="number"
              min={1}
              max={1_000_000}
              defaultValue={1}
              required
              disabled={busy}
            />
          </label>
          <div className="dialog-actions">
            <Button variant="outline" disabled={busy} onClick={() => setOpen(false)}>
              Hủy
            </Button>
            <Button type="submit" disabled={busy}>
              {busy ? "Đang gửi…" : "Gửi yêu cầu đồng bộ"}
            </Button>
          </div>
        </form>
      </Dialog>
    </>
  );
}

export function RunDetail({ repositoryId, runId }: { repositoryId: string; runId: string }) {
  const { selectedId } = useWorkspace();
  return (
    <WorkspaceBoundary>
      <RunDetailContent key={`${selectedId}:${runId}`} repositoryId={repositoryId} runId={runId} />
    </WorkspaceBoundary>
  );
}

function RunDetailContent({ repositoryId, runId }: { repositoryId: string; runId: string }) {
  const { selectedId } = useWorkspace();
  const base = `/api/backend/workspaces/${selectedId}/repositories/${repositoryId}/workflow-runs/${runId}`;
  const run = useResource<WorkflowRun>(base);
  const jobs = useResource<WorkflowJob[]>(`${base}/jobs`);
  if (run.loading && !run.data) return <Loading label="Đang tải lần chạy…" />;
  if (run.error) return <ErrorNotice message={run.error} retry={run.reload} />;
  if (!run.data) return null;
  const item = run.data;
  const url = githubUrl(item.htmlUrl);
  return (
    <>
      <Link href={`/runs?repositoryId=${repositoryId}`} className="back-link">
        <ArrowLeft size={15} />
        Lần chạy
      </Link>
      <PageHeading
        title={item.workflowName}
        description={
          <>
            <code>#{item.githubRunId}</code> · Attempt {item.runAttempt}
          </>
        }
        action={
          <>
            <Button
              variant="outline"
              onClick={() => {
                run.reload();
                jobs.reload();
              }}
            >
              <RefreshCw size={16} />
              Làm mới
            </Button>
            {url && (
              <Button asChild>
                <a href={url} target="_blank" rel="noopener noreferrer">
                  Xem trên GitHub
                  <ExternalLink size={15} />
                </a>
              </Button>
            )}
          </>
        }
      />
      <section className="panel run-summary">
        <StatusBadge status={item.conclusion ?? item.status} />
        <dl className="metadata-grid">
          <div>
            <dt>Nhánh</dt>
            <dd>{item.headBranch ?? "Chưa có"}</dd>
          </div>
          <div>
            <dt>Commit</dt>
            <dd>
              <code>{item.headSha ?? "Chưa có"}</code>
            </dd>
          </div>
          <div>
            <dt>Bắt đầu</dt>
            <dd>{dateTime(item.startedAt)}</dd>
          </div>
          <div>
            <dt>Kết thúc</dt>
            <dd>{dateTime(item.completedAt)}</dd>
          </div>
          <div>
            <dt>Thời lượng</dt>
            <dd>{duration(item.startedAt, item.completedAt)}</dd>
          </div>
        </dl>
      </section>
      <section className="panel">
        <div className="panel-heading">
          <h2>Jobs của attempt {item.runAttempt}</h2>
          <span className="muted">{jobs.data ? `${jobs.data.length} jobs` : ""}</span>
        </div>
        {jobs.loading && !jobs.data ? (
          <Loading label="Đang tải jobs…" />
        ) : jobs.error ? (
          <div className="panel-error">
            <ErrorNotice message={jobs.error} retry={jobs.reload} />
          </div>
        ) : !jobs.data?.length ? (
          <div className="quiet-empty">Chưa có metadata job cho attempt này.</div>
        ) : (
          <ol className="job-trace">
            {jobs.data.map((job) => (
              <li key={job.id}>
                <span className="job-node" />
                <div>
                  <strong>{job.name}</strong>
                  <p className="muted">
                    <code>#{job.githubJobId}</code> · {duration(job.startedAt, job.completedAt)}
                  </p>
                </div>
                <StatusBadge status={job.conclusion ?? job.status} />
                {githubUrl(job.htmlUrl) && (
                  <a
                    href={githubUrl(job.htmlUrl)}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="icon-button"
                    aria-label={`Xem job ${job.name} trên GitHub`}
                  >
                    <ExternalLink size={16} />
                  </a>
                )}
              </li>
            ))}
          </ol>
        )}
      </section>
    </>
  );
}
