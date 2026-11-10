"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, errorMessage } from "@/lib/api";
import type { DataPage, KnowledgeSearchMode, KnowledgeSearchResult } from "@/lib/contracts";
import { useResource } from "@/lib/use-resource";
import { dateTime } from "@/lib/presentation";
import { useWorkspace } from "./workspace-provider";
import { Button } from "./ui/button";
import { Pagination } from "./ui/operational";
import { ErrorNotice, Loading } from "./ui/states";

interface Entry {
  id: string;
  query: string;
  mode: KnowledgeSearchMode;
  topK: number;
  resultCount: number;
  createdAt: string;
}

const PAGE_SIZE = 10;

export function SearchHistory() {
  const { selectedId } = useWorkspace();
  const [page, setPage] = useState(0);
  const [id, setId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const base = `/api/backend/workspaces/${selectedId}/knowledge-articles/search-history`;
  const history = useResource<DataPage<Entry>>(`${base}?page=${page}&size=${PAGE_SIZE}`);
  const detail = useResource<{ search: Entry; result: KnowledgeSearchResult }>(
    id ? `${base}/${id}` : null,
  );
  useEffect(() => {
    if (history.loading || !history.data) return;
    const lastPage = Math.max(0, Math.ceil(history.data.total / PAGE_SIZE) - 1);
    if (page > lastPage) setPage(lastPage);
  }, [history.data, history.loading, page]);

  async function remove() {
    if (!id || busy) return;
    setBusy(true);
    setError("");
    try {
      await api(`${base}/${id}`, { method: "DELETE" });
      setId("");
      history.reload();
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel" aria-label="Lịch sử tra cứu của bạn">
      <div className="panel-heading">
        <h2>Lịch sử tra cứu của bạn</h2>
        <Button variant="ghost" disabled={busy} onClick={history.reload}>
          Làm mới lịch sử
        </Button>
      </div>
      <p className="field-hint history-intro">
        Tự lưu truy vấn và kết quả sau mỗi lần tìm thành công, kể cả khi không có kết quả. Xem lại
        không gọi AI.
      </p>
      {history.loading ? (
        <Loading label="Đang tải lịch sử…" />
      ) : history.error ? (
        <ErrorNotice message={history.error} retry={history.reload} />
      ) : !history.data?.items.length ? (
        <p className="quiet-empty">Chưa có lịch sử tra cứu.</p>
      ) : (
        <>
          <ul className="knowledge-list">
            {history.data.items.map((item) => (
              <li key={item.id}>
                <Button
                  variant="ghost"
                  aria-pressed={id === item.id}
                  disabled={busy}
                  onClick={() => {
                    setId(item.id);
                    setError("");
                  }}
                >
                  {item.query}
                </Button>
                <p className="field-hint">
                  {dateTime(item.createdAt)} · {item.mode} · {item.resultCount} kết quả
                </p>
              </li>
            ))}
          </ul>
          <Pagination
            page={page}
            size={PAGE_SIZE}
            total={history.data.total}
            loading={history.loading || busy}
            onPage={setPage}
          />
        </>
      )}
      {id && (
        <div className="knowledge-results" aria-live="polite">
          {detail.loading ? (
            <Loading label="Đang tải kết quả đã lưu…" />
          ) : detail.error ? (
            <ErrorNotice message={detail.error} retry={detail.reload} />
          ) : (
            detail.data && (
              <>
                <h3>Kết quả đã lưu: {detail.data.search.query}</h3>
                <p className="field-hint">
                  Bản chụp tại {dateTime(detail.data.search.createdAt)}. Bài viết hiện tại có thể đã
                  thay đổi hoặc lưu trữ.
                </p>
                {!detail.data.result.items.length ? (
                  <p>Không có kết quả trong lần tra này.</p>
                ) : (
                  <ol className="evidence-list">
                    {detail.data.result.items.map((item) => (
                      <li key={item.id}>
                        <p className="report-copy">{item.content}</p>
                        <p className="field-hint">
                          Bản nội dung {item.articleVersion} · Độ tương đồng{" "}
                          {item.similarity.toFixed(3)}
                        </p>
                        <Link
                          className="text-link"
                          href={`/knowledge/${item.articleId}?version=${item.articleVersion}`}
                        >
                          Mở bài viết
                        </Link>
                      </li>
                    ))}
                  </ol>
                )}
                {error && <ErrorNotice message={error} />}
                <div className="toolbar-actions">
                  <Button variant="outline" disabled={busy} onClick={remove}>
                    Xóa lần tra này
                  </Button>
                  <Button variant="ghost" disabled={busy} onClick={() => setId("")}>
                    Đóng kết quả đã lưu
                  </Button>
                </div>
              </>
            )
          )}
        </div>
      )}
    </section>
  );
}
