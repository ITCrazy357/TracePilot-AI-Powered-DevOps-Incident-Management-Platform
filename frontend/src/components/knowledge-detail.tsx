"use client";

import Link from "next/link";
import { useState } from "react";
import { ArrowLeft, Archive, CheckCircle2, Pencil, RefreshCw } from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import type { KnowledgeArticle } from "@/lib/contracts";
import { useResource } from "@/lib/use-resource";
import { dateTime, shortId } from "@/lib/presentation";
import { useAuth } from "./auth-provider";
import { useWorkspace } from "./workspace-provider";
import { WorkspaceBoundary } from "./workspace-boundary";
import { KnowledgeEditor } from "./knowledge-editor";
import { Button } from "./ui/button";
import { Dialog } from "./ui/dialog";
import { ErrorNotice, Loading, SuccessNotice } from "./ui/states";
import { PageHeading, StatusBadge } from "./ui/operational";

function usageDescription(article: KnowledgeArticle) {
  if (article.status === "ARCHIVED")
    return "Bài đã lưu trữ, không tham gia tìm kiếm. Người quản lý có thể sửa để đưa về bản nháp.";
  if (article.status === "APPROVED" && article.indexStatus === "READY")
    return "Đã sẵn sàng cho tìm kiếm và làm nguồn tham khảo AI trong workspace này.";
  if (article.indexStatus === "RUNNING")
    return "Đang tạo chỉ mục. Tải lại để kiểm tra tiến độ. Nếu lượt xử lý bị gián đoạn, chỉ thử lại sau khi hết hạn 120 giây.";
  if (article.indexStatus === "FAILED")
    return "Tạo chỉ mục thất bại. Bài chưa tham gia tìm kiếm; người quản lý có thể kiểm tra lỗi và duyệt lại.";
  return "Bản nháp chưa được dùng cho tìm kiếm hoặc làm nguồn tham khảo AI.";
}

export function KnowledgeDetail({
  articleId,
  evidenceVersion,
}: {
  articleId: string;
  evidenceVersion?: string;
}) {
  const { selectedId } = useWorkspace();
  return (
    <WorkspaceBoundary>
      <ArticleContent
        key={`${selectedId}:${articleId}`}
        articleId={articleId}
        evidenceVersion={evidenceVersion}
      />
    </WorkspaceBoundary>
  );
}

