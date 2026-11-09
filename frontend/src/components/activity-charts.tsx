"use client";

import { useState } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { OperationalDashboard } from "@/lib/analytics";
import { chartDate } from "@/lib/analytics";
import { useResource } from "@/lib/use-resource";
import { incidentLabels } from "@/lib/presentation";
import type { IncidentStatus } from "@/lib/contracts";
import { useWorkspace } from "./workspace-provider";
import { Button } from "./ui/button";
import { ErrorNotice, Loading } from "./ui/states";

export function ActivityCharts() {
  const { selectedId } = useWorkspace();
  const [days, setDays] = useState(30);
  const resource = useResource<OperationalDashboard>(
    `/api/backend/workspaces/${selectedId}/dashboard?days=${days}`,
  );
  const data = resource.data;
  const active = data?.daily.some((day) => day.incidents + day.successfulRuns + day.failedRuns > 0);
  const statuses = data?.statuses.map((item) => ({
    ...item,
    label: incidentLabels[item.status as IncidentStatus] ?? item.status,
  }));
  return (
    <section className="activity-analytics" aria-labelledby="activity-charts-heading">
      <div className="analytics-toolbar">
        <h2 id="activity-charts-heading">Hoạt động workspace</h2>
        <div className="toolbar-actions">
          <label>
            Khoảng thời gian biểu đồ
            <select value={days} onChange={(event) => setDays(Number(event.target.value))}>
              {[7, 30, 90].map((value) => (
                <option key={value} value={value}>
                  {value} ngày
                </option>
              ))}
            </select>
          </label>
          <Button variant="outline" disabled={resource.loading} onClick={resource.reload}>
            Làm mới biểu đồ
          </Button>
        </div>
      </div>
      {resource.error ? (
        <ErrorNotice message={resource.error} retry={resource.reload} />
      ) : !data ? (
        <Loading label="Đang tổng hợp hoạt động…" />
      ) : (
        <>
          <p className="field-hint">
            {days} ngày lịch theo UTC, gồm hôm nay đến hiện tại. Run tính theo ngày ghi nhận; sự cố
            tính theo ngày phát hiện. Các nhóm có thể trùng nhau.
          </p>
          <div className="charts-grid">
            <section className="panel">
              <div className="panel-heading">
                <h3>Lần chạy và sự cố theo ngày</h3>
              </div>
              {!active ? (
                <p className="quiet-empty">Chưa có hoạt động phù hợp trong khoảng thời gian này.</p>
              ) : (
                <figure
                  className="chart-canvas"
                  aria-label="Biểu đồ số lần chạy và sự cố theo ngày"
                >
                  <ResponsiveContainer
                    width="100%"
                    height={300}
                    minWidth={0}
                    initialDimension={{ width: 600, height: 300 }}
                  >
                    <ComposedChart
                      data={data.daily}
                      accessibilityLayer
                      margin={{ top: 12, right: 10, left: -20, bottom: 8 }}
                    >
                      <CartesianGrid vertical={false} stroke="var(--line)" />
                      <XAxis
                        dataKey="date"
                        tickFormatter={chartDate}
                        minTickGap={24}
                        tick={{ fontSize: 11 }}
                      />
                      <YAxis allowDecimals={false} tick={{ fontSize: 11 }} />
                      <Tooltip labelFormatter={(value) => chartDate(String(value))} />
                      <Legend wrapperStyle={{ fontSize: 12 }} />
                      <Bar
                        dataKey="successfulRuns"
                        stackId="runs"
                        name="Run thành công"
                        fill="var(--chart-green)"
                        isAnimationActive={false}
                        radius={[3, 3, 0, 0]}
                      />
                      <Bar
                        dataKey="failedRuns"
                        stackId="runs"
                        name="Run thất bại"
                        fill="var(--chart-pink)"
                        isAnimationActive={false}
                        radius={[3, 3, 0, 0]}
                      />
                      <Line
                        dataKey="incidents"
                        name="Sự cố"
                        stroke="var(--chart-blue)"
                        strokeWidth={2}
                        dot={false}
                        isAnimationActive={false}
                      />
                    </ComposedChart>
                  </ResponsiveContainer>
                </figure>
              )}
              <details className="chart-data">
                <summary>Xem số liệu theo ngày</summary>
                <div className="table-scroll">
                  <table>
                    <caption>Lần chạy và sự cố theo ngày UTC</caption>
                    <thead>
                      <tr>
                        <th scope="col">Ngày</th>
                        <th scope="col">Run thành công</th>
                        <th scope="col">Run thất bại</th>
                        <th scope="col">Sự cố</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.daily.map((day) => (
                        <tr key={day.date}>
                          <th scope="row">{chartDate(day.date)}</th>
                          <td>{day.successfulRuns}</td>
                          <td>{day.failedRuns}</td>
                          <td>{day.incidents}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </details>
            </section>
            <section className="panel">
              <div className="panel-heading">
                <h3>Trạng thái sự cố</h3>
              </div>
              <p className="chart-caption">
                Trạng thái hiện tại của các sự cố phát hiện trong khoảng đã chọn.
              </p>
              {!statuses?.length ? (
                <p className="quiet-empty">Chưa ghi nhận sự cố.</p>
              ) : (
                <>
                  <figure className="chart-canvas" aria-label="Biểu đồ phân bố trạng thái sự cố">
                    <ResponsiveContainer
                      width="100%"
                      height={230}
                      minWidth={0}
                      initialDimension={{ width: 320, height: 230 }}
                    >
                      <BarChart
                        layout="vertical"
                        data={statuses}
                        accessibilityLayer
                        margin={{ left: 0, right: 20 }}
                      >
                        <CartesianGrid horizontal={false} stroke="var(--line)" />
                        <XAxis type="number" allowDecimals={false} />
                        <YAxis
                          type="category"
                          dataKey="label"
                          width={100}
                          tick={{ fontSize: 11 }}
                        />
                        <Tooltip />
                        <Bar
                          dataKey="count"
                          name="Sự cố"
                          fill="var(--chart-blue)"
                          isAnimationActive={false}
                          radius={[0, 3, 3, 0]}
                        />
                      </BarChart>
                    </ResponsiveContainer>
                  </figure>
                  <dl className="chart-status-list">
                    {statuses.map((item) => (
                      <div key={item.status}>
                        <dt>{item.label}</dt>
                        <dd>{item.count}</dd>
                      </div>
                    ))}
                  </dl>
                </>
              )}
            </section>
          </div>
        </>
      )}
    </section>
  );
}
