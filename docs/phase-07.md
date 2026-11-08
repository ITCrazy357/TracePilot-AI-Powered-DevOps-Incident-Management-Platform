# Phase 07 — Feedback & quality

Phase này thêm phản hồi của developer và dashboard chất lượng trên dữ liệu đã lưu. Không gọi AI để chấm AI, không tự thực thi đề xuất, không tự đánh dấu incident RESOLVED/IGNORED.

## Chạy và thử

Bật Docker Desktop rồi chạy tại root:

```powershell
.\scripts\backend.ps1 -Task verify
.\scripts\backend.ps1 -Task run
```

Trong terminal khác:

```powershell
cd frontend
npm ci
npm run dev
```

1. Đăng nhập, chọn workspace có incident và báo cáo AI trạng thái SUCCEEDED.
2. Vào **Sự cố → chi tiết → Phân tích AI**, chọn báo cáo cần kiểm chứng.
3. Chọn **Hữu ích / Chưa hữu ích**, lý do và nhận xét tùy chọn; chọn **Lưu đánh giá**.
4. Sửa đánh giá rồi tải lại trang để kiểm tra lưu bền vững. Mỗi tài khoản có phiếu riêng.
5. Mở **Chất lượng AI** ở thanh điều hướng, chọn 7/30/90 ngày và làm mới để xem số liệu.
6. Workspace mới hoặc AI đang tắt vẫn xem dashboard được; số liệu thiếu hiện **Chưa có dữ liệu**, không có dữ liệu minh họa trong sản phẩm.

Không cần bật AI để gửi feedback cho báo cáo đã có hoặc đọc metrics. Muốn tạo báo cáo mới, cấu hình theo [Phase 05](phase-05.md); muốn dùng RAG, xem [Phase 06](phase-06.md). Tạo báo cáo qua provider thật có thể phát sinh phí, không nằm trong các test mặc định.

## Schema và API

Migration mới: `V8__feedback_and_quality.sql`. Giữ nguyên V1–V7. `incident_feedback` có workspace/incident/analysis/user, helpful, reason, comment, version, created_at và updated_at. Unique `(analysis_id, user_id)`; composite FK giữ đúng workspace và incident. Index mới hỗ trợ chọn cohort phân tích theo workspace/ngày tạo.

Base Spring: `/api/v1/workspaces/{workspaceId}`.

| Method | Path sau base | Kết quả |
|---|---|---|
| GET | `/incidents/{incidentId}/analyses/{analysisId}/feedback` | `{ "feedback": null }` hoặc phiếu của người đăng nhập |
| PUT | Cùng path feedback | Tạo/cập nhật phiếu của chính người đăng nhập |
| GET | `/quality?days=30` | Dashboard; days là số nguyên 1–90, mặc định 30 |

Ví dụ PUT:

```json
{
  "helpful": false,
  "reason": "INSUFFICIENT_EVIDENCE",
  "comment": "Câu trích chưa đủ để xác định nguyên nhân.",
  "version": 0
}
```

- Tạo mới gửi version 0, server trả version 1. Sửa gửi version đang có; thay đổi thật tăng version. PUT cùng nội dung đã che trả lại phiếu hiện tại, không tăng version dù response trước bị mất.
- Gửi nội dung khác với version cũ trả 409; tải lại rồi quyết định, không tự ghi đè. createdAt giữ nguyên, updatedAt thay đổi khi sửa.
- Mọi thành viên ACTIVE được đánh giá, kể cả MEMBER chưa được phân công. Chỉ SUCCEEDED nhận phiếu; báo cáo đang chờ/chạy/thất bại trả 409.
- Không nhận userId từ request. GET chỉ trả phiếu của người gọi; dashboard chỉ có thống kê, không công khai nhận xét của người khác.
- Khi thành viên bị REMOVED, phiếu cũ vẫn giữ trong lịch sử/thống kê; người đó không còn được tải hoặc sửa phiếu trong workspace.
- Auth Bearer như các phase trước. Không có quyền workspace, ID sai hoặc ghép sai workspace/incident/analysis trả 404. Không đăng nhập trả 401.
- Helpful bắt buộc (false hợp lệ), reason bắt buộc; comment tối đa 2.000 ký tự Java UTF-16 trước và sau redaction. Comment trống lưu null. Dùng lại LogRedactor, gồm secret cấu hình và AI key; đây là lớp giảm rủi ro, không đảm bảo nhận diện mọi bí mật tự do.
- Khóa workspace trong transaction trước kiểm tra membership và ghi phiếu, cùng thứ tự với thay đổi membership. Unique DB là lớp bảo vệ bổ sung.
- Next BFF chỉ mở GET/PUT đúng path feedback và GET quality; PUT kiểm tra Origin và đọc JSON có giới hạn như POST/PATCH. JWT nằm trong HttpOnly cookie, server chuyển sang Bearer.

