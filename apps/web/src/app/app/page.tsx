"use client";

import { FormEvent, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { AppShell } from "@/components/AppShell";
import { createProject, listProjects, type ProjectSummary } from "@/lib/api";
import { loadSession } from "@/lib/session";

export default function ProjectsPage() {
  const router = useRouter();
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [name, setName] = useState("");
  const [destinationUrl, setDestinationUrl] = useState("");
  const [dedupeHeader, setDedupeHeader] = useState("X-Idempotency-Key");
  const [createdKey, setCreatedKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  async function refresh(sessionToken: string) {
    const data = await listProjects(sessionToken);
    setProjects(data);
  }

  useEffect(() => {
    const session = loadSession();
    if (!session) {
      router.replace("/");
      return;
    }
    refresh(session.sessionToken)
      .catch((err) => setError(err instanceof Error ? err.message : "erro"))
      .finally(() => setLoading(false));
  }, [router]);

  async function onCreate(event: FormEvent) {
    event.preventDefault();
    const session = loadSession();
    if (!session) {
      router.replace("/");
      return;
    }
    setError(null);
    try {
      const project = await createProject(session.sessionToken, {
        name: name.trim(),
        destinationUrl: destinationUrl.trim(),
        dedupeHeader: dedupeHeader.trim() || undefined,
      });
      setCreatedKey(project.projectKey ?? null);
      setName("");
      setDestinationUrl("");
      await refresh(session.sessionToken);
    } catch (err) {
      setError(err instanceof Error ? err.message : "erro_ao_criar");
    }
  }

  return (
    <AppShell>
      <section className="panel">
        <h1 className="hero-title">Projetos</h1>
        <p className="muted">Cada projeto gera uma URL de ingest e um destino de entrega.</p>
      </section>

      <section className="grid-2" style={{ marginTop: "1rem" }}>
        <form className="panel" onSubmit={onCreate}>
          <h2 style={{ marginTop: 0, fontFamily: "var(--font-display)" }}>Novo projeto</h2>
          <div className="field">
            <label htmlFor="name">Nome</label>
            <input id="name" required value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="destination">URL de destino</label>
            <input
              id="destination"
              required
              value={destinationUrl}
              onChange={(e) => setDestinationUrl(e.target.value)}
              placeholder="https://seu-app.com/webhooks"
            />
          </div>
          <div className="field">
            <label htmlFor="dedupe">Header de dedupe</label>
            <input
              id="dedupe"
              value={dedupeHeader}
              onChange={(e) => setDedupeHeader(e.target.value)}
            />
          </div>
          <button className="button" type="submit">
            Criar projeto
          </button>
          {createdKey ? (
            <p className="mono muted">
              projectKey (copie agora): <strong>{createdKey}</strong>
            </p>
          ) : null}
          {error ? <p className="error">{error}</p> : null}
        </form>

        <section className="panel">
          <h2 style={{ marginTop: 0, fontFamily: "var(--font-display)" }}>Lista</h2>
          {loading ? <p className="muted">Carregando...</p> : null}
          {!loading && projects.length === 0 ? <p className="muted">Nenhum projeto ainda.</p> : null}
          {projects.length > 0 ? (
            <table className="table">
              <thead>
                <tr>
                  <th>Nome</th>
                  <th>Status</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {projects.map((project) => (
                  <tr key={project.id}>
                    <td>
                      <div>{project.name}</div>
                      <div className="muted mono" style={{ fontSize: "0.8rem" }}>
                        {project.destinationUrl}
                      </div>
                    </td>
                    <td>
                      <span className={`badge ${project.status}`}>{project.status}</span>
                    </td>
                    <td>
                      <Link href={`/app/projects/${project.id}`}>Abrir</Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
        </section>
      </section>
    </AppShell>
  );
}
