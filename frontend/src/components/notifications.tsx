"use client";

import Link from "next/link";
import { useState } from "react";
import { Bell, Check, ArrowRight, RefreshCw } from "lucide-react";
import type { DataPage, Notification } from "@/lib/contracts";
import { api, errorMessage } from "@/lib/api";
import { useResource } from "@/lib/use-resource";
import { dateTime } from "@/lib/presentation";
import { useWorkspace } from "./workspace-provider";
import { WorkspaceBoundary } from "./workspace-boundary";
import { useRealtime } from "./realtime-provider";
import { Button } from "./ui/button";
import { EmptyState, ErrorNotice, Loading } from "./ui/states";
import { PageHeading, Pagination } from "./ui/operational";

export function Notifications() {
  const { selectedId } = useWorkspace();
  return (
    <>
      <PageHeading
        title="Thông báo"
        description="Dấu vết về sự cố, log và phân tích trong workspace của bạn."
      />
      <WorkspaceBoundary>
        <NotificationList key={selectedId} />
      </WorkspaceBoundary>
    </>
  );
}

function NotificationList() {
  const { selectedId } = useWorkspace();
  const connected = useRealtime();
  const [page, setPage] = useState(0);
  const base = `/api/backend/workspaces/${selectedId}/notifications`;
  const resource = useResource<DataPage<Notification>>(`${base}?page=${page}&size=20`);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  async function read(id: string) {
    setBusy(id);
    setError("");
    try {
      await api<void>(`${base}/${id}/read`, { method: "PATCH" });
      resource.reload();
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy("");
    }
  }
  return (
    <>
      {error && <ErrorNotice message={error} />}
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>Hộp thông báo</h2>
            <p className="muted">
              {connected
                ? "Đang nhận cập nhật trực tiếp"
                : "Bạn có thể làm mới để lấy thông báo mới"}
            </p>
          </div>
          <Button variant="outline" onClick={resource.reload} disabled={resource.loading}>
            <RefreshCw size={16} />
            Làm mới
          </Button>
        </div>
        {resource.loading && !resource.data ? (
          <Loading />
        ) : resource.error ? (
          <div className="panel-error">
            <ErrorNotice message={resource.error} retry={resource.reload} />
          </div>
        ) : !resource.data?.items.length ? (
          <EmptyState
            icon={Bell}
            title="Chưa có thông báo"
            description="Thông báo sẽ được lưu tại đây khi có hoạt động liên quan trong workspace."
          />
        ) : (
          <div className="notification-list">
            {resource.data.items.map((item) => (
              <div key={item.id} className={`notification-row ${item.readAt ? "" : "unread"}`}>
                <span
                  className={`activity-node ${item.type.startsWith("AI_") ? "activity-ai" : ""}`}
                />
                <div>
                  <strong>{item.title}</strong>
                  <time dateTime={item.createdAt}>{dateTime(item.createdAt)}</time>
                  {item.incidentId && (
                    <Link href={`/incidents/${item.incidentId}`} className="text-link">
                      Xem sự cố
                      <ArrowRight size={14} />
                    </Link>
                  )}
                </div>
                {item.readAt ? (
                  <span className="read-label">
                    <Check size={14} />
                    Đã đọc
                  </span>
                ) : (
                  <Button
                    variant="ghost"
                    disabled={Boolean(busy)}
                    onClick={() => read(item.id)}
                    aria-label={`Đánh dấu đã đọc: ${item.title}`}
                  >
                    <Check size={15} />
                    {busy === item.id ? "Đang lưu…" : "Đã đọc"}
                  </Button>
                )}
              </div>
            ))}
          </div>
        )}
        {resource.data && (
          <Pagination
            page={page}
            size={20}
            total={resource.data.total}
            loading={resource.loading}
            onPage={setPage}
          />
        )}
      </section>
    </>
  );
}
