"use client";

import { useId, useState, type FormEvent } from "react";
import type { Analysis, AnalysisFeedback, FeedbackReason } from "@/lib/contracts";
import { ApiError, api, errorMessage } from "@/lib/api";
import { feedbackReasons, reasonsFor } from "@/lib/feedback";
import { useResource } from "@/lib/use-resource";
import { useWorkspace } from "./workspace-provider";
import { Button } from "./ui/button";
import { ErrorNotice, Loading, SuccessNotice } from "./ui/states";

export function FeedbackPanel({ analysis }: { analysis: Analysis }) {
  const { selectedId } = useWorkspace();
  const path = `/api/backend/workspaces/${selectedId}/incidents/${analysis.incidentId}/analyses/${analysis.id}/feedback`;
  const resource = useResource<{ feedback: AnalysisFeedback | null }>(path);
  const [success, setSuccess] = useState("");
  return (
    <section className="panel feedback-panel" aria-label="Đánh giá báo cáo">
      <div className="panel-heading">
        <div>
          <h2>Báo cáo này có hữu ích?</h2>
          <p className="muted">Mỗi người có một đánh giá và có thể cập nhật sau khi kiểm chứng.</p>
        </div>
      </div>
      {success && <SuccessNotice>{success}</SuccessNotice>}
      {resource.error ? (
        <ErrorNotice message={resource.error} retry={resource.reload} />
      ) : !resource.data ? (
        <Loading label="Đang tải đánh giá…" />
      ) : (
        <FeedbackEditor
          key={`${path}:${resource.data.feedback?.version ?? 0}`}
          path={path}
          feedback={resource.data.feedback}
          reload={resource.reload}
          onSaved={() => {
            setSuccess("Đã lưu đánh giá của bạn.");
            resource.reload();
          }}
        />
      )}
    </section>
  );
}

function FeedbackEditor({
  path,
  feedback,
  reload,
  onSaved,
}: {
  path: string;
  feedback: AnalysisFeedback | null;
  reload: () => void;
  onSaved: () => void;
}) {
  const [helpful, setHelpful] = useState<boolean | null>(feedback?.helpful ?? null);
  const fieldId = useId();
  const [reason, setReason] = useState<FeedbackReason>(feedback?.reason ?? "OTHER");
  const [comment, setComment] = useState(feedback?.comment ?? "");
  const [busy, setBusy] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [error, setError] = useState("");

  async function save(event: FormEvent) {
    event.preventDefault();
    if (helpful === null || busy || conflict) return;
    setBusy(true);
    setError("");
    try {
      await api<AnalysisFeedback>(path, {
        method: "PUT",
        body: JSON.stringify({ helpful, reason, comment, version: feedback?.version ?? 0 }),
      });
      onSaved();
    } catch (failure) {
      setError(errorMessage(failure));
      if (failure instanceof ApiError && failure.status === 409) setConflict(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="feedback-form" onSubmit={save}>
      {error && <ErrorNotice message={error} />}
      <fieldset disabled={busy || conflict}>
        <legend>Đánh giá mức hữu ích</legend>
        <div className="feedback-votes">
          {[true, false].map((value) => (
            <label key={String(value)}>
              <input
                type="radio"
                name="helpful"
                checked={helpful === value}
                required
                onChange={() => {
                  setHelpful(value);
                  setReason("OTHER");
                }}
              />
              {value ? "Hữu ích" : "Chưa hữu ích"}
            </label>
          ))}
        </div>
      </fieldset>
      <div className="feedback-field">
        <label htmlFor={`${fieldId}-reason`}>Lý do đánh giá</label>
        <select
          id={`${fieldId}-reason`}
          value={reason}
          disabled={helpful === null || busy || conflict}
          onChange={(event) => setReason(event.target.value as FeedbackReason)}
        >
          {reasonsFor(helpful ?? true).map((value) => (
            <option key={value} value={value}>
              {feedbackReasons[value]}
            </option>
          ))}
        </select>
      </div>
      <div className="feedback-field">
        <label htmlFor={`${fieldId}-comment`}>Nhận xét (không bắt buộc)</label>
        <textarea
          id={`${fieldId}-comment`}
          value={comment}
          maxLength={2000}
          rows={3}
          disabled={busy || conflict}
          onChange={(event) => setComment(event.target.value)}
          aria-describedby={`${fieldId}-hint`}
        />
      </div>
      <p id={`${fieldId}-hint`} className="field-hint">
        Đánh giá chỉ phản ánh nhận xét của bạn; không tự thay đổi trạng thái sự cố.
      </p>
      <div className="toolbar-actions">
        <Button type="submit" disabled={helpful === null || busy || conflict}>
          {busy ? "Đang lưu…" : "Lưu đánh giá"}
        </Button>
        {conflict && (
          <Button type="button" variant="outline" onClick={reload}>
            Tải lại đánh giá
          </Button>
        )}
      </div>
    </form>
  );
}
