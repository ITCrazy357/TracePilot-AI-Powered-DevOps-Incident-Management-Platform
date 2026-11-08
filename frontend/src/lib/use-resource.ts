"use client";

import { useCallback, useEffect, useState } from "react";
import { api, errorMessage } from "./api";

export function useResource<T>(path: string | null) {
  const [version, setVersion] = useState(0);
  const [state, setState] = useState<{
    path: string | null;
    data: T | null;
    loading: boolean;
    error: string;
  }>({ path: null, data: null, loading: false, error: "" });
  const reload = useCallback(() => setVersion((current) => current + 1), []);

  useEffect(() => {
    if (!path) return;
    const controller = new AbortController();
    setState((current) => ({
      path,
      data: current.path === path ? current.data : null,
      loading: true,
      error: "",
    }));
    api<T>(path, { signal: controller.signal })
      .then((data) => {
        if (!controller.signal.aborted) setState({ path, data, loading: false, error: "" });
      })
      .catch((failure) => {
        if (!controller.signal.aborted)
          setState({ path, data: null, loading: false, error: errorMessage(failure) });
      });
    return () => controller.abort();
  }, [path, version]);

  useEffect(() => {
    const updated = (event: Event) => {
      const workspaceId = (event as CustomEvent<string>).detail;
      if (path?.startsWith(`/api/backend/workspaces/${workspaceId}/`)) reload();
    };
    window.addEventListener("workspace-updated", updated);
    return () => window.removeEventListener("workspace-updated", updated);
  }, [path, reload]);

  return {
    data: state.path === path ? state.data : null,
    error: state.path === path ? state.error : "",
    loading: Boolean(path) && (state.path !== path || state.loading),
    reload,
  };
}
