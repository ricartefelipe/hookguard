const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export type AccountSession = {
  accountId: string;
  email: string;
  plan: string;
  usage: number;
  freeMonthlyEvents: number;
};

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
  freeMonthlyEvents: number;
  stripeConfigured: boolean;
};

function authHeaders(accountId: string): HeadersInit {
  return {
    "Content-Type": "application/json",
    "X-Account-Id": accountId,
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

export async function bootstrapAccount(email: string, name: string): Promise<AccountSession> {
  const response = await fetch(`${API_BASE}/v1/bootstrap/account`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, name }),
  });
  return parse<AccountSession>(response);
}

export async function listProjects(accountId: string): Promise<ProjectSummary[]> {
  const response = await fetch(`${API_BASE}/v1/projects`, {
    headers: authHeaders(accountId),
    cache: "no-store",
  });
  return parse<ProjectSummary[]>(response);
}

export async function createProject(
  accountId: string,
  input: { name: string; destinationUrl: string; dedupeHeader?: string }
): Promise<ProjectSummary> {
  const response = await fetch(`${API_BASE}/v1/projects`, {
    method: "POST",
    headers: authHeaders(accountId),
    body: JSON.stringify(input),
  });
  return parse<ProjectSummary>(response);
}

export async function getProject(accountId: string, projectId: string): Promise<ProjectSummary> {
  const response = await fetch(`${API_BASE}/v1/projects/${projectId}`, {
    headers: authHeaders(accountId),
    cache: "no-store",
  });
  return parse<ProjectSummary>(response);
}

export async function listEvents(
  accountId: string,
  projectId: string,
  status?: string
): Promise<EventSummary[]> {
  const params = new URLSearchParams({ projectId });
  if (status) {
    params.set("status", status);
  }
  const response = await fetch(`${API_BASE}/v1/events?${params.toString()}`, {
    headers: authHeaders(accountId),
    cache: "no-store",
  });
  return parse<EventSummary[]>(response);
}

export async function getEvent(accountId: string, eventId: string): Promise<EventDetail> {
  const response = await fetch(`${API_BASE}/v1/events/${eventId}`, {
    headers: authHeaders(accountId),
    cache: "no-store",
  });
  return parse<EventDetail>(response);
}

export async function replayEvent(accountId: string, eventId: string): Promise<{ jobId: string }> {
  const response = await fetch(`${API_BASE}/v1/events/${eventId}/replay`, {
    method: "POST",
    headers: authHeaders(accountId),
  });
  return parse<{ jobId: string }>(response);
}

export async function getUsage(accountId: string): Promise<UsageInfo> {
  const response = await fetch(`${API_BASE}/v1/billing/usage`, {
    headers: authHeaders(accountId),
    cache: "no-store",
  });
  return parse<UsageInfo>(response);
}

export async function startCheckout(
  accountId: string,
  successUrl: string,
  cancelUrl: string
): Promise<{ url: string }> {
  const response = await fetch(`${API_BASE}/v1/billing/checkout`, {
    method: "POST",
    headers: authHeaders(accountId),
    body: JSON.stringify({ successUrl, cancelUrl }),
  });
  return parse<{ url: string }>(response);
}

export async function openPortal(accountId: string, returnUrl: string): Promise<{ url: string }> {
  const response = await fetch(`${API_BASE}/v1/billing/portal`, {
    method: "POST",
    headers: authHeaders(accountId),
    body: JSON.stringify({ returnUrl }),
  });
  return parse<{ url: string }>(response);
}
