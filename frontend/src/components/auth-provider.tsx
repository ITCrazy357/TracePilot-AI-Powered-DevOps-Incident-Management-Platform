"use client";

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { ApiError, api, errorMessage } from "@/lib/api";
import type { User } from "@/lib/contracts";

interface Session {
  user: User | null;
  loading: boolean;
  error: string;
  setUser: (user: User | null) => void;
  reload: () => void;
}

const AuthContext = createContext<Session | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const reload = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setUser(await api<User>("/api/auth/me"));
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) setUser(null);
      else setError(errorMessage(failure));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
    const expired = () => setUser(null);
    window.addEventListener("session-expired", expired);
    return () => window.removeEventListener("session-expired", expired);
  }, [reload]);

  return (
    <AuthContext.Provider value={{ user, setUser, loading, error, reload }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("AuthProvider is required.");
  return context;
}
