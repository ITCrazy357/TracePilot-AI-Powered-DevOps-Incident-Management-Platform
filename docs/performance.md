# Latency smoke — local shipping, 09/11/2026

[Báo cáo JSON gốc](reports/phase09-local-smoke.json) được tạo bởi `scripts/shipping-smoke.mjs` trên frontend/backend production container và PostgreSQL/pgvector/RabbitMQ thật. Host Windows, Docker Desktop 29.7.2 Linux containers, working tree Phase 08 + 09; không phải release đã deploy public.

Mỗi endpoint có 3 request warm-up và 25 request đo tuần tự, concurrency **1**. Thời gian bao gồm round trip từ Node client tới host port Docker và đọc hết response body. p95 lấy mẫu thứ `ceil(n × 0,95)` sau khi sắp xếp; đây là smoke nhỏ, không có khoảng tin cậy hoặc SLA.

| Endpoint | p50 (ms) | p95 (ms) | Max (ms) |
|---|---:|---:|---:|
| Backend readiness (DB + broker) | 26,92 | 50,31 | 73,57 |
| Next BFF auth/me | 67,21 | 170,37 | 360,69 |
| Next BFF workspace operations | 92,11 | 229,97 | 238,47 |

Cả 9 nhóm kiểm tra smoke đều đạt. Timing không đo login BCrypt, ingestion/provider call, embedding, SSE fan-out, nhiều tenant, hàng đợi lớn, throughput hoặc capacity đồng thời. Provider mặc định tắt; không tính phí AI và không thể suy ra latency LLM thật từ bảng này. Tải host/build/browser test và cache/JIT làm số đo thay đổi giữa các lần; không chọn lần nhanh nhất để trình bày.

Chạy lại từ root sau khi stack healthy:

```powershell
.\scripts\shipping.ps1 -Task check
# Hoặc chọn số mẫu 5-100 và nơi lưu report riêng:
node scripts/shipping-smoke.mjs --samples 50 --output .verification/phase09/smoke-rerun.json
```

Script tạo hai user và một workspace synthetic ở database đích, kiểm tra isolation/RBAC/audit/Origin/429/logout, rồi lưu report không chứa password/token/cookie. Chỉ chấp nhận loopback target để tránh vô tình ghi dữ liệu thử lên host khác. Chờ rate-limit window nếu chạy liên tiếp; không tắt limiter hoặc xóa bảng rate để vượt qua test.

Bước đánh giá tiếp theo khi có workload thực: khóa dataset/config/phần cứng, tách phép đo auth/read/ingestion/AI, tăng concurrency theo từng mức và ghi p95/p99, CPU/RAM/DB/broker backlog, error/429 riêng. Chưa có bằng chứng để công bố số người dùng hoặc request/giây ứng dụng chịu được.
