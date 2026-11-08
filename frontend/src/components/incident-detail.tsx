"use client";

import Link from "next/link";
import { Tabs } from "radix-ui";
import { useState, type FormEvent } from "react";
import {
  ArrowLeft,
  ArrowRight,
  FileText,
  RefreshCw,
  ScanText,
  Sparkles,
  UserRound,
} from "lucide-react";
import { ApiError, api, errorMessage } from "@/lib/api";
import type { Incident, IncidentStatus } from "@/lib/contracts";
import { useResource } from "@/lib/use-resource";
import { dateTime, incidentLabels, shortId, statusTransitions } from "@/lib/presentation";
import { useAuth } from "./auth-provider";
import { useWorkspace } from "./workspace-provider";
import { WorkspaceBoundary } from "./workspace-boundary";
import { refreshWorkspace } from "./realtime-provider";
import { AnalysisPanel } from "./analysis-panel";
import { LogViewer } from "./log-viewer";
import { Button } from "./ui/button";
import { ErrorNotice, Loading, SuccessNotice } from "./ui/states";
import { PageHeading, StatusBadge } from "./ui/operational";

export function IncidentDetail({ incidentId }: { incidentId: string }) {
  const { selectedId } = useWorkspace();
  return (
    <WorkspaceBoundary>
      <IncidentContent key={`${selectedId}:${incidentId}`} incidentId={incidentId} />
    </WorkspaceBoundary>
  );
}

function IncidentContent({ incidentId }: { incidentId: string }) {
  const { selectedId, role } = useWorkspace();
  const { user } = useAuth();
  const base = `/api/backend/workspaces/${selectedId}/incidents/${incidentId}`;
  const resource = useResource<Incident>(base);
  const [tab, setTab] = useState("overview");
  const [evidence, setEvidence] = useState("");
  const [notice, setNotice] = useState("");
  if (resource.loading && !resource.data) return <Loading label="Đang tải sự cố…" />;
  if (resource.error) return <ErrorNotice message={resource.error} retry={resource.reload} />;
  if (!resource.data) return null;
  const incident = resource.data;
  const manager = role === "OWNER" || role === "ADMIN";
  const canAct = manager || (role === "MEMBER" && incident.assignedTo === user?.id);
  const assignee =
    incident.assignedTo === user?.id
      ? `${user.displayName} (Bạn)`
      : incident.assignedTo
        ? `Thành viên ${shortId(incident.assignedTo)}`
        : "Chưa phân công";

  return (
    <>
      <Link href="/incidents" className="back-link">
        <ArrowLeft size={15} />
        Sự cố
      </Link>
      <div className="incident-heading-tags">
        <span className="incident-reference">{shortId(incident.id)}</span>
        <StatusBadge status={incident.status} incident />
        <span className="severity-label">Mức độ: {incident.severity}</span>
      </div>
      <PageHeading
        title={incident.title}
        description={
          <>
            Phát hiện {dateTime(incident.detectedAt)} ·{" "}
            <span className="mono">{shortId(incident.repositoryId)}</span>
          </>
        }
        action={
          <Button
            variant="outline"
            onClick={() => refreshWorkspace(selectedId)}
            disabled={resource.loading}
          >
            <RefreshCw size={16} />
            Làm mới
          </Button>
        }
      />
      {notice && <SuccessNotice>{notice}</SuccessNotice>}
      <Tabs.Root value={tab} onValueChange={setTab} className="incident-tabs">
        <Tabs.List aria-label="Nội dung sự cố" className="tab-list">
          <Tabs.Trigger value="overview">
            <FileText size={16} />
            Tổng quan
          </Tabs.Trigger>
          <Tabs.Trigger value="logs">
            <ScanText size={16} />
            Log đã che
          </Tabs.Trigger>
          <Tabs.Trigger value="analysis">
            <Sparkles size={16} />
            Phân tích AI
          </Tabs.Trigger>
        </Tabs.List>
        <Tabs.Content value="overview" className="tab-content">
          <div className="incident-detail-grid">
            <section className="panel">
              <div className="panel-heading">
                <h2>Thông tin điều tra</h2>
              </div>
              <dl className="incident-metadata">
                <div>
                  <dt>Trạng thái</dt>
                  <dd>
                    <StatusBadge status={incident.status} incident />
                  </dd>
                </div>
                <div>
                  <dt>Người phụ trách</dt>
                  <dd>
                    <UserRound size={15} />
                    {assignee}
                  </dd>
                </div>
                <div>
                  <dt>Phát hiện</dt>
                  <dd>{dateTime(incident.detectedAt)}</dd>
                </div>
                <div>
                  <dt>Cập nhật gần nhất</dt>
                  <dd>{dateTime(incident.updatedAt)}</dd>
                </div>
                <div>
                  <dt>Đã xử lý lúc</dt>
                  <dd>{dateTime(incident.resolvedAt)}</dd>
                </div>
                <div>
                  <dt>UUID sự cố</dt>
                  <dd>
                    <code>{incident.id}</code>
                  </dd>
                </div>
              </dl>
              <div className="panel-footer">
                <Link
                  href={`/runs/${incident.repositoryId}/${incident.workflowRunId}`}
                  className="text-link"
                >
                  Xem lần chạy và jobs
                  <ArrowRight size={15} />
                </Link>
              </div>
            </section>
            <div className="context-column">
              <IncidentActions
                key={incident.version}
                incident={incident}
                canChangeStatus={canAct}
                canAssign={manager}
                refreshing={resource.loading}
                onSaved={() => setNotice("Đã cập nhật sự cố.")}
              />
              <section className="investigation-guide">
                <h2>Đi từ dấu vết đến nguyên nhân</h2>
                <ol>
                  <li>Kiểm tra lần chạy và job thất bại.</li>
                  <li>Đọc log đã che để xác định lỗi.</li>
                  <li>Đối chiếu đề xuất AI với bằng chứng trước khi áp dụng.</li>
                </ol>
                <Button variant="outline" onClick={() => setTab("logs")}>
                  Đọc log đã che
                  <ArrowRight size={15} />
                </Button>
              </section>
            </div>
          </div>
        </Tabs.Content>
        <Tabs.Content value="logs" className="tab-content">
          <LogViewer incidentId={incidentId} evidence={evidence} />
        </Tabs.Content>
        <Tabs.Content value="analysis" className="tab-content">
          <AnalysisPanel
            incident={incident}
            canAnalyze={canAct}
            onEvidence={(quote) => {
              setEvidence(quote);
              setTab("logs");
            }}
          />
        </Tabs.Content>
      </Tabs.Root>
      <p className="permission-note">
        {manager
          ? "OWNER/ADMIN được phân công và cập nhật trạng thái."
          : canAct
            ? "Bạn được cập nhật trạng thái và yêu cầu phân tích vì đang phụ trách sự cố này."
            : "Bạn có quyền xem. OWNER, ADMIN hoặc người đang phụ trách có thể cập nhật sự cố."}
      </p>
    </>
  );
}

