"use client";

import { Layers3 } from "lucide-react";
import type { ReactNode } from "react";
import { CreateWorkspace } from "./create-workspace";
import { useWorkspace } from "./workspace-provider";
import { EmptyState, ErrorNotice, Loading } from "./ui/states";

export function WorkspaceBoundary({ children }: { children: ReactNode }) {
  const {
    loading,
    error,
    selectedId,
    workspace,
    detailLoading,
    detailError,
    reload,
    reloadMembers,
  } = useWorkspace();
  if (loading) return <Loading />;
  if (error) return <ErrorNotice message={error} retry={reload} />;
  if (!selectedId)
    return (
      <div className="panel">
        <EmptyState
          icon={Layers3}
          title="Không gian cho dự án tiếp theo"
          description="Tạo workspace đầu tiên để kết nối kho mã và làm việc cùng đội ngũ của bạn."
          action={<CreateWorkspace />}
        />
      </div>
    );
  if (detailLoading && !workspace) return <Loading label="Đang tải workspace…" />;
  if (detailError) return <ErrorNotice message={detailError} retry={reloadMembers} />;
  return children;
}
