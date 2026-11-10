"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError, api, errorMessage } from "./api";

export function useResource<T>(
  path: string | null,
  { retainOnError = false }: { retainOnError?: boolean } = {},
) {
  const [version, setVersion] = useState(0);
  const [state, setState] = useState<{
    path: string | null;
    data: T | null;
    loading: boolean;
    error: string;
  }>({ path: null, data: null, loading: false, error: "" });
  const reload = useCallback(() => setVersion((current) => current + 1), []);

  useEffect(() => {
    if (!path) {
      setState((current) =>
        current.path === null ? current : { path: null, data: null, loading: false, error: "" },
      );
      return;
    }
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
        if (controller.signal.aborted) return;
        // Authorization and missing-resource errors must clear even retained data.
        const temporary =
          !(failure instanceof ApiError) || failure.status === 429 || failure.status >= 500;
        setState((current) => ({
          path,
          data: retainOnError && temporary && current.path === path ? current.data : null,
          loading: false,
          error: errorMessage(failure),
        }));
      });
    return () => controller.abort();
  }, [path, version, retainOnError]);

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
