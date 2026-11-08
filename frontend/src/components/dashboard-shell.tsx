"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import {
  Bell,
  CirclePlay,
  CircleAlert,
  ChevronDown,
  GitBranch,
  LayoutDashboard,
  LogOut,
  Menu,
  UserRound,
  Users,
  X,
} from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import { useAuth } from "./auth-provider";
import { Brand } from "./brand";
import { CreateWorkspace } from "./create-workspace";
import { WorkspaceProvider, useWorkspace } from "./workspace-provider";
import { Button } from "./ui/button";
import { ErrorNotice, Loading } from "./ui/states";
import { RealtimeProvider, refreshWorkspace, useRealtime } from "./realtime-provider";

const links = [
  { href: "/", label: "Tổng quan", icon: LayoutDashboard },
  { href: "/repositories", label: "Kho mã", icon: GitBranch },
  { href: "/runs", label: "Lần chạy", icon: CirclePlay },
  { href: "/incidents", label: "Sự cố", icon: CircleAlert },
  { href: "/notifications", label: "Thông báo", icon: Bell },
  { href: "/members", label: "Thành viên", icon: Users },
  { href: "/account", label: "Tài khoản", icon: UserRound },
];

export function DashboardShell({ children }: { children: ReactNode }) {
  const { user, loading, error, reload } = useAuth();
  const router = useRouter();
  useEffect(() => {
    if (!loading && !user && !error) router.replace("/login");
  }, [loading, user, error, router]);
  if (loading) return <Loading label="Đang kiểm tra phiên đăng nhập…" />;
  if (error)
    return (
      <main className="session-error">
        <Brand />
        <ErrorNotice message={error} retry={reload} />
        <Link href="/login">Về trang đăng nhập</Link>
      </main>
    );
  if (!user) return <Loading label="Đang chuyển đến trang đăng nhập…" />;
  return (
    <WorkspaceProvider>
      <RealtimeProvider>
        <Shell>{children}</Shell>
      </RealtimeProvider>
    </WorkspaceProvider>
  );
}

function Shell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const { user, setUser } = useAuth();
  const { workspaces, selectedId, select, loading, workspace, role } = useWorkspace();
  const connected = useRealtime();
  const [menuOpen, setMenuOpen] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!menuOpen) return;
    const close = (event: KeyboardEvent) => {
      if (event.key === "Escape") setMenuOpen(false);
    };
    window.addEventListener("keydown", close);
    return () => window.removeEventListener("keydown", close);
  }, [menuOpen]);

  async function logout() {
    setSigningOut(true);
    setError("");
    try {
      await api<void>("/api/auth/logout", { method: "POST" });
      setUser(null);
      router.replace("/login");
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setSigningOut(false);
    }
  }

  return (
    <div className="app-layout">
      <a href="#main-content" className="skip-link">
        Đến nội dung chính
      </a>
      <header className="mobile-header">
        <Link href="/" aria-label="TracePilot">
          <Brand />
        </Link>
        <button
          className="icon-button"
          aria-label={menuOpen ? "Đóng điều hướng" : "Mở điều hướng"}
          aria-expanded={menuOpen}
          aria-controls="app-sidebar"
          onClick={() => setMenuOpen(!menuOpen)}
        >
          {menuOpen ? <X /> : <Menu />}
        </button>
      </header>
      <aside className={`sidebar ${menuOpen ? "sidebar-open" : ""}`} id="app-sidebar">
        <Link href="/" className="sidebar-brand" aria-label="TracePilot">
          <Brand />
        </Link>
        <div className="workspace-picker">
          <div className="sidebar-label">
            WORKSPACE
            <CreateWorkspace compact />
          </div>
          <div className="workspace-select">
            <label className="sr-only" htmlFor="workspace">
              Workspace hiện tại
            </label>
            <select
              id="workspace"
              value={selectedId}
              onChange={(event) => {
                select(event.target.value);
                setMenuOpen(false);
                if (pathname.startsWith("/incidents/")) router.push("/incidents");
                if (pathname.startsWith("/runs/")) router.push("/runs");
              }}
              disabled={loading || workspaces.length === 0}
            >
              {workspaces.length === 0 && (
                <option value="">{loading ? "Đang tải…" : "Chưa có workspace"}</option>
              )}
              {workspaces.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.name}
                </option>
              ))}
            </select>
            <ChevronDown size={16} aria-hidden="true" />
          </div>
        </div>
        <div className="sidebar-label nav-label">QUẢN LÝ</div>
        <nav aria-label="Điều hướng chính" className="main-nav">
          {links.map(({ href, label, icon: Icon }) => (
            <Link
              key={href}
              href={href}
              aria-current={
                pathname === href || (href !== "/" && pathname.startsWith(href + "/"))
                  ? "page"
                  : undefined
              }
              className={
                pathname === href || (href !== "/" && pathname.startsWith(href + "/"))
                  ? "nav-active"
                  : ""
              }
              onClick={() => setMenuOpen(false)}
            >
              <Icon size={18} />
              <span>{label}</span>
              {pathname === href && <span className="nav-dot" />}
            </Link>
          ))}
        </nav>
        <div className="sidebar-note">
          <div className="trace-motif" aria-hidden="true">
            <i />
            <span />
            <i />
            <span />
            <i />
          </div>
          <p>Workflow → log → nguyên nhân.</p>
          <span className="field-hint">
            {role ? `Quyền hiện tại: ${role}` : "Chọn workspace để bắt đầu"}
          </span>
        </div>
        <div className="sidebar-user">
          <span className="avatar">{user?.displayName.slice(0, 1).toUpperCase()}</span>
          <div>
            <strong title={user?.displayName}>{user?.displayName}</strong>
            <span title={user?.email}>{user?.email}</span>
          </div>
          <button
            className="icon-button"
            aria-label="Đăng xuất"
            title="Đăng xuất"
            disabled={signingOut}
            onClick={logout}
          >
            <LogOut size={18} />
          </button>
        </div>
      </aside>
      <div className="main-column">
        <div className="topbar">
          <div className="breadcrumb">
            <span className="breadcrumb-workspace">{workspace?.name ?? "Workspace"}</span>
            <span>/</span>
            <strong>
              {
                links.find(
                  (item) =>
                    item.href === pathname ||
                    (item.href !== "/" && pathname.startsWith(item.href + "/")),
                )?.label
              }
            </strong>
          </div>
          <div className="topbar-actions">
            <button
              className={`connection-state ${connected ? "connected" : ""}`}
              onClick={() => refreshWorkspace(selectedId)}
              title="Làm mới dữ liệu workspace"
            >
              <span />
              {connected ? "Trực tiếp" : "Cập nhật thủ công"}
            </button>
            <Link href="/notifications" className="icon-button" aria-label="Xem thông báo">
              <Bell size={19} />
            </Link>
            <Link href="/account" className="topbar-account">
              <span className="avatar">{user?.displayName.slice(0, 1).toUpperCase()}</span>
              <span>{user?.displayName}</span>
            </Link>
          </div>
        </div>
        <main id="main-content" className="page-content">
          {error && <ErrorNotice message={`Chưa đăng xuất được. ${error}`} />}
          {children}
        </main>
        <footer className="app-footer">
          <span>TracePilot</span>
          <span>Từ lần chạy đến nguyên nhân.</span>
        </footer>
      </div>
    </div>
  );
}
