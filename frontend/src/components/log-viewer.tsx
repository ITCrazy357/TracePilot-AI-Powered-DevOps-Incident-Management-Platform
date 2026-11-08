"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { Copy, ScanText, ShieldCheck, RefreshCw } from "lucide-react";
import type { IncidentLogs } from "@/lib/contracts";
import { useResource } from "@/lib/use-resource";
import { dateTime } from "@/lib/presentation";
import { useWorkspace } from "./workspace-provider";
import { Button } from "./ui/button";
import { EmptyState, ErrorNotice, Loading } from "./ui/states";
import { StatusBadge } from "./ui/operational";

export function highlightText(text: string, query: string): ReactNode {
  if (!query) return text;
  const parts = text.split(query);
  return parts.map((part, index) => (
    <span key={index}>
      {index > 0 && <mark>{query}</mark>}
      {part}
    </span>
  ));
}

export function LogViewer({ incidentId, evidence }: { incidentId: string; evidence: string }) {
  const { selectedId } = useWorkspace();
  const resource = useResource<IncidentLogs>(
    `/api/backend/workspaces/${selectedId}/incidents/${incidentId}/logs`,
  );
  const [query, setQuery] = useState(evidence);
  const [copied, setCopied] = useState(false);
  const [copyError, setCopyError] = useState("");
  const viewer = useRef<HTMLDivElement>(null);
  const content = resource.data?.snippets.map((snippet) => snippet.content).join("") ?? "";
  useEffect(() => {
    if (evidence && content.includes(evidence))
      viewer.current?.querySelector("mark")?.scrollIntoView({ block: "center" });
  }, [evidence, content]);
  async function copy() {
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      setCopyError("");
    } catch {
      setCopyError("Không sao chép được. Bạn có thể chọn nội dung log để sao chép.");
    }
  }
  if (resource.loading && !resource.data) return <Loading label="Đang tải log đã che…" />;
  if (resource.error) return <ErrorNotice message={resource.error} retry={resource.reload} />;
  if (!resource.data) return null;
  const logs = resource.data;
  return (
    <section className="panel">
      <div className="panel-heading">
        <div>
          <h2>
            <ScanText size={18} />
            Log đã che
          </h2>
          <p className="muted">Nội dung lưu từ job thất bại đại diện của sự cố.</p>
        </div>
        <Button variant="outline" onClick={resource.reload}>
          <RefreshCw size={15} />
          Làm mới
        </Button>
      </div>
      {logs.status !== "READY" ? (
        <>
          <div className="log-state">
            <StatusBadge status={logs.status} />
          </div>
          <EmptyState
            icon={ScanText}
            title={
              logs.status === "UNAVAILABLE"
                ? "Không có job đại diện để lấy log"
                : logs.status === "FAILED"
                  ? "Thu thập log thất bại"
                  : "Log chưa sẵn sàng"
            }
            description={
              logs.status === "FAILED"
                ? "Liên hệ người vận hành để kiểm tra lỗi thu thập. Sự cố và lịch sử vẫn được giữ lại."
                : "Làm mới để kiểm tra trạng thái. Backend chỉ hiển thị các đoạn log đã được lưu."
            }
          />
          {logs.errorCode && <p className="error-code log-error-code">{logs.errorCode}</p>}
        </>
      ) : !content ? (
        <EmptyState
          icon={ScanText}
          title="Log không có nội dung"
          description="Không có đoạn log có thể hiển thị cho sự cố này."
        />
      ) : (
        <>
          <div className="log-toolbar">
            <label>
              <span className="sr-only">Tìm trong log</span>
              <input
                type="search"
                aria-label="Tìm trong log"
                placeholder="Tìm chuỗi trong log…"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
              />
            </label>
            <Button variant="outline" onClick={copy}>
              <Copy size={15} />
              {copied ? "Đã sao chép" : "Sao chép log"}
            </Button>
          </div>
          {copyError && (
            <div className="panel-error">
              <ErrorNotice message={copyError} />
            </div>
          )}
          {query && (
            <p className="log-search-status" role="status">
              {content.includes(query)
                ? "Đã đánh dấu chuỗi khớp trong log."
                : "Không tìm thấy chuỗi trong log hiện tại."}
            </p>
          )}
          <div className="log-terminal" ref={viewer} tabIndex={0} aria-label="Nội dung log đã che">
            <pre className="log-gutter" aria-hidden="true">
              {content
                .split("\n")
                .map((_, index) => index + 1)
                .join("\n")}
            </pre>
            <pre className="log-content">
              <code>{highlightText(content, query)}</code>
            </pre>
          </div>
          <div className="log-safety">
            <ShieldCheck size={16} />
            <span>
              Log đã qua quy tắc che dữ liệu của backend. Bạn vẫn cần kiểm tra nội dung trước khi
              chia sẻ.
            </span>
          </div>
          <details className="log-provenance">
            <summary>Nguồn dữ liệu và dấu kiểm tra</summary>
            {logs.snippets.map((snippet) => (
              <dl key={snippet.chunkIndex}>
                <div>
                  <dt>Đoạn</dt>
                  <dd>{snippet.chunkIndex + 1}</dd>
                </div>
                <div>
                  <dt>Đã thu thập</dt>
                  <dd>{dateTime(snippet.capturedAt)}</dd>
                </div>
                <div>
                  <dt>SHA-256 nội dung đã che</dt>
                  <dd>
                    <code>{snippet.contentSha256}</code>
                  </dd>
                </div>
              </dl>
            ))}
          </details>
        </>
      )}
    </section>
  );
}
