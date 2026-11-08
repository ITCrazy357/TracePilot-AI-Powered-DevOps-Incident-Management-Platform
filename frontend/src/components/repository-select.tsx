"use client";

import { useEffect, useState } from "react";
import type { Repository, RepositoryPage } from "@/lib/contracts";
import { api, errorMessage } from "@/lib/api";
import { useResource } from "@/lib/use-resource";
import { useWorkspace } from "./workspace-provider";
import { Button } from "./ui/button";
import { ErrorNotice } from "./ui/states";

export function RepositorySelect({
  value,
  onChange,
}: {
  value: string;
  onChange: (id: string) => void;
}) {
  const { selectedId } = useWorkspace();
  const resource = useResource<RepositoryPage>(
    `/api/backend/workspaces/${selectedId}/repositories?page=0&size=100`,
  );
  const [extra, setExtra] = useState<Repository[]>([]);
  const [page, setPage] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const items = [...(resource.data?.items ?? []), ...extra];
  useEffect(() => {
    if (!value && resource.data?.items[0]) onChange(resource.data.items[0].id);
  }, [resource.data, value, onChange]);

  async function more() {
    setBusy(true);
    setError("");
    try {
      const result = await api<RepositoryPage>(
        `/api/backend/workspaces/${selectedId}/repositories?page=${page + 1}&size=100`,
      );
      setExtra((current) => [...current, ...result.items]);
      setPage((current) => current + 1);
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="repository-picker">
      <label>
        Kho mã
        <select
          value={value}
          onChange={(event) => onChange(event.target.value)}
          disabled={resource.loading}
        >
          {!items.length && (
            <option value="">{resource.loading ? "Đang tải kho mã…" : "Chưa có kho mã"}</option>
          )}
          {value && !items.some((repo) => repo.id === value) && (
            <option value={value}>Kho mã {value.slice(0, 8)}</option>
          )}
          {items.map((repo) => (
            <option value={repo.id} key={repo.id}>
              {repo.fullName}
              {repo.active ? "" : " (ngừng theo dõi)"}
            </option>
          ))}
        </select>
      </label>
      {resource.error && <ErrorNotice message={resource.error} retry={resource.reload} />}
      {error && <ErrorNotice message={error} />}
      {items.length < (resource.data?.totalElements ?? 0) && (
        <Button variant="ghost" onClick={more} disabled={busy}>
          {busy ? "Đang tải…" : "Tải thêm kho mã"}
        </Button>
      )}
    </div>
  );
}
