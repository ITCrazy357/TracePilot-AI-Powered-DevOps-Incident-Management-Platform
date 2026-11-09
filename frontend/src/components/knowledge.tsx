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
import { KnowledgeSearch } from "./knowledge-search";

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
  const articles = useResource<DataPage<KnowledgeArticle>>(
    `/api/backend/workspaces/${selectedId}/knowledge-articles?page=${page}&size=20`,
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
      <div className="knowledge-layout">
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
          {articles.loading && !articles.data ? (
            <Loading label="Đang tải kho kiến thức…" />
          ) : articles.error ? (
            <div className="panel-error">
              <ErrorNotice message={articles.error} retry={articles.reload} />
            </div>
          ) : !articles.data?.items.length ? (
            <EmptyState
              icon={BookOpen}
              title="Chưa có bài viết"
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
        <aside className="context-column">
          <KnowledgeSearch />
          <div className="knowledge-guide">
            <h2>Từ kinh nghiệm đến nguồn tham khảo</h2>
            <ol>
              <li>
                <strong>Ghi lại</strong>
                <span>Nội dung do con người kiểm tra, lưu dưới dạng bản nháp.</span>
              </li>
              <li>
                <strong>Duyệt</strong>
                <span>OWNER/ADMIN duyệt và tạo chỉ mục bằng AI.</span>
              </li>
              <li>
                <strong>Tra cứu</strong>
                <span>
                  Chỉ bài đã duyệt, có chỉ mục sẵn sàng mới được dùng để tìm kiếm và tham khảo cho
                  AI.
                </span>
              </li>
            </ol>
          </div>
        </aside>
      </div>
    </>
  );
}
