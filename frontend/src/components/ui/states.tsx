import { AlertCircle, CheckCircle2, LoaderCircle, type LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import { Button } from "./button";

export function Loading({ label = "Đang tải dữ liệu…" }: { label?: string }) {
  return (
    <div className="loading-state" role="status">
      <LoaderCircle className="spin" size={21} />
      <span>{label}</span>
    </div>
  );
}

export function ErrorNotice({ message, retry }: { message: string; retry?: () => void }) {
  return (
    <div className="notice notice-error" role="alert">
      <AlertCircle size={19} />
      <div className="notice-body">{message}</div>
      {retry && (
        <Button variant="outline" onClick={retry}>
          Thử lại
        </Button>
      )}
    </div>
  );
}

export function SuccessNotice({ children }: { children: ReactNode }) {
  return (
    <div className="notice notice-success" role="status">
      <CheckCircle2 size={18} />
      <span>{children}</span>
    </div>
  );
}

export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
}: {
  icon: LucideIcon;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="empty-state">
      <div className="empty-icon">
        <Icon size={26} strokeWidth={1.5} />
      </div>
      <h2>{title}</h2>
      <p className="muted">{description}</p>
      {action}
    </div>
  );
}
