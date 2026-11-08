# TracePilot

Nguồn: `TracePilot_Roadmap_And_Database.md`, backend Phase 01–05 và yêu cầu dùng `Design_TracePilot`.

Người dùng là developer và người phụ trách DevOps trong workspace. Công việc chính: từ workflow thất bại đến incident, log đã che và đề xuất AI có bằng chứng; con người quyết định hướng xử lý.

Backend hiện có auth, workspace/RBAC, GitHub/repository, run/job/sync, incident/version/phân công, log snippets, notifications/SSE, analysis history/evidence/usage. Chưa có API knowledge/RAG, feedback, audit dashboard hay tổng hợp metric toàn workspace. Không tự chạy lệnh, sửa repo, push hoặc deploy từ đề xuất AI.

Thiết kế đã được người dùng chọn: TracePilot Clean Precision, nền trắng/xanh băng, điều hướng xanh dương, hồng cho điều tra/bằng chứng; tiếng Việt; Be Vietnam Pro và JetBrains Mono. Ưu tiên luồng ngắn, nội dung thật, quyền rõ, màn hình 1440/390/320 px. Giữ lớp cookie HttpOnly và kiểm tra Origin của Next, toàn bộ quyền và cách ly workspace của Spring.
