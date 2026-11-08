"use client";

import Link from "next/link";
import { useState } from "react";
import { ArrowRight, CircleAlert, RefreshCw } from "lucide-react";
import type { DataPage, Incident, IncidentStatus } from "@/lib/contracts";
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
  const { selectedId } = useWorkspace();
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState("");
  const resource = useResource<DataPage<Incident>>(
    `/api/backend/workspaces/${selectedId}/incidents?page=${page}&size=20`,
  );
  const items = resource.data?.items.filter((item) => !status || item.status === status) ?? [];
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
          <label className="inline-filter">
            <span className="sr-only">Trạng thái trên trang</span>
            <select
              aria-label="Trạng thái trên trang"
              value={status}
              onChange={(event) => setStatus(event.target.value)}
            >
              <option value="">Tất cả trạng thái</option>
              {Object.entries(incidentLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <Button variant="outline" onClick={resource.reload} disabled={resource.loading}>
            <RefreshCw size={16} />
            Làm mới
          </Button>
        </div>
      </div>
      {status && (
        <p className="filter-scope">Bộ lọc áp dụng cho tối đa 20 sự cố trên trang hiện tại.</p>
      )}
      {resource.loading && !resource.data ? (
        <Loading label="Đang tải sự cố…" />
      ) : resource.error ? (
        <div className="panel-error">
          <ErrorNotice message={resource.error} retry={resource.reload} />
        </div>
      ) : !items.length ? (
        <EmptyState
          icon={CircleAlert}
          title={status ? "Không có sự cố khớp trên trang này" : "Chưa có sự cố nào"}
          description={
            status
              ? "Đổi trạng thái lọc hoặc chuyển sang trang khác."
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