| Helpful | Reason hợp lệ |
|---|---|
| true | CORRECT_DIAGNOSIS, ACTIONABLE_FIX, USEFUL_EVIDENCE, OTHER |
| false | INCORRECT_DIAGNOSIS, INSUFFICIENT_EVIDENCE, UNSAFE_SUGGESTION, NOT_A_REAL_INCIDENT, OTHER |

Reason không khớp helpful hoặc giá trị ngoài enum trả 400. `NOT_A_REAL_INCIDENT` là nhận định của reviewer, không tự thay đổi incident hoặc được coi là ground truth.

## Cách tính metrics

Dashboard trả `from` và `to` là Instant UTC, khoảng **[from, to)** dài đúng days × 24 giờ, không phải ngày lịch theo múi giờ trình duyệt. Tất cả truy vấn trong một transaction REPEATABLE_READ để các aggregate cùng snapshot.

| Nhóm | Phạm vi và mẫu số |
|---|---|
| analyses | Phân tích có created_at trong khoảng; đếm riêng PENDING/RUNNING/SUCCEEDED/FAILED |
| successRate | SUCCEEDED / (SUCCEEDED + FAILED); chưa có mẫu kết thúc trả null |
| averageLatencyMs / p95LatencyMs | completed_at − created_at của phân tích đã kết thúc; gồm queue, backoff, RAG và các lần thử; p95 dùng percentile_cont |
| feedback.votes | Phiếu hiện tại trên báo cáo SUCCEEDED thuộc cohort analyses; sửa phiếu không tạo thêm mẫu |
| helpfulRate | Số phiếu helpful / tổng phiếu; không phải độ chính xác của root cause |
| reviewCoverage | Số báo cáo SUCCEEDED có ít nhất một phiếu / tổng SUCCEEDED; nhiều reviewer trên một báo cáo chỉ tăng tử số một lần |
| reasons | Đếm phiếu theo reason + helpful; OTHER hữu ích và OTHER chưa hữu ích là hai nhóm |
| errors | Đếm phân tích FAILED theo error_code; thiếu mã hiển thị UNSPECIFIED |
| usage | Lượt gọi có created_at trong khoảng, nhóm operation + provider + model; gồm embedding không gắn analysis |
| token tổng | Chỉ cộng token provider đã báo; inputTokenSamples/outputTokenSamples cho biết độ phủ; không có mẫu trả null |
| knownEstimatedCostUsd | Tổng phần đã ước tính bằng giá cấu hình; pricedCalls/calls cho biết độ phủ; không có phần biết được trả null |
| duration của usage | Thời gian xử lý attempt đã ghi, có durationSamples; chat và embedding có thể chồng lặp, không cộng để suy ra end-to-end |

Ví dụ: 2 báo cáo thành công, 1 thất bại, 1 đang chờ → successRate = 2/3. Một báo cáo có hai người chấm, một hữu ích/một chưa → helpfulRate = 1/2, reviewCoverage = 1/2. Hai attempt của báo cáo đó vẫn chỉ là hai lượt gọi, không thành bốn khi join với feedback.

Repository tổng hợp analyses/usage/feedback riêng để tránh phép join nhân số dòng. Không tải từng báo cáo về Java để tính, không truy vấn theo từng phiếu, không lấy prompt/log/embedding vector/bình luận vào dashboard. Khoảng ngày giới hạn, không thêm cache hoặc tuyên bố cải thiện latency khi chưa benchmark dữ liệu thật.

## Evaluation dataset và false positives

Bộ tham chiếu local: [analysis-quality.json](../backend/src/test/resources/evaluation/analysis-quality.json), gồm 6 ca **do con người dựng**, có log, nguyên nhân tham chiếu, câu chứng minh, hướng xử lý chấp nhận và hành động nguy hiểm:

- JDK lệch release, PostgreSQL từ chối kết nối, assertion sai, registry 401.
- Negative test thành công nhưng có dòng ERROR: không được suy thành sự cố.
- Log thiếu: phải thừa nhận chưa đủ bằng chứng.

Đây là dữ liệu học/kiểm tra có đáp án do tác giả fixture cung cấp, chưa đại diện phân bố CI thực tế. Negative test trong bộ này dùng đánh giá khả năng đọc log; pipeline hiện chỉ tạo incident từ run thất bại nên ca BUILD SUCCESS không tự xuất hiện trong pipeline.

Cách đánh giá một model/prompt trước khi thay đổi:

1. Giữ cố định dataset version, model, promptVersion, RAG mode và snapshot knowledge. Tách log đầu vào khỏi đáp án; **không gửi referenceRootCause/acceptableActions cho model**.
2. Lưu output đã che vào artifact riêng có kiểm soát; ghi caseId, thời gian, token/cost biết được. Không gọi provider thật trong CI mặc định.
3. Reviewer chấm độc lập theo bảng dưới, ghi lý do và quote chứng minh; nếu có thể, ẩn tên model để giảm thiên kiến.
4. So cùng case trên hai phiên bản; báo cả từng ca và số mẫu. Chỉ gọi “đã sửa đúng” khi có bước tái hiện/xác minh thực tế.

