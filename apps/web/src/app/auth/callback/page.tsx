"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { verifyMagicLink } from "@/lib/api";
import { saveSession, type AccountSession } from "@/lib/session";

function CallbackInner() {
  const router = useRouter();
  const params = useSearchParams();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const sessionToken = params.get("sessionToken");
    const token = params.get("token");

    if (sessionToken) {
      const session: AccountSession = {
        accountId: "",
        email: "",
        plan: "free",
        usage: 0,
        freeMonthlyEvents: 0,
        sessionToken,
      };
      saveSession(session);
      fetch(`${process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080"}/v1/auth/me`, {
        headers: { Authorization: `Bearer ${sessionToken}` },
      })
        .then(async (response) => {
          if (!response.ok) {
            throw new Error("unauthorized");
          }
          const me = await response.json();
          saveSession({
            accountId: me.accountId,
            email: me.email,
            plan: me.plan,
            usage: me.usage,
            freeMonthlyEvents: me.freeMonthlyEvents,
            sessionToken,
          });
          router.replace("/app");
        })
        .catch((err) => setError(err instanceof Error ? err.message : "falha_session"));
      return;
    }

    if (!token) {
      setError("token_ausente");
      return;
    }
    verifyMagicLink(token)
      .then((session) => {
        saveSession(session);
        router.replace("/app");
      })
      .catch((err) => setError(err instanceof Error ? err.message : "falha_verify"));
  }, [params, router]);

  return (
    <section className="panel" style={{ maxWidth: 480 }}>
      <h1 className="hero-title">Entrando...</h1>
      {error ? <p className="error">{error}</p> : <p className="muted">Validando seu acesso.</p>}
    </section>
  );
}

export default function AuthCallbackPage() {
  return (
    <div className="app-shell">
      <Suspense fallback={<section className="panel">Validando...</section>}>
        <CallbackInner />
      </Suspense>
    </div>
  );
}
