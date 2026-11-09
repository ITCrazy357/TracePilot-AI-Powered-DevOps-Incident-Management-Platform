# Kho kiến thức — giao diện và tra cứu

Luồng hiện tại gồm bài viết, bộ lọc nghiệp vụ, tra vector và lịch sử riêng. Các thay đổi backend/frontend mới, migration V10–V11 và số liệu token được mô tả ở [Phase 10](phase-10.md). Tham chiếu quyền, revision và hành vi indexing gốc ở [Phase 06](phase-06.md). Giữ thiết kế [Clean Precision](../DESIGN.md).

## Luồng sử dụng

1. Mở **Kho kiến thức → Viết bài mới**. Nhập tiêu đề, nguyên nhân đã xác nhận và cách khắc phục đã kiểm tra; lưu thành DRAFT. Không cần bật AI để tạo/sửa bài.
2. Từ sự cố RESOLVED, chọn **Ghi vào kho kiến thức** để viết bài thủ công gắn nguồn. Hoặc trong báo cáo AI SUCCEEDED, chọn **Thêm vào bản nháp tri thức**: form tự điền đề xuất, cho phép chỉnh sửa rồi lưu DRAFT; cách này không yêu cầu sự cố đã RESOLVED. Backend kiểm tra nguồn phân tích/workspace và giữ sourceAnalysisId. Chưa có thao tác duyệt hay embedding khi lưu nháp.
3. Thành viên sửa draft của mình; OWNER/ADMIN sửa được bài trong workspace. Chỉnh sửa gửi version đã đọc, đưa bài về DRAFT và cần duyệt lại. 409 giữ phần nhập, khóa lưu và yêu cầu tải lại bài; không tự gửi lại với version mới.
4. OWNER/ADMIN chọn **Duyệt bài viết**, kiểm tra nội dung và xác nhận việc gửi tới AI để tạo chỉ mục. Chỉ APPROVED/READY tham gia tìm kiếm. Nếu lỗi hoặc mất response, tải lại bài để xác định trạng thái trước khi quyết định thử lại. RUNNING có lease 120 giây; backend quyết định khi nào được thử lại.
5. **Lưu trữ bài** cần xác nhận và version hiện tại. Bài ngừng tham gia tìm kiếm; nguồn trong báo cáo cũ được giữ. Người quản lý có thể sửa bài đã lưu trữ về DRAFT.
6. **Bài viết** lọc từ khóa/trạng thái ở backend, không gọi AI. **Tra lỗi bằng vector** chỉ gửi request sau khi nhập truy vấn, đồng ý gửi tới AI và nhấn tìm. Chọn HYBRID/EXACT/APPROXIMATE và topK 1–5. Truy vấn đã che và kết quả được tự lưu ở **Lịch sử tra cứu**, riêng theo tài khoản/workspace; xem lại không gọi AI. Điểm cosine là độ tương đồng, không phải độ chắc chắn của chẩn đoán. Đổi workspace xóa trạng thái màn hình cũ.
7. Báo cáo AI tải `/evidence`, phân biệt LOG và KNOWLEDGE. Nguồn knowledge hiển thị quote, contentVersion và SHA đã lưu, dẫn đến bài hiện tại. Nếu revision khác, trang báo rõ; API hiện chưa có route đọc đầy đủ article lịch sử. Lỗi tải evidence không che mất báo cáo. Câu trích của báo cáo cũ chưa có mapping không được trình bày như nguồn đã xác nhận.

## Bảo vệ và cấu hình

Next chỉ mở đúng các method/route knowledge và evidence theo workspace UUID. Giữ cookie HttpOnly, Origin validation và backend RBAC. Request knowledge giới hạn 64 KiB để đủ cho nội dung tiếng Việt theo giới hạn 160/3.000/6.000 ký tự của Java; các request khác giữ giới hạn 8 KiB. Approve/search có timeout 45 giây, dài hơn deadline provider mặc định 30 giây. Không tự retry publication hoặc search vì có thể phát sinh chi phí; nếu cấu hình deadline provider lớn hơn cần đối soát timeout proxy tương ứng.

