# Phase 10 — Tra cứu, tri thức và số liệu sử dụng

Sáu phần theo yêu cầu: (1) nháp từ báo cáo AI; (2) tự lưu lịch sử và kết quả tra cứu riêng cho người dùng/workspace; (3) tách bộ lọc nghiệp vụ và tra vector; (4) làm rõ bố cục/màu sắc; (5) biểu đồ dữ liệu vận hành; (6) phân tích token AI.

Đợt rà soát tiếp theo theo ECC, các lỗi đã tái hiện và cách sửa: [ECC review](ecc-review.md).

## 1. Nháp từ báo cáo AI

Trong báo cáo SUCCEEDED, chọn **Thêm vào bản nháp tri thức**. Biểu mẫu tải báo cáo trong workspace hiện tại, điền tiêu đề sự cố, nguyên nhân nghi ngờ và đề xuất khắc phục. Chỉnh sửa rồi chọn **Lưu bản nháp**. Không gọi AI, embedding hoặc tự duyệt khi mở/lưu nháp.

Migration V10 giữ sourceAnalysisId và composite FK với workspace/incident. Backend kiểm tra báo cáo SUCCEEDED, cùng workspace và sự cố. Nguồn AI cho phép tạo nháp khi sự cố chưa RESOLVED; nguồn thủ công gắn incident vẫn yêu cầu RESOLVED. Quyền sửa, version, redaction và duyệt OWNER/ADMIN giữ nguyên. Người duyệt chịu trách nhiệm xác nhận nội dung trước khi công bố vào RAG.

Mỗi phần được commit/push riêng. Kiểm chứng fixture, Maven và giao diện được ghi khi hoàn tất; không đồng nhất với triển khai hoặc độ đúng của mọi kết luận AI.

Kiểm chứng phần 1: báo cáo SUCCEEDED có thể điền nháp trước khi RESOLVED, người dùng sửa rồi lưu DRAFT; nguồn không hợp lệ hoặc sai workspace bị từ chối. Lưu nháp không gọi embedding.

## 2. Lịch sử tra cứu tự động

POST tra vector thành công tự lưu truy vấn đã che, cách tìm, topK và toàn bộ kết quả có revision/hash/độ tương đồng ở server (V11). Cả kết quả rỗng được lưu. Request lỗi không tạo lịch sử. Mỗi bản ghi gắn user từ JWT và workspace; quản lý không được xem lịch sử của tài khoản khác. GET danh sách/chi tiết không gọi embedding. Có thể xóa từng lần tra của chính mình. Snapshot giữ nguyên khi bài gốc sửa hoặc archive, UI ghi rõ đây là kết quả lịch sử.

API: GET knowledge-articles/search-history?page=0&size=10; GET/DELETE search-history/{id}. BFF chỉ mở đúng method/path, DELETE kiểm tra Origin. RAG dùng nội bộ để phân tích không được ghi thành lịch sử tra thủ công.

Kiểm chứng phần 2: snapshot còn nguyên sau archive; truy vấn được che dữ liệu nhạy cảm; chỉ chủ lịch sử có quyền đọc/xóa. Kết quả rỗng được lưu; request không hợp lệ không được lưu.

## 3. Tách tiêu chí nghiệp vụ và vector

Sự cố hỗ trợ từ khóa tiêu đề, trạng thái, repositoryId, assignedTo, severity. SQL lọc trước count/phân trang, phạm vi toàn workspace. Kho tri thức lọc từ khóa trong tiêu đề/nguyên nhân/khắc phục và trạng thái. Dùng tham số SQL và substring literal, ký tự % không biến thành wildcard. Bộ lọc không gọi AI hoặc tạo lịch sử vector.

Trang /knowledge chỉ hiển thị bài viết; /knowledge/search dành cho vector; /knowledge/history dành cho lịch sử. Navigation có nhãn rõ; đổi workspace xóa trạng thái cũ. BFF chuyển tiếp đúng danh sách query param được phép theo route.

Kiểm chứng phần 3: fixture tạo 23 sự cố để kiểm tra lọc trước phân trang; tìm kiếm bài viết hoạt động khi AI tắt, xử lý % như ký tự thường, kiểm tra validation và workspace.

## 4. Bố cục và màu có ý nghĩa

Báo cáo tách vùng tóm tắt, nguyên nhân nghi ngờ, đề xuất khắc phục và bằng chứng. Nút tạo nháp nằm ngay tiêu đề kết quả. Metadata mô hình/lượt gọi và hướng dẫn viết bài mở khi cần. Tra vector chia vùng nhập và vùng kết quả ở desktop, xếp dọc trên mobile. Xem nguồn thiết kế và thư viện trong docs/ui-improvements.md.

