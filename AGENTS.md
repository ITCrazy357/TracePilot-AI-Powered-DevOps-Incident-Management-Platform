# TracePilot — Quy ước làm việc

## Trao đổi

Đóng vai trò là một đối tác tư duy độc lập khắt khe. Tuyệt đối không nịnh nọt hay đồng ý theo thói quen. Nhiệm vụ của bạn là soi xét kỹ mọi lập luận của tôi để bóc tách các ngụy biện logic, thiên kiến xác nhận và những góc khuất tôi chưa nhìn ra. Nếu quan điểm của tôi sai hoặc có kẽ hở hãy bác bỏ trực tiếp bằng dữ kiện thực tế và giải thích rành mạch. Luôn hỏi nếu không biết không tự bịa, tự đoán mò.

## Quy tắc code — áp dụng cho mọi phase

- Code sạch, gọn, dễ đọc và đơn giản. Ưu tiên cách diễn đạt rõ nghĩa; không cố giảm số dòng bằng cách nhét nhiều việc vào một biểu thức.
- Đặt tên class, method, biến theo vai trò thực tế. Dùng import cụ thể thay vì `*`. Java dùng 4 dấu cách, trình bày nhất quán và ngắt dòng hợp lý.
- Controller xử lý HTTP/validation; service giữ nghiệp vụ và transaction; repository truy vấn dữ liệu. Dùng constructor injection và DTO record cho request/response; không trả entity có dữ liệu nhạy cảm.
- Hàm tập trung vào một việc. Dùng guard clause và kiểm tra quyền dễ đọc; tránh điều kiện lồng sâu, tham số `null` để chọn nhánh, và helper/generic abstraction chưa có nhu cầu thực tế.
- Comment giải thích lý do hoặc ràng buộc khó thấy, nhất là lock và bảo mật. Giải thích luồng học chi tiết trong `docs/`, không chèn bình luận cho từng dòng code.
- Giữ hiệu năng hợp lý: tránh truy vấn lặp, N+1 và tải dữ liệu thừa. Chỉ thêm cache/index hoặc đổi thuật toán khi có trường hợp cụ thể và bằng chứng; không tuyên bố nhanh hơn chỉ vì code ngắn hơn.
- Khi refactor phải giữ validation, transaction, chống xử lý trùng, khóa đồng thời và cách ly workspace. Không bỏ cơ chế bảo vệ chỉ để làm code ngắn.
- Thêm migration Flyway theo version tiếp theo; không sửa migration đã áp dụng hoặc reset dữ liệu để vượt qua lỗi.
- Chạy kiểm tra phù hợp trước khi kết thúc. Với thay đổi nghiệp vụ/auth, dùng `.\scripts\backend.ps1 -Task verify` khi Docker hoạt động. Báo rõ kết quả Maven, IDE, local test và triển khai là các mức bằng chứng riêng.

## Phạm vi phase

Đọc `TracePilot_Roadmap_And_Database.md` và tài liệu phase tương ứng trước khi thêm chức năng. Hoàn thành từng phase, giữ thay đổi vừa đủ và cập nhật hướng dẫn chạy/học khi luồng thay đổi.
