"use client";

import Link from "next/link";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { Search, ArrowUpRight } from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import type { KnowledgeSearchMode, KnowledgeSearchResult } from "@/lib/contracts";
import { useWorkspace } from "./workspace-provider";
import { Button } from "./ui/button";
import { EmptyState, ErrorNotice, Loading } from "./ui/states";

export function KnowledgeSearch() {
  const { selectedId } = useWorkspace();
  const [query, setQuery] = useState("");
  const [mode, setMode] = useState<KnowledgeSearchMode>("HYBRID");
  const [topK, setTopK] = useState(3);
  const [consent, setConsent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState<KnowledgeSearchResult | null>(null);
  const [searchedQuery, setSearchedQuery] = useState("");
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);

  async function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || !consent || !query.trim()) return;
    const request = new AbortController();
    controller.current = request;
    setBusy(true);
    setError("");
    setResult(null);
    setSearchedQuery(query.trim());
    try {
      const data = await api<KnowledgeSearchResult>(
        `/api/backend/workspaces/${selectedId}/knowledge-articles/search`,
        {
          method: "POST",
          body: JSON.stringify({ query: query.trim(), mode, topK }),
          signal: request.signal,
        },
      );
      if (!request.signal.aborted) {
        setResult(data);
      }
    } catch (failure) {
      if (!request.signal.aborted) setError(errorMessage(failure));
    } finally {
      if (!request.signal.aborted) setBusy(false);
    }
  }

  return (
    <>
      <section className="panel" aria-labelledby="knowledge-search-heading">
        <div className="panel-heading">
          <h2 id="knowledge-search-heading">
            <Search size={17} />
            Tìm lỗi tương tự
          </h2>
        </div>
        <div className="lookup-workbench">
          <form className="form-stack knowledge-search" onSubmit={search}>
            <label>
              Mô tả lỗi
              <textarea
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                maxLength={2000}
                rows={3}
                required
                disabled={busy}
                placeholder="Ví dụ: dịch vụ không khởi động vì cổng đang được sử dụng"
              />
            </label>
            <div className="knowledge-search-options">
              <label>
                Cách tìm
                <select
                  value={mode}
                  onChange={(event) => setMode(event.target.value as KnowledgeSearchMode)}
                  disabled={busy}
                >
                  <option value="HYBRID">Ngữ nghĩa + từ khóa</option>
                  <option value="EXACT">Ngữ nghĩa chính xác</option>
                  <option value="APPROXIMATE">Ngữ nghĩa gần đúng</option>
                </select>
              </label>
              <label>
                Số kết quả
                <select
                  value={topK}
                  onChange={(event) => setTopK(Number(event.target.value))}
                  disabled={busy}
                >
                  {[1, 2, 3, 4, 5].map((value) => (
                    <option key={value} value={value}>
                      {value}
                    </option>
                  ))}
                </select>
              </label>
            </div>
            <label className="knowledge-consent">
              <input
                type="checkbox"
                checked={consent}
                onChange={(event) => setConsent(event.target.checked)}
                disabled={busy}
              />
              <span>
                Tôi đồng ý gửi truy vấn tới nhà cung cấp AI đã cấu hình; có thể phát sinh chi phí.
              </span>
            </label>
            <Button type="submit" disabled={busy || !consent || !query.trim()}>
              <Search size={16} />
              {busy ? "Đang tìm…" : "Tìm trong kho kiến thức"}
            </Button>
            <p className="field-hint">
              Chỉ tìm bài đã duyệt và sẵn sàng trong workspace hiện tại. Không nhập thông tin nhạy
              cảm. Truy vấn và kết quả được tự lưu vào lịch sử riêng của bạn.
            </p>
          </form>
          <section className="lookup-output" aria-label="Kết quả tra vector">
            {!busy && !error && !result && (
              <EmptyState
                icon={Search}
                title="Kết quả tra vector"
                description="Nhập mô tả lỗi và bắt đầu tra cứu. Kết quả sẽ xuất hiện tại đây."
              />
            )}
            {busy && <Loading label="Đang tìm bài viết liên quan…" />}
            {error && (
              <div className="panel-error">
                <ErrorNotice message={error} />
              </div>
            )}
            {result && (
              <div className="knowledge-results" aria-live="polite">
                <h3>Kết quả cho “{searchedQuery}”</h3>
                <p className="field-hint">
                  {result.mode} · {result.embeddingModel}
                </p>
                {!result.items.length ? (
                  <p className="quiet-empty">
                    Chưa tìm thấy đoạn phù hợp. Thử mô tả khác hoặc bổ sung bài đã duyệt.
                  </p>
                ) : (
                  <ol className="evidence-list">
                    {result.items.map((item) => (
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
                          Mở bài viết <ArrowUpRight size={14} />
                        </Link>
                      </li>
                    ))}
                  </ol>
                )}
                <p className="field-hint">Độ tương đồng không phải độ tin cậy của chẩn đoán.</p>
              </div>
            )}
          </section>
        </div>
      </section>
      {result && (
        <p className="notice notice-info">
          Đã tự lưu lần tra.{" "}
          <Link className="text-link" href="/knowledge/history">
            Xem lịch sử tra cứu
          </Link>
        </p>
      )}
    </>
  );
}
