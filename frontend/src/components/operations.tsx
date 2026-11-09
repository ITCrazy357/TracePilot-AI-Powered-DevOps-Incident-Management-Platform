"use client";

import { useState } from "react";
import { RefreshCw, Activity } from "lucide-react";
import type { AuditEntry, Backlog, DataPage, OutboxEvent } from "@/lib/contracts";
import { ApiError, api, errorMessage } from "@/lib/api";
import { dateTime, shortId } from "@/lib/presentation";
import { useResource } from "@/lib/use-resource";
import { useWorkspace } from "./workspace-provider";
import { WorkspaceBoundary } from "./workspace-boundary";
import { Button } from "./ui/button";
import { Dialog } from "./ui/dialog";
import { PageHeading, Pagination, StatusBadge } from "./ui/operational";
import { EmptyState, ErrorNotice, Loading, SuccessNotice } from "./ui/states";

const eventLabels: Record<string, string> = {
  WORKFLOW_SYNC: "Đồng bộ workflow",
  INCIDENT_LOG_CAPTURE: "Thu thập log",
  AI_ANALYSIS: "Phân tích AI",
};
const actionLabels: Record<string, string> = {
  WORKSPACE_CREATED: "Tạo workspace",
  MEMBER_ADDED: "Thêm thành viên",
  MEMBER_ROLE_CHANGED: "Đổi quyền thành viên",
  MEMBER_REMOVED: "Xóa thành viên",
  INCIDENT_STATUS_CHANGED: "Đổi trạng thái sự cố",
  INCIDENT_ASSIGNED: "Phân công sự cố",
  KNOWLEDGE_CREATED: "Tạo tri thức",
  KNOWLEDGE_EDITED: "Sửa tri thức",
  KNOWLEDGE_APPROVED: "Duyệt tri thức",
  KNOWLEDGE_ARCHIVED: "Lưu trữ tri thức",
  ANALYSIS_REQUESTED: "Yêu cầu phân tích",
  WORKFLOW_SYNC_REQUESTED: "Yêu cầu đồng bộ workflow",
  FEEDBACK_SAVED: "Lưu đánh giá",
  OUTBOX_REPLAYED: "Thử lại tác vụ",
};

export function Operations() {
  const { selectedId, role } = useWorkspace();
  return (
    <>
      <PageHeading
        title="Vận hành & audit"
        description="Theo dõi công việc tồn đọng và đối soát các thay đổi quan trọng."
      />
      <WorkspaceBoundary>
        {role === "OWNER" || role === "ADMIN" ? (
          <ManagerOperations key={selectedId} />
        ) : (
          <p className="permission-note">Chỉ OWNER hoặc ADMIN được xem vận hành và audit.</p>
        )}
      </WorkspaceBoundary>
    </>
  );
}

