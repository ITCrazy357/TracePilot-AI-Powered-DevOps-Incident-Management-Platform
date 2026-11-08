"use client";

import Link from "next/link";
import { CircleAlert, ArrowRight, GitBranch, RefreshCw, Users, Workflow } from "lucide-react";
import type { DataPage, Incident, Notification, RepositoryPage } from "@/lib/contracts";
import { useResource } from "@/lib/use-resource";
import { dateTime, shortId } from "@/lib/presentation";
import { CreateWorkspace } from "./create-workspace";
import { useWorkspace } from "./workspace-provider";
import { WorkspaceBoundary } from "./workspace-boundary";
import { refreshWorkspace } from "./realtime-provider";
import { Button } from "./ui/button";
import { EmptyState, ErrorNotice, Loading } from "./ui/states";
import { PageHeading, StatusBadge } from "./ui/operational";

export function Overview() {
  const { selectedId, workspace } = useWorkspace();
  return (
    <>
      <PageHeading
        title="Tổng quan"
        description={
          <span className="workspace-context">
            {workspace?.name ?? "Không gian cho đội ngũ và dự án của bạn."}
          </span>
        }
        action={
          <>
            <Button
              variant="outline"
              onClick={() => refreshWorkspace(selectedId)}
              disabled={!selectedId}
            >
              <RefreshCw size={16} />
              Làm mới
            </Button>
            <CreateWorkspace />
          </>
        }
      />
      <WorkspaceBoundary>
        <WorkspaceOverview key={selectedId} />
      </WorkspaceBoundary>
    </>
  );
}

function WorkspaceOverview() {
  const { selectedId, members } = useWorkspace();
  const base = `/api/backend/workspaces/${selectedId}`;
  const repositories = useResource<RepositoryPage>(`${base}/repositories?page=0&size=5`);
  const incidents = useResource<DataPage<Incident>>(`${base}/incidents?page=0&size=5`);
  const notifications = useResource<DataPage<Notification>>(`${base}/notifications?page=0&size=5`);
  const names = new Map(repositories.data?.items.map((repo) => [repo.id, repo.fullName]));

  return (
    <div className="overview-content">
      <div className="metrics-strip" aria-label="Thông tin workspace">
        <div>
          <GitBranch size={18} />
          <span>Kho mã GitHub</span>
          <strong>{repositories.data?.totalElements ?? "—"}</strong>
        </div>
        <div>
          <Users size={18} />
          <span>Thành viên</span>
          <strong>{members.length}</strong>
        </div>
        <div>
          <CircleAlert size={18} />
          <span>Sự cố đã ghi nhận</span>
          <strong>{incidents.data?.total ?? "—"}</strong>
        </div>
      </div>
      <div className="overview-grid">
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>Sự cố gần đây</h2>
              <p className="muted">Bắt đầu từ lần chạy thất bại, lần theo bằng chứng.</p>
            </div>
            <Button asChild variant="ghost">
              <Link href="/incidents">
                Tất cả
                <ArrowRight size={16} />
              </Link>
            </Button>
          </div>
          {incidents.loading && !incidents.data ? (
            <Loading label="Đang tải sự cố…" />
          ) : incidents.error ? (
            <div className="panel-error">
              <ErrorNotice message={incidents.error} retry={incidents.reload} />
            </div>
          ) : !incidents.data?.items.length ? (
            <EmptyState
              icon={Workflow}
              title="Chưa có sự cố nào"
              description="Sự cố được ghi nhận khi một workflow thất bại. Bạn có thể kiểm tra các lần chạy đã đồng bộ."
              action={
                <Button asChild variant="outline">
                  <Link href="/runs">
                    Xem lần chạy
                    <ArrowRight size={16} />
                  </Link>
                </Button>
              }
            />
          ) : (
            <div className="incident-feed">
              {incidents.data.items.map((incident) => (
                <Link
                  className="incident-feed-item"
                  href={`/incidents/${incident.id}`}
                  key={incident.id}
                >
                  <div className="incident-feed-meta">
                    <span className="incident-reference">
                      <span className="trace-point" />
                      {shortId(incident.id)}
                    </span>
                    <StatusBadge incident status={incident.status} />
                    <time dateTime={incident.detectedAt}>{dateTime(incident.detectedAt)}</time>
                  </div>
                  <h3>{incident.title}</h3>
                  <div className="incident-feed-bottom">
                    <span>
                      <GitBranch size={14} />
                      {names.get(incident.repositoryId) ??
                        `Kho mã ${shortId(incident.repositoryId)}`}
                    </span>
                    <span className="text-link">
                      Xem sự cố
                      <ArrowRight size={15} />
                    </span>
                  </div>
                </Link>
              ))}
            </div>
          )}
        </section>
        <div className="context-column">
          <section className="panel">
            <div className="panel-heading">
              <h2>Dấu vết gần đây</h2>
              <Link href="/notifications" className="text-link" aria-label="Xem tất cả thông báo">
                <ArrowRight size={17} />
              </Link>
            </div>
            {notifications.loading && !notifications.data ? (
              <Loading label="Đang tải dấu vết…" />
            ) : notifications.error ? (
              <div className="panel-error">
                <ErrorNotice message={notifications.error} retry={notifications.reload} />
              </div>
            ) : !notifications.data?.items.length ? (
              <div className="quiet-empty">Hoạt động về sự cố, log và AI sẽ xuất hiện tại đây.</div>
            ) : (
              <ol className="activity-trace">
                {notifications.data.items.map((item) => (
                  <li key={item.id}>
                    <span
                      className={`activity-node ${item.type.startsWith("AI_") ? "activity-ai" : ""}`}
                    />
                    <time dateTime={item.createdAt}>{dateTime(item.createdAt)}</time>
                    <p>{item.title}</p>
                    {item.incidentId && (
                      <Link href={`/incidents/${item.incidentId}`} className="text-link">
                        Mở sự cố
                        <ArrowRight size={13} />
                      </Link>
                    )}
                  </li>
                ))}
              </ol>
            )}
          </section>
          <section className="panel">
            <div className="panel-heading">
              <h2>Kho mã trong workspace</h2>
            </div>
            {repositories.loading && !repositories.data ? (
              <Loading label="Đang tải kho mã…" />
            ) : repositories.error ? (
              <div className="panel-error">
                <ErrorNotice message={repositories.error} retry={repositories.reload} />
              </div>
            ) : !repositories.data?.items.length ? (
              <div className="quiet-empty">
                Chưa có kho mã. OWNER có thể đồng bộ sau khi cấu hình GitHub App.
              </div>
            ) : (
              <div className="compact-repositories">
                {repositories.data.items.map((repo) => (
                  <Link href={`/runs?repositoryId=${repo.id}`} key={repo.id}>
                    <GitBranch size={15} />
                    <span>{repo.fullName}</span>
                    <ArrowRight size={14} />
                  </Link>
                ))}
              </div>
            )}
            <div className="panel-footer">
              <Link href="/repositories" className="text-link">
                Quản lý kho mã
                <ArrowRight size={14} />
              </Link>
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}
