"use client";

import Link from "next/link";
import { useState } from "react";
import { BookOpen, Plus, RefreshCw, ArrowUpRight } from "lucide-react";
import type { DataPage, KnowledgeArticle } from "@/lib/contracts";
import { useResource } from "@/lib/use-resource";
import { dateTime } from "@/lib/presentation";
import { useWorkspace } from "./workspace-provider";
import { WorkspaceBoundary } from "./workspace-boundary";
import { Button } from "./ui/button";
import { EmptyState, ErrorNotice, Loading } from "./ui/states";
import { PageHeading, Pagination, StatusBadge } from "./ui/operational";
import { KnowledgeNavigation } from "./knowledge-navigation";

export function Knowledge() {
  const { selectedId } = useWorkspace();
  return (
    <WorkspaceBoundary>
      <KnowledgeContent key={selectedId} />
    </WorkspaceBoundary>
  );
}

function KnowledgeContent() {
  const { selectedId } = useWorkspace();
  const [page, setPage] = useState(0);
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("");
  const [criteria, setCriteria] = useState("");
  const articles = useResource<DataPage<KnowledgeArticle>>(
    `/api/backend/workspaces/${selectedId}/knowledge-articles?page=${page}&size=20${criteria}`,
  );
  return (
    <>
      <PageHeading
        title="Kho kiến thức"
        description="Lưu cách khắc phục đã xác nhận để lần điều tra sau có điểm bắt đầu."
        action={
          <Button asChild>
            <Link href="/knowledge/new">
              <Plus size={16} />
              Viết bài mới
            </Link>
          </Button>
        }
      />
      <KnowledgeNavigation />
      <div>
        <section className="panel knowledge-library" aria-labelledby="library-heading">
          <div className="panel-heading">
            <h2 id="library-heading">
              <BookOpen size={18} />
              Bài viết của workspace
            </h2>
            <Button
              variant="ghost"
              aria-label="Làm mới bài viết"
              disabled={articles.loading}
              onClick={articles.reload}
            >
              <RefreshCw size={16} />
            </Button>
          </div>
          <form
            className="criteria-form"
            onSubmit={(event) => {
              event.preventDefault();
              setPage(0);
              const params = new URLSearchParams();
              if (q.trim()) params.set("q", q.trim());
              if (status) params.set("status", status);
              setCriteria(params.size ? `&${params}` : "");
            }}
          >
            <label>
              Từ khóa bài viết
              <input maxLength={200} value={q} onChange={(event) => setQ(event.target.value)} />
            </label>
            <label>
              Trạng thái bài viết
              <select value={status} onChange={(event) => setStatus(event.target.value)}>
                <option value="">Tất cả trạng thái</option>
                <option value="DRAFT">Bản nháp</option>
                <option value="APPROVED">Đã duyệt</option>
                <option value="ARCHIVED">Đã lưu trữ</option>
              </select>
            </label>
            <div className="toolbar-actions">
              <Button type="submit">Lọc bài viết</Button>
              <Button
                variant="ghost"
                onClick={() => {
                  setQ("");
                  setStatus("");
                  setCriteria("");
                  setPage(0);
                }}
              >
                Xóa bộ lọc
              </Button>
            </div>
            <p className="field-hint">
              Tìm trong tiêu đề, nguyên nhân và cách khắc phục. Không gọi AI.
            </p>
          </form>
          {articles.loading && !articles.data ? (
            <Loading label="Đang tải kho kiến thức…" />
          ) : articles.error ? (
            <div className="panel-error">
              <ErrorNotice message={articles.error} retry={articles.reload} />
            </div>
          ) : !articles.data?.items.length ? (
            <EmptyState
              icon={BookOpen}
              title={criteria ? "Không có bài viết khớp tiêu chí" : "Chưa có bài viết"}
              description="Bắt đầu bằng cách ghi lại nguyên nhân và cách khắc phục mà bạn đã kiểm tra."
              action={
                <Link className="text-link" href="/knowledge/new">
                  Viết bài đầu tiên <ArrowUpRight size={15} />
                </Link>
              }
            />
          ) : (
            <>
              <ul className="knowledge-list">
                {articles.data.items.map((article) => (
                  <li key={article.id}>
                    <div className="knowledge-tags">
                      <StatusBadge status={article.status} />
                      <StatusBadge status={article.indexStatus} />
                    </div>
                    <h3>
                      <Link href={`/knowledge/${article.id}`}>
                        {article.title}
                        <ArrowUpRight size={16} />
                      </Link>
                    </h3>
                    <p className="knowledge-excerpt">{article.rootCause}</p>
                    <span className="field-hint">
                      Bản nội dung {article.contentVersion} · Cập nhật {dateTime(article.updatedAt)}
                    </span>
                  </li>
                ))}
              </ul>
              <Pagination
                page={page}
                size={20}
                total={articles.data.total}
                loading={articles.loading}
                onPage={setPage}
              />
            </>
          )}
        </section>
      </div>
    </>
  );
}
