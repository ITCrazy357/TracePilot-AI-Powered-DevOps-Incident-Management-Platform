# Phase 10 — Tra cứu, tri thức và số liệu sử dụng

Sáu phần theo yêu cầu: (1) nháp từ báo cáo AI; (2) tự lưu lịch sử và kết quả tra cứu riêng cho người dùng/workspace; (3) tách bộ lọc nghiệp vụ và tra vector; (4) làm rõ bố cục/màu sắc; (5) biểu đồ dữ liệu vận hành; (6) phân tích token AI.

## 1. Nháp từ báo cáo AI

Trong báo cáo SUCCEEDED, chọn **Thêm vào bản nháp tri thức**. Biểu mẫu tải báo cáo trong workspace hiện tại, điền tiêu đề sự cố, nguyên nhân nghi ngờ và đề xuất khắc phục. Chỉnh sửa rồi chọn **Lưu bản nháp**. Không gọi AI, embedding hoặc tự duyệt khi mở/lưu nháp.

Migration V10 giữ sourceAnalysisId và composite FK với workspace/incident. Backend kiểm tra báo cáo SUCCEEDED, cùng workspace và sự cố. Nguồn AI cho phép tạo nháp khi sự cố chưa RESOLVED; nguồn thủ công gắn incident vẫn yêu cầu RESOLVED. Quyền sửa, version, redaction và duyệt OWNER/ADMIN giữ nguyên. Người duyệt chịu trách nhiệm xác nhận nội dung trước khi công bố vào RAG.

Mỗi phần được commit/push riêng. Kiểm chứng fixture, Maven và giao diện được ghi khi hoàn tất; không đồng nhất với triển khai hoặc độ đúng của mọi kết luận AI.

Kiểm chứng phần 1: production build và TypeScript đạt; 15 test UI hiện có đạt, test điền nháp AI mới đạt khi chạy lại sau sửa fixture. Maven verify chạy 134 test: 133 đạt, test mới dùng sai câu trích fixture; sau sửa, toàn bộ Phase06 chạy lại 15/15 đạt. Các test dùng Testcontainers/HTTP fixture, không gọi provider thật.

## 2. Lịch sử tra cứu tự động

POST tra vector thành công tự lưu truy vấn đã che, cách tìm, topK và toàn bộ kết quả có revision/hash/độ tương đồng ở server (V11). Cả kết quả rỗng được lưu. Request lỗi không tạo lịch sử. Mỗi bản ghi gắn user từ JWT và workspace; quản lý không được xem lịch sử của tài khoản khác. GET danh sách/chi tiết không gọi embedding. Có thể xóa từng lần tra của chính mình. Snapshot giữ nguyên khi bài gốc sửa hoặc archive, UI ghi rõ đây là kết quả lịch sử.

API: GET knowledge-articles/search-history?page=0&size=10; GET/DELETE search-history/{id}. BFF chỉ mở đúng method/path, DELETE kiểm tra Origin. RAG dùng nội bộ để phân tích không được ghi thành lịch sử tra thủ công.

Kiểm chứng phần 2: 17/17 UI, TypeScript và production build đạt. Verify chạy 135 test; test mới ban đầu kiểm tra nhầm tài khoản helper. Đã sửa và chạy lại toàn bộ Phase06: 16/16 đạt; các phase còn lại đạt trong verify. Kiểm tra snapshot sau archive, redaction, quyền riêng tư, workspace, xóa và kết quả rỗng bằng DB tạm/HTTP fixture.
