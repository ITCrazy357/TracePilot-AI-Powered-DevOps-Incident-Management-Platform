"use client";

import { useState } from "react";
import { ChartNoAxesCombined } from "lucide-react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { chartDate } from "@/lib/analytics";
import { tokenNumber, usageUsd, type UsageDashboard, type UsageTotals } from "@/lib/usage";
import { useResource } from "@/lib/use-resource";
import { useWorkspace } from "./workspace-provider";
import { WorkspaceBoundary } from "./workspace-boundary";
import { PageHeading } from "./ui/operational";
import { Button } from "./ui/button";
import { EmptyState, ErrorNotice, Loading } from "./ui/states";

export function AiUsage() {
  const { selectedId } = useWorkspace();
  return (
    <WorkspaceBoundary>
      <PageHeading
        title="Tiêu thụ AI"
        description="Theo dõi token đã ghi nhận, lượt gọi và chi phí ước tính của workspace."
      />
      <UsageContent key={selectedId} />
    </WorkspaceBoundary>
  );
}

function UsageContent() {
  const { selectedId } = useWorkspace();
  const [days, setDays] = useState(30);
  const [operation, setOperation] = useState("");
  const [model, setModel] = useState("");
  const query = new URLSearchParams({ days: String(days) });
  if (operation) query.set("operation", operation);
  if (model) query.set("model", model);
  const resource = useResource<UsageDashboard>(
    `/api/backend/workspaces/${selectedId}/ai-usage?${query}`,
  );
  const data = resource.data;
  return (
    <div className="usage-content">
      <div className="usage-filters">
        <label>
          Khoảng thời gian tiêu thụ
          <select
            value={days}
            onChange={(event) => {
              setDays(Number(event.target.value));
              setModel("");
            }}
          >
            {[7, 30, 90].map((value) => (
              <option key={value} value={value}>
                {value} ngày
              </option>
            ))}
          </select>
        </label>
        <label>
          Loại lượt gọi
          <select
            value={operation}
            onChange={(event) => {
              setOperation(event.target.value);
              setModel("");
            }}
          >
            <option value="">CHAT và EMBEDDING</option>
            <option value="CHAT">CHAT — phân tích lỗi</option>
            <option value="EMBEDDING">EMBEDDING — tạo chỉ mục, tra cứu</option>
          </select>
        </label>
        <label>
          Mô hình sử dụng
          <select value={model} onChange={(event) => setModel(event.target.value)}>
            <option value="">Tất cả mô hình</option>
            {(data?.models ?? (model ? [model] : [])).map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
        <Button variant="outline" disabled={resource.loading} onClick={resource.reload}>
          Làm mới tiêu thụ
        </Button>
      </div>
      {resource.error ? (
        <ErrorNotice message={resource.error} retry={resource.reload} />
      ) : !data ? (
        <Loading label="Đang tổng hợp tiêu thụ AI…" />
      ) : (
        <>
          <p className="field-hint">
            {days} ngày lịch theo UTC, gồm hôm nay đến hiện tại. Mỗi attempt được tính là một lượt,
            gồm cả embedding không gắn báo cáo.
          </p>
          <UsageSummary totals={data.totals} />
          {data.totals.calls > 0 &&
            (data.totals.inputSamples < data.totals.calls ||
              data.totals.outputSamples < data.totals.calls ||
              data.totals.pricedCalls < data.totals.calls) && (
              <p className="notice notice-partial" role="status">
                Dữ liệu chưa đầy đủ. Token và chi phí chỉ cộng phần đã ghi nhận; phần còn thiếu
                không được coi là 0. Lượt chưa rõ kết quả vẫn có thể phát sinh phí.
              </p>
            )}
          {!data.totals.calls ? (
            <section className="panel">
              <EmptyState
                icon={ChartNoAxesCombined}
                title="Chưa có lượt gọi AI trong phạm vi này"
                description="Thử đổi khoảng thời gian hoặc bỏ bộ lọc. Số liệu xuất hiện sau khi backend ghi nhận lượt gọi AI."
              />
            </section>
          ) : (
            <>
              <UsageCharts daily={data.daily} />
              <section className="panel">
                <div className="panel-heading">
                  <h2>Theo mô hình và loại lượt gọi</h2>
                </div>
                <div
                  className="table-scroll usage-table"
                  tabIndex={0}
                  role="region"
                  aria-label="Số liệu mô hình có thể cuộn"
                >
                  <table>
                    <caption>Token và chi phí đã ghi nhận theo mô hình</caption>
                    <thead>
                      <tr>
                        <th scope="col">Mô hình / loại</th>
                        <th scope="col">Lượt gọi</th>
                        <th scope="col">Đầu vào</th>
                        <th scope="col">Đầu ra</th>
                        <th scope="col">Có số liệu vào / ra</th>
                        <th scope="col">Chi phí ước tính</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.byModel.map((item) => (
                        <tr key={`${item.operation}:${item.provider}:${item.model}`}>
                          <th scope="row">
                            <span className="model-label">{item.model}</span>
                            <small>
                              {item.provider} · {item.operation}
                            </small>
                          </th>
                          <td>
                            {item.usage.calls}
                            <small>{item.usage.unknown} chưa rõ</small>
                          </td>
                          <td>{tokenNumber(item.usage.inputTokens)}</td>
                          <td>{tokenNumber(item.usage.outputTokens)}</td>
                          <td>
                            {item.usage.inputSamples}/{item.usage.calls} ·{" "}
                            {item.usage.outputSamples}/{item.usage.calls}
                          </td>
                          <td>
                            {usageUsd(item.usage.knownEstimatedCostUsd)}
                            <small>
                              {item.usage.pricedCalls}/{item.usage.calls} lượt có ước tính
                            </small>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            </>
          )}
          <p className="field-hint">
            Chi phí dùng mức giá đã cấu hình tại thời điểm gọi và dữ liệu token đã ghi nhận. Đây là
            ước tính, không phải hóa đơn của nhà cung cấp. Chỉ xem số liệu không gửi yêu cầu AI mới.
          </p>
        </>
      )}
    </div>
  );
}

function UsageSummary({ totals }: { totals: UsageTotals }) {
  const coverage = (samples: number) =>
    totals.calls ? `${samples}/${totals.calls} lượt có dữ liệu` : "Chưa có lượt gọi";
  return (
    <dl className="usage-summary">
      <div>
        <dt>Token đầu vào đã ghi nhận</dt>
        <dd>
          <span>{tokenNumber(totals.inputTokens)}</span>
          <small>{coverage(totals.inputSamples)}</small>
        </dd>
      </div>
      <div>
        <dt>Token đầu ra đã ghi nhận</dt>
        <dd>
          <span>{tokenNumber(totals.outputTokens)}</span>
          <small>{coverage(totals.outputSamples)}</small>
        </dd>
      </div>
      <div>
        <dt>Lượt gọi AI</dt>
        <dd>
          <span>{tokenNumber(totals.calls)}</span>
          <small>
            {totals.succeeded} thành công · {totals.failed} lỗi · {totals.unknown} chưa rõ
          </small>
        </dd>
      </div>
      <div>
        <dt>Chi phí đã có ước tính</dt>
        <dd>
          <span>{usageUsd(totals.knownEstimatedCostUsd)}</span>
          <small>{coverage(totals.pricedCalls)}</small>
        </dd>
      </div>
    </dl>
  );
}

function UsageCharts({ daily }: { daily: UsageDashboard["daily"] }) {
  const rows = daily.map((day) => ({
    date: day.date,
    input: day.usage.inputTokens,
    output: day.usage.outputTokens,
    cost: day.usage.knownEstimatedCostUsd,
  }));
  return (
    <>
      <div className="charts-grid">
        <section className="panel">
          <div className="panel-heading">
            <h2>Token đã ghi nhận theo ngày</h2>
          </div>
          <figure className="chart-canvas" aria-label="Biểu đồ token đầu vào và đầu ra">
            <ResponsiveContainer
              width="100%"
              height={300}
              minWidth={0}
              initialDimension={{ width: 600, height: 300 }}
            >
              <BarChart data={rows} accessibilityLayer margin={{ left: -10, right: 12 }}>
                <CartesianGrid vertical={false} stroke="var(--line)" />
                <XAxis
                  dataKey="date"
                  tickFormatter={chartDate}
                  minTickGap={30}
                  tick={{ fontSize: 11 }}
                />
                <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
                <Tooltip labelFormatter={(value) => chartDate(String(value))} />
                <Legend wrapperStyle={{ fontSize: 12 }} />
                <Bar
                  dataKey="input"
                  stackId="tokens"
                  name="Đầu vào"
                  fill="var(--chart-blue)"
                  isAnimationActive={false}
                />
                <Bar
                  dataKey="output"
                  stackId="tokens"
                  name="Đầu ra"
                  fill="var(--chart-pink)"
                  isAnimationActive={false}
                />
              </BarChart>
            </ResponsiveContainer>
          </figure>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <h2>Chi phí đã có ước tính theo ngày</h2>
          </div>
          <figure className="chart-canvas" aria-label="Biểu đồ chi phí ước tính theo ngày">
            <ResponsiveContainer
              width="100%"
              height={300}
              minWidth={0}
              initialDimension={{ width: 360, height: 300 }}
            >
              <LineChart data={rows} accessibilityLayer margin={{ left: 0, right: 12 }}>
                <CartesianGrid vertical={false} stroke="var(--line)" />
                <XAxis
                  dataKey="date"
                  tickFormatter={chartDate}
                  minTickGap={40}
                  tick={{ fontSize: 11 }}
                />
                <YAxis
                  width={82}
                  tickFormatter={(value) => usageUsd(Number(value))}
                  tick={{ fontSize: 10 }}
                />
                <Tooltip
                  labelFormatter={(value) => chartDate(String(value))}
                  formatter={(value) => usageUsd(typeof value === "number" ? value : null)}
                />
                <Line
                  dataKey="cost"
                  name="USD ước tính"
                  stroke="var(--chart-green)"
                  strokeWidth={2}
                  dot={false}
                  connectNulls={false}
                  isAnimationActive={false}
                />
              </LineChart>
            </ResponsiveContainer>
          </figure>
        </section>
      </div>
      <details className="panel chart-data">
        <summary>Xem token và chi phí từng ngày</summary>
        <div
          className="table-scroll usage-table"
          tabIndex={0}
          role="region"
          aria-label="Số liệu tiêu thụ từng ngày có thể cuộn"
        >
          <table>
            <caption>Token, lượt gọi và chi phí theo ngày UTC</caption>
            <thead>
              <tr>
                <th scope="col">Ngày</th>
                <th scope="col">Lượt gọi</th>
                <th scope="col">Token đầu vào</th>
                <th scope="col">Token đầu ra</th>
                <th scope="col">Có số liệu vào / ra</th>
                <th scope="col">USD ước tính</th>
              </tr>
            </thead>
            <tbody>
              {daily.map((day) => (
                <tr key={day.date}>
                  <th scope="row">{chartDate(day.date)}</th>
                  <td>{day.usage.calls}</td>
                  <td>{tokenNumber(day.usage.inputTokens)}</td>
                  <td>{tokenNumber(day.usage.outputTokens)}</td>
                  <td>
                    {day.usage.inputSamples}/{day.usage.calls} · {day.usage.outputSamples}/
                    {day.usage.calls}
                  </td>
                  <td>{usageUsd(day.usage.knownEstimatedCostUsd)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </>
  );
}
