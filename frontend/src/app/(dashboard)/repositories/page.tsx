import { RepositoryPanel } from "@/components/repository-panel";
import { WorkspaceBoundary } from "@/components/workspace-boundary";
export default function RepositoriesPage() {
  return (
    <>
      <div className="page-heading">
        <div>
          <h1>Kho mã</h1>
          <p className="muted">Các repository và quyền theo dõi trong workspace của bạn.</p>
        </div>
      </div>
      <WorkspaceBoundary>
        <RepositoryPanel />
      </WorkspaceBoundary>
    </>
  );
}
