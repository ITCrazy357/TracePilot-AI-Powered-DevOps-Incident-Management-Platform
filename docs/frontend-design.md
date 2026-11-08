# Frontend — thiết kế và tiêu chí kiểm chứng

Nguồn yêu cầu: `AuthDtos`, `WorkspaceDtos`, `GitHubDtos`, các controller/service tương ứng và roadmap. Giao diện dùng cho người quản lý workspace, cần xem kho mã, thành viên và quyền hiện tại. Hành động chính: tạo workspace; trong từng workspace là thêm thành viên và OWNER đồng bộ GitHub.

Chọn phong cách công cụ vận hành sáng, yên tĩnh: nền xám nhạt, panel trắng, xanh lá cho hành động/chọn workspace, amber cho OWNER và đỏ cho lỗi/xóa. Thanh bên giữ ngữ cảnh workspace; thứ tự nội dung là workspace → số liệu thực → repository → thiết lập đội ngũ. Không có số liệu pipeline/incident giả. Chi tiết nhận diện là đường kết nối GitHub/workspace/quyền trên trang đăng nhập.

Stack theo roadmap: Next.js App Router, TypeScript strict, Tailwind CSS v4; Button/Dialog theo cách ghép thành phần shadcn/ui, sử dụng Radix/CVA. Font hệ thống để hỗ trợ tiếng Việt và build không phải tải font ngoài.

Viewport cần kiểm tra: **1440x900, 390x844, 320x812**. Desktop có sidebar cố định; mobile dùng điều hướng mở rộng trong luồng trang. Không đặt nút nổi che form. Tên dài và UUID phải xuống dòng; số liệu xếp dọc ở màn hình hẹp. Dialog giới hạn chiều cao, hỗ trợ focus trap, Escape và trả focus về trigger. Có skip link, focus ring, label và chế độ reduced motion.

| Trạng thái | Trigger | Phản hồi | Bước tiếp theo |
|---|---|---|---|
| Loading | Đọc phiên/workspace/repos | `role=status`, spinner và nhãn | Chờ kết quả |
| Empty | Chưa có workspace/repos | Giải thích dữ liệu còn thiếu | Tạo workspace hoặc OWNER sync |
| Error | API lỗi, sai đăng nhập, GitHub chưa sẵn sàng | Thông báo tại form/panel | Sửa dữ liệu hoặc thử lại |
| Partial | Workspace/members tải được, repos lỗi | Giữ số liệu workspace/members; số repo là dấu gạch | Thử tải lại repos |
| Success | Đăng ký, thêm thành viên, sync thành công | Xác nhận và dữ liệu mới | Đăng nhập hoặc tiếp tục quản lý |
| Permission | MEMBER/ADMIN/OWNER khác nhau | Hành động khớp role membership, bảo vệ OWNER | Xem hoặc nhờ người có quyền |

Ảnh và JSON kiểm chứng được lưu local trong `.verification/frontend/`, không đưa lên Git. Test UI dùng fixture để kiểm tra trạng thái, hiển thị và responsive; `live.spec.ts` dùng Next + Spring + PostgreSQL thật để kiểm tra auth, workspace và cách ly quyền. Fixture GitHub không chứng minh GitHub App thật đã hoạt động.
