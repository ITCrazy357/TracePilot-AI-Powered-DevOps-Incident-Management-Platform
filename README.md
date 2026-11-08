# TracePilot

Nền tảng theo dõi GitHub Actions và phân tích incident bằng AI. Backend hiện có **Phase 00–06: nền tảng local, tài khoản/workspace, GitHub App/webhook, workflow run/job, incident, log đã che dữ liệu nhạy cảm, SSE, phân tích AI và knowledge/RAG với pgvector**. AI và RAG mặc định tắt.

Hướng dẫn học, API và chạy thử: [Phase 01 — Identity & Workspace](docs/phase-01.md).

Kết nối repo thật, cấu hình key và webhook: [Phase 02 — GitHub App](docs/phase-02.md). MVP dùng binding installation/workspace do operator cấu hình; OWNER gọi sync, thành viên ACTIVE xem repo. Tích hợp mặc định tắt.

Luồng webhook → outbox → worker, API run/jobs và workflow mẫu: [Phase 03 — Pipeline ingestion](docs/phase-03.md). Pipeline mặc định bật cùng GitHub; có retry, DLQ và khôi phục công việc sau gián đoạn. Đây là phần backend/API của Phase 03.

Incident theo run attempt, lifecycle/phân công/version, log đã redacted và notification/SSE: [Phase 04 — Incident & safe logs](docs/phase-04.md). Kiểm chứng backend và frontend được ghi riêng trong tài liệu tương ứng.

Phân tích qua Spring AI/OpenAI, lịch sử re-analysis, validation JSON/evidence, token usage/quota và API: [Phase 05 — LLM root cause analysis](docs/phase-05.md). Cần chủ động cấu hình AI_ENABLED, model và API key trước khi gửi log đã che đến provider; kiểm thử dùng fixture, không phát sinh phí AI thật.

Knowledge do con người duyệt, embedding 1.536 chiều, exact/HNSW/hybrid search và dẫn chứng nguồn: [Phase 06 — pgvector & RAG](docs/phase-06.md). Chỉ APPROVED/READY/current revision trong cùng workspace được dùng cho RAG; báo cáo Recall@K local phân biệt rõ fixture với chất lượng provider thật.

Frontend Next.js tương ứng Phase 01–05: [hướng dẫn chạy và học frontend](docs/frontend.md). Thiết kế theo `Design_TracePilot`/Clean Precision và Impeccable; có tài khoản/workspace, kho mã, lần chạy/jobs, sự cố/log/AI và thông báo trực tiếp. Chạy backend, sau đó tại `frontend/` dùng `npm ci` và `npm run dev`, mở http://127.0.0.1:3000. Yêu cầu Node.js 22+.

Quy tắc code cho các phase: [AGENTS.md](AGENTS.md). Ưu tiên code rõ nghĩa, đơn giản và hiệu năng có căn cứ; `.editorconfig` thống nhất định dạng Java.

## Yêu cầu

- Windows PowerShell 5.1+ hoặc PowerShell 7.
- **JDK 21** (có cả `java` và `javac`). Script tìm JDK trong `JAVA_HOME`, thư mục cài đặt thông thường và `~/.jdks`.
- **Docker Desktop**, khởi động Docker Engine ở chế độ Linux containers.
- Internet để tải Maven, dependencies và Docker images lần đầu. Không cần cài Maven riêng.

Phiên bản được khóa: Spring Boot **4.1.2**, Spring AI **2.0.2**, Maven Wrapper **3.10.0**, PostgreSQL **16** với pgvector **0.8.7**, RabbitMQ **4.4.0-management**.

