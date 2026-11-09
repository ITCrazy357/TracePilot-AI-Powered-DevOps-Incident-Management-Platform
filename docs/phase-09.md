# Phase 09 — Shipping & CV

Phase này đóng gói backend/frontend thành image nhiều stage, chạy đủ stack bằng Compose riêng, thêm CI/smoke và tài liệu bàn giao. Giữ bảo vệ, transaction và migration Phase 08. Không có migration mới sau V9.

## Chạy từ checkout mới

Cần Docker Desktop/Linux containers, PowerShell 5.1+ và Node.js 22+ cho smoke/kiểm tra frontend. Lần build đầu cần Internet; nên dành ít nhất 6 GiB RAM cho Docker khi build và kiểm thử cùng lúc.

Tại root:

```powershell
.\scripts\shipping.ps1 -Task init
.\scripts\shipping.ps1 -Task up
.\scripts\shipping.ps1 -Task check
```

- Frontend: **http://127.0.0.1:13000**; đăng ký tài khoản và tạo workspace.
- Backend health: **http://127.0.0.1:18081/actuator/health/readiness**.
- PostgreSQL/RabbitMQ chỉ ở mạng Compose, không publish port database/AMQP/management ra host.
- `.env.shipping` được tạo với secret ngẫu nhiên, bị Git ignore; chạy `init` lại giữ nguyên file. Không commit file đó. `.env.shipping.example` là mẫu được track.
- Project mặc định `tracepilot-shipping`, volume riêng; không dùng volume `tracepilot_postgres_data`/`tracepilot_rabbitmq_data` của `compose.yaml`. Database local có lệch checksum V4 vẫn được giữ nguyên.

Muốn DBeaver xem đúng dữ liệu của frontend 13000, bật [truy cập database local tại 127.0.0.1:15433](database.md). Copy `compose.shipping.local.example.yaml` thành `compose.shipping.local.yaml`; helper tự nạp override này ở các lần chạy sau. Chỉ override local mới publish port PostgreSQL; không dùng override khi triển khai public.

Không cần JDK/Maven/npm install trên host để build image. Dùng đầy đủ `--env-file .env.shipping -f compose.shipping.yaml` khi gọi Docker trực tiếp; không ghép file shipping vào Compose phát triển.

```powershell
.\scripts\shipping.ps1 -Task status
.\scripts\shipping.ps1 -Task stop
# Gỡ container/network, GIỮ named volumes và dữ liệu:
.\scripts\shipping.ps1 -Task down
```

`-Project <tên-riêng>` đổi namespace container/volume, không đổi port hoặc file env. Muốn chạy hai stack cùng lúc cần cấu hình port/origin riêng. Không chạy `down --volumes` trên project có dữ liệu cần giữ.

GitHub/AI/RAG mặc định tắt. Một dashboard trống không phải lỗi ingestion. [Kịch bản demo](demo.md) hướng dẫn kết nối provider thật, workflow fail, evidence và human feedback. GitHub App PEM chỉ được mount read-only qua `compose.github.yaml`, không copy vào image. Thay env rồi `up -d` để recreate; `restart` không nạp biến mới.

## Đóng gói và ranh giới kiểm chứng

Backend build bằng JDK 21/Maven Wrapper, runtime JRE 21. Frontend dùng Next standalone và copy static assets. Runtime backend UID 10001/frontend UID 1000, root filesystem read-only và `/tmp` là tmpfs; đã kiểm tra qua container thật. Env/private keys/local build output nằm ngoài build context. Frontend chỉ nhận BACKEND_URL/APP_ORIGIN; không nhận khóa ký JWT, DB password hoặc AI key.

Healthcheck backend dùng readiness gồm DB/RabbitMQ; frontend healthcheck chỉ xác nhận Next phục vụ trang login. `depends_on: service_healthy` hỗ trợ startup, không phải cơ chế tự phục hồi lỗi nghiệp vụ hoặc tự restart container unhealthy. Theo dõi health và log sau khi host/broker gặp sự cố; outbox/retry phục hồi theo Phase 08.

