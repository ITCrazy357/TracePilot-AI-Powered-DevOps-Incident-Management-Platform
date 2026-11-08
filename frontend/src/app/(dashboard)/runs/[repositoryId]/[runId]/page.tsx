import { RunDetail } from "@/components/runs";
export default async function RunPage({
  params,
}: {
  params: Promise<{ repositoryId: string; runId: string }>;
}) {
  return <RunDetail {...await params} />;
}
