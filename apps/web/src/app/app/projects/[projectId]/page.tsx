"use client";

import { FormEvent, useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { AppShell } from "@/components/AppShell";
import {
  getEvent,
  getProject,
  listEvents,
  replayEvent,
  resolveApiBase,
  rotateProjectKey,
  updateProject,
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
  const [rotatedKey, setRotatedKey] = useState<string | null>(null);
  const [destinationUrl, setDestinationUrl] = useState("");
  const [timeoutMs, setTimeoutMs] = useState("10000");
  const [maxAttempts, setMaxAttempts] = useState("6");
  const [dedupeHeader, setDedupeHeader] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const apiBase = resolveApiBase();

  async function loadAll(sessionToken: string) {
    const [projectData, eventData] = await Promise.all([
      getProject(sessionToken, projectId),
      listEvents(sessionToken, projectId, statusFilter || undefined),
    ]);
    setProject(projectData);
    setDestinationUrl(projectData.destinationUrl);
    setTimeoutMs(String(projectData.timeoutMs));
    setMaxAttempts(String(projectData.maxAttempts));
    setDedupeHeader(projectData.dedupeHeader ?? "");
    setEvents(eventData);
  }

  useEffect(() => {
    const session = loadSession();
    if (!session) {
      router.replace("/");
      return;
    }
    loadAll(session.sessionToken).catch((err) =>
      setError(err instanceof Error ? err.message : "erro")
    );
  }, [projectId, router, statusFilter]);

  async function openEvent(eventId: string) {
    const session = loadSession();
    if (!session) {
      return;
    }
    try {
      const detail = await getEvent(session.sessionToken, eventId);
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
      await replayEvent(session.sessionToken, eventId);
      await loadAll(session.sessionToken);
      await openEvent(eventId);
    } catch (err) {
      setError(err instanceof Error ? err.message : "erro_replay");
    }
  }

  async function onRotate() {
    const session = loadSession();
    if (!session) {
      return;
    }
    try {
      const updated = await rotateProjectKey(session.sessionToken, projectId);
      setRotatedKey(updated.projectKey ?? null);
      setProject(updated);
    } catch (err) {
      setError(err instanceof Error ? err.message : "erro_rotate");
    }
  }

  async function onSaveSettings(event: FormEvent) {
    event.preventDefault();
    const session = loadSession();
    if (!session) {
      return;
    }
    setSaved(false);
    setError(null);
    try {
      const updated = await updateProject(session.sessionToken, projectId, {
        destinationUrl: destinationUrl.trim(),
        timeoutMs: Number(timeoutMs),
        maxAttempts: Number(maxAttempts),
        dedupeHeader: dedupeHeader.trim(),
      });
      setProject(updated);
      setSaved(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "erro_salvar");
    }
  }

  return (
    <AppShell>
      <section className="panel">
        <h1 className="hero-title">{project?.name ?? "Projeto"}</h1>
        {project ? (
          <div className="stack">
            <div className="muted mono">
              Ingest: {apiBase}/v1/ingest/&lt;projectKey&gt;
            </div>
            {rotatedKey ? (
              <div className="mono">
                Nova projectKey: <strong>{rotatedKey}</strong>
              </div>
            ) : (
              <div className="muted">A projectKey só aparece na criação ou ao rotacionar.</div>
            )}
            <div className="actions" style={{ marginTop: 0 }}>
              <button className="button-secondary" type="button" onClick={onRotate}>
                Rotacionar projectKey
              </button>
            </div>
          </div>
        ) : (
          <p className="muted">Carregando...</p>
        )}
      </section>

      <section className="panel" style={{ marginTop: "1rem" }}>
        <h2 style={{ marginTop: 0, fontFamily: "var(--font-display)" }}>Configurações</h2>
        <form onSubmit={onSaveSettings}>
          <div className="field">
            <label htmlFor="destination">URL de destino</label>
            <input
              id="destination"
              required
              value={destinationUrl}
              onChange={(e) => setDestinationUrl(e.target.value)}
            />
          </div>
          <div className="grid-2">
            <div className="field">
              <label htmlFor="timeout">Timeout (ms)</label>
              <input
                id="timeout"
                type="number"
                min={500}
                required
                value={timeoutMs}
                onChange={(e) => setTimeoutMs(e.target.value)}
              />
            </div>
            <div className="field">
              <label htmlFor="attempts">Max attempts</label>
              <input
                id="attempts"
                type="number"
                min={1}
                max={20}
                required
                value={maxAttempts}
                onChange={(e) => setMaxAttempts(e.target.value)}
              />
            </div>
          </div>
          <div className="field">
            <label htmlFor="dedupe">Header de dedupe</label>
            <input
              id="dedupe"
              value={dedupeHeader}
              onChange={(e) => setDedupeHeader(e.target.value)}
              placeholder="X-Idempotency-Key"
            />
          </div>
          <button className="button" type="submit">
            Salvar
          </button>
          {saved ? <p className="muted">Salvo.</p> : null}
        </form>
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