Base Java/Node và các action được khóa digest/SHA đã kiểm tra. PostgreSQL/pgvector, RabbitMQ có version tag; dependency Java/Node theo POM/lockfile. OS package repository vẫn có thể đổi, nên không tuyên bố build bit-for-bit. Nhãn revision local mặc định `worktree`; CI cấp `SOURCE_REVISION=github.sha`, không lấy HEAD làm chứng cứ cho working tree chưa commit.

Cookie production vẫn HttpOnly/SameSite/Secure. Chromium live test đã chạy được tại HTTP loopback `127.0.0.1`; host public bắt buộc HTTPS và APP_ORIGIN chính xác, không tắt Secure/Origin check để vượt lỗi. Chỉ một Next instance được hỗ trợ cho đến khi refresh lock/session store được chia sẻ.

## CI và bàn giao CD

`.github/workflows/ci.yml` chạy khi push main, pull request hoặc dispatch:

1. JDK 21 → Maven verify với PostgreSQL/pgvector/RabbitMQ Testcontainers.
2. Node 22 → npm ci, format/build/typecheck, Chromium và UI fixture tests.
3. Sau khi hai job đạt, build/chạy Compose mới trên runner, kiểm chứng cookie/audit/tenant/rate limit bằng smoke; thu hồi đúng project CI và volume tạm.

Workflow dùng quyền `contents: read`, checkout không giữ credential, không cần provider key/deploy token. Artifact chỉ gồm test report/fixture và smoke JSON, retention 7 ngày; không upload env hoặc image chứa secret. CI này đã lint local bằng actionlint; **chưa có bằng chứng remote CI** trước khi code được push/chạy trên GitHub.

CD hiện là operator chạy Compose trên host đã chọn; chưa cài credential hoặc tự deploy. Trước khi public:

1. Chọn host/domain, cấu hình DNS/TLS và backup; tạo `.env.shipping` riêng trên host. Không copy credential demo local thành credential production.
2. Backup PostgreSQL bằng `pg_dump -Fc` và kiểm tra restore trên database khác; giữ backup ngoài host. Backup RabbitMQ definitions và có kế hoạch outbox/replay; không cho rằng queue volume thay backup dữ liệu.
3. Với DB mới, Flyway tự migrate V1–V9 khi backend lên; với DB cũ phải đối soát V4/schema trước, không repair/reset tự động. Tách DB user migration/runtime khi vận hành public; cấu hình Compose demo hiện dùng một user có quyền bootstrap extension.
4. Đặt APP_ORIGIN là URL HTTPS thật và recreate frontend. Mẫu [Caddyfile](../deploy/Caddyfile.example) dành cho Caddy chạy trên cùng host với các port loopback: chỉ `/webhooks/github` vào Spring, đường khác qua Next, không buffer SSE. Operator phải validate cấu hình Caddy, cấp DNS/cert, điều chỉnh body/connection/rate policy theo host; file mẫu chưa được triển khai public.
5. Bật GitHub/AI chủ động theo demo, kiểm thử bằng repo/workspace thử. Kiểm tra request ID, audit, readiness và một workflow fail thật trước khi nhận dữ liệu vận hành.
6. Ghi commit/image digest và kết quả kiểm chứng trước/sau deploy. Giữ image trước để rollback ứng dụng; migration không tự rollback. Nếu schema không tương thích thì dừng và phục hồi từ kế hoạch backup đã kiểm tra, không xóa volume để khởi động lại.

Metrics registry hiện chỉ nội bộ; chưa có Prometheus exporter/collector public. Không mở Actuator metrics cho mọi thành viên để tiện theo dõi. [ADR 0001](adr/0001-shipping-single-instance.md) ghi quyết định và giới hạn topology.

## Tài liệu cho reviewer

- [Kiến trúc và ERD từ V1–V9](architecture.md): 21 bảng ứng dụng hiện có.
- [API bàn giao](api.md): route/method/body/quyền và status; chưa phải Swagger tự sinh.
- [Demo và synthetic incident](demo.md), [sample JSON](examples/incident-report.json).
- [Ảnh dashboard](images/dashboard-fixture.png), [report có evidence](images/analysis-fixture.png), [audit/tác vụ mobile](images/operations-mobile-fixture.png): đều là browser fixture, không phải incident provider thật.
- [Kết quả latency local](performance.md): cách chạy, số đo và giới hạn.

