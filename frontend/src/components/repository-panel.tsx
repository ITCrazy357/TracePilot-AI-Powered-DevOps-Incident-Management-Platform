"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import {
  ArrowRight,
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  GitBranch,
  GitFork,
  RefreshCw,
  ShieldCheck,
} from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import type { GitHubConnection, RepositoryPage } from "@/lib/contracts";
import { useWorkspace } from "./workspace-provider";
import { Button } from "./ui/button";
import { EmptyState, ErrorNotice, Loading, SuccessNotice } from "./ui/states";

export function RepositoryPanel({
  preview = false,
  onTotal,
}: {
  preview?: boolean;
  onTotal?: (total: number | null) => void;
}) {
  const { selectedId, role } = useWorkspace();
  return (
    <RepositoryList
      key={selectedId}
      workspaceId={selectedId}
      role={role}
      preview={preview}
      onTotal={onTotal}
    />
  );
}

function RepositoryList({
  workspaceId,
  role,
  preview,
  onTotal,
}: {
  workspaceId: string;
  role: string | undefined;
  preview: boolean;
  onTotal?: (total: number | null) => void;
}) {
  const [data, setData] = useState<RepositoryPage | null>(null);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [version, setVersion] = useState(0);
  const [syncing, setSyncing] = useState(false);
  const [syncError, setSyncError] = useState("");
  const [connection, setConnection] = useState<GitHubConnection | null>(null);
  const size = preview ? 5 : 20;

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    onTotal?.(null);
    api<RepositoryPage>(
      `/api/backend/workspaces/${workspaceId}/repositories?page=${page}&size=${size}`,
      { signal: controller.signal },
    )
      .then((result) => {
        if (!controller.signal.aborted) {
          setData(result);
          onTotal?.(result.totalElements);
        }
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError(errorMessage(failure));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [workspaceId, page, size, version, onTotal]);

  async function sync() {
    setSyncing(true);
    setSyncError("");
    setConnection(null);
    try {
      const result = await api<GitHubConnection>(
        `/api/backend/workspaces/${workspaceId}/github/sync`,
        { method: "POST" },
      );
      setConnection(result);
      setPage(0);
      setVersion((value) => value + 1);
    } catch (failure) {
      setSyncError(errorMessage(failure));
    } finally {
      setSyncing(false);
    }
  }

  return (
    <section className="panel" aria-label="Kho mã GitHub">
      <div className="panel-heading">
        <div>
          <h2>
            <GitFork size={18} />
            Kho mã GitHub
          </h2>
          <p className="muted">Các repository được cấp quyền cho workspace này.</p>
        </div>
        {preview ? (
          <Button asChild variant="ghost">
            <Link href="/repositories">
              Xem tất cả
              <ArrowRight size={16} />
            </Link>
          </Button>
        ) : (
          role === "OWNER" && (
            <Button variant="outline" disabled={syncing} onClick={sync}>
              <RefreshCw size={16} className={syncing ? "spin" : ""} />
              {syncing ? "Đang đồng bộ…" : "Đồng bộ GitHub"}
            </Button>
          )
        )}
      </div>
      <div className="panel-notices">
        {syncError && <ErrorNotice message={syncError} />}
        {connection && (
          <SuccessNotice>
            Đã đồng bộ {connection.repositoryCount} kho mã từ {connection.accountLogin}.
            Installation: {connection.status}.
          </SuccessNotice>
        )}
        {syncing && (
          <p className="field-hint" role="status">
            Đang đối soát danh sách với GitHub. Thao tác có thể mất vài phút.
          </p>
        )}
      </div>
      {loading ? (
        <Loading label="Đang tải kho mã…" />
      ) : error ? (
        <div className="panel-error">
          <ErrorNotice message={error} retry={() => setVersion((value) => value + 1)} />
        </div>
      ) : !data?.items.length ? (
        <EmptyState
          icon={GitBranch}
          title="Chưa có kho mã"
          description={
            role === "OWNER"
              ? "Sau khi người vận hành cấu hình GitHub App cho workspace, chọn Đồng bộ GitHub để tải danh sách kho mã."
              : "OWNER cần kết nối và đồng bộ GitHub App cho workspace này."
          }
          action={
            preview && (
              <Button asChild variant="outline">
                <Link href="/repositories">
                  Đến kho mã
                  <ArrowRight size={16} />
                </Link>
              </Button>
            )
          }
        />
      ) : (
        <div className="repository-list">
          {data.items.map((repository) => (
            <div className="repository-row" key={repository.id}>
              <div className="repository-icon">
                <GitBranch size={19} />
              </div>
              <div className="repository-info">
                <a
                  href={`https://github.com/${repository.fullName.split("/").map(encodeURIComponent).join("/")}`}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="repository-name"
                >
                  {repository.fullName}
                  <ExternalLink size={13} />
                  <span className="sr-only"> (mở tab mới)</span>
                </a>
                <span className="repository-branch">
                  <GitFork size={13} />
                  {repository.defaultBranch ?? "Chưa xác định nhánh mặc định"}
                </span>
              </div>
              <span className={`badge ${repository.active ? "badge-green" : "badge-neutral"}`}>
                <span className="badge-dot" />
                {repository.active ? "Đang theo dõi" : "Đã ngừng theo dõi"}
              </span>
            </div>
          ))}
        </div>
      )}
      {!preview && (
        <div className="panel-footer">
          <span className="muted">
            {data && !error && !loading
              ? `${data.totalElements} kho mã · Trang ${page + 1}/${Math.max(1, Math.ceil(data.totalElements / size))}`
              : "Danh sách kho mã"}
          </span>
          <div className="pagination">
            <Button
              variant="outline"
              aria-label="Trang trước"
              disabled={loading || page === 0}
              onClick={() => setPage(page - 1)}
            >
              <ChevronLeft size={16} />
            </Button>
            <Button
              variant="outline"
              aria-label="Trang sau"
              disabled={loading || !data || (page + 1) * size >= data.totalElements}
              onClick={() => setPage(page + 1)}
            >
              <ChevronRight size={16} />
            </Button>
          </div>
        </div>
      )}
      {!preview && (
        <div className="integration-note">
          <ShieldCheck size={16} />
          <span>
            Chỉ OWNER được đồng bộ. Quyền truy cập kho mã được quản lý qua GitHub App; không nhập
            private key tại đây.
          </span>
        </div>
      )}
    </section>
  );
}
