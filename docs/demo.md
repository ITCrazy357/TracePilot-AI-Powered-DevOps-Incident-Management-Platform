# Kịch bản demo có thể tái hiện

## 1. Không cần credential provider

Chạy bộ shipping theo [Phase 09](phase-09.md), mở `http://127.0.0.1:13000`, đăng ký/đăng nhập và tạo workspace. Xem menu **Vận hành & audit**: audit tạo workspace là dữ liệu thật trong PostgreSQL shipping; tác vụ/incident ban đầu trống vì provider đang tắt.

Chạy `scripts/shipping-smoke.mjs` để kiểm chứng auth, audit, role, cách ly workspace, 429 và logout qua BFF/container. Script tạo hai tài khoản synthetic và một workspace; giữ dữ liệu trong database đích, không có cleanup xóa dữ liệu nghiệp vụ qua SQL.

Để xem giao diện báo cáo đầy đủ mà không phát sinh phí, chạy tại `frontend/`:

```powershell
npm ci
npm run test:ui
```

Browser fixture tạo ảnh dashboard/report/log và audit ở desktop/mobile dưới `.verification/frontend/`, `.verification/redesign/`, `.verification/phase08/`. Báo cáo mẫu [incident-report.json](examples/incident-report.json) là dữ liệu synthetic. Ảnh fixture chứng minh giao diện và dẫn chứng được hiển thị; không chứng minh GitHub/LLM thật đã xử lý run đó.

## 2. Workflow thất bại và phân tích với provider thật

Đây là đường demo end-to-end cần cấu hình và chủ động bật gửi dữ liệu tới provider. Chuẩn bị một repository thử, GitHub App có **Metadata: read**, **Actions: read**, event **Workflow runs**, installation được cấp quyền cho repo. Tham khảo [Phase 02](phase-02.md), [Phase 03](phase-03.md), [Phase 05](phase-05.md).

1. Trong stack shipping, đăng ký tài khoản OWNER, tạo workspace; lấy UUID từ UI/API. Không dùng UUID của DB local hoặc DB test khác.
2. Sửa `.env.shipping`: `GITHUB_ENABLED=true`, app/installation/workspace ID thật, webhook secret riêng; `GITHUB_PRIVATE_KEY_FILE` là absolute host path của PEM PKCS#8. Key phải cho UID 10001 của backend đọc được khi bind mount, nhưng không public/Git.
3. Bật `AI_ENABLED=true`, đặt `AI_MODEL` và `AI_API_KEY` phù hợp structured output. Chỉ đặt đơn giá khi đã kiểm tra giá; mặc định chưa biết cost là đúng. Để `RAG_ENABLED=false` cho lần demo đầu; bật RAG sau khi có knowledge được con người duyệt.
4. Khởi động với override key (từ root):

```powershell
docker compose --env-file .env.shipping -f compose.shipping.yaml -f compose.github.yaml up -d --build --wait
```

5. Cấu hình GitHub App webhook HTTPS đến `/webhooks/github` trên backend của stack này. Loopback port 18081 không tự truy cập được từ GitHub; cần host TLS hoặc tunnel do operator cấu hình. Không bật forwarded-header trust tùy tiện. OWNER vào **Kho mã → Đồng bộ GitHub**.
6. Trong repo đã kết nối, đưa workflow `.github/workflows/tracepilot-demo.yml` lên default branch, mở Actions → **TracePilot ingestion demo** → Run workflow → `fail=true`. Workflow cố ý exit 1, không push code hoặc deploy.
7. Đợi workflow run/jobs → incident → log READY → analysis SUCCEEDED. Mở báo cáo, chọn **Xem trong log đã che**: câu evidence phải trỏ vào log thực. LLM có thể thất bại hoặc đưa nhận định chưa đúng; lưu error/usage và để con người đánh giá.
8. Rerun workflow, xác nhận attempt tăng và mỗi attempt có incident riêng. Yêu cầu phân tích lại phải tạo báo cáo mới. Đổi status/assignee và kiểm tra audit tương ứng.
9. Nếu muốn RAG: con người xác nhận root cause/fix, tạo knowledge DRAFT qua backend API, OWNER/ADMIN approve; chỉ APPROVED/READY/current revision cùng workspace mới được truy xuất. Xem [Phase 06](phase-06.md) để gửi request đúng version và thu evidence.

Nếu failed task đã vào DLQ, sửa nguyên nhân rồi dùng màn hình Vận hành yêu cầu replay theo quyền; không reset SQL hoặc tự rút toàn bộ DLQ. Tắt lại AI/GitHub và recreate backend khi kết thúc demo nếu không muốn tiếp tục nhận webhook/gửi dữ liệu.

## 3. Bằng chứng cần giữ cho CV

Một demo có giá trị cần ghi run URL/attempt, thời gian, incident/report ID, log đã redacted, evidence, provider/model và trạng thái usage. Không đưa token/key/raw log riêng tư vào screenshot hoặc Git. Hiện chỉ bộ container và fixture được xác minh local; không trình bày synthetic sample như production incident.