function ManagerOperations() {
  const { selectedId, role } = useWorkspace();
  const base = `/api/backend/workspaces/${selectedId}`;
  const [tab, setTab] = useState<"tasks" | "audit">("tasks");
  const [page, setPage] = useState(0);
  const [auditPage, setAuditPage] = useState(0);
  const backlog = useResource<Backlog[]>(`${base}/operations`);
  const events = useResource<DataPage<OutboxEvent>>(
    `${base}/operations/outbox?page=${page}&size=20`,
  );
  const audit = useResource<DataPage<AuditEntry>>(
    tab === "audit" ? `${base}/audit-logs?page=${auditPage}&size=20` : null,
  );
  const [selected, setSelected] = useState<OutboxEvent | null>(null);
  const [busy, setBusy] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  function reload() {
    backlog.reload();
    events.reload();
    audit.reload();
  }

  async function retry() {
    if (!selected || busy || conflict) return;
    setBusy(true);
    setError("");
    try {
      await api<OutboxEvent>(`${base}/operations/outbox/${selected.id}/retry`, {
        method: "POST",
        body: JSON.stringify({ replayCount: selected.replayCount }),
      });
      setSelected(null);
      setSuccess("Đã xếp hàng thử lại. Kết quả sẽ cập nhật sau khi worker xử lý.");
      reload();
    } catch (failure) {
      setError(errorMessage(failure));
      if (failure instanceof ApiError && failure.status === 409) setConflict(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="quality-content">
      <div className="toolbar-actions">
        <Button
          variant={tab === "tasks" ? "default" : "outline"}
          onClick={() => setTab("tasks")}
          aria-pressed={tab === "tasks"}
        >
          Tác vụ
        </Button>
        <Button
          variant={tab === "audit" ? "default" : "outline"}
          onClick={() => setTab("audit")}
          aria-pressed={tab === "audit"}
        >
          Audit
        </Button>
        <Button variant="outline" onClick={reload}>
          <RefreshCw size={16} />
          Làm mới
        </Button>
      </div>
      {success && <SuccessNotice>{success}</SuccessNotice>}
      {tab === "tasks" ? (
        <>
          <section className="panel">
            <div className="panel-heading">
              <h2>Công việc chưa hoàn tất</h2>
            </div>
            <div className="quality-panel-body">
              {backlog.error ? (
                <ErrorNotice message={backlog.error} retry={backlog.reload} />
              ) : !backlog.data ? (
                <Loading />
              ) : !backlog.data.length ? (
                <p className="muted">Chưa có công việc tồn đọng.</p>
              ) : (
                <ul className="operations-backlog">
                  {backlog.data.map((item) => (
                    <li key={`${item.eventType}:${item.status}`}>
                      <span>{eventLabels[item.eventType] ?? item.eventType}</span>
                      <StatusBadge status={item.status} />
                      <strong>{item.count}</strong>
                      <small>Cũ nhất: {item.oldestAgeSeconds ?? "—"} giây</small>
                    </li>
                  ))}
                </ul>
              )}
              <p className="field-hint">
                FAILED cần xem lỗi và quyết định xử lý. Số tồn đọng lấy từ database, có thể gồm công
                việc đang chờ thời gian thử lại.
              </p>
            </div>
          </section>
          <section className="panel">
            <div className="panel-heading">
              <h2>Lịch sử tác vụ</h2>
            </div>
            {events.error ? (
              <div className="quality-panel-body">
                <ErrorNotice message={events.error} retry={events.reload} />
              </div>
            ) : !events.data ? (
              <Loading />
            ) : !events.data.items.length ? (
              <EmptyState
                icon={Activity}
                title="Chưa có tác vụ"
                description="Công việc đồng bộ workflow, lấy log và phân tích AI sẽ xuất hiện tại đây."
              />
            ) : (
              <ul className="operations-list">
                {events.data.items.map((event) => (
                  <li key={event.id}>
                    <div className="operations-entry-heading">
                      <div>
                        <h3>{eventLabels[event.eventType] ?? event.eventType}</h3>
                        <code>{shortId(event.id)}</code>
                      </div>
                      <StatusBadge status={event.processingStatus} />
                    </div>
                    <p className="muted">
                      {dateTime(event.createdAt)} · {event.attempts} lần trong đợt ·{" "}
                      {event.totalAttempts} lần tổng · {event.replayCount} lần yêu cầu thử lại
                    </p>
                    {event.errorCode && <code className="error-code">{event.errorCode}</code>}
                    {event.processingStatus === "FAILED" &&
                      (event.eventType === "AI_ANALYSIS" ? (
                        <p className="field-hint">
                          Mở sự cố và yêu cầu phân tích mới để giữ lịch sử báo cáo và chi phí.
                        </p>
                      ) : event.eventType === "WORKFLOW_SYNC" && role !== "OWNER" ? (
                        <p className="field-hint">OWNER có thể yêu cầu thử lại đồng bộ workflow.</p>
                      ) : (
                        <Button
                          variant="outline"
                          disabled={busy || event.publicationStatus !== "PUBLISHED"}
                          onClick={() => {
                            setSelected(event);
                            setError("");
                            setConflict(false);
                          }}
                        >
                          Thử lại tác vụ {shortId(event.id)}
                        </Button>
                      ))}
                  </li>
                ))}
              </ul>
            )}
            {events.data && (
              <Pagination
                page={page}
                size={20}
                total={events.data.total}
                loading={events.loading}
                onPage={setPage}
              />
            )}
          </section>
        </>
      ) : (
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>Lịch sử thay đổi</h2>
              <p className="muted">
                ID người thực hiện, đối tượng và dữ kiện thay đổi; không chứa nội dung log hay nhận
                xét.
              </p>
            </div>
          </div>
          {audit.error ? (
            <div className="quality-panel-body">
              <ErrorNotice message={audit.error} retry={audit.reload} />
            </div>
          ) : !audit.data ? (
            <Loading />
          ) : !audit.data.items.length ? (
            <div className="quiet-empty">Chưa có audit cho workspace này.</div>
          ) : (
            <ol className="operations-list">
              {audit.data.items.map((entry) => (
                <li key={entry.id}>
                  <h3>{actionLabels[entry.action] ?? entry.action}</h3>
                  <p className="muted">
                    {dateTime(entry.createdAt)} ·{" "}
                    {entry.actorUserId
                      ? `Người thực hiện: ${shortId(entry.actorUserId)}`
                      : "Hệ thống"}
                  </p>
                  <p className="field-hint">
                    {entry.entityType} · {entry.entityId ?? "—"}
                  </p>
                  {Object.keys(entry.metadata).length > 0 && (
                    <code className="operations-metadata">{JSON.stringify(entry.metadata)}</code>
                  )}
                  {entry.requestId && (
                    <p className="field-hint">
                      Mã yêu cầu: <code>{entry.requestId}</code>
                    </p>
                  )}
                </li>
              ))}
            </ol>
          )}
          {audit.data && (
            <Pagination
              page={auditPage}
              size={20}
              total={audit.data.total}
              loading={audit.loading}
              onPage={setAuditPage}
            />
          )}
        </section>
      )}
      <Dialog
        title="Thử lại tác vụ"
        description="Tác vụ sẽ chạy lại tối đa số lần cho phép của một đợt. Kiểm tra nguồn GitHub và nguyên nhân lỗi trước khi gửi."
        open={Boolean(selected)}
        onOpenChange={(open) => {
          if (!busy && !open) setSelected(null);
        }}
      >
        {error && <ErrorNotice message={error} />}
        <p>
          Mã tác vụ: <code>{selected?.id}</code>
        </p>
        <div className="dialog-actions">
          <Button variant="outline" disabled={busy} onClick={() => setSelected(null)}>
            Hủy
          </Button>
          {conflict && (
            <Button
              variant="outline"
              onClick={() => {
                setSelected(null);
                reload();
              }}
            >
              Tải lại tác vụ
            </Button>
          )}
          <Button disabled={busy || conflict} onClick={retry}>
            {busy ? "Đang gửi…" : "Xác nhận thử lại"}
          </Button>
        </div>
      </Dialog>
    </div>
  );
}
