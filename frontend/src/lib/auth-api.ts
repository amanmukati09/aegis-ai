import { apiGet, apiPost } from "./api";

export type AuthResponse = {
  accessToken: string;
  tokenType: string;
  userId: string;
  orgId: string | null;
  email: string;
  fullName: string;
  role: string;
};

export type MeResponse = {
  userId: string;
  orgId: string | null;
  email: string;
  fullName: string | null;
  role: string;
};

export function login(email: string, password: string) {
  return apiPost<AuthResponse>("/auth/login", { email, password });
}

export function register(input: {
  email: string;
  password: string;
  fullName: string;
  organizationName: string;
}) {
  return apiPost<AuthResponse>("/auth/register", input);
}

export function fetchMe(token: string) {
  return apiGet<MeResponse>("/auth/me", token);
}
