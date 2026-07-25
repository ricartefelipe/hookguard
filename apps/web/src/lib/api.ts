import type { AccountSession } from "@/lib/session";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export type ProjectSummary = {
  id: string;
  name: string;
  destinationUrl: string;
  timeoutMs: number;
  maxAttempts: number;
  dedupeHeader: string | null;
  status: string;
  createdAt: string;
  projectKey?: string;
  signingSecret?: string;
  ingestPath?: string;
};

export type EventSummary = {
  id: string;
  projectId: string;
  receivedAt: string;
  status: string;
};

export type EventDetail = EventSummary & {
  headersJson: string;
  bodyBase64: string;
  bodyPreview: string;
  contentType: string | null;
  dedupeKey: string | null;
  attempts: Array<{
    id: string;
    attemptNumber: number;
    startedAt: string;
    finishedAt: string | null;
    httpStatus: number | null;
    errorMessage: string | null;
    responseBodySnippet: string | null;
  }>;
};

export type UsageInfo = {
  plan: string;
  eventCount: number;
  includedMonthlyEvents: number;
  freeMonthlyEvents: number;
  proMonthlyEvents: number;
  businessMonthlyEvents: number;
  stripeConfigured: boolean;
};

function authHeaders(sessionToken: string): HeadersInit {
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${sessionToken}`,
  };
}

async function parse<T>(response: Response): Promise<T> {
  const text = await response.text();
  const data = text ? JSON.parse(text) : {};
  if (!response.ok) {
    const error = typeof data.error === "string" ? data.error : "request_failed";
    throw new Error(error);
  }
  return data as T;
}

export async function requestMagicLink(
  email: string,
  name: string
): Promise<{ sent: boolean; email: string; magicLink?: string }> {
  const response = await fetch(`${API_BASE}/v1/auth/magic-link`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, name }),
  });
  return parse(response);
}

export async function verifyMagicLink(token: string): Promise<AccountSession> {
  const response = await fetch(`${API_BASE}/v1/auth/verify`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token }),
  });
  return parse<AccountSession>(response);
}

export async function logout(sessionToken: string): Promise<void> {
  await fetch(`${API_BASE}/v1/auth/logout`, {
    method: "POST",
    headers: authHeaders(sessionToken),
  });
}

export async function listProjects(sessionToken: string): Promise<ProjectSummary[]> {
  const response = await fetch(`${API_BASE}/v1/projects`, {
    headers: authHeaders(sessionToken),
    cache: "no-store",
  });
  return parse<ProjectSummary[]>(response);
}

export async function createProject(
  sessionToken: string,
  input: { name: string; destinationUrl: string; dedupeHeader?: string }
): Promise<ProjectSummary> {
  const response = await fetch(`${API_BASE}/v1/projects`, {
    method: "POST",
    headers: authHeaders(sessionToken),
    body: JSON.stringify(input),
  });
  return parse<ProjectSummary>(response);
}

export async function updateProject(
  sessionToken: string,
  projectId: string,
  input: {
    destinationUrl?: string;
    timeoutMs?: number;
    maxAttempts?: number;
    dedupeHeader?: string;
  }
): Promise<ProjectSummary> {
  const response = await fetch(`${API_BASE}/v1/projects/${projectId}`, {
    method: "PUT",
    headers: authHeaders(sessionToken),
    body: JSON.stringify(input),
  });
  return parse<ProjectSummary>(response);
}

export async function getProviders(): Promise<{ magicLink: boolean; github: boolean }> {
  const response = await fetch(`${API_BASE}/v1/auth/providers`, { cache: "no-store" });
  return parse(response);
}

export function githubLoginUrl(): string {
  return `${API_BASE}/v1/auth/github`;
}

export async function getProject(sessionToken: string, projectId: string): Promise<ProjectSummary> {
  const response = await fetch(`${API_BASE}/v1/projects/${projectId}`, {
    headers: authHeaders(sessionToken),
    cache: "no-store",
  });
  return parse<ProjectSummary>(response);
}

export async function rotateProjectKey(
  sessionToken: string,
  projectId: string
): Promise<ProjectSummary> {
  const response = await fetch(`${API_BASE}/v1/projects/${projectId}/rotate-key`, {
    method: "POST",
    headers: authHeaders(sessionToken),
  });
  return parse<ProjectSummary>(response);
}

export async function listEvents(
  sessionToken: string,
  projectId: string,
  status?: string
): Promise<EventSummary[]> {
  const params = new URLSearchParams({ projectId });
  if (status) {
    params.set("status", status);
  }
  const response = await fetch(`${API_BASE}/v1/events?${params.toString()}`, {
    headers: authHeaders(sessionToken),
    cache: "no-store",
  });
  return parse<EventSummary[]>(response);
}

export async function getEvent(sessionToken: string, eventId: string): Promise<EventDetail> {
  const response = await fetch(`${API_BASE}/v1/events/${eventId}`, {
    headers: authHeaders(sessionToken),
    cache: "no-store",
  });
  return parse<EventDetail>(response);
}

export async function replayEvent(
  sessionToken: string,
  eventId: string
): Promise<{ jobId: string }> {
  const response = await fetch(`${API_BASE}/v1/events/${eventId}/replay`, {
    method: "POST",
    headers: authHeaders(sessionToken),
  });
  return parse<{ jobId: string }>(response);
}

export async function getUsage(sessionToken: string): Promise<UsageInfo> {
  const response = await fetch(`${API_BASE}/v1/billing/usage`, {
    headers: authHeaders(sessionToken),
    cache: "no-store",
  });
  return parse<UsageInfo>(response);
}

export async function startCheckout(
  sessionToken: string,
  successUrl: string,
  cancelUrl: string,
  plan: "pro" | "business" = "pro"
): Promise<{ url: string }> {
  const response = await fetch(`${API_BASE}/v1/billing/checkout`, {
    method: "POST",
    headers: authHeaders(sessionToken),
    body: JSON.stringify({ successUrl, cancelUrl, plan }),
  });
  return parse<{ url: string }>(response);
}

export async function openPortal(sessionToken: string, returnUrl: string): Promise<{ url: string }> {
  const response = await fetch(`${API_BASE}/v1/billing/portal`, {
    method: "POST",
    headers: authHeaders(sessionToken),
    body: JSON.stringify({ returnUrl }),
  });
  return parse<{ url: string }>(response);
}