function IncidentActions({
  incident,
  canChangeStatus,
  canAssign,
  refreshing,
  onSaved,
}: {
  incident: Incident;
  canChangeStatus: boolean;
  canAssign: boolean;
  refreshing: boolean;
  onSaved: () => void;
}) {
  const { selectedId, members } = useWorkspace();
  const { user } = useAuth();
  const [status, setStatus] = useState<IncidentStatus>(incident.status);
  const [assignedTo, setAssignedTo] = useState(incident.assignedTo ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [conflict, setConflict] = useState(false);
  async function save(event: FormEvent<HTMLFormElement>, action: "status" | "assignee") {
    event.preventDefault();
    setBusy(true);
    setError("");
    const body =
      action === "status"
        ? { status, version: incident.version }
        : { assignedTo: assignedTo || null, version: incident.version };
    try {
      await api<Incident>(
        `/api/backend/workspaces/${selectedId}/incidents/${incident.id}/${action}`,
        { method: "PATCH", body: JSON.stringify(body) },
      );
      onSaved();
      refreshWorkspace(selectedId);
    } catch (failure) {
      setError(errorMessage(failure));
      setConflict(failure instanceof ApiError && failure.status === 409);
    } finally {
      setBusy(false);
    }
  }
  if (!canChangeStatus && !canAssign) return null;
  return (
    <section className="panel">
      <div className="panel-heading">
        <h2>Cập nhật sự cố</h2>
      </div>
      <div className="action-forms">
        {error && (
          <ErrorNotice
            message={error}
            retry={conflict ? () => refreshWorkspace(selectedId) : undefined}
          />
        )}
        {canChangeStatus && (
          <form className="form-stack" onSubmit={(event) => save(event, "status")}>
            <label>
              Trạng thái
              <select
                value={status}
                onChange={(event) => setStatus(event.target.value as IncidentStatus)}
                disabled={busy || refreshing || conflict}
              >
                <option value={incident.status}>{incidentLabels[incident.status]}</option>
                {statusTransitions[incident.status].map((value) => (
                  <option key={value} value={value}>
                    {incidentLabels[value]}
                  </option>
                ))}
              </select>
            </label>
            <Button
              type="submit"
              disabled={busy || refreshing || conflict || status === incident.status}
            >
              {busy ? "Đang lưu…" : "Lưu trạng thái"}
            </Button>
          </form>
        )}
        {canAssign && (
          <form className="form-stack" onSubmit={(event) => save(event, "assignee")}>
            <label>
              Người phụ trách
              <select
                value={assignedTo}
                onChange={(event) => setAssignedTo(event.target.value)}
                disabled={busy || refreshing || conflict}
              >
                <option value="">Chưa phân công</option>
                {incident.assignedTo &&
                  !members.some((member) => member.userId === incident.assignedTo) && (
                    <option value={incident.assignedTo}>
                      Thành viên cũ ({shortId(incident.assignedTo)})
                    </option>
                  )}
                {members.map((member) => (
                  <option value={member.userId} key={member.userId}>
                    {member.userId === user?.id
                      ? `${user.displayName} (Bạn)`
                      : `Thành viên ${shortId(member.userId)}`}
                  </option>
                ))}
              </select>
            </label>
            <Button
              variant="outline"
              type="submit"
              disabled={
                busy || refreshing || conflict || assignedTo === (incident.assignedTo ?? "")
              }
            >
              Lưu phân công
            </Button>
          </form>
        )}
      </div>
    </section>
  );
}
