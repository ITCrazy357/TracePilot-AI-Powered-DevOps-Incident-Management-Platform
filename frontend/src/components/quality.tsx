"use client";

import { useState } from "react";
import Link from "next/link";
import { RefreshCw } from "lucide-react";
import type { QualityDashboard, QualityUsage } from "@/lib/contracts";
import { feedbackReasons } from "@/lib/feedback";
import { dateTime } from "@/lib/presentation";
import { useResource } from "@/lib/use-resource";
import { useWorkspace } from "./workspace-provider";
import { WorkspaceBoundary } from "./workspace-boundary";
import { Button } from "./ui/button";
import { PageHeading } from "./ui/operational";
import { ErrorNotice, Loading } from "./ui/states";

const percent = (value: number | null) =>
  value === null
    ? "Chưa có dữ liệu"
    : new Intl.NumberFormat("vi-VN", { style: "percent", maximumFractionDigits: 1 }).format(value);
const milliseconds = (value: number | null) =>
  value === null
    ? "Chưa có dữ liệu"
    : `${new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 0 }).format(value)} ms`;
const tokens = (value: number | null) =>
  value === null ? "Chưa có" : value.toLocaleString("vi-VN");

export function Quality() {
  const { selectedId } = useWorkspace();
  return (
    <>
      <PageHeading
        title="Chất lượng AI"
        description="Đối chiếu kết quả xử lý, đánh giá của đội ngũ và chi phí ghi nhận."
        action={
          <Button asChild variant="outline">
            <Link href="/usage">Xem tiêu thụ token</Link>
          </Button>
        }
      />
      <WorkspaceBoundary>
        <WorkspaceQuality key={selectedId} />
      </WorkspaceBoundary>
    </>
  );
}

function WorkspaceQuality() {
  const { selectedId } = useWorkspace();
  const [days, setDays] = useState(30);
  const resource = useResource<QualityDashboard>(
    `/api/backend/workspaces/${selectedId}/quality?days=${days}`,
  );
  return (
    <div className="quality-content">
      <div className="quality-toolbar">
        <div className="quality-range">
          <label htmlFor="quality-days">Khoảng thời gian</label>
          <select
            id="quality-days"
            value={days}
            onChange={(event) => setDays(Number(event.target.value))}
          >
            {[7, 30, 90].map((value) => (
              <option key={value} value={value}>
                {value} ngày qua
              </option>
            ))}
          </select>
        </div>
        <Button variant="outline" onClick={resource.reload} disabled={resource.loading}>
          <RefreshCw size={16} />
          Làm mới
        </Button>
      </div>
      {resource.error ? (
        <ErrorNotice message={resource.error} retry={resource.reload} />
      ) : !resource.data ? (
        <Loading label="Đang tổng hợp chất lượng AI…" />
      ) : (
        <QualityReport data={resource.data} />
      )}
    </div>
  );
}

