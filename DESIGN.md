# TracePilot — Clean Precision

Nguồn thị giác có thẩm quyền: `Design_TracePilot/tracepilot_clean_precision/DESIGN.md`, các `screen.png` và `tracepilot_ui_prompts.md`. Áp dụng hướng dẫn Impeccable từ commit `924bbd194d02e5e15a227e75b5901fb0d7a4ecad`.

- Nền `#F5FAFE`, surface `#FFFFFF`, chữ `#10243A` / `#475569`, đường phân cách `#DDE8F0`.
- Xanh `#0297DE` cho hình/đường dấu vết; xanh đậm `#00618E` cho chữ nhỏ và nút có chữ trắng để đạt tương phản. Hồng `#D83995` cho điểm điều tra, `#9D2368` cho chữ trên nền `#FDF2F8`. Xanh lá/amber chỉ dùng trạng thái.
- Font tự host Be Vietnam Pro 400/500/600/700; JetBrains Mono cho ID, SHA, log. Body 14px, phụ 12px, heading 28/22/16px; không dùng nhãn trang trí phía trên heading.
- Sidebar desktop 224px. Dashboard chia 2:1 giữa luồng điều tra và ngữ cảnh; mobile xếp dọc và điều hướng mở rộng. Panel radius 12px, control 8px, spacing theo nhịp 8px. Border giữ cấu trúc; shadow dành cho dialog. Không gradient/glass.
- Nhận diện: hai điểm xanh nối qua một điểm hồng. Không logo bị lỗi/ảnh avatar mẫu. Status có nhãn và icon; dữ liệu thiếu hiện dấu gạch hoặc giải thích.
- Một hành động chính mỗi ngữ cảnh. Lần chạy phải chọn repository do API scope theo repo. Incident có ba tab: Tổng quan, Log đã che, Phân tích AI. Thao tác AI có xác nhận gửi log và requestId ổn định khi thử lại.
- Dữ liệu mẫu trong HTML thiết kế không phải dữ liệu sản phẩm. Không hiện confidence AI, số liệu 7 ngày, kết nối healthy, hóa đơn, RAG hoặc nút sửa/push nếu không có bằng chứng/API.
