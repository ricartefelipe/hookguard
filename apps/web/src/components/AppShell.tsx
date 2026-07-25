"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { clearSession, loadSession } from "@/lib/session";
import { logout } from "@/lib/api";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

export function AppShell({ children }: { children: ReactNode }) {
  const router = useRouter();
  const [email, setEmail] = useState<string | null>(null);

  useEffect(() => {
    const session = loadSession();
    setEmail(session?.email ?? null);
  }, []);

  async function onLogout() {
    const session = loadSession();
    if (session?.sessionToken) {
      try {
        await logout(session.sessionToken);
      } catch {
      }
    }
    clearSession();
    router.push("/");
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <Link href="/app" className="brand">
          Hook<span>Guard</span>
        </Link>
        <nav className="nav">
          <Link href="/app">Projetos</Link>
          <Link href="/app/billing">Billing</Link>
          {email ? (
            <button type="button" className="button-secondary" onClick={onLogout}>
              Sair ({email})
            </button>
          ) : null}
        </nav>
      </header>
      {children}
    </div>
  );
}
