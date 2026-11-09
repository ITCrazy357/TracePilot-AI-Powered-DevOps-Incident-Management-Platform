# Hướng thiết kế và thư viện tham khảo

Yêu cầu: làm rõ kết quả, thao tác và hướng dẫn; thêm màu có ý nghĩa và biểu đồ dữ liệu thật. Giữ Clean Precision, Be Vietnam Pro, nền xanh băng và nút xanh đậm.

Nguồn đã kiểm tra từ repository chính thức ngày 09/11/2026:

- [Impeccable](https://github.com/pbakaus/impeccable): áp dụng hướng dẫn clarify/craft-floor vào cấu trúc đọc và trạng thái. Bản local đã có trong `.tools`, không cài hook hoặc thay cấu hình toàn máy.
- [shadcn/ui](https://github.com/shadcn-ui/ui), [MIT](https://github.com/shadcn-ui/ui/blob/main/LICENSE.md): tham khảo cách chia điều hướng, bộ lọc và khu vực dữ liệu, dùng tiếp composition Radix/CVA đang có. Không chép dashboard minh họa hoặc số liệu mẫu vào sản phẩm.
- [Recharts](https://github.com/recharts/recharts), [MIT](https://github.com/recharts/recharts/blob/main/LICENSE): thư viện biểu đồ React/SVG cho số liệu backend. Giữ chú giải, tên trục và bảng số liệu để hỗ trợ đọc bằng bàn phím/trình đọc màn hình.

Phân cấp: tiêu chí ở vùng nền nhẹ, kết quả trên surface trắng, bằng chứng có dấu hiệu màu hồng. Hướng dẫn dài mở khi cần. Màu luôn đi cùng nhãn; không thay bằng chứng bằng màu hoặc điểm tự tin giả.

Kiểm tra ở 1440×900, 390×844, 320×812: không tràn ngang; long text, empty/loading/error, focus, xác nhận thao tác và contrast. Ảnh/kiểm tra fixture là bằng chứng giao diện, không xác nhận lưu bền vững hoặc provider thật.
