# Phase 12 — GitHub cho nhiều workspace

Một installation được dùng ở nhiều workspace; mỗi workspace có một liên kết và danh sách repository
theo dõi riêng. Không chuyển hay xóa liên kết/dữ liệu cũ. Một repository cũng có thể được hai workspace
chọn; workflow, incident và lịch sử vẫn độc lập theo ID nội bộ/workspace.

## Sử dụng với TracePilot Demo và OfficeFlow

1. Trong cài đặt GitHub App installation, cấp quyền cho cả repository Roomora và OfficeFlow nếu chưa có.
2. Tại root chạy `scripts/shipping.ps1 -Task up` để cập nhật backend/frontend và migration V15. Giữ
   `.env.shipping`, key, webhook URL và volume; không đổi GITHUB_WORKSPACE_ID sang OfficeFlow.
3. Đăng nhập bằng OWNER của TracePilot Demo và OfficeFlow. Trong **OfficeFlow → Kho mã → Chọn repository**,
   chọn tài khoản GitHub, tích repo OfficeFlow và **Lưu repository theo dõi**.
4. Trong TracePilot Demo cũng có thể chọn lại chỉ repo Roomora. **Đồng bộ GitHub** đối soát repo đã chọn;
   không tự nhập mọi repo mới của installation vào workspace.
5. Webhook chung tiếp tục hoạt động. Các lần chạy/sự cố mới đi vào những workspace đã chọn repository đó.
   Không tự tải toàn bộ lịch sử GitHub sau khi kết nối; dùng đồng bộ workflow theo run ID như trước.

Bỏ chọn repo đặt tracking/active false nhưng giữ UUID, workflow, incident và bằng chứng đã có. Repo bị
GitHub thu hồi vẫn giữ lựa chọn nhưng inactive; sync sau khi GitHub cấp lại có thể phục hồi. Cần chọn
ít nhất một repo khi lưu. Đổi installation của workspace đã liên kết chưa được hỗ trợ để tránh chuyển
nhầm lịch sử; cần quy trình di chuyển riêng nếu theo dõi tài khoản GitHub khác.

## Quyền kết nối

Installation ID không phải bằng chứng sở hữu. API không cho bất kỳ OWNER nào nhập ID để xem repo riêng tư.
Một OWNER chỉ có thể dùng installation nếu đang là OWNER ACTIVE của workspace nguồn đã có liên kết được
phê duyệt, hoặc là OWNER của workspace bootstrap trong cấu hình operator. OWNER đó có thể gắn installation
vào workspace đích mà chính họ cũng sở hữu. Đây là chính sách dùng chung kết nối của người quản lý;
không phải GitHub OAuth tự phục vụ hoặc phân quyền riêng từng repo trong cùng một tài khoản quản lý.

GITHUB_WORKSPACE_ID/GITHUB_INSTALLATION_ID được giữ làm binding bootstrap tin cậy; không còn chặn worker
ở workspace khác đã được cấp liên kết. App ID, private key và webhook secret dùng chung ở server.
App/installation khác cần operator xác minh và phê duyệt binding nguồn, chưa có UI tự cấp quyền bằng ID.
MEMBER/ADMIN vẫn không quản lý kết nối. Tạo workspace không tự có quyền GitHub.

Liên kết đã cấp trở thành quyền của workspace đích. Thay đổi membership ở workspace nguồn sau đó không
tự hủy liên kết đã cấp; webhook thu hồi/suspend installation áp dụng mọi workspace. Audit giữ actor,
workspace nguồn và số repo đã chọn để đối soát việc cấp liên kết.

## API sau `/api/v1/workspaces/{workspaceId}`

| Method/path | Hành vi |
|---|---|
| GET `/github/connection` | Liên kết hiện tại/version/repo IDs được chọn và installation mà OWNER được phép dùng |
| GET `/github/installations/{id}/repositories` | Catalog mới từ GitHub sau kiểm quyền nguồn/đích; không lưu repo chỉ vì xem |
| POST `/github/connection` | `{installationId, version, repositoryIds}`; version null khi mới, 1–1000 ID dương, không trùng |
| POST `/github/sync` | Refresh đúng connection hiện tại; giữ lựa chọn, không tự chọn repo mới |
| GET `/repositories` | Danh sách có active/trackingEnabled; giữ cả lịch sử repo đã bỏ chọn |

Nguồn/đích không hợp lệ trả 404, không phải OWNER trả 403, thiếu cấu hình trả 503. Version/selection
thay đổi trả 409 và cần tải lại. BFF chỉ mở path/method chính xác, mutation kiểm Origin, giới hạn body
connection 64 KiB. Không chuyển JWT sang client hoặc lưu private key/token installation trong DB/UI.

## Transaction, đồng thời và webhook

Provider I/O nằm ngoài transaction. Sau fetch, khóa advisory theo external installation ID, rồi khóa các
workspace theo thứ tự UUID; kiểm lại OWNER nguồn/đích, version nguồn và version đích trước ghi. Webhook
cùng installation dùng cùng thứ tự khóa. Grant bị thu hồi hoặc webhook đổi selection lúc fetch không
được biến thành liên kết mới từ snapshot cũ. Catalog cũng kiểm lại quyền trước trả kết quả.

Webhook HMAC/inbox giữ nguyên, sau xác thực fan-out vào mọi binding đã lưu. Repo mới chỉ được GitHub cấp
quyền không tự tham gia mọi workspace: chỉ repo đã được chọn mới có thể active. Suspend/deleted vô hiệu
hóa mọi binding; unsuspend cần REST sync lại. Worker lấy installation ID/token từ scope DB và kiểm scope
lại sau fetch; không dùng mặc định của bootstrap cho workspace khác.

V15 đổi unique installation từ global sang workspace, thêm unique một connection/workspace và tracking flag
mặc định true cho repo cũ. Composite FK giữ tenant isolation. Unique outbox đổi từ một event/delivery sang
`(source_delivery_id, aggregate_id)` để cho phép fan-out, vẫn chống trùng theo repository. Inbox ghi metadata
installation nội bộ khi chỉ có một binding; nhiều binding để null, outbox chỉ rõ repo/workspace. Không lưu
raw webhook hoặc signed URL. Thay đổi kết nối có audit cùng transaction.

## Kiểm chứng

`scripts/backend.ps1 -Task verify` dùng Testcontainers riêng; frontend `npm run test:ui`, build, typecheck,
format và kiểm axe ở 1440/390/320px. Các fixture mới kiểm chọn riêng hai workspace, không đoán ID để lấy
quyền, stale version, thu hồi OWNER/quyền GitHub trong lúc fetch, fan-out không trùng và token installation
502 khác bootstrap. Test migration giữ active/tracking, lịch sử AI, tri thức và vector cũ.

Không gọi GitHub/AI thật hoặc rebuild stack shipping trong các test này. Kết quả local và CI remote được
báo riêng; không xem fixture là bằng chứng cấu hình GitHub App đã được cấp quyền cho repo OfficeFlow thật.

Kết quả local ngày 10/11/2026: Maven verify **162/162** (0 failures/errors/skipped), **87/87 UI fixture**,
production build, TypeScript và format đạt. Đã xem render dialog 320px; axe và overflow đạt tại 1440×900,
390×844, 320×812. Source version thay đổi lúc fetch trả 409, OWNER bị thu hồi trả 404 và không tạo binding.
Phần kiểm chứng này không thay đổi `.env.shipping`, không chuyển liên kết thật sang workspace mới và không
đụng volume shipping; người dùng cập nhật stack rồi chọn đúng repo theo các bước trên.