Khi AI/RAG tắt, backend trả 503 cho approve/search và giao diện giải thích cách tiếp tục; việc viết/đọc bản nháp vẫn hoạt động. Không suy đoán trạng thái tích hợp từ việc danh sách bài rỗng.

## Hợp đồng thiết kế và kiểm chứng

- Công việc chính: lưu kiến thức đã kiểm tra và tra cứu để điều tra lỗi, dành cho developer đang dùng dashboard. Hành động chính của danh sách là viết bài; trang bài tập trung nội dung, quyền duyệt ở cột ngữ cảnh.
- Bài viết, vector và lịch sử có trang riêng. Trang vector đặt biểu mẫu cạnh kết quả ở desktop, xếp dọc ở mobile. Dùng token/font/component hiện tại; Recharts được thêm cho dashboard/token ở Phase 10.
- Viewport mục tiêu: **1440x900, 390x844, 320x812**. Tiêu đề dài xuống dòng, textarea co theo khung, menu và nội dung không tràn ngang; xác nhận dùng Radix với bàn phím/focus trả về nút mở.
- Loading có nhãn tiến độ; empty giải thích và dẫn đến viết bài; lỗi tải có retry; lỗi indexing yêu cầu đọc lại; partial giữ báo cáo khi evidence lỗi; success hiển thị trạng thái đã lưu; quyền không cho phép thì ẩn mutation và giải thích vai trò.

Lệnh kiểm tra tại `frontend/`:

```powershell
npm run format:check
npm run typecheck
npm run build
npm run test:ui
```

Live test riêng yêu cầu backend hiện tại với AI/RAG tắt. Chạy `.\scripts\backend.ps1 -Task preview` tại root để dùng database Testcontainers riêng ở port backend 18080, rồi mở terminal mới:

```powershell
cd frontend
$env:BACKEND_URL = 'http://127.0.0.1:18080'
$env:TRACEPILOT_LIVE = '1'
npx playwright test tests/live-knowledge.spec.ts
```

Nếu Next đã chạy, phải khởi động lại để nạp BACKEND_URL. Fixture tests kiểm tra UI/quyền/concurrency/trạng thái/responsive/axe. Live test đi qua Next/Spring để kiểm tra lưu tiếng Việt lớn hơn 8 KiB, chỉnh sửa, version 409, role/tenant và trạng thái AI/RAG tắt. Các tài khoản `frontend-knowledge-*` cùng bài thử được thêm vào database đích; không dùng database có dữ liệu quan trọng khi muốn phép thử tạm thời. Không bật provider thật trong test này. Bằng chứng ảnh/report local ở `.verification/knowledge-ui/`; kết quả fixture không chứng minh chất lượng embedding hoặc provider thật.

## Kết quả local — 09/11/2026

| Kiểm tra | Kết quả |
| --- | --- |
| Next production build, TypeScript, Prettier | Đạt |
| Browser fixture | 43 ca hiện có và 15 ca knowledge đạt; ca phân trang mới đã được sửa assertion và chạy lại toàn bộ 15 ca |
| Tích hợp thật | 1 ca mới qua Next/Spring/PostgreSQL đạt: lưu/sửa tiếng Việt dài, version 409, quyền và workspace, AI/RAG tắt |
| Responsive/accessibility | Danh sách, editor và dialog tại 1440x900, 390x844, 320x812: không tràn ngang, axe không có vi phạm theo rule đã chạy; bàn phím/focus đạt |
| Impeccable | Detector source trên thành phần mới/CSS: 0 finding; đã xem ảnh render |
| Chưa kiểm chứng | Provider AI/embedding thật, chất lượng semantic search, public deployment; không chạy lại Maven vì không sửa backend/migration |

Log riêng ở `.verification/knowledge-*.log`, bản ghi thiết kế ở `.verification/knowledge-ui/report.json`. Backend preview và các container Testcontainers của lần kiểm chứng đã được thu hồi; database Compose gốc được giữ nguyên. Xem [Phase 09](phase-09.md) để chạy stack đóng gói với volume riêng và cấu hình provider chủ động.