Kiểm chứng phần 4: đã xem ảnh render và chạy axe/overflow ở 1440/390/320 px cho báo cáo, editor, dialog và tra vector. Bàn phím/focus, empty/loading/error và luồng mở metadata đạt kiểm tra.

## 5. Biểu đồ hoạt động

GET /dashboard?days=7|30|90 tổng hợp toàn workspace trong một snapshot: run thành công/thất bại theo ngày ghi nhận, sự cố theo ngày phát hiện và trạng thái hiện tại của sự cố trong kỳ. Điền ngày không hoạt động bằng 0; nhóm theo UTC, gồm ngày hiện tại đến lúc gọi. Không suy ra tổng từ danh sách 5/20 mục. Ngừng theo dõi repo không xóa lịch sử khỏi thống kê.

Frontend dùng Recharts 3.10.1 và react-is 19.3.1; run hiển thị cột xếp chồng, sự cố là đường riêng, có bảng số liệu theo ngày và số lượng theo trạng thái. Thêm analytics.spec.ts vào test:ui/CI. Giữ license của thư viện ở frontend/THIRD_PARTY_NOTICES.md.

Kiểm chứng phần 5: API tổng hợp đủ dữ liệu, điền ngày trống, kiểm tra quyền và kỳ hợp lệ. UI kiểm tra đổi kỳ, đổi workspace, trống, lỗi/thử lại, bảng số liệu và responsive/accessibility ở ba kích thước.

## 6. Token và chi phí AI

Trang /usage (Tiêu thụ AI) gọi GET /ai-usage?days=30&operation=CHAT&model=... trong workspace. Chọn 7/30/90 ngày lịch UTC, CHAT/EMBEDDING hoặc cả hai và mô hình. API nhận days 1–90, model tối đa 120 ký tự, operation hợp lệ. Tổng hợp trong một REPEATABLE_READ snapshot, bao gồm mọi attempt và embedding độc lập không gắn analysis.

Có tổng input/output token đã ghi nhận, số lượt theo trạng thái, chi phí đã có ước tính, biểu đồ token/chi phí theo ngày, bảng theo mô hình/operation/provider và bảng từng ngày. Mỗi tổng có số mẫu để biết mức đầy đủ. Null giữ nghĩa chưa có dữ liệu; ngày không có lượt gọi hiển thị 0. Chi phí thiếu không quy thành 0, tổng đã biết không được trình bày như tổng hóa đơn. Chỉ đọc dashboard không gọi AI. Bảng hỗ trợ cuộn ngang bằng bàn phím trên màn hình nhỏ.

Không thêm migration cho thống kê: đọc ai_usage_events hiện có theo workspace/ngày, không join feedback làm nhân số lượt. Trạng thái UNKNOWN vẫn có thể đã bị provider tính phí. Không thêm mức giá giả; dùng estimated_cost_usd đã ghi tại thời điểm gọi.

## Kiểm chứng toàn bộ và cập nhật local

- Backend: scripts/backend.ps1 -Task verify — **140/140 đạt**, 0 failures/errors/skipped. Sau khi trình bày lại SQL, Phase10 chạy lại **5/5 đạt**.
- Frontend: **72/72 UI đạt** theo test:ui (đã thêm analytics và usage vào CI), production build, TypeScript và format:check đạt.
- Giao diện: axe theo WCAG 2 A/AA và 2.1 AA, không tràn ngang ở 1440×900, 390×844, 320×812; đã xem ảnh render. Impeccable detector **0 finding** trên source thay đổi.
- Backend dùng PostgreSQL/RabbitMQ tạm và HTTP fixture; UI dùng API fixture. Lần kiểm chứng này không gọi provider AI thật và không chứng minh độ đúng của mọi chẩn đoán/triển khai production.

Tại root, cập nhật stack đang có:

```powershell
.\scripts\shipping.ps1 -Task up
.\scripts\shipping.ps1 -Task status
```

Mở http://127.0.0.1:13000, vào Tổng quan, Kho kiến thức hoặc Tiêu thụ AI. Flyway tự áp dụng V10/V11 khi backend khởi động. Giữ .env.shipping, compose.shipping.local.yaml và volume; không reset database. Cổng DBeaver shipping vẫn là 127.0.0.1:15433 theo override local.

Lịch sử tra cứu không tự hết hạn; người dùng có thể xóa từng lần tra. V11 chỉ lưu các lần tra thành công kể từ khi cập nhật, không khôi phục truy vấn cũ chưa từng được lưu.
