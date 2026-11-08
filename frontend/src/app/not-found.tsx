import Link from "next/link";
import { Button } from "@/components/ui/button";
export default function NotFound() {
  return (
    <main className="not-found">
      <span className="eyebrow">404</span>
      <h1>Không tìm thấy trang</h1>
      <p className="muted">Đường dẫn này không tồn tại.</p>
      <Button asChild>
        <Link href="/">Về tổng quan</Link>
      </Button>
    </main>
  );
}
