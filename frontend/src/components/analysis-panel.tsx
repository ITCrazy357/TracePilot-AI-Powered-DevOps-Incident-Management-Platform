"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { Sparkles, RefreshCw, Quote, ShieldCheck } from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import type { Analysis, AnalysisUsage, DataPage, Incident, IncidentLogs } from "@/lib/contracts";
import { useResource } from "@/lib/use-resource";
import { dateTime } from "@/lib/presentation";
import { useWorkspace } from "./workspace-provider";
import { Button } from "./ui/button";
import { Dialog } from "./ui/dialog";
import { EmptyState, ErrorNotice, Loading, SuccessNotice } from "./ui/states";
import { Pagination, StatusBadge } from "./ui/operational";
import { FeedbackPanel } from "./feedback-panel";
import { AnalysisSources } from "./analysis-sources";

export function AnalysisPanel({
  incident,
  canAnalyze,
  onEvidence,
}: {
  incident: Incident;
  canAnalyze: boolean;
  onEvidence: (quote: string) => void;
}) {
  const { selectedId } = useWorkspace();
  const base = `/api/backend/workspaces/${selectedId}/incidents/${incident.id}`;
  const [page, setPage] = useState(0);
  const history = useResource<DataPage<Analysis>>(`${base}/analyses?page=${page}&size=20`, {
    retainOnError: true,
  });
  const logs = useResource<IncidentLogs>(`${base}/logs`);
  const [analysisId, setAnalysisId] = useState("");
  const report = useResource<Analysis>(analysisId ? `${base}/analyses/${analysisId}` : null, {
    retainOnError: true,
  });
  const usage = useResource<AnalysisUsage[]>(
    analysisId ? `${base}/analyses/${analysisId}/usage` : null,
  );
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const requestId = useRef<string | null>(null);
  const pending =
    history.data?.items.some((item) => ["PENDING", "RUNNING"].includes(item.status)) ?? false;
  const logsReady =
    logs.data?.status === "READY" && logs.data.snippets.some((snippet) => snippet.content.trim());

  useEffect(() => {
    if (!analysisId && history.data?.items.length) setAnalysisId(history.data.items[0].id);
  }, [history.data, analysisId]);

  async function request() {
    setBusy(true);
    setError("");
    setSuccess("");
    requestId.current ??= crypto.randomUUID();
    try {
      const result = await api<Analysis>(`${base}/analyses`, {
        method: "POST",
        body: JSON.stringify({ requestId: requestId.current }),
      });
      requestId.current = null;
      setAnalysisId(result.id);
      setOpen(false);
      setPage(0);
      history.reload();
      setSuccess("Đã gửi yêu cầu phân tích. Báo cáo sẽ xuất hiện khi xử lý hoàn tất.");
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  function reload() {
    history.reload();
    report.reload();
    usage.reload();
    logs.reload();
  }

  return (
    <>
      <div className="analysis-toolbar">
        <div>
          <h2>Phân tích AI</h2>
          <p className="muted">Giữ lịch sử, kiểm tra bằng chứng, tự quyết định hướng xử lý.</p>
        </div>
        <div className="toolbar-actions">
          <Button variant="outline" onClick={reload}>
            <RefreshCw size={15} />
            Làm mới
          </Button>
          {canAnalyze && (
            <Button
              disabled={busy || pending || !logsReady || history.loading || Boolean(history.error)}
              onClick={() => {
                setError("");
                setOpen(true);
              }}
            >
              <Sparkles size={16} />
              {pending ? "Đang chờ phân tích" : "Yêu cầu phân tích"}
            </Button>
          )}
        </div>
      </div>
      {success && <SuccessNotice>{success}</SuccessNotice>}
      {history.error && history.data && (
        <ErrorNotice
          message={`Chưa cập nhật được lịch sử phân tích. ${history.error}`}
          retry={history.reload}
        />
      )}
      {report.error && report.data && (
        <ErrorNotice
          message={`Chưa cập nhật được báo cáo; đang hiển thị dữ liệu đã tải. ${report.error}`}
          retry={report.reload}
        />
      )}
      {logs.error && (
        <ErrorNotice message={`Không xác nhận được log: ${logs.error}`} retry={logs.reload} />
      )}
      {canAnalyze && !logsReady && !logs.loading && !logs.error && (
        <p className="permission-note">Cần có log READY với nội dung để yêu cầu phân tích.</p>
      )}
      {history.loading && !history.data ? (
        <Loading label="Đang tải lịch sử phân tích…" />
      ) : history.error && !history.data ? (
        <ErrorNotice message={history.error} retry={history.reload} />
      ) : !history.data?.items.length ? (
        <section className="panel analysis-result">
          <EmptyState
            icon={Sparkles}
            title="Chưa có phân tích AI"
            description={
              canAnalyze
                ? "Khi có log phù hợp và AI được cấu hình, bạn có thể yêu cầu một phân tích mới."
                : "Người quản lý hoặc người đang phụ trách có thể yêu cầu phân tích khi log sẵn sàng."
            }
          />
        </section>
      ) : (
        <>
          <div className="analysis-history-picker">
            <label>
              Lần phân tích
              <select value={analysisId} onChange={(event) => setAnalysisId(event.target.value)}>
                {history.data.items.map((item) => (
                  <option key={item.id} value={item.id}>
                    {dateTime(item.createdAt)} · {item.model} · {item.status}
                  </option>
                ))}
              </select>
            </label>
            <Pagination
              page={page}
              size={20}
              total={history.data.total}
              loading={history.loading}
              onPage={(value) => {
                setPage(value);
                setAnalysisId("");
              }}
            />
          </div>
          {report.loading && !report.data ? (
            <Loading label="Đang tải báo cáo…" />
          ) : report.error && !report.data ? (
            <ErrorNotice message={report.error} retry={report.reload} />
          ) : (
            report.data && (
              <Report
                analysis={report.data}
                usage={usage.data}
                usageError={usage.error}
                retryUsage={usage.reload}
                onEvidence={onEvidence}
              />
            )
          )}
        </>
      )}
      <Dialog
        title="Yêu cầu phân tích AI"
        description="Log đã che của sự cố sẽ được gửi đến nhà cung cấp AI đã cấu hình. Thao tác có thể phát sinh chi phí. Bạn cần kiểm tra đề xuất trước khi áp dụng."
        open={open}
        onOpenChange={(value) => {
          if (!busy) setOpen(value);
        }}
      >
        {error && <ErrorNotice message={error} />}
        <div className="notice notice-info">
          <ShieldCheck size={18} />
          <span>
            Báo cáo có thể tham khảo knowledge đã duyệt khi RAG được bật. Không tự chạy lệnh, sửa mã
            hoặc triển khai.
          </span>
        </div>
        <div className="dialog-actions">
          <Button variant="outline" disabled={busy} onClick={() => setOpen(false)}>
            Hủy
          </Button>
          <Button disabled={busy} onClick={request}>
            {busy ? "Đang gửi…" : "Gửi yêu cầu phân tích"}
          </Button>
        </div>
      </Dialog>
    </>
  );
}

function Report({
  analysis,
  usage,
  usageError,
  retryUsage,
  onEvidence,
}: {
  analysis: Analysis;
  usage: AnalysisUsage[] | null;
  usageError: string;
  retryUsage: () => void;
  onEvidence: (quote: string) => void;
}) {
  const completed = analysis.status === "SUCCEEDED";
  return (
    <div className="analysis-report-grid">
      <div className="report-main">
        <section className="panel analysis-result">
          <div className="panel-heading">
            <h2>Kết quả phân tích</h2>
            <div className="toolbar-actions">
              <StatusBadge status={analysis.status} />
              {completed && (
                <Button asChild variant="outline">
                  <Link
                    href={`/knowledge/new?incidentId=${analysis.incidentId}&analysisId=${analysis.id}`}
                  >
                    Thêm vào bản nháp tri thức
                  </Link>
                </Button>
              )}
            </div>
          </div>
          {completed ? (
            <div className="report-sections">
              <section>
                <h3>Tóm tắt</h3>
                <p className="report-copy">{analysis.summary}</p>
              </section>
              <section>
                <h3>Nguyên nhân nghi ngờ</h3>
                <p className="report-copy">{analysis.suspectedRootCause}</p>
              </section>
              <section>
                <h3>Hướng khắc phục đề xuất</h3>
                <p className="report-copy">{analysis.suggestedFix}</p>
              </section>
            </div>
          ) : (
            <div className="analysis-pending">
              <Sparkles size={24} />
              <h3>
                {analysis.status === "FAILED"
                  ? "Phân tích chưa thành công"
                  : "Phân tích đang được xử lý"}
              </h3>
              <p className="muted">
                {analysis.status === "FAILED"
                  ? "Kiểm tra mã lỗi và lịch sử gọi trước khi yêu cầu một lần phân tích mới."
                  : "Bạn có thể tiếp tục xem log. Thông báo sẽ cập nhật khi có kết quả; nút Làm mới luôn sẵn sàng."}
              </p>
              {analysis.errorCode && <code className="error-code">{analysis.errorCode}</code>}
            </div>
          )}
        </section>
        {completed && (
          <>
            <p className="analysis-caveat">
              Đây là đề xuất AI. Câu trích xác nhận nguồn văn bản, không chứng minh kết luận đã
              đúng.
            </p>
            <FeedbackPanel key={analysis.id} analysis={analysis} />
          </>
        )}
      </div>
      <aside className="context-column">
        <section className="panel evidence-panel">
          <div className="panel-heading">
            <h2>
              <Quote size={17} />
              Bằng chứng và nguồn
            </h2>
          </div>
          <AnalysisSources key={analysis.id} analysis={analysis} onLog={onEvidence} />
        </section>
        <details className="panel report-details">
          <summary>Mô hình & lượt gọi</summary>
          <dl className="report-metadata">
            <div>
              <dt>Nhà cung cấp</dt>
              <dd>{analysis.provider}</dd>
            </div>
            <div>
              <dt>Mô hình</dt>
              <dd>
                <code>{analysis.model}</code>
              </dd>
            </div>
            <div>
              <dt>Prompt</dt>
              <dd>
                <code>{analysis.promptVersion}</code>
              </dd>
            </div>
            <div>
              <dt>Tạo lúc</dt>
              <dd>{dateTime(analysis.createdAt)}</dd>
            </div>
            <div>
              <dt>Hoàn tất</dt>
              <dd>{dateTime(analysis.completedAt)}</dd>
            </div>
          </dl>
          {usageError ? (
            <div className="panel-error">
              <ErrorNotice message={usageError} retry={retryUsage} />
            </div>
          ) : usage === null ? (
            <Loading label="Đang tải lượt gọi…" />
          ) : !usage.length ? (
            <div className="quiet-empty">Chưa có lượt gọi được ghi nhận.</div>
          ) : (
            <div className="usage-list">
              {usage.map((item) => (
                <div key={item.id}>
                  <strong>
                    Attempt {item.attempt} <span className="muted">{item.status}</span>
                  </strong>
                  <p className="field-hint">
                    {item.operation ?? "CHAT"} · {item.model ?? analysis.model}
                  </p>
                  <dl>
                    <div>
                      <dt>Input / output tokens</dt>
                      <dd>
                        {item.inputTokens ?? "—"} / {item.outputTokens ?? "—"}
                      </dd>
                    </div>
                    <div>
                      <dt>Chi phí ước tính</dt>
                      <dd>
                        {item.estimatedCostUsd === null
                          ? "Chưa có"
                          : new Intl.NumberFormat("en-US", {
                              style: "currency",
                              currency: "USD",
                              maximumFractionDigits: 6,
                            }).format(item.estimatedCostUsd)}
                      </dd>
                    </div>
                    <div>
                      <dt>Thời gian attempt</dt>
                      <dd>{item.durationMs === null ? "—" : `${item.durationMs} ms`}</dd>
                    </div>
                  </dl>
                  {item.errorCode && <code className="error-code">{item.errorCode}</code>}
                </div>
              ))}
            </div>
          )}
          <div className="panel-footer">
            <Link className="text-link" href="/usage">
              Xem tiêu thụ AI toàn workspace
            </Link>
          </div>
        </details>
      </aside>
    </div>
  );
}
