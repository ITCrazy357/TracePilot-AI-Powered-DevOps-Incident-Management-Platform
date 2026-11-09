# ADR 0001 — Shipping bằng Compose và một BFF instance

Trạng thái: chấp nhận cho demo/local hoặc một host có vận hành; chưa xác nhận triển khai public.

## Bối cảnh

TracePilot là modular monolith có transaction PostgreSQL, outbox và RabbitMQ. Mục tiêu Phase 09 là để reviewer chạy lại được ứng dụng và hiểu giới hạn, trong khi database local hiện hữu có lệch checksum V4 cần đối soát riêng.

## Quyết định

- Giữ monolith, chạy frontend/backend/DB/broker riêng bằng `compose.shipping.yaml`. Project/volume tách khỏi `compose.yaml` local.
- Image backend build với JDK 21, runtime JRE 21; frontend dùng Next standalone. Base build/runtime Java/Node được khóa digest. Maven Wrapper và npm lockfile là nguồn phiên bản dependency; package hệ điều hành cài từ repository lúc build nên không tuyên bố image tái tạo bit-for-bit.
- Backend/frontend chạy non-root, root filesystem read-only, `/tmp` là tmpfs, bỏ capabilities. Secret chỉ cấp runtime; context build loại env/key/output local. GitHub App PEM chỉ mount read-only qua override chủ động.
- Giữ một Next instance. Khóa refresh token trong BFF chỉ nằm trong process; chưa scale ngang trước khi có session/lock store dùng chung.
- CI kiểm tra backend, frontend và stack thật nhưng không giữ credential deploy, không tự gọi provider trả phí hoặc phát hành public. CD hiện là thao tác operator theo runbook sau khi chọn host/domain.
- Port local bind loopback. Host public cần TLS/reverse proxy, trusted edge rate policy và đường webhook riêng; SSE không được buffer.

## Hệ quả

Có đường chạy mới trên database riêng mà không reset/repair V4 cũ. Chưa có HA, autoscaling, managed secrets, collector metrics hoặc quy trình backup dịch vụ được vận hành thực tế. Readiness đánh giá DB/broker; container health không chứng minh dữ liệu/provider đúng. Thử lại vẫn cần giữ audit, idempotency và tenant isolation.

Nguồn kỹ thuật: [Next standalone](https://nextjs.org/docs/app/api-reference/config/next-config-js/output), [Compose startup health](https://docs.docker.com/compose/how-tos/startup-order/). Các quyết định topology và giới hạn là lựa chọn của dự án.
