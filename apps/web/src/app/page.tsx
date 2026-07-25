"use client";

import { FormEvent, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { bootstrapAccount } from "@/lib/api";
import { loadSession, saveSession } from "@/lib/session";

export default function HomePage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (loadSession()) {
      router.replace("/app");
    }
  }, [router]);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const session = await bootstrapAccount(email.trim(), name.trim() || email.trim());
      saveSession(session);
      router.push("/app");
    } catch (err) {
      setError(err instanceof Error ? err.message : "falha_ao_entrar");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand">
          Hook<span>Guard</span>
        </div>
      </header>
      <section className="panel" style={{ maxWidth: 520 }}>
        <h1 className="hero-title">Webhooks que não somem</h1>
        <p className="muted">
          Entre com e-mail para criar ou recuperar sua conta, configurar um projeto e colar a URL de
          ingest no provedor.
        </p>
        <form onSubmit={onSubmit} style={{ marginTop: "1.25rem" }}>
          <div className="field">
            <label htmlFor="email">E-mail</label>
            <input
              id="email"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="voce@empresa.com"
            />
          </div>
          <div className="field">
            <label htmlFor="name">Nome</label>
            <input
              id="name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Opcional"
            />
          </div>
          <button className="button" type="submit" disabled={loading}>
            {loading ? "Entrando..." : "Entrar no painel"}
          </button>
          {error ? <p className="error">{error}</p> : null}
        </form>
      </section>
    </div>
  );
}
