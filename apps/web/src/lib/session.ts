export type AccountSession = {
  accountId: string;
  email: string;
  plan: string;
  usage: number;
  freeMonthlyEvents: number;
  sessionToken: string;
};

const KEY = "hookguard.session";

export function loadSession(): AccountSession | null {
  if (typeof window === "undefined") {
    return null;
  }
  const raw = window.localStorage.getItem(KEY);
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as AccountSession;
    if (!parsed.sessionToken) {
      window.localStorage.removeItem(KEY);
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

export function saveSession(session: AccountSession): void {
  window.localStorage.setItem(KEY, JSON.stringify(session));
}

export function clearSession(): void {
  window.localStorage.removeItem(KEY);
}
