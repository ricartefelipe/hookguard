"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { AppShell } from "@/components/AppShell";
import {
  getEvent,
  getProject,
  listEvents,
  replayEvent,
  type EventDetail,
  type EventSummary,
  type ProjectSummary,
} from "@/lib/api";
import { loadSession } from "@/lib/session";

export default function ProjectDetailPage() {
  const params = useParams<{ projectId: string }>();
  const router = useRouter();
  const projectId = params.projectId;
  const [project, setProject] = useState<ProjectSummary | null>(null);
  const [events, setEvents] = useState<EventSummary[]>([]);
  const [selected, setSelected] = useState<EventDetail | null>(null);
  const [statusFilter, setStatusFilter] = useState("");
  const [error, setError] = useState<string | null>(null);
  const apiBase = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

  async function loadAll(accountId: string) {
    const [projectData, eventData] = await Promise.all([
      getProject(accountId, projectId),
      listEvents(accountId, projectId, statusFilter || undefined),
    ]);
    setProject(projectData);
    setEvents(eventData);
  }

  useEffect(() => {
    const session = loadSession();
    if (!session) {
      router.replace("/");
      return;
    }
    loadAll(session.accountId).catch((err) =>
      setError(err instanceof Error ? err.message : "erro")
    );
  }, [projectId, router, statusFilter]);

  async function openEvent(eventId: string) {
    const session = loadSession();
    if (!session) {
      return;
    }
    try {
      const detail = await getEvent(session.accountId, eventId);
      setSelected(detail);
    } catch (err) {
      setError(err instanceof Error ? err.message : "erro");
    }
  }

  async function onReplay(eventId: string) {
    const session = loadSession();
    if (!session) {
      return;
    }
    try {
      await replayEvent(session.accountId, eventId);
      await loadAll(session.accountId);
      await openEvent(eventId);
    } catch (err) {
      setError(err instanceof Error ? err.message : "erro_replay");
    }
  }

  return (
    <AppShell>
      <section className="panel">
        <h1 className="hero-title">{project?.name ?? "Projeto"}</h1>
        {project ? (
          <div className="stack">
            <div className="muted mono">Destino: {project.destinationUrl}</div>
            <div className="muted mono">
              Ingest: {apiBase}/v1/ingest/&lt;projectKey&gt;
            </div>
            <div className="muted">
              A projectKey só é exibida na criação. Use o valor salvo ou recrie o projeto.
            </div>
          </div>
        ) : (
          <p className="muted">Carregando...</p>
        )}
      </section>

      <section className="grid-2" style={{ marginTop: "1rem" }}>
        <div className="panel">
          <div className="actions" style={{ marginTop: 0, marginBottom: "1rem" }}>
            <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
              <option value="">Todos</option>
              <option value="received">received</option>
              <option value="delivering">delivering</option>
              <option value="delivered">delivered</option>
              <option value="dead">dead</option>
            </select>
          </div>
          <table className="table">
            <thead>
              <tr>
                <th>Evento</th>
                <th>Status</th>
                <th>Recebido</th>
              </tr>
            </thead>
            <tbody>
              {events.map((event) => (
                <tr key={event.id} style={{ cursor: "pointer" }} onClick={() => openEvent(event.id)}>
                  <td className="mono">{event.id.slice(0, 8)}</td>
                  <td>
                    <span className={`badge ${event.status}`}>{event.status}</span>
                  </td>
                  <td className="muted">{new Date(event.receivedAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {events.length === 0 ? <p className="muted">Sem eventos.</p> : null}
        </div>

        <div className="panel">
          <h2 style={{ marginTop: 0, fontFamily: "var(--font-display)" }}>Detalhe</h2>
          {!selected ? <p className="muted">Selecione um evento.</p> : null}
          {selected ? (
            <div className="stack">
              <div>
                <span className={`badge ${selected.status}`}>{selected.status}</span>
              </div>
              <div className="mono muted">{selected.id}</div>
              <pre className="code">{selected.bodyPreview || "(vazio)"}</pre>
              <div>
                <strong>Tentativas</strong>
                <ul>
                  {selected.attempts.map((attempt) => (
                    <li key={attempt.id} className="muted">
                      #{attempt.attemptNumber} · {attempt.httpStatus ?? "-"} ·{" "}
                      {attempt.errorMessage ?? "ok"}
                    </li>
                  ))}
                </ul>
              </div>
              <button className="button" type="button" onClick={() => onReplay(selected.id)}>
                Replay
              </button>
            </div>
          ) : null}
          {error ? <p className="error">{error}</p> : null}
        </div>
      </section>
    </AppShell>
  );
}
