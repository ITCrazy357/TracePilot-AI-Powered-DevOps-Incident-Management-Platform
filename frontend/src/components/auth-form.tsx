"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { ArrowRight, Eye, EyeOff, GitBranch, Layers3, ShieldCheck } from "lucide-react";
import { ApiError, api, errorMessage, passwordError } from "@/lib/api";
import type { User } from "@/lib/contracts";
import { useAuth } from "./auth-provider";
import { Brand } from "./brand";
import { Button } from "./ui/button";
import { ErrorNotice, SuccessNotice } from "./ui/states";

export function AuthForm({ register = false }: { register?: boolean }) {
  const router = useRouter();
  const { user, setUser, loading } = useAuth();
  const [busy, setBusy] = useState(false);
  const [visible, setVisible] = useState(false);
  const [error, setError] = useState("");
  const [created, setCreated] = useState(false);
  const [registeredEmail, setRegisteredEmail] = useState("");
  const isRegister = register && !created;
  const locked = busy || loading;

  useEffect(() => {
    if (!loading && user) router.replace("/");
  }, [user, loading, router]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const email = String(data.get("email")).trim();
    const password = String(data.get("password"));
    const displayName = String(data.get("displayName") ?? "").trim();
    const invalid = passwordError(password, isRegister);
    if (invalid) {
      setError(invalid);
      return;
    }
    if (isRegister && !displayName) {
      setError("Nhập tên hiển thị của bạn.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      if (isRegister) {
        await api<User>("/api/auth/register", {
          method: "POST",
          body: JSON.stringify({ email, password, displayName }),
        });
        setRegisteredEmail(email);
        setCreated(true);
        setVisible(false);
      } else {
        const current = await api<User>("/api/auth/login", {
          method: "POST",
          body: JSON.stringify({ email, password }),
        });
        setUser(current);
        router.replace("/");
      }
    } catch (failure) {
      setError(
        failure instanceof ApiError && failure.status === 401
          ? "Email hoặc mật khẩu không đúng."
          : errorMessage(failure),
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth-layout">
      <section className="auth-story" aria-label="TracePilot">
        <Link href="/login" className="brand-link" aria-label="TracePilot">
          <Brand />
        </Link>
        <div className="auth-story-body">
          <h1>
            Từ dấu vết
            <br />
            đến nguyên nhân.
          </h1>
          <p>Theo dõi GitHub Actions, đọc log đã che và đối chiếu từng đề xuất với bằng chứng.</p>
          <div className="connection-visual" aria-hidden="true">
            <div className="visual-node">
              <GitBranch size={21} />
              <span>Lần chạy GitHub Actions</span>
              <span className="status-dot" />
            </div>
            <div className="visual-line" />
            <div className="visual-node visual-node-main">
              <Layers3 size={21} />
              <span>Log & dấu vết sự cố</span>
              <span className="visual-chip">TracePilot</span>
            </div>
            <div className="visual-line" />
            <div className="visual-node">
              <ShieldCheck size={21} />
              <span>Nguyên nhân cần kiểm chứng</span>
            </div>
          </div>
        </div>
        <span className="auth-story-footer">Giữ bằng chứng. Hiểu sự cố. Chủ động xử lý.</span>
      </section>
      <section className="auth-form-side">
        <div className="auth-form-wrap">
          <div className="auth-mobile-brand">
            <Brand />
          </div>
          <h2>{isRegister ? "Tạo tài khoản" : "Chào mừng trở lại"}</h2>
          <p className="muted">
            {isRegister
              ? "Tạo không gian cho đội ngũ và những dự án tiếp theo."
              : "Đăng nhập để tiếp tục với workspace của bạn."}
          </p>
          {created && <SuccessNotice>Tài khoản đã được tạo. Đăng nhập để tiếp tục.</SuccessNotice>}
          {error && <ErrorNotice message={error} />}
          <form onSubmit={submit} key={String(created)} className="form-stack">
            {isRegister && (
              <label>
                Tên hiển thị
                <input
                  name="displayName"
                  autoComplete="name"
                  placeholder="Nguyễn Minh"
                  maxLength={120}
                  required
                  disabled={locked}
                />
              </label>
            )}
            <label>
              Email
              <input
                name="email"
                type="email"
                autoComplete="email"
                placeholder="ban@congty.com"
                maxLength={320}
                defaultValue={registeredEmail}
                required
                disabled={locked}
              />
            </label>
            <label>
              Mật khẩu
              <span className="password-field">
                <input
                  name="password"
                  type={visible ? "text" : "password"}
                  autoComplete={isRegister ? "new-password" : "current-password"}
                  placeholder={isRegister ? "Ít nhất 12 ký tự" : "Nhập mật khẩu"}
                  maxLength={72}
                  required
                  disabled={locked}
                  aria-describedby={isRegister ? "password-hint" : undefined}
                />
                <button
                  type="button"
                  className="password-toggle"
                  aria-label={visible ? "Ẩn mật khẩu" : "Hiện mật khẩu"}
                  aria-pressed={visible}
                  onClick={() => setVisible(!visible)}
                >
                  {visible ? <EyeOff size={18} /> : <Eye size={18} />}
                </button>
              </span>
            </label>
            {isRegister && (
              <p id="password-hint" className="field-hint">
                Ít nhất 12 ký tự, tối đa 72 byte UTF-8.
              </p>
            )}
            <Button type="submit" disabled={locked} className="auth-submit">
              {loading
                ? "Đang kiểm tra phiên…"
                : busy
                  ? "Đang xử lý…"
                  : isRegister
                    ? "Tạo tài khoản"
                    : "Đăng nhập"}
              <ArrowRight size={17} />
            </Button>
          </form>
          <p className="auth-switch">
            {isRegister ? "Đã có tài khoản?" : "Chưa có tài khoản?"}{" "}
            <Link
              href={isRegister ? "/login" : "/register"}
              onClick={() => {
                setCreated(false);
                setError("");
              }}
            >
              {isRegister ? "Đăng nhập" : "Đăng ký ngay"}
            </Link>
          </p>
          <div className="auth-security">
            <ShieldCheck size={16} />
            <span>Phiên đăng nhập của bạn được bảo vệ.</span>
          </div>
        </div>
        <p className="auth-copyright">© {new Date().getFullYear()} TracePilot</p>
      </section>
    </main>
  );
}
