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

## 2. Ngữ cảnh của lần chạy

POST analyses nhận thêm `additionalContext` tùy chọn, tối đa 2.000 ký tự. UI có ô nhập lệnh,
step, runtime/OS và cấu hình liên quan; không nhập bí mật. Backend ghép metadata đã có trong DB
(repository/workflow/branch/commit/attempt/job) rồi redaction và giới hạn ngân sách tối đa 4.000 ký tự,
không quá khoảng một phần tư prompt. V12 lưu `run_context` của từng analysis; báo cáo cũ để null.
Giao diện cho xem snapshot đã gửi. Metadata hoặc bổ sung thủ công không được dùng làm nguồn quote.

Worker dùng lại snapshot khi retry; hash bao gồm cả context và log. Replay requestId không thay
snapshot đã nhận; frontend giữ cùng body khi thử gửi lại. Không tải file repository, cấu hình,
diff hoặc phiên bản runtime qua GitHub trong phần này. Những dữ kiện chưa có được ghi rõ là chưa biết,
người dùng có thể cung cấp qua ô bổ sung. Prompt v3 yêu cầu không tự suy diễn dữ kiện thiếu.

Kiểm chứng phần 2: Maven verify 146/146 đạt; sau guard khi giảm ngân sách prompt,
chạy thêm AnalysisInputTests/RagContextTests 3/3 đạt (2 ca hash/ngân sách mới).
UI operations 12/12 đạt, gồm giữ nguyên context khi retry và xem snapshot; production build đạt.