function ArticleContent({
  articleId,
  evidenceVersion,
}: {
  articleId: string;
  evidenceVersion?: string;
}) {
  const { selectedId, role } = useWorkspace();
  const { user } = useAuth();
  const base = `/api/backend/workspaces/${selectedId}/knowledge-articles/${articleId}`;
  const resource = useResource<KnowledgeArticle>(base);
  const [editing, setEditing] = useState(false);
  const [action, setAction] = useState<"approve" | "archive" | null>(null);
  const [actionVersion, setActionVersion] = useState(0);
  const [busy, setBusy] = useState(false);
  const [mustReload, setMustReload] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const article = resource.data;
  const manager = role === "OWNER" || role === "ADMIN";
  const canEdit = manager || (article?.status === "DRAFT" && article.createdBy === user?.id);
  const canApprove =
    manager && article && article.status !== "ARCHIVED" && article.indexStatus !== "READY";

  function reload() {
    setEditing(false);
    setMustReload(false);
    setError("");
    setSuccess("");
    resource.reload();
  }
  function confirm(next: "approve" | "archive") {
    if (!article) return;
    setActionVersion(article.version);
    setError("");
    setSuccess("");
    setAction(next);
  }
  async function execute() {
    if (!action || busy) return;
    setBusy(true);
    try {
      await api<KnowledgeArticle>(`${base}/${action}`, {
        method: "POST",
        body: JSON.stringify({ version: actionVersion }),
      });
      setSuccess(
        action === "approve"
          ? "Đã duyệt bài viết và tạo chỉ mục sẵn sàng."
          : "Đã lưu trữ bài viết. Nội dung không còn được dùng cho tìm kiếm.",
      );
      setAction(null);
      resource.reload();
    } catch (failure) {
      setError(errorMessage(failure));
      // Approval can commit APPROVED/FAILED before returning an error or losing its response.
      setMustReload(true);
      setAction(null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Link href="/knowledge" className="back-link">
        <ArrowLeft size={15} />
        Kho kiến thức
      </Link>
      {resource.loading && !article ? (
        <Loading label="Đang tải bài viết…" />
      ) : resource.error ? (
        <ErrorNotice message={resource.error} retry={reload} />
      ) : (
        article && (
          <>
            <div className="knowledge-tags">
              <StatusBadge status={article.status} />
              <StatusBadge status={article.indexStatus} />
            </div>
            <PageHeading
              title={article.title}
              description={`Bản nội dung ${article.contentVersion} · Cập nhật ${dateTime(article.updatedAt)}`}
              action={
                <Button variant="outline" disabled={busy} onClick={reload}>
                  <RefreshCw size={16} />
                  Tải lại bài viết
                </Button>
              }
            />
            {success && <SuccessNotice>{success}</SuccessNotice>}
            {error && <ErrorNotice message={error} />}
            {mustReload && (
              <p className="notice notice-info">
                Thao tác có thể đã thay đổi trạng thái trên server. Tải lại bài viết để kiểm tra
                trước khi thử tiếp.
              </p>
            )}
            {evidenceVersion &&
              /^\d+$/.test(evidenceVersion) &&
              Number(evidenceVersion) !== article.contentVersion && (
                <p className="notice notice-info">
                  Nguồn trích dẫn thuộc bản nội dung {evidenceVersion}. Trang này hiển thị bản hiện
                  tại ({article.contentVersion}); xem câu trích và mã đối chiếu lưu trong báo cáo để
                  đối chiếu bản lịch sử.
                </p>
              )}
            <div className="knowledge-editor-layout">
              <section className="panel knowledge-editor-panel">
                {editing ? (
                  <KnowledgeEditor
                    article={article}
                    onCancel={() => setEditing(false)}
                    onReload={reload}
                    onSaved={() => {
                      setEditing(false);
                      setSuccess("Đã lưu bản nháp. Kiểm tra nội dung đã được che trước khi duyệt.");
                      resource.reload();
                    }}
                  />
                ) : (
                  <div className="report-sections">
                    <section>
                      <h2>Nguyên nhân đã xác nhận</h2>
                      <p className="report-copy">{article.rootCause}</p>
                    </section>
                    <section>
                      <h2>Cách khắc phục đã kiểm tra</h2>
                      <p className="report-copy">{article.resolution}</p>
                    </section>
                  </div>
                )}
              </section>
              <aside className="context-column">
                <section className="panel">
                  <div className="panel-heading">
                    <h2>Trạng thái sử dụng</h2>
                  </div>
                  <div className="knowledge-context">
                    <p>{usageDescription(article)}</p>
                    {article.indexErrorCode && (
                      <code className="error-code">{article.indexErrorCode}</code>
                    )}
                    <dl className="knowledge-metadata">
                      <div>
                        <dt>Người tạo</dt>
                        <dd>
                          {article.createdBy === user?.id ? "Bạn" : shortId(article.createdBy)}
                        </dd>
                      </div>
                      <div>
                        <dt>Duyệt lúc</dt>
                        <dd>{dateTime(article.approvedAt)}</dd>
                      </div>
                    </dl>
                    {article.sourceIncidentId && (
                      <Link className="text-link" href={`/incidents/${article.sourceIncidentId}`}>
                        Xem sự cố nguồn
                      </Link>
                    )}
                    {!editing && (
                      <div className="knowledge-actions">
                        {canEdit && (
                          <Button
                            variant="outline"
                            disabled={busy || mustReload || resource.loading}
                            onClick={() => {
                              setSuccess("");
                              setEditing(true);
                            }}
                          >
                            <Pencil size={15} />
                            Chỉnh sửa bài
                          </Button>
                        )}
                        {canApprove && (
                          <Button
                            disabled={busy || mustReload || resource.loading}
                            onClick={() => confirm("approve")}
                          >
                            <CheckCircle2 size={16} />
                            {article.indexStatus === "FAILED" || article.indexStatus === "RUNNING"
                              ? "Thử tạo lại chỉ mục"
                              : "Duyệt bài viết"}
                          </Button>
                        )}
                        {manager && article.status !== "ARCHIVED" && (
                          <Button
                            variant="outline"
                            disabled={busy || mustReload || resource.loading}
                            onClick={() => confirm("archive")}
                          >
                            <Archive size={15} />
                            Lưu trữ bài
                          </Button>
                        )}
                      </div>
                    )}
                    {!manager && (
                      <p className="field-hint">
                        OWNER/ADMIN duyệt và lưu trữ. Thành viên chỉ sửa bản nháp do mình tạo.
                      </p>
                    )}
                  </div>
                </section>
              </aside>
            </div>
          </>
        )
      )}
      <Dialog
        open={action !== null}
        onOpenChange={(open) => {
          if (!open && !busy) setAction(null);
        }}
        title={action === "archive" ? "Lưu trữ bài viết?" : "Duyệt và tạo chỉ mục?"}
        description={
          action === "archive"
            ? "Bài viết ngừng tham gia tìm kiếm ngay. Các báo cáo cũ vẫn giữ nguồn đã trích dẫn."
            : "Nội dung bài viết đã che sẽ được gửi tới nhà cung cấp AI để tạo chỉ mục; có thể phát sinh chi phí. Xác nhận bạn đã kiểm tra cách khắc phục trước khi duyệt."
        }
      >
        <div className="dialog-actions">
          <Button variant="outline" disabled={busy} onClick={() => setAction(null)}>
            Hủy
          </Button>
          <Button disabled={busy} onClick={execute}>
            {busy ? "Đang xử lý…" : action === "archive" ? "Xác nhận lưu trữ" : "Xác nhận duyệt"}
          </Button>
        </div>
      </Dialog>
    </>
  );
}
