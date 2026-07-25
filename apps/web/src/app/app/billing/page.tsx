"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { AppShell } from "@/components/AppShell";
import { getUsage, openPortal, startCheckout, type UsageInfo } from "@/lib/api";
import { loadSession } from "@/lib/session";

export default function BillingPage() {
  const router = useRouter();
  const [usage, setUsage] = useState<UsageInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loadingAction, setLoadingAction] = useState(false);

  useEffect(() => {
    const session = loadSession();
    if (!session) {
      router.replace("/");
      return;
    }
    getUsage(session.accountId)
      .then(setUsage)
      .catch((err) => setError(err instanceof Error ? err.message : "erro"));
  }, [router]);

  async function checkout() {
    const session = loadSession();
    if (!session) {
      return;
    }
    setLoadingAction(true);
    setError(null);
    try {
      const origin = window.location.origin;
      const result = await startCheckout(
        session.accountId,
        `${origin}/app/billing?ok=1`,
        `${origin}/app/billing?cancel=1`
      );
      window.location.href = result.url;
    } catch (err) {
      setError(err instanceof Error ? err.message : "stripe_error");
      setLoadingAction(false);
    }
  }

  async function portal() {
    const session = loadSession();
    if (!session) {
      return;
    }
    setLoadingAction(true);
    setError(null);
    try {
      const result = await openPortal(session.accountId, `${window.location.origin}/app/billing`);
      window.location.href = result.url;
    } catch (err) {
      setError(err instanceof Error ? err.message : "stripe_error");
      setLoadingAction(false);
    }
  }

  return (
    <AppShell>
      <section className="panel">
        <h1 className="hero-title">Billing</h1>
        <p className="muted">Uso do mês e upgrade self-serve.</p>
        {usage ? (
          <div className="stat-row" style={{ marginTop: "1.25rem" }}>
            <div className="stat">
              <strong>{usage.plan}</strong>
              <span className="muted">Plano</span>
            </div>
            <div className="stat">
              <strong>
                {usage.eventCount}/{usage.freeMonthlyEvents}
              </strong>
              <span className="muted">Eventos (free cap)</span>
            </div>
            <div className="stat">
              <strong>{usage.stripeConfigured ? "sim" : "não"}</strong>
              <span className="muted">Stripe configurado</span>
            </div>
          </div>
        ) : (
          <p className="muted">Carregando...</p>
        )}
        <div className="actions">
          <button className="button" type="button" disabled={loadingAction} onClick={checkout}>
            Assinar Pro
          </button>
          <button
            className="button-secondary"
            type="button"
            disabled={loadingAction}
            onClick={portal}
          >
            Portal do cliente
          </button>
        </div>
        {error ? <p className="error">{error}</p> : null}
      </section>
    </AppShell>
  );
}
