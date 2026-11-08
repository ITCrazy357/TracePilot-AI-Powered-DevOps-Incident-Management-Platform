"use client";

import { Mail, ShieldCheck, UserRound } from "lucide-react";
import { useAuth } from "./auth-provider";
import { CopyId } from "./members";

export function Account() {
  const { user } = useAuth();
  if (!user) return null;
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">HỒ SƠ CỦA BẠN</span>
          <h1>Tài khoản</h1>
          <p className="muted">Thông tin tài khoản và mã định danh của bạn.</p>
        </div>
      </div>
      <section className="panel account-panel">
        <div className="account-header">
          <span className="account-avatar">{user.displayName.slice(0, 1).toUpperCase()}</span>
          <div>
            <h2>{user.displayName}</h2>
            <p className="muted">{user.email}</p>
          </div>
        </div>
        <dl className="account-details">
          <div>
            <dt>
              <UserRound size={16} />
              Tên hiển thị
            </dt>
            <dd>{user.displayName}</dd>
          </div>
          <div>
            <dt>
              <Mail size={16} />
              Email
            </dt>
            <dd>{user.email}</dd>
          </div>
          <div>
            <dt>UUID tài khoản</dt>
            <dd>
              <code>{user.id}</code>
              <p className="field-hint">
                Chia sẻ UUID với OWNER hoặc ADMIN để được thêm vào workspace.
              </p>
              <CopyId id={user.id} />
            </dd>
          </div>
        </dl>
      </section>
      <div className="notice notice-info account-panel">
        <ShieldCheck size={18} />
        <span>
          Quyền quản trị được cấp riêng trong từng workspace. Trang này hiển thị thông tin tài khoản
          hiện tại.
        </span>
      </div>
    </>
  );
}
