# Phase 11 — Cải thiện dữ kiện và đánh giá phân tích AI

Triển khai theo năm phần: chọn log chẩn đoán; ngữ cảnh lần chạy; tri thức có cấu trúc và xác minh;
báo cáo phân biệt giả thuyết/thông tin thiếu/bước kiểm tra; bộ đánh giá có đáp án.
Các kiểm tra fixture bảo vệ hợp đồng và dữ liệu, không chứng minh độ chính xác của model thật.

## 1. Chọn log chẩn đoán

`DiagnosticLogSelector` chọn các vùng nguyên văn quanh lỗi, ưu tiên `Caused by` và giữ phần cuối.
Áp dụng sau redaction khi lưu log (32 KiB ký tự), chuẩn bị prompt, rút truy vấn RAG (2.000 ký tự)
và nhường ngân sách cho tri thức. Đoạn bị bỏ có dấu `... omitted ...`; không coi dấu này là bằng chứng.
Giữ giới hạn hiện có, cặp surrogate Unicode, hash và đối chiếu trích dẫn với nguồn đã lưu.
Log ngắn giữ nguyên; không có tín hiệu lỗi thì dùng phần cuối. Đây là heuristic, có thể chọn nhầm
ERROR của negative test; bộ đánh giá phần 5 kiểm tra trường hợp này. Không tự xác nhận nguyên nhân.

Các log cũ không được tải lại hoặc ghi đè. Nếu nguyên nhân đã bị bỏ ở lần thu thập cũ, phải thu
thập log mới theo cơ chế được phép. Không sửa migration đã áp dụng hoặc reset database.

Kiểm chứng phần 1: `scripts/backend.ps1 -Task verify` đạt 145/145 test trên Testcontainers,
gồm 4 ca mới về lỗi đầu log/chuỗi nguyên nhân, Unicode, fallback và redaction. Không gọi AI thật.
