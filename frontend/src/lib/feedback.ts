import type { FeedbackReason } from "./contracts";

export const feedbackReasons: Record<FeedbackReason, string> = {
  CORRECT_DIAGNOSIS: "Chẩn đoán đúng",
  ACTIONABLE_FIX: "Hướng sửa có thể áp dụng",
  USEFUL_EVIDENCE: "Bằng chứng hữu ích",
  INCORRECT_DIAGNOSIS: "Chẩn đoán sai",
  INSUFFICIENT_EVIDENCE: "Thiếu bằng chứng",
  UNSAFE_SUGGESTION: "Đề xuất thiếu an toàn",
  NOT_A_REAL_INCIDENT: "Không phải sự cố thực tế",
  OTHER: "Lý do khác",
};

export function reasonsFor(helpful: boolean): FeedbackReason[] {
  return helpful
    ? ["CORRECT_DIAGNOSIS", "ACTIONABLE_FIX", "USEFUL_EVIDENCE", "OTHER"]
    : [
        "INCORRECT_DIAGNOSIS",
        "INSUFFICIENT_EVIDENCE",
        "UNSAFE_SUGGESTION",
        "NOT_A_REAL_INCIDENT",
        "OTHER",
      ];
}
