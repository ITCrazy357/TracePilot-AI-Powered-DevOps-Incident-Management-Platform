"use client";

import { useEffect, useState } from "react";
import { ApiError, api, errorMessage } from "@/lib/api";
import type { GitHubConnection } from "@/lib/contracts";
import { Button } from "./ui/button";
import { Dialog } from "./ui/dialog";
import { ErrorNotice, Loading } from "./ui/states";

interface Options {
  connection: {
    installationId: number | null;
    accountLogin: string | null;
    version: number | null;
    repositoryIds: number[];
  };
  installations: { installationId: number; accountLogin: string }[];
}
interface Catalog {
  status: string;
  repositories: { id: number; fullName: string; defaultBranch: string | null }[];
}

export function GitHubConnectionEditor({
  workspaceId,
  onClose,
  onSaved,
}: {
  workspaceId: string;
  onClose: () => void;
  onSaved: (connection: GitHubConnection) => void;
}) {
  const base = `/api/backend/workspaces/${workspaceId}/github`;
  const [options, setOptions] = useState<Options | null>(null);
  const [installationId, setInstallationId] = useState("");
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    api<Options>(`${base}/connection`, { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        setOptions(result);
        setSelected(new Set(result.connection.repositoryIds));
        setInstallationId(
          String(result.connection.installationId ?? result.installations[0]?.installationId ?? ""),
        );
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError(errorMessage(failure));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [base, reload]);

  useEffect(() => {
    if (!installationId) return;
    const controller = new AbortController();
    setCatalog(null);
    setError("");
    api<Catalog>(`${base}/installations/${installationId}/repositories`, {
      signal: controller.signal,
    })
      .then((result) => {
        if (controller.signal.aborted) return;
        setCatalog(result);
        const available = new Set(result.repositories.map((item) => item.id));
        setSelected((current) => new Set([...current].filter((id) => available.has(id))));
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError(errorMessage(failure));
      });
    return () => controller.abort();
  }, [base, installationId, reload]);

  async function save() {
    if (!options || !catalog || busy || conflict || !selected.size || error) return;
    setBusy(true);
    try {
      const result = await api<GitHubConnection>(`${base}/connection`, {
        method: "POST",
        body: JSON.stringify({
          installationId: Number(installationId),
          version: options.connection.version,
          repositoryIds: [...selected],
        }),
      });
      onSaved(result);
    } catch (failure) {
      setError(
        failure instanceof ApiError && failure.status === 409
          ? "Liên kết hoặc quyền repository đã thay đổi. Tải lại lựa chọn trước khi lưu."
          : errorMessage(failure),
      );
      setConflict(true);
    } finally {
      setBusy(false);
    }
  }

  const visible =
    catalog?.repositories.filter((item) =>
      item.fullName.toLowerCase().includes(query.trim().toLowerCase()),
    ) ?? [];
  return (
    <Dialog
      open
      title="Chọn repository cho workspace"
      description="Chỉ các repository bạn chọn được theo dõi trong workspace này. Bỏ chọn sẽ dừng theo dõi và giữ lịch sử đã có."
      onOpenChange={(open) => {
        if (!open && !busy) onClose();
      }}
    >
      {error && <ErrorNotice message={error} />}
      {loading ? (
        <Loading label="Đang tải liên kết GitHub…" />
      ) : options && !installationId ? (
        <p className="notice notice-info">
          Chưa có GitHub App được cấp quyền cho bạn. Bạn cần là OWNER của workspace đã được người
          vận hành liên kết GitHub trước khi dùng chung kết nối.
        </p>
      ) : (
        options && (
          <div className="form-stack">
            <label>
              Tài khoản GitHub
              <select
                value={installationId}
                disabled={busy || Boolean(options.connection.installationId)}
                onChange={(event) => {
                  setSelected(new Set());
                  setInstallationId(event.target.value);
                  setQuery("");
                }}
              >
                {options.installations.map((item) => (
                  <option key={item.installationId} value={item.installationId}>
                    {item.accountLogin}
                  </option>
                ))}
              </select>
            </label>
            {!catalog && !error ? (
              <Loading label="Đang lấy repository được GitHub cấp quyền…" />
            ) : (
              catalog && (
                <>
                  <label>
                    Lọc repository
                    <input
                      value={query}
                      onChange={(event) => setQuery(event.target.value)}
                      disabled={busy}
                    />
                  </label>
                  <p className="field-hint">
                    Đã chọn {selected.size} repository. Danh sách chỉ gồm repo mà GitHub App hiện
                    được cấp quyền; thêm repo trong cài đặt GitHub App nếu chưa thấy project.
                  </p>
                  <fieldset disabled={busy || conflict} className="github-selection-list">
                    <legend>Repository theo dõi</legend>
                    {visible.map((item) => (
                      <label key={item.id} className="verification-confirmation">
                        <input
                          type="checkbox"
                          checked={selected.has(item.id)}
                          onChange={(event) =>
                            setSelected((current) => {
                              const next = new Set(current);
                              if (event.target.checked) next.add(item.id);
                              else next.delete(item.id);
                              return next;
                            })
                          }
                        />
                        {item.fullName}
                      </label>
                    ))}
                    {!visible.length && (
                      <p className="muted">
                        Không có repository phù hợp hoặc GitHub App chưa được cấp quyền.
                      </p>
                    )}
                  </fieldset>
                </>
              )
            )}
          </div>
        )
      )}
      {(error || conflict) && (
        <Button
          variant="outline"
          disabled={busy}
          onClick={() => {
            setConflict(false);
            setOptions(null);
            setCatalog(null);
            setReload((value) => value + 1);
          }}
        >
          Tải lại lựa chọn
        </Button>
      )}
      <div className="dialog-actions">
        <Button variant="outline" disabled={busy} onClick={onClose}>
          Hủy
        </Button>
        <Button
          disabled={
            busy || loading || !options || !catalog || !selected.size || conflict || Boolean(error)
          }
          onClick={save}
        >
          {busy ? "Đang lưu…" : "Lưu repository theo dõi"}
        </Button>
      </div>
    </Dialog>
  );
}
