import { KnowledgeCreate } from "@/components/knowledge-editor";

export default async function NewKnowledgePage({
  searchParams,
}: {
  searchParams: Promise<{ incidentId?: string }>;
}) {
  const { incidentId } = await searchParams;
  return <KnowledgeCreate incidentId={incidentId} />;
}
