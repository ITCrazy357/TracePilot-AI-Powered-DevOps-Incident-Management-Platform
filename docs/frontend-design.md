# Frontend — hợp đồng thiết kế Clean Precision

Tham chiếu: `Design_TracePilot/tracepilot_clean_precision/DESIGN.md`, ảnh Tổng quan, Lần chạy, Sự cố, Log, Phân tích AI và Thành viên. Quyết định áp dụng ở [DESIGN.md](../DESIGN.md), phạm vi sản phẩm ở [PRODUCT.md](../PRODUCT.md). Backend/DTO Phase 01–05 là nguồn dữ liệu; hình mẫu là nguồn thị giác.

Thiết kế cho developer điều tra CI thường xuyên. Mục tiêu: từ lần chạy/sự cố đến log và bằng chứng, xem đề xuất AI trước khi tự quyết định. Nền `#F5FAFE`, panel trắng, chữ navy, điều hướng xanh đậm, điểm điều tra hồng. Be Vietnam Pro cho nội dung và JetBrains Mono cho mã/dữ liệu máy. Bỏ nhóm thẻ trang trí và số liệu không có API, giữ cấu trúc 2:1 ở desktop và một cột trên mobile.

Đã áp dụng Impeccable từ commit `924bbd194d02e5e15a227e75b5901fb0d7a4ecad`: context, Operate, distill, craft floor và polish. Tôn trọng thiết kế đã được người dùng chọn; không tổ chức lại vòng chọn phong cách. Clone/engine nằm trong `.tools/`, không thêm dependency sản phẩm hoặc hook toàn cục. Detector cơ học là một bước kiểm tra; ảnh render và test thao tác mới kiểm tra trải nghiệm.

Viewport: **1440x900, 390x844, 320x812**. Sidebar 224px; mobile điều hướng mở rộng trong luồng trang. Chỉ terminal log được cuộn ngang. Tên dài/UUID xuống dòng; nút đủ diện tích chạm; focus ring rõ; Radix xử lý dialog/tab, bàn phím và trả focus. Motion nhẹ khi đổi tab, tôn trọng reduced motion.

| Trạng thái | Trigger | Phản hồi và phục hồi |
|---|---|---|
| Loading | Đọc API/phiên | Nhãn `role=status`; giữ dữ liệu trong cùng scope khi làm mới |
| Empty | Chưa có workspace/repos/run/incident | Hướng dẫn bước tiếp theo phù hợp quyền |
| Error | Backend/provider lỗi | Lỗi tại panel/form, nút thử lại; 409 cần đọc version mới |
| Partial | Một nguồn lỗi | Giữ phần dữ liệu khác; không thay dữ liệu thiếu bằng số giả |
| Success | Tạo/sửa/đánh dấu/yêu cầu đồng bộ | Xác nhận và dữ liệu mới; 202 diễn đạt đã xếp hàng |
| Permission | MEMBER/ADMIN/OWNER và assignee | UI bám quyền; Spring vẫn xác thực từng request |
| AI consent | Yêu cầu phân tích | Xác nhận gửi log/chi phí; requestId không đổi khi retry |
| Stream disconnected | SSE ngắt/mất mạng/hết access cookie | Kết nối lại có backoff/cursor; làm mới thủ công luôn có |

Không sao chép các dữ kiện hư cấu của hình mẫu: tỷ lệ thành công, mức độ P1 suy luận, độ tin cậy AI, đã kết nối GitHub, billing, RAG hoặc zero data retention. Không thêm nút thực thi patch/push/deploy. Thành viên hiện chỉ có UUID/role/status nên không bịa tên, email hoặc avatar.

Kiểm chứng local ở `.verification/redesign/`: screenshot tổng quan, báo cáo AI và log tại cả ba viewport; JSON axe; log Playwright, Maven và Impeccable detector. Test UI dùng fixture; live test dùng Next/Spring/PostgreSQL/RabbitMQ thật cho các ranh giới đã nêu. Chưa chứng minh provider AI/GitHub thật hoặc triển khai production.
