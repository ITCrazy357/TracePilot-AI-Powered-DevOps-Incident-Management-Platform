import { KnowledgeDetail } from "@/components/knowledge-detail";

export default async function KnowledgeArticlePage({
  params,
  searchParams,
}: {
  params: Promise<{ articleId: string }>;
  searchParams: Promise<{ version?: string }>;
}) {
  const { articleId } = await params;
  const { version } = await searchParams;
  return <KnowledgeDetail articleId={articleId} evidenceVersion={version} />;
}
