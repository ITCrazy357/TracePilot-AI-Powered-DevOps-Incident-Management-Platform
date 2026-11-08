"use client";

import { useState, type FormEvent } from "react";
import { Plus } from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import type { Workspace } from "@/lib/contracts";
import { useWorkspace } from "./workspace-provider";
import { Button } from "./ui/button";
import { Dialog } from "./ui/dialog";
import { ErrorNotice } from "./ui/states";

export function CreateWorkspace({ compact = false }: { compact?: boolean }) {
  const { created } = useWorkspace();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const name = String(data.get("name")).trim();
    const slug = String(data.get("slug"));
    if (!name) {
      setError("Nhập tên workspace.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      created(
        await api<Workspace>("/api/backend/workspaces", {
          method: "POST",
          body: JSON.stringify({ name, slug }),
        }),
      );
      setOpen(false);
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button
        variant={compact ? "ghost" : "default"}
        onClick={() => {
          setError("");
          setOpen(true);
        }}
        className={compact ? "create-compact" : undefined}
        aria-label="Tạo workspace"
      >
        <Plus size={17} />
        {!compact && "Tạo workspace"}
      </Button>
      <Dialog
        title="Tạo workspace"
        description="Bạn sẽ là OWNER của workspace mới."
        open={open}
        onOpenChange={(value) => {
          if (!busy) setOpen(value);
        }}
      >
        {error && <ErrorNotice message={error} />}
        <form className="form-stack" onSubmit={submit}>
          <label>
            Tên workspace
            <input
              name="name"
              placeholder="Đội ngũ kỹ thuật"
              required
              maxLength={120}
              disabled={busy}
            />
          </label>
          <label>
            Slug
            <input
              name="slug"
              placeholder="doi-ngu-ky-thuat"
              pattern="[a-z0-9]+(?:-[a-z0-9]+)*"
              title="Chữ thường, số, các nhóm ngăn bằng dấu gạch ngang"
              required
              maxLength={120}
              disabled={busy}
            />
          </label>
          <p className="field-hint">
            Chỉ dùng chữ thường, số và dấu gạch ngang. Slug phải duy nhất.
          </p>
          <div className="dialog-actions">
            <Button variant="outline" disabled={busy} onClick={() => setOpen(false)}>
              Hủy
            </Button>
            <Button type="submit" disabled={busy}>
              {busy ? "Đang tạo…" : "Tạo workspace"}
            </Button>
          </div>
        </form>
      </Dialog>
    </>
  );
}