## Khắc phục lỗi

- **Container unhealthy:** `docker compose --env-file .env.shipping -f compose.shipping.yaml ps`, rồi `logs --tail 100 backend frontend`. Không dùng `config` không kèm `--quiet` trong log chia sẻ vì có thể in secret.
- **Backend không lên:** kiểm tra Flyway, DB/broker health, signing secret và port nội bộ. Không dùng địa chỉ DB `127.0.0.1:15432` từ trong backend container.
- **Đổi password nhưng DB lỗi login:** mật khẩu init chỉ áp dụng lần tạo volume; phải đổi password DB theo quy trình vận hành, không chỉ sửa env.
- **POST 403:** đối chiếu APP_ORIGIN với origin trình duyệt, không nhầm `localhost`/`127.0.0.1` hoặc HTTP/HTTPS.
- **Smoke 429 trước ca giới hạn:** BFF dùng chung peer-IP bucket; đợi window kết thúc trước khi chạy lại liên tục. Không tắt limiter để làm report đẹp.
- **Windows báo Docker progress là lỗi:** script đã xử lý stderr tiến độ của PowerShell 5.1 và vẫn fail khi Docker exit code khác 0.

## Kết quả kiểm chứng local — 09/11/2026

| Mức bằng chứng | Kết quả |
|---|---|
| Nền nghiệp vụ sau khôi phục Phase 08 | Hash các file backend main/migration khớp `fec1ebb`; Maven verify **133 test**, 0 failure/error/skipped, BUILD SUCCESS |
| Frontend | TypeScript/Prettier đạt, standalone production image build thành công; **43 browser fixture test** và **9 live test** qua Next/Spring trong Compose shipping đạt |
| Chạy đủ stack | PostgreSQL, RabbitMQ, backend, frontend đều healthy trên project `tracepilot-phase09-check`; DB mới migrate **V1–V9** thành công |
| Smoke/performance | 9 nhóm kiểm tra auth/audit/tenant/role/Origin/proxy/rate/logout/readiness đạt; 25 mẫu tuần tự/endpoint trong [báo cáo local](performance.md) |
| Runtime | Backend UID 10001, frontend UID 1000; read-only rootfs, capabilities ALL bị bỏ; port DB/broker không publish; init lại không đổi credential |
| Cấu hình vận hành | Compose config và syntax script đạt; actionlint không lỗi; Caddyfile validate offline đạt. Chưa thử TLS/DNS/ACME thật |
| Dọn sau kiểm chứng | Đã thu hồi 4 container, 2 network và 2 volume của riêng project kiểm thử; không còn Testcontainers. Volume/container local gốc giữ nguyên |
| Chưa có bằng chứng | Remote GitHub CI, push/release, public deployment, demo workflow/AI provider thật, host backup/restore và nâng DB Compose cũ |

Log local: `.verification/phase09-backend-verify.log`, `phase09-docker-final.log`, `phase09-stack-up.log`, `.verification/phase09/`. Các log riêng tư nằm ngoài Git. Source/ảnh/JSON report chia sẻ trong `docs/` được giữ tracked. Không có test tạo secret hoặc token thật được đưa vào ảnh/report.

## Mô tả dự án cho CV

“Xây dựng nền tảng theo dõi CI incidents với Java 21/Spring Boot, PostgreSQL/pgvector và Next.js. Thiết kế webhook HMAC, transactional outbox/RabbitMQ, phân tích AI có evidence, knowledge do con người duyệt, workspace RBAC và audit; đóng gói Docker/Compose và xây pipeline CI.”

Khi nêu bằng chứng, ghi **133 backend test, 43 browser fixture, 9 live test local qua container**; phân biệt fixture GitHub/AI với provider thật. Chưa ghi “production-ready”, “đã triển khai public” hoặc số người dùng/request mỗi giây khi chưa có bằng chứng tương ứng. Khi phỏng vấn, dùng ADR để giải thích transaction/network boundaries, at-least-once delivery, refresh lock một instance và giới hạn của phép đo latency.
