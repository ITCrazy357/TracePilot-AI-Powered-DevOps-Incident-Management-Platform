"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { ArrowLeft, Save } from "lucide-react";
import { ApiError, api, errorMessage } from "@/lib/api";
import type { Incident, KnowledgeArticle } from "@/lib/contracts";
import { useResource } from "@/lib/use-resource";
import { useWorkspace } from "./workspace-provider";
import { WorkspaceBoundary } from "./workspace-boundary";
import { Button } from "./ui/button";
import { ErrorNotice, Loading } from "./ui/states";
import { PageHeading } from "./ui/operational";

export function KnowledgeCreate({ incidentId }: { incidentId?: string }) {
  const { selectedId } = useWorkspace();
  return (
    <WorkspaceBoundary>
      <CreateContent key={`${selectedId}:${incidentId ?? ""}`} incidentId={incidentId} />
    </WorkspaceBoundary>
  );
}

function CreateContent({ incidentId }: { incidentId?: string }) {
  const { selectedId } = useWorkspace();
  const router = useRouter();
  const validId =
    !incidentId ||
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(incidentId);
  const source = useResource<Incident>(
    incidentId && validId ? `/api/backend/workspaces/${selectedId}/incidents/${incidentId}` : null,
  );
  return (
    <>
      <Link href="/knowledge" className="back-link">
        <ArrowLeft size={15} />
        Kho kiến thức
      </Link>
      <PageHeading
        title="Viết bài kiến thức"
        description="Ghi lại nguyên nhân và cách khắc phục bạn đã kiểm tra. Bài mới được lưu dưới dạng bản nháp."
      />
      {!validId ? (
        <ErrorNotice message="ID sự cố không hợp lệ. Quay lại kho kiến thức để tạo bài mới." />
      ) : source.loading ? (
        <Loading label="Đang kiểm tra sự cố nguồn…" />
      ) : source.error ? (
        <ErrorNotice message={source.error} retry={source.reload} />
      ) : incidentId && source.data?.status !== "RESOLVED" ? (
        <ErrorNotice message="Sự cố phải được đánh dấu Đã giải quyết trước khi ghi lại cách khắc phục." />
      ) : (
        <div className="knowledge-editor-layout">
          <section className="panel knowledge-editor-panel">
            {source.data && (
              <p className="knowledge-source">
                Sự cố nguồn:{" "}
                <Link className="text-link" href={`/incidents/${source.data.id}`}>
                  {source.data.title}
                </Link>
              </p>
            )}
            <KnowledgeEditor
              sourceIncidentId={incidentId}
              onSaved={(article) => router.push(`/knowledge/${article.id}`)}
            />
          </section>
          <aside className="knowledge-guide">
            <h2>Nội dung để dùng lại</h2>
            <p>
              Giải thích điều gì gây lỗi, cách bạn xác nhận và các bước đã khắc phục thành công.
            </p>
            <p>
              Không tự xem đề xuất AI là cách sửa đã được kiểm chứng. OWNER/ADMIN sẽ duyệt bài trước
              khi đưa vào tìm kiếm.
            </p>
          </aside>
        </div>
      )}
    </>
  );
}

export function KnowledgeEditor({
  article,
  sourceIncidentId,
  onSaved,
  onCancel,
  onReload,
}: {
  article?: KnowledgeArticle;
  sourceIncidentId?: string;
  onSaved: (article: KnowledgeArticle) => void;
  onCancel?: () => void;
  onReload?: () => void;
}) {
  const { selectedId } = useWorkspace();
  // Keep the version captured when editing began; never silently rebase a user's draft.
  const [version] = useState(article?.version);
  const [title, setTitle] = useState(article?.title ?? "");
  const [rootCause, setRootCause] = useState(article?.rootCause ?? "");
  const [resolution, setResolution] = useState(article?.resolution ?? "");
  const [busy, setBusy] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [error, setError] = useState("");
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || conflict || !title.trim() || !rootCause.trim() || !resolution.trim()) return;
    setBusy(true);
    setError("");
    const base = `/api/backend/workspaces/${selectedId}/knowledge-articles`;
    try {
      const saved = await api<KnowledgeArticle>(article ? `${base}/${article.id}` : base, {
        method: article ? "PATCH" : "POST",
        body: JSON.stringify({
          title: title.trim(),
          rootCause: rootCause.trim(),
          resolution: resolution.trim(),
          ...(article ? { version } : { sourceIncidentId: sourceIncidentId ?? null }),
        }),
      });
      onSaved(saved);
    } catch (failure) {
      setError(errorMessage(failure));
      setConflict(failure instanceof ApiError && failure.status === 409);
    } finally {
      setBusy(false);
    }
  }
  return (
    <form className="form-stack" onSubmit={save}>
      {error && <ErrorNotice message={error} />}
      {conflict && onReload && (
        <Button variant="outline" onClick={onReload}>
          Tải lại bài viết
        </Button>
      )}
      {article && article.status !== "DRAFT" && (
        <p className="notice notice-info">
          Lưu chỉnh sửa sẽ đưa bài về bản nháp và ngừng dùng nội dung cũ cho tìm kiếm. Cần duyệt lại
          để tạo chỉ mục.
        </p>
      )}
      <fieldset className="knowledge-fields" disabled={busy || conflict}>
        <label>
          Tiêu đề
          <input
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            required
            maxLength={160}
          />
        </label>
        <label>
          Nguyên nhân đã xác nhận
          <textarea
            value={rootCause}
            onChange={(event) => setRootCause(event.target.value)}
            required
            maxLength={3000}
            rows={5}
          />
          <span className="field-hint">{rootCause.length}/3.000 ký tự</span>
        </label>
        <label>
          Cách khắc phục đã kiểm tra
          <textarea
            value={resolution}
            onChange={(event) => setResolution(event.target.value)}
            required
            maxLength={6000}
            rows={9}
          />
          <span className="field-hint">{resolution.length}/6.000 ký tự</span>
        </label>
      </fieldset>
      <p className="field-hint">
        Không nhập secret, token hoặc thông tin cá nhân. Nội dung được backend che dữ liệu nhạy cảm
        trước khi lưu; bản lưu có thể khác nội dung bạn nhập.
      </p>
      <div className="toolbar-actions">
        {onCancel && (
          <Button variant="outline" disabled={busy} onClick={onCancel}>
            Hủy chỉnh sửa
          </Button>
        )}
        <Button
          type="submit"
          disabled={busy || conflict || !title.trim() || !rootCause.trim() || !resolution.trim()}
        >
          <Save size={16} />
          {busy ? "Đang lưu…" : "Lưu bản nháp"}
        </Button>
      </div>
    </form>
  );
}
