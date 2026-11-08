import type { IncidentStatus } from "./contracts";

export const incidentLabels: Record<IncidentStatus, string> = {
  OPEN: "Mở",
  INVESTIGATING: "Đang điều tra",
  RESOLVED: "Đã xử lý",
  IGNORED: "Bỏ qua",
};
export const statusTransitions: Record<IncidentStatus, IncidentStatus[]> = {
  OPEN: ["INVESTIGATING", "IGNORED"],
  INVESTIGATING: ["OPEN", "RESOLVED", "IGNORED"],
  RESOLVED: ["INVESTIGATING"],
  IGNORED: ["OPEN"],
};

export function shortId(id: string) {
  return id.slice(0, 8);
}

export function dateTime(value: string | null) {
  if (!value) return "Chưa có";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Chưa xác định";
  return new Intl.DateTimeFormat("vi-VN", { dateStyle: "short", timeStyle: "short" }).format(date);
}

export function duration(start: string | null, end: string | null) {
  if (!start || !end) return "—";
  const seconds = Math.max(0, Math.round((Date.parse(end) - Date.parse(start)) / 1000));
  if (!Number.isFinite(seconds)) return "—";
  return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

export function githubUrl(url: string | null) {
  if (!url) return undefined;
  try {
    const parsed = new URL(url);
    return parsed.protocol === "https:" &&
      parsed.hostname === "github.com" &&
      !parsed.username &&
      !parsed.password
      ? parsed.href
      : undefined;
  } catch {
    return undefined;
  }
}
