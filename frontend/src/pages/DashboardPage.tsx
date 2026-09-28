import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { Navbar } from "../components/Navbar";
import { Footer } from "../components/Footer";
import { listSessions } from "../api/sessions";
import { useTopicCatalog } from "../hooks/useTopicCatalog";
import type { SessionSummaryResponse } from "../types/api";

export default function DashboardPage() {
  const { token, displayName } = useAuth();
  const [sessions, setSessions] = useState<SessionSummaryResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { sessionLabel, iconFor } = useTopicCatalog(token);

  useEffect(() => {
    if (!token) return;
    listSessions(token)
      .then(setSessions)
      .catch(() => setError("Could not load your past sessions."));
  }, [token]);

  const completedCount = sessions?.filter((s) => s.status === "COMPLETED").length ?? 0;
  const scored = sessions?.filter((s) => s.overallScore !== null) ?? [];
  const avgScore =
    scored.length > 0 ? Math.round(scored.reduce((sum, s) => sum + (s.overallScore ?? 0), 0) / scored.length) : null;

  return (
    <>
      <Navbar />
      <div className="page">
        <div className="dashboard-hero">
          <div>
            <p className="eyebrow">Dashboard</p>
            <h1>Welcome back, {displayName}</h1>
            <p className="hint">Pick up where you left off, or start a fresh mock interview.</p>
          </div>
          <Link to="/sessions/new" className="button primary start-cta">
            + Start New Interview
          </Link>
        </div>

        {sessions && sessions.length > 0 && (
          <div className="stats-overview">
            <div className="stat-box">
              <span className="stat-value">{sessions.length}</span>
              <span className="stat-label">Total sessions</span>
            </div>
            <div className="stat-box">
              <span className="stat-value">{completedCount}</span>
              <span className="stat-label">Completed</span>
            </div>
            <div className="stat-box">
              <span className="stat-value">{avgScore !== null ? avgScore : "—"}</span>
              <span className="stat-label">Avg score</span>
            </div>
          </div>
        )}

        <Link to="/packs" className="card session-row dashboard-packs-card">
          <span className="topic-icon topic-icon-default" aria-hidden>
            📚
          </span>
          <div className="session-row-info">
            <strong>Study Packs</strong>
            <span className="hint">Upload your notes or slides and turn them into study material.</span>
          </div>
          <span className="session-row-link">Open →</span>
        </Link>

        <h2>Past sessions</h2>
        {error && <p className="error-text">{error}</p>}
        {sessions === null && !error && (
          <div className="card state-card">
            <span className="spinner" aria-hidden />
            <p className="state-message">Loading…</p>
          </div>
        )}
        {sessions?.length === 0 && (
          <div className="empty-state">
            <span className="empty-state-icon">🎯</span>
            <p>No interviews yet — start your first one above.</p>
          </div>
        )}

        <ul className="session-list">
          {sessions?.map((s) => {
            const icon = iconFor(s);
            return (
              <li key={s.id} className="card session-row">
                <div className="session-row-main">
                  <span className={`topic-icon topic-icon-${icon.className}`} aria-hidden>
                    {icon.icon}
                  </span>
                  <div className="session-row-info">
                    <div className="session-row-title">
                      <strong>{sessionLabel(s)}</strong>
                      <span className={`status-badge status-${s.status.toLowerCase()}`}>{s.status}</span>
                      {s.overallScore !== null && <span className="score-badge">{s.overallScore}/100</span>}
                    </div>
                    <div className="session-meta">
                      <span>
                        {s.questionsAsked}/{s.targetQuestionCount} questions
                      </span>
                      <span>{new Date(s.createdAt).toLocaleString()}</span>
                    </div>
                  </div>
                </div>
                {s.status === "COMPLETED" && (
                  <Link to={`/sessions/${s.id}/report`} className="session-row-link">
                    View report →
                  </Link>
                )}
                {s.status === "IN_PROGRESS" && (
                  <Link to={`/interview/${s.id}`} className="session-row-link">
                    Continue →
                  </Link>
                )}
              </li>
            );
          })}
        </ul>
      </div>
      <Footer />
    </>
  );
}
