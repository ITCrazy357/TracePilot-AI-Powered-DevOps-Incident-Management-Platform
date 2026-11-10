"use client";

import { useWorkspace } from "./workspace-provider";
import { WorkspaceBoundary } from "./workspace-boundary";
import { PageHeading } from "./ui/operational";
import { KnowledgeNavigation } from "./knowledge-navigation";
import { KnowledgeSearch } from "./knowledge-search";
import { SearchHistory } from "./search-history";

export function KnowledgeLookup({ history = false }: { history?: boolean }) {
  const { selectedId } = useWorkspace();
  return (
    <WorkspaceBoundary>
      <PageHeading
        title={history ? "Lịch sử tra cứu" : "Tra lỗi bằng vector"}
        description={
          history
            ? "Xem lại truy vấn và kết quả đã lưu của riêng bạn."
            : "Mô tả lỗi để tìm nội dung tương tự trong các bài tri thức đã duyệt."
        }
      />
      <KnowledgeNavigation />
      <div className="lookup-layout" key={selectedId}>
        {history ? <SearchHistory /> : <KnowledgeSearch />}
      </div>
    </WorkspaceBoundary>
  );
}
