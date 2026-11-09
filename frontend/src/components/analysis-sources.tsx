"use client";

import Link from "next/link";
import { ArrowRight, BookOpen, ScanText } from "lucide-react";
import type { Analysis, AnalysisEvidence } from "@/lib/contracts";
import { useResource } from "@/lib/use-resource";
import { useWorkspace } from "./workspace-provider";
import { ErrorNotice, Loading } from "./ui/states";

export function AnalysisSources({
  analysis,
  onLog,
}: {
  analysis: Analysis;
  onLog: (quote: string) => void;
}) {
  const { selectedId } = useWorkspace();
  const sources = useResource<AnalysisEvidence[]>(
    analysis.status === "SUCCEEDED"
      ? `/api/backend/workspaces/${selectedId}/incidents/${analysis.incidentId}/analyses/${analysis.id}/evidence`
      : null,
  );
  return (
    <>
      {sources.loading && <Loading label="Đang tải nguồn trích dẫn…" />}
      {sources.error && (
        <div className="panel-error">
          <ErrorNotice message={`Chưa tải được nguồn: ${sources.error}`} retry={sources.reload} />
        </div>
      )}
      {sources.data?.length ? (
        <ol className="evidence-list">
          {sources.data.map((item, index) => (
            <li key={item.id}>
              <span className="evidence-number">[{index + 1}]</span>
              <p className="knowledge-evidence-label">
                {item.sourceType === "KNOWLEDGE" ? <BookOpen size={14} /> : <ScanText size={14} />}
                {item.sourceType === "KNOWLEDGE"
                  ? `Knowledge · bản nội dung ${item.articleVersion}`
                  : `Log · đoạn ${item.logChunkIndex ?? "—"}`}
              </p>
              <blockquote>{item.quote}</blockquote>
              {item.sourceType === "KNOWLEDGE" && item.articleId ? (
                <Link
                  className="text-link"
                  href={`/knowledge/${item.articleId}?version=${item.articleVersion}`}
                >
                  Mở bài viết nguồn <ArrowRight size={14} />
                </Link>
              ) : item.sourceType === "LOG" ? (
                <button className="text-link" onClick={() => onLog(item.quote)}>
                  Xem trong log đã che <ArrowRight size={14} />
                </button>
              ) : null}
              <details className="knowledge-source-details">
                <summary>Mã đối chiếu nguồn</summary>
                <code>{item.sourceSha256}</code>
                <p className="field-hint">Mã của đoạn được trích tại thời điểm phân tích.</p>
              </details>
            </li>
          ))}
        </ol>
      ) : analysis.evidence.length ? (
        <>
          {!sources.loading && (
            <p className="knowledge-legacy-note">
              Các câu trích dưới đây chưa có liên kết nguồn được xác nhận
              {sources.error
                ? " do chưa tải được dữ liệu."
                : "; báo cáo cũ có thể chưa lưu nguồn chi tiết."}
            </p>
          )}
          <ol className="evidence-list">
            {analysis.evidence.map((quote, index) => (
              <li key={index}>
                <span className="evidence-number">[{index + 1}]</span>
                <blockquote>{quote}</blockquote>
                <button className="text-link" onClick={() => onLog(quote)}>
                  Xem trong log đã che <ArrowRight size={14} />
                </button>
              </li>
            ))}
          </ol>
        </>
      ) : (
        !sources.loading && (
          <div className="quiet-empty">Chưa có câu trích dẫn cho lần phân tích này.</div>
        )
      )}
    </>
  );
}
