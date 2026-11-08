import { Activity } from "lucide-react";

export function Brand() {
  return (
    <span className="brand">
      <span className="brand-mark">
        <Activity size={24} strokeWidth={2.4} />
      </span>
      <span>
        Trace<span className="brand-light">Pilot</span>
        <span className="brand-dot">.</span>
      </span>
    </span>
  );
}
