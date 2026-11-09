# TracePilot

Nguồn: `TracePilot_Roadmap_And_Database.md`, backend Phase 01–08, đóng gói Phase 09 và yêu cầu dùng `Design_TracePilot`.

Người dùng là developer và người phụ trách DevOps trong workspace. Công việc chính: từ workflow thất bại đến incident, log đã che và đề xuất AI có bằng chứng; con người quyết định hướng xử lý.

Backend và frontend hiện có auth, workspace/RBAC, GitHub/repository, run/job/sync, incident/version/phân công, log snippets, notifications/SSE, analysis history/evidence/usage, knowledge do con người xác nhận/duyệt, tìm kiếm RAG, feedback, chất lượng AI và vận hành/audit theo quyền. AI/RAG/GitHub cần cấu hình và kiểm chứng provider thật riêng; không suy ra tình trạng kết nối từ dữ liệu trống. Không tự chạy lệnh, sửa repo, push hoặc deploy từ đề xuất AI.

Thiết kế đã được người dùng chọn: TracePilot Clean Precision, nền trắng/xanh băng, điều hướng xanh dương, hồng cho điều tra/bằng chứng; tiếng Việt; Be Vietnam Pro và JetBrains Mono. Ưu tiên luồng ngắn, nội dung thật, quyền rõ, màn hình 1440/390/320 px. Giữ lớp cookie HttpOnly và kiểm tra Origin của Next, toàn bộ quyền và cách ly workspace của Spring.
