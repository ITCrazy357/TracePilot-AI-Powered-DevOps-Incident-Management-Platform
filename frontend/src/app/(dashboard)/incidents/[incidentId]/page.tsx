import { IncidentDetail } from "@/components/incident-detail";
export default async function IncidentPage({
  params,
}: {
  params: Promise<{ incidentId: string }>;
}) {
  return <IncidentDetail {...await params} />;
}
