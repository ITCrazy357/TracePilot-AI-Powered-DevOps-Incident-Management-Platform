# Database dùng hằng ngày và kết nối DBeaver

Chốt môi trường local đang dùng: frontend `http://127.0.0.1:13000`, backend `http://127.0.0.1:18081`, database shipping được mở riêng cho công cụ desktop tại **127.0.0.1:15433**. Chạy bằng `scripts/shipping.ps1`; credential lấy từ `.env.shipping`.

## Vì sao cùng tên database nhưng dữ liệu khác nhau?

| Môi trường | Địa chỉ từ máy Windows | Volume | Vai trò |
| --- | --- | --- | --- |
| Shipping | `127.0.0.1:15433` khi bật truy cập local | `tracepilot-shipping_postgres_data` | Database chính cho ứng dụng tại port 13000/18081 |
| Compose phát triển cũ | `127.0.0.1:15432` | `tracepilot_postgres_data` | Database riêng, dùng `.env`; giữ lại dữ liệu cũ |
| Preview/Testcontainers | Port ngẫu nhiên | Tạm thời | Chỉ kiểm thử; dữ liệu có thể mất khi dừng preview |

Hai server có thể cùng tên database/user `tracepilot` nhưng vẫn giữ hai bộ dữ liệu. Sửa `POSTGRES_PORT` trong `.env` không đổi database của stack shipping: backend container lấy credential từ `.env.shipping` và kết nối service `postgres:5432` trong mạng Docker.

`postgres:5432` từ backend và `127.0.0.1:15433` từ DBeaver là hai đường truy cập **cùng một container/volume shipping**. Không sửa host bên trong backend thành `127.0.0.1`: đó là localhost của chính backend container.

## Bật truy cập từ DBeaver

Tại thư mục gốc dự án, lần đầu copy file mẫu nếu chưa có file local:

```powershell
if (-not (Test-Path -LiteralPath compose.shipping.local.yaml)) {
    Copy-Item -LiteralPath compose.shipping.local.example.yaml -Destination compose.shipping.local.yaml
}
.\scripts\shipping.ps1 -Task up
```

`shipping.ps1` tự nạp `compose.shipping.local.yaml` trên những lần chạy tiếp theo. File local bị Git ignore; file mẫu được track. Override chỉ bind PostgreSQL vào `127.0.0.1:15433`; Compose shipping cơ bản vẫn không publish port database. Chỉ dùng override này trên máy phát triển.

Tạo hoặc sửa PostgreSQL connection trong DBeaver:

| Trường | Giá trị |
| --- | --- |
| Host | `127.0.0.1` |
| Port | `15433` |
| Database | `tracepilot`, hoặc giá trị `POSTGRES_DB` trong `.env.shipping` nếu đã tùy chỉnh |
| User | `tracepilot`, hoặc giá trị `POSTGRES_USER` trong `.env.shipping` nếu đã tùy chỉnh |
| Password | Giá trị `POSTGRES_PASSWORD` trong **`.env.shipping`** |
| Schema | `public` |

Đặt tên connection là `TracePilot - shipping - 15433`, nhấn Test Connection rồi mở `Schemas → public → Tables`. Tên connection chỉ là nhãn, không quyết định database đích. Không dùng mật khẩu từ `.env` hoặc tài khoản `test/test` của Testcontainers cho connection này. Không thay mật khẩu trong file env chỉ để thử đăng nhập: credential khởi tạo không tự cập nhật password trong volume đã tồn tại.

SQL kiểm tra workspace:

```sql
SELECT id, name, slug
FROM public.workspaces
ORDER BY name;
```

Nếu chạy frontend bằng `npm run dev` tại port 3000, đặt `BACKEND_URL=http://127.0.0.1:18081` và `APP_ORIGIN=http://127.0.0.1:3000` trước khi khởi động Next. Như vậy cả frontend 13000 và frontend dev 3000 đều dùng database shipping.

## Giữ dữ liệu nhất quán

- Dùng `scripts/shipping.ps1 -Task up` để khởi động và `-Task stop` để dừng. Cả `stop` và `down` của helper đều giữ named volumes.
- Lệnh Docker Compose viết tay cần thêm `-f compose.shipping.local.yaml` nếu muốn giữ port desktop. Không ghép `compose.yaml` vào stack shipping.
- Giữ nguyên project mặc định `tracepilot-shipping`. Đổi `-Project` tạo namespace/volume khác và có thể làm bạn gặp database mới trống.
- Không dùng `down --volumes`, reset database hoặc sửa lịch sử Flyway để đổi cổng kết nối. Dữ liệu cũ ở 15432 không tự gộp vào shipping; việc chuyển dữ liệu cần xử lý riêng có đối chiếu schema/backup.

## Đối chiếu local ngày 09/11/2026

Trước khi mở port, shipping có 1 user/1 workspace, còn database cũ có 23 user/8 workspace và migration V1–V4. Port 15433 đã được gắn vào đúng volume shipping hiện có; đối chiếu số lượng và hash tập UUID user/workspace trước/sau khớp, không tạo database mới hoặc chuyển/xóa dữ liệu cũ. Backend readiness UP và trang login frontend trả HTTP 200 sau khi đổi port mapping. Đây là ảnh chụp trạng thái local; số bản ghi sẽ thay đổi khi người dùng thao tác.
