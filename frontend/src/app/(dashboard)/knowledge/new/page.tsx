import { KnowledgeCreate } from "@/components/knowledge-editor";

export default async function NewKnowledgePage({
  searchParams,
}: {
  searchParams: Promise<{ incidentId?: string; analysisId?: string }>;
}) {
  const { incidentId, analysisId } = await searchParams;
  return <KnowledgeCreate incidentId={incidentId} analysisId={analysisId} />;
}
