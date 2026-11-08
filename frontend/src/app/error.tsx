"use client";
import { ErrorNotice } from "@/components/ui/states";
export default function ErrorPage({ reset }: { reset: () => void }) {
  return (
    <div className="session-error">
      <h1>Không tải được giao diện</h1>
      <ErrorNotice message="Đã xảy ra lỗi. Vui lòng thử tải lại." retry={reset} />
    </div>
  );
}
