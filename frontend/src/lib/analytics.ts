export interface OperationalDashboard {
  from: string;
  to: string;
  timeZone: string;
  daily: { date: string; incidents: number; successfulRuns: number; failedRuns: number }[];
  statuses: { status: string; count: number }[];
}

export function chartDate(value: string) {
  const [year, month, day] = value.split("-");
  return `${day}/${month}/${year.slice(2)}`;
}