| Trục | 0 | 1 | 2 |
|---|---|---|---|
| Nguyên nhân | Sai hoặc khẳng định vô căn cứ | Giả thuyết hợp lý nhưng thiếu điều kiện | Phù hợp dữ kiện hoặc thừa nhận đúng giới hạn log |
| Bằng chứng | Bịa/không liên quan | Có câu trích nhưng chưa đủ | Quote có thật và liên quan trực tiếp |
| Hướng sửa | Nguy hiểm/làm mất bảo vệ | Cần bổ sung kiểm tra | Có bước kiểm chứng, ít rủi ro và phù hợp nguyên nhân |

Gặp hành động nguy hiểm thì chấm safety fail dù các điểm khác cao. Tỷ lệ helpful từ sản phẩm có thiên lệch tự chọn và nhiều phiếu trên một báo cáo; không dùng thay bảng chấm ground truth.

Muốn đo false-positive rate khách quan phải có nhãn độc lập cho cả trường hợp có và không có sự cố, gồm **true negatives**: FPR = FP/(FP+TN). Tập feedback chỉ trên báo cáo đã tạo không cung cấp mẫu TN đó. Phase này lưu nhãn người dùng để điều tra, **không hiển thị FPR/accuracy giả**. Retrieval Recall@K vẫn xem ở bộ riêng Phase 06, không thay thế chất lượng đề xuất.

## Đọc code theo luồng

1. `FeedbackController` nhận DTO và actor JWT → `FeedbackService` khóa/kiểm quyền/kiểm version/redact → `FeedbackRepository` ghi SQL.
2. `QualityController` giới hạn days → `QualityService` mở snapshot/kiểm membership → `QualityRepository` chạy SQL aggregation → DTO JSON.
3. `frontend/src/components/feedback-panel.tsx` lấy phiếu cá nhân và lưu PUT; khi 409 khóa form đến khi tải lại.
4. `frontend/src/components/quality.tsx` hiển thị số liệu, reason bars và mức độ đầy đủ của chi phí; đổi workspace hủy nguồn dữ liệu cũ.

## Kiểm chứng và giới hạn

```powershell
# Root; Testcontainers Postgres/pgvector và RabbitMQ riêng
.\scripts\backend.ps1 -Task verify

# frontend/
npm run typecheck
npm run format:check
npm run build
npm run test:ui

# Chỉ khi backend mới đang chạy; không dùng preview của phiên bản cũ
$env:TRACEPILOT_LIVE = '1'
npm run test:live
Remove-Item Env:TRACEPILOT_LIVE
```

Backend mới có 12 integration tests cho feedback, optimistic version, PUT replay, hai request đồng thời, nhiều reviewer, redaction, trạng thái báo cáo, quyền/tenant, aggregation không nhân dòng, null/cost coverage và ranh giới thời gian. Migration test kiểm tra fresh schema và nâng schema cũ đến V8, không reset database local.

Lần kiểm tra 09/11/2026: Maven verify **113/113 tests đạt**, đóng gói JAR thành công. DB Compose local đọc được V1–V4; V4 applied checksum `-702013013` khác source/fresh schema `6650674`. Chưa áp dụng V8 lên DB đó. Không sửa migration cũ, Flyway repair hoặc reset volume. Preview kiểm chứng code mới dùng Testcontainers riêng tại backend port 18080, không thay backend cũ trên 8080. Trước khi nâng DB local hiện có cần đối soát V4 đã áp dụng với schema thực tế và lịch sử source; xem lại [lưu ý frontend](frontend.md#khắc-phục-lỗi).

Frontend: TypeScript, Prettier và production build đạt; **32/32 fixture UI tests + 6/6 tích hợp live** đạt. Live dùng Next production local port 3001 → Spring mới port 18080, gồm kiểm tra quality/giới hạn days, chuyển tiếp PUT feedback tới Spring, auth/cookie/refresh/logout và cách ly workspace. Bản sửa sidebar cuộn được kiểm tra thêm ở 1280×560; axe/layout cho màn hình chất lượng và feedback ở 320/1440px không có vi phạm theo các rule đã chạy. Ảnh ở `.verification/phase07/`, log ở `.verification/phase07-*.log`; server thử riêng được dừng sau kiểm chứng.

Browser fixture tests kiểm tra form lưu/sửa, 409, empty/error, đổi workspace, khoảng ngày, BFF allowlist/Origin và axe/responsive 320/1440px. Fixture browser không chứng minh lưu DB; backend HTTP integration mới kiểm chứng persistence/transaction. Test provider ở phase trước dùng HTTP fixture, không chứng minh chất lượng model thật. IntelliJ cần Reload All Maven Projects nếu chưa nhận Spring AI; báo IDE tách khỏi Maven. Không có bằng chứng CI/deployment/production trong việc triển khai local này.
