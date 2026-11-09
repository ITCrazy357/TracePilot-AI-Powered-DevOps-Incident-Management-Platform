# API bàn giao

Backend local shipping: `http://127.0.0.1:18081/api/v1`. JSON UTF-8, UUID cho ID nội bộ. API bảo vệ dùng `Authorization: Bearer <access-token>`. Browser dùng Next tại `http://127.0.0.1:13000`: `/api/auth/*` đổi token thành cookie HttpOnly, `/api/backend/*` chỉ proxy allowlist hiện có; không phải toàn bộ API Spring đều đi qua BFF.

Request mutation qua Next cần `Origin` đúng `APP_ORIGIN`, content-type JSON khi có body, body tối đa 8 KiB. Không gửi token trong URL hoặc lưu vào localStorage. Spring dùng Bearer trực tiếp; webhook nằm ngoài `/api/v1`.

## Xác thực và workspace

| Method | Backend path sau `/api/v1` | Hợp đồng |
|---|---|---|
| POST | `/auth/register` | `{email,password,displayName}` → 201 user |
| POST | `/auth/login` | `{email,password}` → token pair + user; Next chỉ trả user |
| POST | `/auth/refresh` | `{refreshToken}` → token pair mới, token cũ không dùng lại |
| POST | `/auth/logout` | `{refreshToken}` → 204; Next lấy token từ cookie |
| GET | `/auth/me` | User hiện tại |
| GET / POST | `/workspaces` | Danh sách / `{name,slug}` tạo workspace, creator thành OWNER |
| GET | `/workspaces/{workspaceId}` | Membership ACTIVE; không có quyền trả 404 |
| GET / POST | `/workspaces/{workspaceId}/members` | Danh sách / `{userId,role}` thêm thành viên theo quyền quản lý |
| PATCH / DELETE | `/workspaces/{workspaceId}/members/{userId}` | `{role}` / xóa membership; không tự nâng lên OWNER |

## API theo workspace

Base trong bảng: `/api/v1/workspaces/{workspaceId}`. `R` là `/repositories/{repositoryId}/workflow-runs`; `I` là `/incidents/{incidentId}`; `A` là `I + /analyses/{analysisId}`. Các endpoint đọc nghiệp vụ vẫn cần membership và object scope hiện tại.

| Method | Path sau base | Hợp đồng/quyền chính |
|---|---|---|
| POST | `/github/sync` | OWNER; đồng bộ danh sách repo của installation đã được operator bind |
| GET | `/repositories` | Phân trang repo của workspace |
| GET | `R`, `R/{runId}`, `R/{runId}/jobs`, `R/sync-requests` | Run attempts, jobs và lịch sử xử lý |
| POST | `R/sync` | OWNER; `{githubRunId,runAttempt}` → 202 ý định đã lưu, chưa phải worker thành công |
| GET | `/incidents`, `I`, `I/logs` | Lifecycle và log đã redacted; trạng thái log có thể chưa READY |
| PATCH | `I/status` | `{status,version}`; quyền theo role/assignee, stale version trả 409 |
| PATCH | `I/assignee` | `{assignedTo,version}`; `assignedTo:null` để bỏ phân công |
| GET / POST | `I/analyses` | Lịch sử / `{requestId}` yêu cầu phân tích; cần quyền và AI được bật |
| GET | `A`, `A/usage`, `A/evidence` | Báo cáo, usage từng attempt và dẫn chứng; `/evidence` dùng backend trực tiếp |
| GET / PUT | `A/feedback` | Đánh giá của chính user / `{helpful,reason,comment,version}`; xem enum và version ở Phase 07 |
| GET | `/quality?days=30` | Tổng hợp 1–90 ngày; không đồng nhất helpfulness với accuracy |
| GET | `/notifications` | Chỉ notification của user hiện tại |
| PATCH | `/notifications/{id}/read` | Mark read, không cần body |
| GET | `/notifications/stream` | SSE; `Last-Event-ID` thuộc user/workspace, không buffer tại proxy |
| GET | `/audit-logs`, `/operations`, `/operations/outbox` | OWNER/ADMIN; metadata, không trả payload/prompt/log riêng tư |
| POST | `/operations/outbox/{id}/retry` | `{replayCount}`; OWNER với workflow, OWNER/ADMIN với log; AI phải yêu cầu analysis mới |

Phân trang mặc định `page=0&size=20`, page 0–10.000 và size 1–100. DTO page có thể dùng `total` hoặc `totalElements` tùy endpoint; xem `frontend/src/lib/contracts.ts` và DTO backend, không tự đổi tên field khi viết client.

Knowledge API chỉ qua backend trực tiếp trong UI hiện tại: `/knowledge-articles` (GET/POST), `/{articleId}` (GET/PATCH), `/{articleId}/approve` (POST `{version}`), `/{articleId}/archive` (POST `{version}`), `/search` (POST `{query,topK,mode}`). Create/edit cần nội dung `title`, `rootCause`, `resolution`; edit thêm `version`. Approve/index/search cần provider được bật và quyền theo [Phase 06](phase-06.md).

## HTTP webhook, health và lỗi

`POST /webhooks/github` yêu cầu raw-body HMAC SHA-256 đúng webhook secret, `X-GitHub-Delivery`, event metadata; delivery ID/digest chống xử lý trùng. Chỉ publish đường webhook HTTPS khi triển khai; không expose toàn bộ Actuator hoặc broker management.

`GET /actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` public nhưng ẩn chi tiết. Readiness có DB/RabbitMQ; liveness không phụ thuộc provider. `/actuator/metrics` và `/prometheus` không public.

| Status | Ý nghĩa và cách xử lý |
|---|---|
| 400 / 415 | Body/validation/content-type sai |
| 401 | Thiếu/hết hạn token; BFF có luồng refresh riêng |
| 403 / 404 | Không đủ quyền / object không thuộc scope hoặc không tồn tại |
| 409 | Version/replay count thay đổi hoặc trạng thái không cho phép; tải lại, không retry mutation mù |
| 429 | Có `Retry-After` số giây; không tự lặp request vượt giới hạn |
| 502 / 503 | Kết nối BFF/backend, provider/cấu hình hoặc storage chưa sẵn sàng; kiểm tra health và mã request |

Backend sinh `X-Request-ID`; BFF chuyển tiếp ID hợp lệ. Error body dùng Problem Detail hoặc dạng BFF `{status,detail}`; không kỳ vọng stack trace/SQL. Tài liệu này đối chiếu controller/DTO hiện có, **chưa phải OpenAPI/Swagger tự sinh**. Các phase 01–08 chứa ví dụ PowerShell chi tiết; bộ `scripts/shipping-smoke.mjs` là client chạy được cho auth/workspace/audit/rate limit.
