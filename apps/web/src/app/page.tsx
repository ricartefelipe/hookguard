"use client";

import { FormEvent, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { requestMagicLink } from "@/lib/api";
import { loadSession } from "@/lib/session";

export default function HomePage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [sent, setSent] = useState(false);
  const [devLink, setDevLink] = useState<string | null>(null);

  useEffect(() => {
    if (loadSession()) {
      router.replace("/app");
    }
  }, [router]);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setLoading(true);
    setError(null);
    setSent(false);
    setDevLink(null);
    try {
      const result = await requestMagicLink(email.trim(), name.trim() || email.trim());
      setSent(true);
      if (result.magicLink) {
        setDevLink(result.magicLink);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "falha_ao_enviar");
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
          Informe seu e-mail. Enviamos um link mágico para entrar no painel — sem senha.
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
            {loading ? "Enviando..." : "Enviar link de acesso"}
          </button>
          {sent ? (
            <p className="muted" style={{ marginTop: "0.9rem" }}>
              Link enviado. Confira o e-mail (Mailpit em local: http://localhost:8025).
            </p>
          ) : null}
          {devLink ? (
            <p className="mono" style={{ marginTop: "0.75rem" }}>
              Dev: <a href={devLink}>{devLink}</a>
            </p>
          ) : null}
          {error ? <p className="error">{error}</p> : null}
        </form>
      </section>
    </div>
  );
}
