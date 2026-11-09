// JSON contracts mirrored from the Spring backend DTO records.
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
  operation: string;
  provider: string;
  model: string;
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

export type FeedbackReason =
  | "CORRECT_DIAGNOSIS"
  | "ACTIONABLE_FIX"
  | "USEFUL_EVIDENCE"
  | "INCORRECT_DIAGNOSIS"
  | "INSUFFICIENT_EVIDENCE"
  | "UNSAFE_SUGGESTION"
  | "NOT_A_REAL_INCIDENT"
  | "OTHER";

export interface AnalysisFeedback {
  id: string;
  analysisId: string;
  helpful: boolean;
  reason: FeedbackReason;
  comment: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface QualityUsage {
  operation: string;
  provider: string;
  model: string;
  calls: number;
  succeeded: number;
  failed: number;
  unknown: number;
  inputTokens: number | null;
  outputTokens: number | null;
  inputTokenSamples: number;
  outputTokenSamples: number;
  knownEstimatedCostUsd: number | null;
  pricedCalls: number;
  durationSamples: number;
  averageDurationMs: number | null;
  p95DurationMs: number | null;
}

export interface QualityDashboard {
  from: string;
  to: string;
  analyses: {
    total: number;
    pending: number;
    running: number;
    succeeded: number;
    failed: number;
    successRate: number | null;
    averageLatencyMs: number | null;
    p95LatencyMs: number | null;
  };
  feedback: {
    votes: number;
    helpful: number;
    unhelpful: number;
    reviewedAnalyses: number;
    helpfulRate: number | null;
    reviewCoverage: number | null;
  };
  reasons: { reason: FeedbackReason; helpful: boolean; count: number }[];
  usage: QualityUsage[];
  errors: { code: string; count: number }[];
}

export interface AuditEntry {
  id: string;
  actorUserId: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  metadata: Record<string, string | number | boolean>;
  requestId: string | null;
  createdAt: string;
}

export interface OutboxEvent {
  id: string;
  repositoryId: string;
  eventType: string;
  publicationStatus: string;
  processingStatus: string;
  attempts: number;
  totalAttempts: number;
  replayCount: number;
  errorCode: string | null;
  createdAt: string;
  availableAt: string;
  processedAt: string | null;
}

export interface Backlog {
  eventType: string;
  status: string;
  count: number;
  oldestAgeSeconds: number | null;
}

export interface KnowledgeArticle {
  id: string;
  workspaceId: string;
  sourceIncidentId: string | null;
  title: string;
  rootCause: string;
  resolution: string;
  status: "DRAFT" | "APPROVED" | "ARCHIVED";
  indexStatus: "NONE" | "RUNNING" | "READY" | "FAILED";
  version: number;
  contentVersion: number;
  createdBy: string;
  approvedBy: string | null;
  approvedAt: string | null;
  indexErrorCode: string | null;
  createdAt: string;
  updatedAt: string;
}

export type KnowledgeSearchMode = "EXACT" | "APPROXIMATE" | "HYBRID";

export interface KnowledgeSearchResult {
  items: {
    id: string;
    articleId: string;
    articleVersion: number;
    content: string;
    contentSha256: string;
    similarity: number;
  }[];
  mode: KnowledgeSearchMode;
  embeddingModel: string;
}

export interface AnalysisEvidence {
  id: string;
  sourceType: "LOG" | "KNOWLEDGE";
  logSnippetId: string | null;
  logChunkIndex: number | null;
  knowledgeChunkId: string | null;
  articleId: string | null;
  articleVersion: number | null;
  quote: string;
  sourceSha256: string;
  similarityScore: number | null;
}
