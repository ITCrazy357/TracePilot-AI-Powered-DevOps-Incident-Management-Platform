"use client";

import Link from "next/link";
import { useState } from "react";
import { ArrowRight, Check, GitBranch, Layers3, Plus, Users } from "lucide-react";
import { useAuth } from "./auth-provider";
import { CreateWorkspace } from "./create-workspace";
import { RepositoryPanel } from "./repository-panel";
import { useWorkspace } from "./workspace-provider";
import { WorkspaceBoundary } from "./workspace-boundary";

export function Overview() {
  const { user } = useAuth();
  const { selectedId } = useWorkspace();
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">KHÔNG GIAN LÀM VIỆC</span>
          <h1>Tổng quan</h1>
          <p className="muted">Xin chào {user?.displayName}. Cùng chuẩn bị cho dự án tiếp theo.</p>
        </div>
        <CreateWorkspace />
      </div>
      <WorkspaceBoundary>
        <WorkspaceOverview key={selectedId} />
      </WorkspaceBoundary>
    </>
  );
}

function WorkspaceOverview() {
  const { workspace, selectedId, workspaces, members, role } = useWorkspace();
  const [repositoryTotal, setRepositoryTotal] = useState<number | null>(null);
  return (
    <div key={selectedId} className="overview-content">
      <section className="workspace-banner">
        <div className="workspace-banner-icon">
          <Layers3 size={26} />
        </div>
        <div className="workspace-banner-text">
          <span className="small-eyebrow">WORKSPACE HIỆN TẠI</span>
          <h2>{workspace?.name}</h2>
          <p>/{workspace?.slug}</p>
        </div>
        <span className="badge badge-dark">{role ?? "Chưa xác định quyền"}</span>
      </section>
      <div className="stats-grid">
        <div className="stat">
          <span className="stat-label">
            Workspace của bạn
            <Layers3 size={18} />
          </span>
          <strong>{workspaces.length.toString().padStart(2, "0")}</strong>
          <span className="stat-caption">Không gian bạn có quyền truy cập</span>
        </div>
        <div className="stat">
          <span className="stat-label">
            Kho mã GitHub
            <GitBranch size={18} />
          </span>
          <strong>
            {repositoryTotal === null ? "—" : repositoryTotal.toString().padStart(2, "0")}
          </strong>
          <span className="stat-caption">Tổng kho mã trong workspace hiện tại</span>
        </div>
        <div className="stat">
          <span className="stat-label">
            Thành viên
            <Users size={18} />
          </span>
          <strong>{members.length.toString().padStart(2, "0")}</strong>
          <span className="stat-caption">Thành viên đang hoạt động</span>
        </div>
      </div>
      <div className="overview-grid">
        <RepositoryPanel preview onTotal={setRepositoryTotal} />
        <section className="panel setup-panel">
          <div className="panel-heading">
            <div>
              <span className="small-eyebrow">SẴN SÀNG LÀM VIỆC</span>
              <h2>Thiết lập workspace</h2>
            </div>
          </div>
          <ol className="setup-list">
            <li>
              <span className="setup-number done">
                <Check size={15} />
              </span>
              <div>
                <strong>Tạo workspace</strong>
                <p>Không gian riêng cho dự án và đội ngũ.</p>
              </div>
            </li>
            <li>
              <span className={`setup-number ${members.length > 1 ? "done" : ""}`}>
                {members.length > 1 ? <Check size={15} /> : "2"}
              </span>
              <div>
                <strong>Thêm thành viên</strong>
                <p>Cộng tác với đúng quyền truy cập.</p>
                <Link href="/members">
                  Quản lý thành viên
                  <ArrowRight size={13} />
                </Link>
              </div>
            </li>
            <li>
              <span className={`setup-number ${repositoryTotal ? "done" : ""}`}>
                {repositoryTotal ? <Check size={15} /> : "3"}
              </span>
              <div>
                <strong>Kết nối kho mã</strong>
                <p>Đồng bộ repository từ GitHub App.</p>
                <Link href="/repositories">
                  Quản lý kho mã
                  <ArrowRight size={13} />
                </Link>
              </div>
            </li>
          </ol>
        </section>
      </div>
      <section className="workspace-section">
        <div className="section-heading">
          <h2>
            Workspace của bạn<span className="count-label">{workspaces.length}</span>
          </h2>
          <span className="muted">Chọn workspace ở thanh điều hướng để chuyển đổi</span>
        </div>
        <div className="workspace-cards">
          {workspaces.map((item) => (
            <WorkspaceCard key={item.id} id={item.id} name={item.name} slug={item.slug} />
          ))}
          <div className="workspace-create-card">
            <Plus size={22} />
            <strong>Một ý tưởng mới?</strong>
            <CreateWorkspace />
          </div>
        </div>
      </section>
    </div>
  );
}

function WorkspaceCard({ id, name, slug }: { id: string; name: string; slug: string }) {
  const { select, selectedId } = useWorkspace();
  return (
    <button
      className={`workspace-card ${id === selectedId ? "workspace-card-selected" : ""}`}
      onClick={() => select(id)}
      aria-pressed={id === selectedId}
    >
      <span className="workspace-card-top">
        <span className="workspace-monogram">{name.slice(0, 2).toUpperCase()}</span>
        {id === selectedId && <span className="badge badge-green">Đang chọn</span>}
      </span>
      <strong>{name}</strong>
      <span className="muted">/{slug}</span>
      <span className="workspace-card-bottom">
        {id === selectedId ? "Workspace hiện tại" : "Mở workspace"}
        <ArrowRight size={15} />
      </span>
    </button>
  );
}
