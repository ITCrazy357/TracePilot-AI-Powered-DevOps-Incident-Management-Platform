"use client";

import { useState, type FormEvent } from "react";
import { Copy, LockKeyhole, Pencil, Trash2, UserPlus, Users } from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import type { Member, WorkspaceRole } from "@/lib/contracts";
import { useAuth } from "./auth-provider";
import { useWorkspace } from "./workspace-provider";
import { WorkspaceBoundary } from "./workspace-boundary";
import { Button } from "./ui/button";
import { Dialog } from "./ui/dialog";
import { ErrorNotice, SuccessNotice } from "./ui/states";

export function Members() {
  return (
    <>
      <div className="page-heading">
        <div>
          <h1>Thành viên</h1>
          <p className="muted">Quản lý người tham gia và quyền truy cập workspace.</p>
        </div>
      </div>
      <WorkspaceBoundary>
        <MemberList />
      </WorkspaceBoundary>
    </>
  );
}

function MemberList() {
  const { selectedId } = useWorkspace();
  return <MemberManager key={selectedId} />;
}

function MemberManager() {
  const { user } = useAuth();
  const { workspace, members, role, reloadMembers } = useWorkspace();
  const [dialog, setDialog] = useState<"add" | "role" | "remove" | null>(null);
  const [target, setTarget] = useState<Member | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const canAdd = role === "OWNER" || role === "ADMIN";

  function open(kind: "add" | "role" | "remove", member?: Member) {
    setDialog(kind);
    setTarget(member ?? null);
    setError("");
    setSuccess("");
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const memberRole = String(data.get("role") ?? "MEMBER") as WorkspaceRole;
    const userId = String(data.get("userId") ?? target?.userId ?? "").trim();
    setBusy(true);
    setError("");
    try {
      const path = `/api/backend/workspaces/${workspace?.id}/members`;
      if (dialog === "add")
        await api<Member>(path, {
          method: "POST",
          body: JSON.stringify({ userId, role: memberRole }),
        });
      if (dialog === "role")
        await api<Member>(`${path}/${target?.userId}`, {
          method: "PATCH",
          body: JSON.stringify({ role: memberRole }),
        });
      if (dialog === "remove") await api<void>(`${path}/${target?.userId}`, { method: "DELETE" });
      setSuccess(
        dialog === "add"
          ? "Đã thêm thành viên."
          : dialog === "role"
            ? "Đã cập nhật quyền thành viên."
            : "Đã xóa thành viên khỏi workspace.",
      );
      setDialog(null);
      reloadMembers();
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      {success && <SuccessNotice>{success}</SuccessNotice>}
      <div className="notice notice-info">
        <LockKeyhole size={18} />
        <span>
          {role === "MEMBER"
            ? "Bạn có quyền xem danh sách. OWNER hoặc ADMIN có thể thêm và quản lý thành viên."
            : "Thêm tài khoản đã đăng ký bằng UUID. OWNER được quản lý ADMIN và MEMBER; ADMIN chỉ quản lý MEMBER."}
        </span>
      </div>
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>
              <Users size={18} />
              Thành viên workspace<span className="count-label">{members.length}</span>
            </h2>
            <p className="muted">{workspace?.name}</p>
          </div>
          {canAdd && (
            <Button onClick={() => open("add")}>
              <UserPlus size={16} />
              Thêm thành viên
            </Button>
          )}
        </div>
        <div className="member-table">
          <div className="member-table-head">
            <span>THÀNH VIÊN / UUID</span>
            <span>VAI TRÒ</span>
            <span>THAO TÁC</span>
          </div>
          {members.map((member) => {
            const self = member.userId === user?.id;
            const manageable =
              member.role !== "OWNER" &&
              (role === "OWNER" || (role === "ADMIN" && member.role === "MEMBER"));
            return (
              <div className="member-row" key={member.userId}>
                <div className="member-identity">
                  <span className="avatar">
                    {self ? user?.displayName.slice(0, 1).toUpperCase() : "TV"}
                  </span>
                  <div>
                    <strong>
                      {self ? user?.displayName : "Thành viên"}
                      {self && <span className="you-label">Bạn</span>}
                    </strong>
                    <span className="uuid-text">{member.userId}</span>
                  </div>
                </div>
                <span
                  className={`badge ${member.role === "OWNER" ? "badge-amber" : member.role === "ADMIN" ? "badge-blue" : "badge-neutral"}`}
                >
                  {member.role}
                </span>
                <div className="member-actions">
                  {role === "OWNER" && manageable && (
                    <Button
                      variant="ghost"
                      aria-label={`Đổi quyền ${member.userId}`}
                      onClick={() => open("role", member)}
                    >
                      <Pencil size={15} />
                    </Button>
                  )}
                  {manageable && (
                    <Button
                      variant="ghost"
                      className="text-danger"
                      aria-label={`Xóa ${member.userId}`}
                      onClick={() => open("remove", member)}
                    >
                      <Trash2 size={16} />
                    </Button>
                  )}
                  {member.role === "OWNER" && (
                    <span className="owner-lock" title="Không thể sửa hoặc xóa OWNER">
                      <LockKeyhole size={15} />
                      <span className="sr-only">OWNER được bảo vệ</span>
                    </span>
                  )}
                </div>
              </div>
            );
          })}
        </div>
        <div className="panel-footer">
          <span className="muted">Chỉ hiển thị thành viên đang hoạt động.</span>
        </div>
      </section>
      <Dialog
        open={dialog !== null}
        onOpenChange={(value) => {
          if (!value && !busy) setDialog(null);
        }}
        title={
          dialog === "add"
            ? "Thêm thành viên"
            : dialog === "role"
              ? "Đổi quyền thành viên"
              : "Xóa thành viên"
        }
        description={
          dialog === "add"
            ? "Nhập UUID của tài khoản đã đăng ký TracePilot. Người đó có thể sao chép UUID ở trang Tài khoản."
            : `Tài khoản: ${target?.userId ?? ""}`
        }
      >
        {error && <ErrorNotice message={error} />}
        <form className="form-stack" onSubmit={submit}>
          {dialog === "add" && (
            <label>
              UUID tài khoản
              <input
                name="userId"
                required
                pattern="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                title="UUID tài khoản hợp lệ"
                placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                disabled={busy}
              />
            </label>
          )}
          {dialog !== "remove" && (
            <label>
              Vai trò
              <select name="role" defaultValue={target?.role ?? "MEMBER"} disabled={busy}>
                <option value="MEMBER">MEMBER — Thành viên</option>
                {role === "OWNER" && <option value="ADMIN">ADMIN — Quản trị viên</option>}
              </select>
            </label>
          )}
          {dialog === "remove" && (
            <p>
              Thành viên sẽ mất quyền truy cập workspace này. Bạn có thể thêm lại tài khoản sau.
            </p>
          )}
          <div className="dialog-actions">
            <Button variant="outline" disabled={busy} onClick={() => setDialog(null)}>
              Hủy
            </Button>
            <Button
              type="submit"
              variant={dialog === "remove" ? "destructive" : "default"}
              disabled={busy}
            >
              {busy
                ? "Đang lưu…"
                : dialog === "remove"
                  ? "Xóa thành viên"
                  : dialog === "add"
                    ? "Thêm thành viên"
                    : "Lưu quyền"}
            </Button>
          </div>
        </form>
      </Dialog>
    </>
  );
}

export function CopyId({ id }: { id: string }) {
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState("");
  async function copy() {
    try {
      await navigator.clipboard.writeText(id);
      setCopied(true);
      setError("");
    } catch {
      setError("Không sao chép được. Hãy chọn và sao chép UUID bên trên.");
    }
  }
  return (
    <div className="copy-id">
      <Button variant="outline" onClick={copy}>
        <Copy size={15} />
        {copied ? "Đã sao chép" : "Sao chép UUID"}
      </Button>
      <span role="status" className="field-hint">
        {error}
      </span>
    </div>
  );
}
