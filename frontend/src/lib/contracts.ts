// JSON contracts from AuthDtos, WorkspaceDtos and GitHubDtos in the Spring backend.
export interface User {
  id: string;
  email: string;
  displayName: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export type WorkspaceRole = "OWNER" | "ADMIN" | "MEMBER";

export interface Workspace {
  id: string;
  name: string;
  slug: string;
  createdBy: string;
}

export interface Member {
  userId: string;
  role: WorkspaceRole;
  status: "ACTIVE" | "REMOVED";
}

export interface Repository {
  id: string;
  githubRepositoryId: number;
  fullName: string;
  defaultBranch: string | null;
  active: boolean;
}

export interface RepositoryPage {
  items: Repository[];
  page: number;
  size: number;
  totalElements: number;
}

export interface GitHubConnection {
  id: string;
  installationId: number;
  accountLogin: string;
  status: "ACTIVE" | "SUSPENDED" | "REMOVED";
  repositoryCount: number;
}

export interface DataPage<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface WorkflowRun {
  id: string;
  repositoryId: string;
  githubRunId: number;
  runAttempt: number;
  workflowName: string;
  headBranch: string | null;
  headSha: string | null;
  status: string;
  conclusion: string | null;
  htmlUrl: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

export interface RunPage {
  items: WorkflowRun[];
  page: number;
  size: number;
  totalElements: number;
}

export interface WorkflowJob {
  id: string;
  githubJobId: number;
  name: string;
  status: string;
  conclusion: string | null;
  htmlUrl: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

export interface SyncRequest {
  id: string;
  repositoryId: string;
  githubRunId: number;
  runAttempt: number;
  publicationStatus: string;
  processingStatus: string;
  attempts: number;
  lastErrorCode: string | null;
  createdAt: string;
  processedAt: string | null;
}

export interface SyncPage {
  items: SyncRequest[];
  page: number;
  size: number;
  totalElements: number;
}

export type IncidentStatus = "OPEN" | "INVESTIGATING" | "RESOLVED" | "IGNORED";

export interface Incident {
  id: string;
  workspaceId: string;
  repositoryId: string;
  workflowRunId: string;
  failedJobId: string | null;
  assignedTo: string | null;
  title: string;
  status: IncidentStatus;
  severity: string;
  version: number;
  detectedAt: string;
  resolvedAt: string | null;
  updatedAt: string;
}

export interface LogSnippet {
  chunkIndex: number;
  content: string;
  contentSha256: string;
  capturedAt: string;
}

export interface IncidentLogs {
  status: string;
  errorCode: string | null;
  snippets: LogSnippet[];
}

export interface Analysis {
  id: string;
  incidentId: string;
  provider: string;
  model: string;
  promptVersion: string;
  status: "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED";
  summary: string | null;
  suspectedRootCause: string | null;
  suggestedFix: string | null;
  evidence: string[];
  errorCode: string | null;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
}

export interface AnalysisUsage {
  id: string;
  analysisId: string;
  attempt: number;
  status: string;
  inputTokens: number | null;
  outputTokens: number | null;
  estimatedCostUsd: number | null;
  durationMs: number | null;
  errorCode: string | null;
  createdAt: string;
}

export interface Notification {
  id: string;
  incidentId: string | null;
  type: string;
  title: string;
  readAt: string | null;
  createdAt: string;
}