Nguồn phiên bản: [Spring Boot 4.1.2](https://spring.io/blog/2026/10/23/spring-boot-4-1-2-available-now/), [Spring AI 2.0](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/), [pgvector Docker](https://github.com/pgvector/pgvector#docker).

## Khởi tạo lần đầu

Mở PowerShell tại thư mục gốc dự án và bật Docker Desktop:

```powershell
.\scripts\backend.ps1 -Task doctor
.\scripts\backend.ps1 -Task setup
.\scripts\backend.ps1 -Task run
```

`setup` tạo `.env` với mật khẩu local ngẫu nhiên nếu file chưa tồn tại, chạy PostgreSQL/RabbitMQ rồi build và chạy integration tests. File `.env` hiện có được giữ nguyên. Maven và dependencies tải vào `.tools/` của dự án. Lần đầu có thể mất vài phút.

`run` chạy backend ở foreground; nhấn `Ctrl+C` để dừng. Spring Boot tự đọc `.env` ở thư mục gốc khi chạy từ root hoặc `backend/`. Script chọn JDK 21 cho tiến trình hiện tại; PATH hệ thống vẫn có thể trỏ tới Java 8.

Nếu PowerShell chặn script, chỉ cho phép trong terminal hiện tại rồi chạy lại:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

Mở terminal thứ hai để kiểm tra:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

Kỳ vọng `status = UP`. Health tổng hợp kiểm tra kết nối PostgreSQL và RabbitMQ; chi tiết nội bộ được ẩn. `/actuator/health/liveness` và `/actuator/health/readiness` cũng được bật. Sau `setup`, các test dùng container riêng nên database local chỉ được migrate khi chạy backend lần đầu.

## Địa chỉ local

| Dịch vụ | Địa chỉ mặc định | Đăng nhập |
|---|---|---|
| Backend health | http://127.0.0.1:8080/actuator/health | Public, ẩn chi tiết |
| PostgreSQL | `127.0.0.1:15432`, database `tracepilot` | `POSTGRES_USER` / `POSTGRES_PASSWORD` trong `.env` |
| RabbitMQ AMQP | `127.0.0.1:5673` | `RABBITMQ_USER` / `RABBITMQ_PASSWORD` trong `.env` |
| RabbitMQ Management | http://127.0.0.1:15673 | Cùng tài khoản RabbitMQ trong `.env` |

Tất cả port chỉ bind loopback. Các port có thể sửa trong `.env`. Credential PostgreSQL/RabbitMQ được dùng khi khởi tạo volume lần đầu; sửa mật khẩu trong `.env` sau đó không tự đổi tài khoản trong volume đã có. Tài khoản PostgreSQL local có quyền tạo extension; môi trường triển khai cần tách tài khoản migration và tài khoản ứng dụng.

## Lệnh hằng ngày

```powershell
# Chỉ bật hạ tầng và chờ healthy
.\scripts\backend.ps1 -Task infra

# Build, integration tests và đóng gói JAR; yêu cầu Docker đang chạy
.\scripts\backend.ps1 -Task verify

# Chạy backend sau khi bật hạ tầng
.\scripts\backend.ps1 -Task run

# Dừng hai dịch vụ của dự án, giữ dữ liệu trong volume
.\scripts\backend.ps1 -Task stop
```

`verify` dùng Testcontainers với PostgreSQL/pgvector và RabbitMQ riêng, port ngẫu nhiên. Các test kiểm tra health, Flyway, auth/RBAC, GitHub sync/webhook, pipeline/incident/AI worker, redaction, usage/quota và SSE qua HTTP/broker thật. GitHub và AI dùng HTTP fixture local, không cần credential provider thật hoặc phát sinh phí AI. Nếu Docker không chạy, kiểm thử sẽ báo lỗi thay vì bỏ qua. Báo cáo ở `backend/target/surefire-reports/`.

## Cấu trúc và migration

```text
backend/
  pom.xml                         # Java 21 / Spring Boot
  mvnw, mvnw.cmd, .mvn/wrapper/    # Maven Wrapper
  src/main/java/com/tracepilot/    # common, identity, workspace, github, pipeline, incident, analysis, notification, infrastructure
  src/main/resources/
    application.yaml
    db/migration/V1__enable_pgvector.sql
    db/migration/V2__identity_and_workspaces.sql
    db/migration/V3__github_connections_and_webhooks.sql
    db/migration/V4__pipeline_ingestion_and_outbox.sql
    db/migration/V5__incidents_safe_logs_and_notifications.sql
    db/migration/V6__ai_analysis_and_usage.sql
    db/migration/V7__knowledge_vectors_and_evidence.sql
  src/test/java/com/tracepilot/    # Integration tests + Testcontainers
scripts/backend.ps1               # Chọn JDK và chạy các tác vụ local
compose.yaml                      # PostgreSQL/pgvector và RabbitMQ
.env.example                      # Cấu hình mẫu; .env không đưa vào Git
TracePilot_Roadmap_And_Database.md # Roadmap / schema mục tiêu
V1__init.sql                      # SQL tham khảo đủ 20 bảng; chưa dùng làm migration
```

Phase 00 chỉ bật extension `vector`, chưa khóa embedding dimension và chưa tạo bảng nghiệp vụ. **Không chép file `V1__init.sql` ở root vào thư mục migration**: dự án đã dùng version `V1` cho bootstrap. Từ Phase 01, thêm `V2__...sql`, `V3__...sql` theo từng tính năng. Không sửa migration đã áp dụng; Hibernate dùng `ddl-auto=validate`, Flyway quản lý schema.

Trong IntelliJ IDEA, mở/import `backend/pom.xml` dưới dạng Maven project và đặt Project SDK / Maven Runner JRE thành JDK 21. Sau khi đổi dependencies, dùng **Reload All Maven Projects** nếu IDE chưa nhận Spring Security hoặc Spring AI. Khi chạy `TracePilotApplication`, đặt working directory là thư mục `backend/` để đọc `.env` đúng vị trí. Frontend nằm trong `frontend/`; RAG/embedding thuộc Phase 06.

Phase 01 cần `AUTH_SIGNING_SECRET` tối thiểu 64 ký tự. `setup`, `verify`, `run` tự thêm secret ngẫu nhiên nếu `.env` hiện có chưa khai báo biến này; các giá trị sẵn có được giữ nguyên. Khi chạy trực tiếp bằng IntelliJ, chạy script một lần trước để chuẩn bị `.env`. Secret thiếu hoặc quá ngắn làm ứng dụng từ chối khởi động.

## Khi gặp lỗi

- **Docker Engine unavailable:** bật Docker Desktop, chờ Engine sẵn sàng rồi chạy `doctor` và `setup` lại.
- **Java 8 trong terminal:** dùng script trên; nếu chạy Maven trực tiếp, cấu hình `JAVA_HOME` và PATH về JDK 21 trước.
- **Port đã sử dụng:** đổi port tương ứng trong `.env`, chạy lại `infra`, rồi khởi động backend lại.
- **Không tải được Maven/image:** kiểm tra mạng, proxy và Docker Hub. `setup` có thể chạy lại; không xóa volume để sửa lỗi tải.
- **Không rename được JAR khi build trên Windows:** dừng tiến trình đang chạy bằng `java -jar backend/target/...jar` trước khi đóng gói lại.
- **Health trả 503 / kết nối thất bại:** dùng `docker compose ps` và `docker compose logs --tail 100 postgres rabbitmq`; kiểm tra cấu hình `.env`.
- **Flyway validation lỗi:** kiểm tra migration và database đích. Không bật `baseline-on-migrate` hoặc xóa lịch sử chỉ để bỏ qua lỗi.

Đây là môi trường local. Kiểm thử local không chứng minh CI hay triển khai production đã hoạt động.
