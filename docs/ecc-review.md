# ECC review — cấu trúc, tính đúng và độ dễ đọc

Mốc review: `e1baddb` (commit 17), ngày 10/11/2026. Phạm vi: cấu trúc module, các luồng Phase 10 và ranh giới auth/workspace/transaction liên quan. Áp dụng hướng dẫn ECC `java-coding-standards`, `springboot-security`, `frontend-patterns` và `verification-loop`; kiểm tra source, tái hiện lỗi rồi chạy hồi quy.

## Đánh giá cấu trúc

Monolith chia package theo nghiệp vụ hiện phù hợp: identity, workspace, github, pipeline, incident, analysis, knowledge, quality, audit và dashboard. Controller nhận HTTP/validation, service điều phối quyền/nghiệp vụ/transaction, repository truy vấn. JPA cho entity và JDBC cho truy vấn native/vector/aggregate có lý do thực tế; không cần chuyển toàn bộ sang một kiểu hoặc thêm lớp generic.

Constructor injection, DTO record, optimistic version, workspace lock, outbox token/generation và composite FK được giữ. Tạo nháp AI không tự duyệt hoặc gọi embedding. Dữ liệu số liệu thiếu giữ null, và truy vấn aggregate không join feedback làm nhân số lượt gọi.

## Findings và sửa tương ứng

Vị trí bên dưới là ở mốc review `e1baddb`; dòng hiện tại có thể thay đổi sau sửa.

| ID | Mức | Vị trí trước sửa | Trigger và hậu quả | Sửa và bằng chứng |
|---|---|---|---|---|
| R1 | P1 | `knowledge-editor.tsx:62`, `knowledge-detail.tsx:60,121` | Realtime làm nguồn chuyển loading/error, unmount editor và mất nội dung chưa lưu. | Chụp nguồn lúc bắt đầu tạo/sửa; dừng tải lại nguồn trong lúc nhập. Hai test tái hiện thất bại trước sửa và đạt sau sửa; version vẫn là bản lúc bắt đầu chỉnh. |
| R2 | P1 | `feedback-panel.tsx:29`, `use-resource.ts`, `incident-detail.tsx`, `analysis-panel.tsx` | Lỗi đọc tạm thời ở form hoặc parent làm mất nhận xét; dữ liệu feedback mới có thể thay editor qua key version. | Giữ snapshot feedback, dùng kết quả lưu trả về, chỉ tải lại khi người dùng yêu cầu. Retain dữ liệu đọc là tùy chọn cho ngữ cảnh cần giữ form, chỉ áp dụng lỗi mạng/429/5xx và có cảnh báo. 401/403/404 không giữ dữ liệu. Test 503 rồi 403 kiểm tra vừa giữ input, vừa ẩn khi bị từ chối quyền. |
| R3 | P2 | `knowledge-editor.tsx:175` | Tạo bài gặp 409 vì sự cố nguồn đổi trạng thái làm form khóa vĩnh viễn như lỗi version của bài đang sửa. | Chỉ khóa optimistic conflict khi sửa bài đã có. Tạo mới giữ input và cho phép người dùng thử lại; backend vẫn kiểm tra trạng thái nguồn mỗi lần lưu. |
| R4 | P2 | `search-history.tsx:41,65` | Xóa mục cuối trang sau làm danh sách trống và mất nút quay về trang trước. | Hiệu chỉnh page theo total mới; khóa chọn/xóa/phân trang trong lúc DELETE; bỏ refresh query không dùng. Test lịch sử 11 mục, xóa mục ở trang 2 rồi trở về 10 mục trang 1. |
| R5 | P2 | `KnowledgeService.list`, các service phân trang tương tự | Count và items dùng READ COMMITTED riêng từng statement. Insert giữa hai truy vấn tạo response total=1 nhưng items=2. | Read-only REPEATABLE_READ cho các API phân trang. Test dùng PostgreSQL thật, latch và insert trên transaction khác để chứng minh một snapshot; request tiếp theo vẫn thấy dữ liệu mới. Không thay transaction ghi hoặc khóa workspace. |
| R6 | P3 | `SearchHistoryRepository`, BFF catch-all route | HTTP DTO nằm trong repository làm controller phụ thuộc lớp SQL; query allowlist dùng nhiều ternary lồng nhau, khó đọc. | Tách `SearchHistoryDtos`, gom mapping record và đổi query selection thành guard clause. JSON, path, method, Origin và danh sách query được phép giữ nguyên. |

R5 áp dụng cho danh sách tri thức, sự cố, lịch sử tra, phân tích, audit, repository, workflow run, sync request, notification và outbox operation. SSE polling vẫn đọc transaction riêng từng lượt; transaction ghi không bị đổi isolation.

## Hiệu năng và giới hạn kết luận

Trong phần source đã rà, danh sách lịch sử chỉ tải metadata, snapshot lớn tải khi mở chi tiết. Truy vấn số liệu chạy theo tập dữ liệu, phạm vi workspace/ngày; không có vòng lặp gọi SQL cho từng model hoặc từng ngày. Editor không tiếp tục tải lại nguồn qua SSE khi đang nhập.

Chưa có benchmark tải lớn/EXPLAIN trên dữ liệu đại diện nên không kết luận đã tối ưu mọi trường hợp. Không thêm cache hoặc index thiếu bằng chứng. Chưa cấu hình đo coverage phần trăm, vì vậy số test đạt không được trình bày như coverage 80% hay 100%.

## Kiểm chứng

Baseline trước sửa: 140 test backend và 72 test UI đạt; build, TypeScript và format đạt. Năm regression UI và một regression PostgreSQL đồng thời đã thất bại trước khi sửa. Test giữ input đồng thời kiểm tra body/version gửi lên server; không chỉ kiểm tra giao diện tồn tại.

| Hạng mục | Kết quả local |
|---|---|
| Build | PASS — Maven verify và Next production build |
| Types | PASS — TypeScript không có lỗi |
| Format | PASS — Prettier theo lệnh CI; repo chưa có lệnh lint ESLint riêng |
| Tests | PASS — 141 backend, 77 UI, không test nào bị skip trong Maven |
| Security | Các hồi quy auth/RBAC/workspace và 503→403 đạt; npm audit production báo 0 advisory; secret-pattern scan chỉ khớp PEM header trong parser, không phải private key literal |
| Diff | DTO JSON/path/Origin giữ nguyên, không thêm/sửa migration, không đổi dependency hoặc khóa/transaction ghi |
| Target acceptance | Không thực hiện runtime shipping/production ở lượt review này; commit/push và CI được đối soát riêng |

Coverage phần trăm chưa đo. Các test backend dùng Testcontainers/HTTP fixture; UI dùng API fixture. Kiểm chứng local, GitHub CI và runtime shipping được phân biệt; lần review không gửi dữ liệu đến AI thật.

Sau khi lấy bản sửa, chạy tại root để cập nhật stack local:

```powershell
.\scripts\shipping.ps1 -Task up
```

Giữ `.env.shipping`, local override và volume hiện tại. Không cần reset database vì đợt review không thay schema.