function QualityReport({ data }: { data: QualityDashboard }) {
  const { analyses, feedback } = data;
  return (
    <>
      <p className="field-hint">
        Từ {dateTime(data.from)} đến {dateTime(data.to)}.
      </p>
      <div className="quality-cards">
        <Metric
          label="Xử lý thành công"
          value={percent(analyses.successRate)}
          detail={`${analyses.succeeded} thành công / ${analyses.succeeded + analyses.failed} đã kết thúc`}
        />
        <Metric
          label="Developer thấy hữu ích"
          value={percent(feedback.helpfulRate)}
          detail={`${feedback.helpful} hữu ích / ${feedback.votes} đánh giá`}
        />
        <Metric
          label="Báo cáo đã được đánh giá"
          value={percent(feedback.reviewCoverage)}
          detail={`${feedback.reviewedAnalyses} / ${analyses.succeeded} báo cáo thành công`}
        />
        <Metric
          label="Thời gian hoàn tất p95"
          value={milliseconds(analyses.p95LatencyMs)}
          detail={`Trung bình: ${milliseconds(analyses.averageLatencyMs)}`}
        />
      </div>
      <p className="quality-explanation">
        {analyses.total} phân tích · {analyses.pending} đang chờ · {analyses.running} đang chạy ·{" "}
        {analyses.failed} thất bại. Thời gian hoàn tất tính từ lúc tạo yêu cầu, gồm chờ hàng đợi và
        thử lại.
      </p>
      <div className="quality-grid">
        <section className="panel">
          <div className="panel-heading">
            <h2>Lý do đánh giá thường gặp</h2>
          </div>
          <div className="quality-panel-body">
            {data.reasons.length ? (
              <ul className="quality-reasons">
                {data.reasons.map((item) => (
                  <li key={`${item.reason}:${item.helpful}`}>
                    <div>
                      <span>
                        {feedbackReasons[item.reason]} · {item.helpful ? "Hữu ích" : "Chưa hữu ích"}
                      </span>
                      <strong>{item.count}</strong>
                    </div>
                    <div className="quality-bar" aria-hidden="true">
                      <span style={{ width: `${(item.count / feedback.votes) * 100}%` }} />
                    </div>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="muted">Chưa có đánh giá trong khoảng này.</p>
            )}
            <p className="field-hint">
              Mỗi người có một phiếu trên mỗi báo cáo. “Không phải sự cố thực tế” là nhãn do người
              dùng nhận định; chưa đủ để tính độ chính xác hoặc tỷ lệ cảnh báo nhầm.
            </p>
          </div>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <h2>Lỗi phân tích thường gặp</h2>
          </div>
          <div className="quality-panel-body">
            {data.errors.length ? (
              <ul className="quality-errors">
                {data.errors.map((item) => (
                  <li key={item.code}>
                    <code>{item.code}</code>
                    <strong>{item.count}</strong>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="muted">Chưa ghi nhận phân tích thất bại trong khoảng này.</p>
            )}
          </div>
        </section>
      </div>
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>Token, chi phí & thời gian lượt gọi</h2>
            <p className="muted">
              Theo thời điểm ghi nhận lượt gọi; gồm chat và embedding độc lập.
            </p>
          </div>
        </div>
        <div className="quality-panel-body">
          <p className="field-hint">
            Chi phí là phần ước tính từ đơn giá cấu hình, chưa phải hóa đơn. Mẫu thiếu token hoặc
            chi phí được giữ là chưa biết. Thời gian lượt gọi có thể chồng lặp; không cộng để suy ra
            thời gian hoàn tất.
          </p>
          {data.usage.length ? (
            <div className="quality-usage-grid">
              {data.usage.map((item) => (
                <Usage key={`${item.operation}:${item.provider}:${item.model}`} item={item} />
              ))}
            </div>
          ) : (
            <p className="muted">Chưa có lượt gọi AI trong khoảng này.</p>
          )}
        </div>
      </section>
      <p className="field-hint">
        Kết quả và đánh giá chọn theo ngày tạo phân tích; phiếu sửa thay thế phiếu trước. Tỷ lệ hữu
        ích là phản hồi tự nguyện, có thể thiên lệch so với toàn bộ báo cáo.
      </p>
    </>
  );
}

function Metric({ label, value, detail }: { label: string; value: string; detail: string }) {
  return (
    <section className="quality-card">
      <h2>{label}</h2>
      <strong>{value}</strong>
      <p>{detail}</p>
    </section>
  );
}

function Usage({ item }: { item: QualityUsage }) {
  const cost =
    item.knownEstimatedCostUsd === null
      ? "Chưa biết"
      : new Intl.NumberFormat("en-US", {
          style: "currency",
          currency: "USD",
          maximumFractionDigits: 8,
        }).format(item.knownEstimatedCostUsd);
  return (
    <section className="quality-usage">
      <h3>
        {item.operation} · {item.provider}
      </h3>
      <code>{item.model}</code>
      <p className="muted">
        {item.calls} lượt · {item.succeeded} thành công · {item.failed} thất bại · {item.unknown}{" "}
        chưa rõ kết quả
      </p>
      <dl>
        <div>
          <dt>Input tokens đã biết</dt>
          <dd>
            {tokens(item.inputTokens)}{" "}
            <small>
              ({item.inputTokenSamples}/{item.calls} mẫu)
            </small>
          </dd>
        </div>
        <div>
          <dt>Output tokens đã biết</dt>
          <dd>
            {tokens(item.outputTokens)}{" "}
            <small>
              ({item.outputTokenSamples}/{item.calls} mẫu)
            </small>
          </dd>
        </div>
        <div>
          <dt>Chi phí đã ước tính</dt>
          <dd>
            {cost}{" "}
            <small>
              ({item.pricedCalls}/{item.calls} lượt có chi phí)
            </small>
          </dd>
        </div>
        <div>
          <dt>Thời gian trung bình / p95</dt>
          <dd>
            {milliseconds(item.averageDurationMs)} / {milliseconds(item.p95DurationMs)}
          </dd>
        </div>
        <div>
          <dt>Mẫu có thời gian</dt>
          <dd>
            {item.durationSamples}/{item.calls}
          </dd>
        </div>
      </dl>
    </section>
  );
}
