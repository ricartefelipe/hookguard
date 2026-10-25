"use client";

import { FormEvent, useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { AppShell } from "@/components/AppShell";
import {
  deleteProject,
  getEvent,
  getProject,
  listEvents,
  pauseProject,
  replayEvent,
  resolveApiBase,
  resumeProject,
  rotateProjectKey,
  rotateSigningSecret,
  sendTestEvent,
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
  const [rotatedSecret, setRotatedSecret] = useState<string | null>(null);
  const [replayReason, setReplayReason] = useState("reprocessar");
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
    if (!replayReason.trim()) {
      setError("informe o motivo do replay");
      return;
    }
    try {
      await replayEvent(session.sessionToken, eventId, replayReason.trim());
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

  async function onRotateSecret() {
    const session = loadSession();
    if (!session) {
      return;
    }
    try {
      const updated = await rotateSigningSecret(session.sessionToken, projectId);
      setRotatedSecret(updated.signingSecret ?? null);
      setProject(updated);
    } catch (err) {
      setError(err instanceof Error ? err.message : "erro_rotate_secret");
    }
  }

  async function onTogglePause() {
    const session = loadSession();
    if (!session || !project) {
      return;
    }
    try {
      const updated =
        project.status === "paused"
          ? await resumeProject(session.sessionToken, projectId)
          : await pauseProject(session.sessionToken, projectId);
      setProject(updated);
    } catch (err) {
      setError(err instanceof Error ? err.message : "erro_pausa");
    }
  }

  async function onTest() {
    const session = loadSession();
    if (!session) {
      return;
    }
    try {
      await sendTestEvent(session.sessionToken, projectId);
      await loadAll(session.sessionToken);
    } catch (err) {
      setError(err instanceof Error ? err.message : "erro_teste");
    }
  }

  async function onDelete() {
    const session = loadSession();
    if (!session) {
      return;
    }
    if (!window.confirm("Excluir este projeto e os eventos retidos?")) {
      return;
    }
    try {
      await deleteProject(session.sessionToken, projectId);
      router.push("/app");
    } catch (err) {
      setError(err instanceof Error ? err.message : "erro_excluir");
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
            <div className="muted">
              Status: <span className={`badge ${project.status}`}>{project.status}</span>
              {project.signingSecretHint ? ` · segredo ${project.signingSecretHint}` : ""}
            </div>
            {rotatedKey ? (
              <div className="mono">
                Nova projectKey: <strong>{rotatedKey}</strong>
              </div>
            ) : (
              <div className="muted">A projectKey só aparece na criação ou ao rotacionar.</div>
            )}
            {rotatedSecret ? (
              <div className="mono">
                Novo signingSecret (copie agora): <strong>{rotatedSecret}</strong>
              </div>
            ) : null}
            <div className="actions" style={{ marginTop: 0 }}>
              <button className="button-secondary" type="button" onClick={onRotate}>
                Rotacionar projectKey
              </button>
              <button className="button-secondary" type="button" onClick={onRotateSecret}>
                Rotacionar segredo
              </button>
              <button className="button-secondary" type="button" onClick={onTest}>
                Enviar evento de teste
              </button>
              {project.status === "suspended" ? (
                <span className="muted">Entrega suspensa por cobrança.</span>
              ) : (
                <button className="button-secondary" type="button" onClick={onTogglePause}>
                  {project.status === "paused" ? "Retomar entrega" : "Pausar entrega"}
                </button>
              )}
              <button className="button-secondary" type="button" onClick={onDelete}>
                Excluir projeto
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
            <label htmlFor="status-filter" className="muted">
              Status
            </label>
            <select
              id="status-filter"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
            >
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
              <p className="muted">Prévia mascarada. O corpo entregue permanece byte a byte.</p>
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
              {selected.jobs && selected.jobs.length > 0 ? (
                <div>
                  <strong>Entregas</strong>
                  <ul>
                    {selected.jobs.map((job) => (
                      <li key={job.id} className="muted">
                        {job.triggerType} · {job.state}
                        {job.replayReason ? ` · ${job.replayReason}` : ""}
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}
              <div className="field">
                <label htmlFor="replay-reason">Motivo do replay</label>
                <input
                  id="replay-reason"
                  value={replayReason}
                  onChange={(e) => setReplayReason(e.target.value)}
                  maxLength={500}
                  required
                />
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
