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

## 3. Tri thức có cấu trúc và xác nhận của người duyệt

V13 bổ sung `symptoms` (1.000 ký tự), `applicability` (1.000), `verification` (1.500).
Draft cho phép để trống; PATCH bỏ qua trường mới giữ giá trị hiện có, gửi chuỗi rỗng thì xóa.
Mọi trường được redaction trước khi lưu/chunking; lọc nghiệp vụ cũng tìm trong các trường mới.
Bản nháp AI điền triệu chứng từ tóm tắt, không tự xác nhận phạm vi hoặc kết quả kiểm chứng.

POST approve yêu cầu `{version, verified:true}` và đủ ba trường. Kiểm quyền OWNER/ADMIN,
version/khóa workspace vẫn áp dụng trước xử lý; thiếu xác nhận trả 400, thiếu nội dung trả 409,
không gọi embedding. UI có checkbox xác nhận riêng cho từng lần duyệt. Người duyệt chịu trách nhiệm
về dữ kiện; checkbox không chứng minh cách sửa đã chạy thành công. Chunks gắn nhãn triệu chứng,
phạm vi, nguyên nhân, khắc phục và kiểm chứng để RAG đọc đúng vai trò.

Giữ nguyên bài APPROVED/chunks đã có từ trước migration; không gán kết quả kiểm chứng giả,
không tự tạo embedding hoặc thu hồi bài cũ. Lần sửa/duyệt tiếp theo phải bổ sung các trường mới.
Như trước, sửa bài đã duyệt đưa về DRAFT và loại revision cũ khỏi truy xuất ngay.

Kiểm chứng phần 3: Maven verify 149/149 đạt sau khi sửa DTO cho request không có verified;
knowledge UI 23/23 và hồi quy knowledge/operations/quality 45/45 đạt, production build đạt.
Đã xem ảnh editor 320px; axe và kiểm tra tràn ngang ở 1440/390/320px đạt.

## 4. Báo cáo nêu giả thuyết và bước kiểm chứng

Prompt v4 và schema yêu cầu `assessmentStatus`: HYPOTHESIS, INSUFFICIENT_EVIDENCE hoặc
NO_FAILURE_INDICATED. Có `missingInformation` (0–5 mục, phải có khi thiếu bằng chứng),
`verificationSteps` (1–5 mục); mỗi mục 8–500 ký tự, không trùng. V14 lưu các trường này;
báo cáo cũ giữ phân loại null và danh sách rỗng. UI hiển thị rõ báo cáo cũ thay vì tự gán nhãn.

SUCCEEDED chỉ có nghĩa xử lý/kiểm tra cấu trúc thành công, không phải đã chẩn đoán chính xác.
Không có phần trăm tin cậy tự đặt; mọi hướng sửa/bước kiểm tra đều là đề xuất chưa chạy.
Prompt yêu cầu phân biệt negative test, exit code thiếu dữ kiện và nguyên nhân nghi ngờ, không
bỏ TLS/auth/test, xóa dữ liệu hoặc in secret để làm CI xanh. Đây là ràng buộc hướng dẫn model,
không phải chứng minh tự động rằng mọi câu trả lời đều an toàn/đúng. Quote vẫn phải có nguồn thật.

Khi đổi prompt/ngân sách, yêu cầu đang chờ của phiên bản cũ có thể kết thúc AI_SOURCE_CHANGED;
tạo yêu cầu mới sau khi xem trạng thái. Không sửa/ghi đè báo cáo lịch sử hoặc tự chạy lại có tính phí.

Kiểm chứng phần 4: Maven verify 152/152 đạt, gồm parser và persistence của nhận định/dữ kiện thiếu;
45 ca UI knowledge/quality/operations khác đạt, ca UI mới chọn lại đúng nút refresh trong tab AI
và chạy lại operations 13/13 đạt. Production build, axe/overflow báo cáo mới ở 1440/390/320px đạt;
đã xem ảnh render 320px. Không gọi model thật để suy ra mức chính xác.
