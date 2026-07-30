"use client";

import { FormEvent, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { getProviders, githubLoginUrl, loginWithPassword, requestMagicLink } from "@/lib/api";
import { loadSession, saveSession } from "@/lib/session";

export default function HomePage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [sent, setSent] = useState(false);
  const [devLink, setDevLink] = useState<string | null>(null);
  const [githubEnabled, setGithubEnabled] = useState(false);

  useEffect(() => {
    if (loadSession()) {
      router.replace("/app");
    }
    getProviders()
      .then((providers) => setGithubEnabled(providers.github))
      .catch(() => setGithubEnabled(false));
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
          Use e-mail/senha do TotalRecall, link mágico ou GitHub.
        </p>
        {githubEnabled ? (
          <div className="actions">
            <a className="button" href={githubLoginUrl()}>
              Entrar com GitHub
            </a>
          </div>
        ) : null}
        <form
          onSubmit={async (event) => {
            event.preventDefault();
            if (!password.trim()) {
              await onSubmit(event);
              return;
            }
            setLoading(true);
            setError(null);
            try {
              const session = await loginWithPassword(email.trim(), password);
              saveSession(session);
              router.replace("/app");
            } catch (err) {
              setError(err instanceof Error ? err.message : "falha_no_login");
            } finally {
              setLoading(false);
            }
          }}
          style={{ marginTop: "1.25rem" }}
        >
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
            <label htmlFor="password">Senha TotalRecall (opcional)</label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="trp_…"
              autoComplete="current-password"
            />
          </div>
          <div className="field">
            <label htmlFor="name">Nome</label>
            <input
              id="name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Opcional para magic link"
            />
          </div>
          <button className="button" type="submit" disabled={loading}>
            {loading ? "Entrando..." : password ? "Entrar com senha" : "Enviar link de acesso"}
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
