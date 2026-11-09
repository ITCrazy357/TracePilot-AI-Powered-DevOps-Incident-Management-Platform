export interface UsageTotals {
  calls: number;
  succeeded: number;
  failed: number;
  unknown: number;
  inputTokens: number | null;
  outputTokens: number | null;
  inputSamples: number;
  outputSamples: number;
  knownEstimatedCostUsd: number | null;
  pricedCalls: number;
}

export interface UsageDashboard {
  from: string;
  to: string;
  timeZone: string;
  totals: UsageTotals;
  daily: { date: string; usage: UsageTotals }[];
  byModel: { operation: string; provider: string; model: string; usage: UsageTotals }[];
  models: string[];
}

export const tokenNumber = (value: number | null) =>
  value === null ? "Chưa có" : value.toLocaleString("vi-VN");
export const usageUsd = (value: number | null) =>
  value === null
    ? "Chưa có"
    : new Intl.NumberFormat("en-US", {
        style: "currency",
        currency: "USD",
        maximumFractionDigits: 8,
      }).format(value);
