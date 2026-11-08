export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

const messages: Record<number, string> = {
  400: "Dữ liệu chưa hợp lệ. Kiểm tra thông tin rồi thử lại.",
  401: "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.",
  403: "Bạn không có quyền thực hiện thao tác này.",
  404: "Không tìm thấy dữ liệu hoặc bạn không còn quyền truy cập.",
  409: "Dữ liệu đã tồn tại hoặc vừa thay đổi. Tải lại rồi thử lại.",
  429: "Quá nhiều yêu cầu. Vui lòng thử lại sau.",
  502: "Không kết nối được dịch vụ. Kiểm tra backend rồi thử lại.",
  503: "Tích hợp GitHub chưa sẵn sàng. OWNER cần kiểm tra cấu hình với người vận hành.",
};

const details: Record<string, string> = {
  "Email is already registered.": "Email này đã được đăng ký.",
  "User not found.": "Không tìm thấy tài khoản với UUID này.",
  "User is already a member.": "Tài khoản này đã là thành viên workspace.",
  "Password exceeds 72 UTF-8 bytes.": "Mật khẩu vượt quá 72 byte UTF-8.",
};

let refreshPending: Promise<Response> | null = null;

async function refresh() {
  if (!refreshPending) {
    refreshPending = fetch("/api/auth/refresh", { method: "POST" }).finally(() => {
      refreshPending = null;
    });
  }
  return refreshPending;
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const options: RequestInit = {
    ...init,
    cache: "no-store",
    headers: { "Content-Type": "application/json", ...init.headers },
  };
  let response = await fetch(path, options);
  const authenticated = path.startsWith("/api/backend/") || path === "/api/auth/me";
  if (response.status === 401 && authenticated) {
    const renewed = await refresh();
    if (renewed.ok) response = await fetch(path, options);
    else if (renewed.status !== 401) response = renewed;
  }
  if (!response.ok) {
    if (response.status === 401 && authenticated)
      window.dispatchEvent(new Event("session-expired"));
    const body = await response.json().catch(() => null);
    const message =
      details[body?.detail] ?? messages[response.status] ?? "Có lỗi xảy ra. Vui lòng thử lại.";
    throw new ApiError(response.status, message);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export function errorMessage(error: unknown) {
  return error instanceof ApiError
    ? error.message
    : "Không kết nối được dịch vụ. Vui lòng thử lại.";
}

export function passwordError(password: string, register: boolean) {
  if (!password.trim()) return "Nhập mật khẩu của bạn.";
  if (register && password.length < 12) return "Mật khẩu cần ít nhất 12 ký tự.";
  if (new TextEncoder().encode(password).length > 72)
    return "Mật khẩu tối đa 72 byte UTF-8; ký tự tiếng Việt có thể dùng nhiều byte.";
  return "";
}
