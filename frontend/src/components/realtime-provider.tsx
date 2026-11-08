"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { ApiError, api } from "@/lib/api";
import { useWorkspace } from "./workspace-provider";

const RealtimeContext = createContext(false);

export function refreshWorkspace(workspaceId: string) {
  window.dispatchEvent(new CustomEvent("workspace-updated", { detail: workspaceId }));
}

export function RealtimeProvider({ children }: { children: ReactNode }) {
  const { selectedId, workspace } = useWorkspace();
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    if (!selectedId || !workspace) return;
    let source: EventSource | undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let disposed = false;
    let lastEventId = "";
    let failures = 0;
    setConnected(false);
    async function connect() {
      try {
        // Refresh an expired access cookie before EventSource, which cannot do it itself.
        await api("/api/auth/me");
        if (disposed) return;
        const cursor = lastEventId ? `?lastEventId=${encodeURIComponent(lastEventId)}` : "";
        source = new EventSource(
          `/api/backend/workspaces/${selectedId}/notifications/stream${cursor}`,
        );
        source.onopen = () => {
          failures = 0;
          setConnected(true);
          refreshWorkspace(selectedId);
        };
        source.addEventListener("notification", (event: MessageEvent) => {
          lastEventId = event.lastEventId;
          refreshWorkspace(selectedId);
        });
        source.onerror = () => {
          source?.close();
          if (!disposed) {
            setConnected(false);
            schedule();
          }
        };
      } catch (error) {
        if (!disposed && !(error instanceof ApiError && [401, 403, 404].includes(error.status)))
          schedule();
      }
    }
    function schedule() {
      timer = setTimeout(connect, Math.min(30_000, 2_000 * 2 ** failures++));
    }
    void connect();
    return () => {
      disposed = true;
      source?.close();
      clearTimeout(timer);
      setConnected(false);
    };
  }, [selectedId, workspace]);

  return <RealtimeContext.Provider value={connected}>{children}</RealtimeContext.Provider>;
}

export function useRealtime() {
  return useContext(RealtimeContext);
}
