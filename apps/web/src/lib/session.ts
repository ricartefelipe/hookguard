import type { AccountSession } from "@/lib/api";

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
    return JSON.parse(raw) as AccountSession;
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
