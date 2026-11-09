"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

export function KnowledgeNavigation() {
  const pathname = usePathname();
  return (
    <nav className="section-navigation" aria-label="Chức năng kho tri thức">
      {[
        ["/knowledge", "Bài viết"],
        ["/knowledge/search", "Tra lỗi bằng vector"],
        ["/knowledge/history", "Lịch sử tra cứu"],
      ].map(([href, label]) => (
        <Link key={href} href={href} aria-current={pathname === href ? "page" : undefined}>
          {label}
        </Link>
      ))}
    </nav>
  );
}
