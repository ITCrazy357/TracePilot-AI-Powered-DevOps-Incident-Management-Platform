"use client";

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { api, errorMessage } from "@/lib/api";
import type { Member, Workspace, WorkspaceRole } from "@/lib/contracts";
import { useAuth } from "./auth-provider";

interface WorkspaceContextValue {
  workspaces: Workspace[];
  selectedId: string;
  workspace: Workspace | null;
  members: Member[];
  role: WorkspaceRole | undefined;
  loading: boolean;
  detailLoading: boolean;
  error: string;
  detailError: string;
  select: (id: string) => void;
  reload: () => void;
  reloadMembers: () => void;
  created: (workspace: Workspace) => void;
}

const WorkspaceContext = createContext<WorkspaceContextValue | null>(null);

export function WorkspaceProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [members, setMembers] = useState<Member[]>([]);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState("");
  const [detailError, setDetailError] = useState("");
  const [version, setVersion] = useState(0);

  const select = useCallback(
    (id: string) => {
      setSelectedId(id);
      setWorkspace(null);
      setMembers([]);
      setDetailError("");
      if (user) localStorage.setItem(`tracepilot.workspace.${user.id}`, id);
    },
    [user],
  );

  const reload = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const list = await api<Workspace[]>("/api/backend/workspaces");
      setWorkspaces(list);
      const saved = user ? localStorage.getItem(`tracepilot.workspace.${user.id}`) : null;
      const chosen = list.find((item) => item.id === saved) ?? list[0];
      select(chosen?.id ?? "");
      setVersion((value) => value + 1);
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setLoading(false);
    }
  }, [user, select]);

  useEffect(() => {
    void reload();
  }, [reload]);

  useEffect(() => {
    if (!selectedId) return;
    const controller = new AbortController();
    setDetailLoading(true);
    setDetailError("");
    Promise.all([
      api<Workspace>(`/api/backend/workspaces/${selectedId}`, { signal: controller.signal }),
      api<Member[]>(`/api/backend/workspaces/${selectedId}/members`, { signal: controller.signal }),
    ])
      .then(([current, people]) => {
        if (controller.signal.aborted) return;
        setWorkspace(current);
        setMembers(people);
      })
      .catch((failure) => {
        if (!controller.signal.aborted) {
          setMembers([]);
          setWorkspace(null);
          setDetailError(errorMessage(failure));
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setDetailLoading(false);
      });
    return () => controller.abort();
  }, [selectedId, version]);

  function created(current: Workspace) {
    setWorkspaces((list) => [...list, current]);
    select(current.id);
  }

  const role = members.find((member) => member.userId === user?.id)?.role;
  return (
    <WorkspaceContext.Provider
      value={{
        workspaces,
        selectedId,
        workspace,
        members,
        role,
        loading,
        detailLoading,
        error,
        detailError,
        select,
        reload,
        reloadMembers: () => setVersion((value) => value + 1),
        created,
      }}
    >
      {children}
    </WorkspaceContext.Provider>
  );
}

export function useWorkspace() {
  const context = useContext(WorkspaceContext);
  if (!context) throw new Error("WorkspaceProvider is required.");
  return context;
}
