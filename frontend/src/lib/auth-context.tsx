"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { fetchMe, login as apiLogin, register as apiRegister, type MeResponse } from "./auth-api";

const TOKEN_KEY = "aegis_token";

type AuthUser = {
  userId: string;
  orgId: string | null;
  email: string;
  fullName: string | null;
  role: string;
};

type AuthContextValue = {
  user: AuthUser | null;
  token: string | null;
  loading: boolean;
  isAdmin: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (input: {
    email: string;
    password: string;
    fullName: string;
    organizationName: string;
  }) => Promise<void>;
  setToken: (token: string) => Promise<void>;
  logout: () => void;
};

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [token, setTokenState] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const loadMe = useCallback(async (t: string) => {
    const me: MeResponse = await fetchMe(t);
    setUser({
      userId: me.userId,
      orgId: me.orgId,
      email: me.email,
      fullName: me.fullName,
      role: me.role,
    });
  }, []);

  // Rehydrate from localStorage on mount.
  useEffect(() => {
    const stored = typeof window !== "undefined" ? localStorage.getItem(TOKEN_KEY) : null;
    if (!stored) {
      setLoading(false);
      return;
    }
    setTokenState(stored);
    loadMe(stored)
      .catch(() => {
        localStorage.removeItem(TOKEN_KEY);
        setTokenState(null);
        setUser(null);
      })
      .finally(() => setLoading(false));
  }, [loadMe]);

  const persist = useCallback(
    async (t: string) => {
      localStorage.setItem(TOKEN_KEY, t);
      setTokenState(t);
      await loadMe(t);
    },
    [loadMe]
  );

  const login = useCallback(
    async (email: string, password: string) => {
      const res = await apiLogin(email, password);
      await persist(res.accessToken);
    },
    [persist]
  );

  const register = useCallback(
    async (input: { email: string; password: string; fullName: string; organizationName: string }) => {
      const res = await apiRegister(input);
      await persist(res.accessToken);
    },
    [persist]
  );

  const logout = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    setTokenState(null);
    setUser(null);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      token,
      loading,
      isAdmin: user?.role === "super_admin" || user?.role === "org_admin",
      login,
      register,
      setToken: persist,
      logout,
    }),
    [user, token, loading, login, register, persist, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
