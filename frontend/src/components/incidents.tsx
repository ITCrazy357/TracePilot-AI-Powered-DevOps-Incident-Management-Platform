"use client";

import Link from "next/link";
import { useState } from "react";
import { ArrowRight, CircleAlert, RefreshCw } from "lucide-react";
import type { DataPage, Incident, RepositoryPage } from "@/lib/contracts";
import { useResource } from "@/lib/use-resource";
import { dateTime, incidentLabels, shortId } from "@/lib/presentation";
import { useAuth } from "./auth-provider";
import { useWorkspace } from "./workspace-provider";
import { WorkspaceBoundary } from "./workspace-boundary";
import { Button } from "./ui/button";
import { EmptyState, ErrorNotice, Loading } from "./ui/states";
import { PageHeading, Pagination, StatusBadge } from "./ui/operational";

export function Incidents() {
  const { selectedId } = useWorkspace();
  return (
    <>
      <PageHeading
        title="Sự cố"
        description="Theo dõi, phân công và điều tra các workflow thất bại."
      />
      <WorkspaceBoundary>
        <IncidentList key={selectedId} />
      </WorkspaceBoundary>
    </>
  );
}

function IncidentList() {
  const { user } = useAuth();
  const { selectedId, members } = useWorkspace();
  const [page, setPage] = useState(0);
  const empty = { q: "", status: "", repositoryId: "", assignedTo: "", severity: "" };
  const [criteria, setCriteria] = useState(empty);
  const [applied, setApplied] = useState(empty);
  const filters = new URLSearchParams(Object.entries(applied).filter(([, value]) => value));
  const repositories = useResource<RepositoryPage>(
    `/api/backend/workspaces/${selectedId}/repositories?page=0&size=100`,
  );
  const resource = useResource<DataPage<Incident>>(
    `/api/backend/workspaces/${selectedId}/incidents?page=${page}&size=20&${filters}`,
  );
  const items = resource.data?.items ?? [];
  const filtered = filters.size > 0;
  return (
    <section className="panel">
      <div className="panel-heading incident-list-toolbar">
        <div>
          <h2>Sự cố trong workspace</h2>
          <p className="muted">
            {resource.data
              ? `${resource.data.total} sự cố đã ghi nhận`
              : "Danh sách mới nhất trước"}
          </p>
        </div>
        <div className="toolbar-actions">
          <Button variant="outline" onClick={resource.reload} disabled={resource.loading}>
            <RefreshCw size={16} />
            Làm mới
          </Button>
        </div>
      </div>
      <form
        className="criteria-form"
        onSubmit={(event) => {
          event.preventDefault();
          setPage(0);
          setApplied({ ...criteria, q: criteria.q.trim() });
        }}
      >
        <label>
          Từ khóa tiêu đề
          <input
            maxLength={200}
            value={criteria.q}
            onChange={(event) => setCriteria({ ...criteria, q: event.target.value })}
          />
        </label>
        <label>
          Trạng thái sự cố
          <select
            value={criteria.status}
            onChange={(event) => setCriteria({ ...criteria, status: event.target.value })}
          >
            <option value="">Tất cả trạng thái</option>
            {Object.entries(incidentLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label>
          Kho mã
          <select
            value={criteria.repositoryId}
            onChange={(event) => setCriteria({ ...criteria, repositoryId: event.target.value })}
          >
            <option value="">Tất cả kho mã</option>
            {repositories.data?.items.map((repo) => (
              <option key={repo.id} value={repo.id}>
                {repo.fullName}
              </option>
            ))}
          </select>
        </label>
        <label>
          Mức độ
          <select
            value={criteria.severity}
            onChange={(event) => setCriteria({ ...criteria, severity: event.target.value })}
          >
            <option value="">Tất cả mức độ</option>
            {["LOW", "MEDIUM", "HIGH", "CRITICAL"].map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
        <label>
          Người phụ trách
          <select
            value={criteria.assignedTo}
            onChange={(event) => setCriteria({ ...criteria, assignedTo: event.target.value })}
          >
            <option value="">Tất cả người phụ trách</option>
            {members.map((member) => (
              <option key={member.userId} value={member.userId}>
                {member.userId === user?.id ? "Bạn" : shortId(member.userId)}
              </option>
            ))}
          </select>
        </label>
        <div className="toolbar-actions">
          <Button type="submit" disabled={resource.loading}>
            Tìm theo tiêu chí
          </Button>
          <Button
            variant="ghost"
            onClick={() => {
              setCriteria(empty);
              setApplied(empty);
              setPage(0);
            }}
          >
            Xóa bộ lọc
          </Button>
        </div>
        <p className="field-hint">
          Lọc trên toàn workspace, không gọi AI.{" "}
          <Link className="text-link" href="/knowledge/search">
            Tra lỗi bằng vector
          </Link>
        </p>
        {repositories.error && (
          <ErrorNotice
            message="Không tải được danh sách kho mã. Bạn vẫn có thể dùng các tiêu chí khác."
            retry={repositories.reload}
          />
        )}
      </form>
      {resource.loading && !resource.data ? (
        <Loading label="Đang tải sự cố…" />
      ) : resource.error ? (
        <div className="panel-error">
          <ErrorNotice message={resource.error} retry={resource.reload} />
        </div>
      ) : !items.length ? (
        <EmptyState
          icon={CircleAlert}
          title={filtered ? "Không có sự cố khớp tiêu chí" : "Chưa có sự cố nào"}
          description={
            filtered
              ? "Đổi tiêu chí hoặc xóa bộ lọc để xem tất cả sự cố."
              : "Backend tự ghi nhận sự cố khi một workflow hoàn tất với kết quả thất bại."
          }
        />
      ) : (
        <div className="incident-list">
          <div className="incident-list-head">
            <span>SỰ CỐ</span>
            <span>TRẠNG THÁI</span>
            <span>PHÂN CÔNG</span>
            <span>PHÁT HIỆN</span>
          </div>
          {items.map((item) => (
            <Link href={`/incidents/${item.id}`} key={item.id} className="incident-list-row">
              <div>
                <span className="incident-reference">
                  <span className="trace-point" />
                  {shortId(item.id)}
                </span>
                <strong>{item.title}</strong>
                <span className="muted">
                  Mức độ: {item.severity} · Kho mã <code>{shortId(item.repositoryId)}</code>
                </span>
              </div>
              <StatusBadge incident status={item.status} />
              <span className="assignee-cell">
                {item.assignedTo === user?.id
                  ? "Bạn"
                  : item.assignedTo
                    ? shortId(item.assignedTo)
                    : "Chưa phân công"}
              </span>
              <time dateTime={item.detectedAt}>{dateTime(item.detectedAt)}</time>
              <ArrowRight className="row-arrow" size={16} />
            </Link>
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
  );
}
