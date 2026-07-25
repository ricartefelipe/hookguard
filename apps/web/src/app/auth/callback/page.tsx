"use client";

import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { verifyMagicLink } from "@/lib/api";
import { saveSession } from "@/lib/session";

function CallbackInner() {
  const router = useRouter();
  const params = useSearchParams();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const token = params.get("token");
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
      {error ? <p className="error">{error}</p> : <p className="muted">Validando seu link.</p>}
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
