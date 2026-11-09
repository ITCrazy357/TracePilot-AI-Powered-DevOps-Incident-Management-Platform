import {
  CheckCircle2,
  Circle,
  Clock3,
  Ban,
  AlertCircle,
  ChevronLeft,
  ChevronRight,
} from "lucide-react";
import type { ReactNode } from "react";
import { Button } from "./button";
import { incidentLabels } from "@/lib/presentation";
import type { IncidentStatus } from "@/lib/contracts";

const labels: Record<string, string> = {
  DRAFT: "Bản nháp",
  APPROVED: "Đã duyệt",
  ARCHIVED: "Đã lưu trữ",
  NONE: "Chưa tạo chỉ mục",
  READY: "Sẵn sàng",
  success: "Thành công",
  failure: "Thất bại",
  timed_out: "Hết thời gian",
  startup_failure: "Không khởi chạy được",
  cancelled: "Đã hủy",
  skipped: "Bỏ qua",
  completed: "Hoàn tất",
  queued: "Đang chờ",
  in_progress: "Đang chạy",
  PENDING: "Đang chờ",
  RUNNING: "Đang xử lý",
  SUCCEEDED: "Hoàn tất",
  FAILED: "Thất bại",
  RETRY: "Chờ thử lại",
  IGNORED: "Bỏ qua",
  PUBLISHED: "Đã gửi",
  PUBLISHING: "Đang gửi",
};

export function StatusBadge({ status, incident = false }: { status: string; incident?: boolean }) {
  const success = ["success", "SUCCEEDED", "RESOLVED", "READY"].includes(status);
  const failed = ["failure", "timed_out", "startup_failure", "FAILED", "INVESTIGATING"].includes(
    status,
  );
  const waiting = ["queued", "in_progress", "PENDING", "RUNNING", "RETRY", "PUBLISHING"].includes(
    status,
  );
  const Icon = success
    ? CheckCircle2
    : failed
      ? AlertCircle
      : waiting
        ? Clock3
        : status === "cancelled"
          ? Ban
          : Circle;
  const label = incident ? incidentLabels[status as IncidentStatus] : labels[status];
  return (
    <span
      className={`badge ${success ? "badge-green" : failed ? "badge-pink" : waiting ? "badge-amber" : "badge-blue"}`}
    >
      <Icon size={13} />
      {label ?? status}
    </span>
  );
}

export function PageHeading({
  title,
  description,
  action,
}: {
  title: string;
  description?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="page-heading">
      <div>
        <h1>{title}</h1>
        {description && <p className="muted">{description}</p>}
      </div>
      {action && <div className="heading-actions">{action}</div>}
    </div>
  );
}

export function Pagination({
  page,
  size,
  total,
  loading,
  onPage,
}: {
  page: number;
  size: number;
  total: number;
  loading?: boolean;
  onPage: (page: number) => void;
}) {
  return (
    <div className="panel-footer">
      <span className="muted">
        {total} mục · Trang {page + 1}/{Math.max(1, Math.ceil(total / size))}
      </span>
      <div className="pagination">
        <Button
          variant="outline"
          disabled={loading || page === 0}
          onClick={() => onPage(page - 1)}
          aria-label="Trang trước"
        >
          <ChevronLeft size={16} />
        </Button>
        <Button
          variant="outline"
          disabled={loading || (page + 1) * size >= total}
          onClick={() => onPage(page + 1)}
          aria-label="Trang sau"
        >
          <ChevronRight size={16} />
        </Button>
      </div>
    </div>
  );
}
