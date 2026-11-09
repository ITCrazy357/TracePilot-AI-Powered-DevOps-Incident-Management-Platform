export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly retryAfterSeconds: number | null = null,
    public readonly requestId: string | null = null,
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
  503: "Dịch vụ tạm thời chưa sẵn sàng. Vui lòng thử lại sau.",
};

const details: Record<string, string> = {
  "RAG integration is not configured.":
    "Tìm kiếm AI chưa được bật. Bạn vẫn có thể tạo, sửa và đọc bài viết; nhờ người vận hành bật AI/RAG để duyệt hoặc tìm kiếm.",
  "Knowledge changed. Reload before updating.":
    "Bài viết vừa thay đổi. Tải lại bài viết trước khi cập nhật.",
  "Knowledge indexing failed. Reload before retrying approval.":
    "Tạo chỉ mục chưa thành công. Tải lại bài viết để kiểm tra trạng thái trước khi duyệt lại.",
  "Indexing is already in progress.":
    "Bài viết đang tạo chỉ mục. Làm mới để kiểm tra; chỉ thử lại sau khi lượt xử lý cũ hết hạn (120 giây).",
  "Knowledge is already indexed.":
    "Bài viết đã sẵn sàng tìm kiếm. Tải lại để xem trạng thái mới nhất.",
  "Confirm the incident as RESOLVED before recording its fix.":
    "Sự cố cần ở trạng thái Đã giải quyết trước khi lưu cách khắc phục vào kho kiến thức.",
  "GitHub integration is not configured.":
    "Tích hợp GitHub chưa sẵn sàng. OWNER cần kiểm tra cấu hình với người vận hành.",
  "Rate limiting is temporarily unavailable.":
    "Bộ giới hạn yêu cầu tạm thời chưa sẵn sàng. Vui lòng thử lại sau.",
  "Storage is temporarily unavailable.":
    "Dịch vụ dữ liệu tạm thời chưa sẵn sàng. Vui lòng thử lại sau.",
  "Repository is inactive or installation is unavailable.":
    "Kho mã đã ngừng theo dõi hoặc kết nối GitHub không còn khả dụng. Kiểm tra nguồn trước khi thử lại.",
  "Feedback changed. Reload before updating.":
    "Đánh giá vừa thay đổi. Tải lại đánh giá trước khi lưu.",
  "Request a new analysis to preserve report and usage history.":
    "Mở sự cố và yêu cầu phân tích mới để giữ lịch sử báo cáo.",
  "Only a failed task delivered to the DLQ with the current replay count can be retried.":
    "Tác vụ đã thay đổi hoặc chưa vào hàng đợi lỗi. Tải lại trước khi quyết định thử lại.",
  "Pipeline integration is not configured.":
    "Pipeline chưa được bật. Nhờ người vận hành kiểm tra cấu hình.",
  "Only a completed successful report can receive feedback.":
    "Chỉ có thể đánh giá báo cáo đã phân tích thành công.",
  "AI integration is not configured.":
    "AI chưa được bật. Nhờ người vận hành kiểm tra cấu hình rồi thử lại.",
  "An analysis is already pending for this incident.":
    "Sự cố đang có một phân tích chờ hoặc đang chạy. Tải lại lịch sử để xem tiến độ.",
  "Redacted logs are not available for analysis.":
    "Chưa có log phù hợp để phân tích. Kiểm tra trạng thái thu thập log.",
  "Workspace AI daily call limit reached.":
    "Workspace đã dùng hết lượt phân tích AI trong 24 giờ. Vui lòng thử lại sau.",
  "Incident changed. Reload before updating.":
    "Sự cố vừa được cập nhật. Tải lại dữ liệu trước khi quyết định thay đổi.",
  "Invalid incident status transition.":
    "Không thể chuyển trực tiếp sang trạng thái này. Tải lại và chọn bước chuyển hợp lệ.",
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
    const retryHeader = response.headers.get("retry-after");
    const retryAfter = retryHeader && /^\d{1,6}$/.test(retryHeader) ? Number(retryHeader) : null;
    throw new ApiError(
      response.status,
      response.status === 429 && retryAfter !== null
        ? `Quá nhiều yêu cầu. Vui lòng thử lại sau ${retryAfter} giây.`
        : message,
      retryAfter,
      response.headers.get("x-request-id"),
    );
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
