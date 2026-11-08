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
