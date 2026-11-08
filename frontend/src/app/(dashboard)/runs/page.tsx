import { Runs } from "@/components/runs";
export default async function RunsPage({
  searchParams,
}: {
  searchParams: Promise<{ repositoryId?: string }>;
}) {
  const { repositoryId } = await searchParams;
  return <Runs initialRepository={repositoryId} />;
}
