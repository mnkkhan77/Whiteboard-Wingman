import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { Navbar } from "../components/Navbar";
import { Footer } from "../components/Footer";
import { getProgress } from "../api/sessions";
import { ApiError } from "../api/client";
import { useTopicCatalog } from "../hooks/useTopicCatalog";
import type { PersonalProgressResponse } from "../types/api";

// Duplicated from InterviewPage.tsx/ReportPage.tsx — same red/yellow/green score tiering, kept
// consistent with the existing score-badge-good/mid/poor CSS classes.
function scoreTier(score: number): "good" | "mid" | "poor" {
  if (score >= 80) return "good";
  if (score >= 50) return "mid";
  return "poor";
}

export default function ProgressPage() {
  const { token } = useAuth();
  const [progress, setProgress] = useState<PersonalProgressResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { label: topicLabel } = useTopicCatalog(token);

  useEffect(() => {
    if (!token) return;
    getProgress(token)
      .then(setProgress)
      .catch((err) => setError(err instanceof ApiError ? err.message : "Could not load your progress."));
  }, [token]);

  if (error) {
    return (
      <>
        <Navbar />
        <div className="page">
          <div className="card state-card state-card-error">
            <span className="state-icon" aria-hidden>⚠️</span>
            <p className="state-message">{error}</p>
            <Link to="/" className="state-card-link">Back to dashboard</Link>
          </div>
        </div>
        <Footer />
      </>
    );
  }

  if (!progress) {
    return (
      <>
        <Navbar />
        <div className="page">
          <div className="card state-card">
            <span className="spinner" aria-hidden />
            <p className="state-message">Loading your progress…</p>
          </div>
        </div>
        <Footer />
      </>
    );
  }

  const topicAverages = Object.entries(progress.averageScoreByTopic) as [string, number][];
  topicAverages.sort((a, b) => a[1] - b[1]);

  return (
    <>
      <Navbar />
      <div className="page">
        <p className="eyebrow">Progress</p>
        <h1>Your progress</h1>
        <p className="hint">Track your score trend across past sessions and see which topics need the most work.</p>

        {progress.totalSessions === 0 && (
          <div className="empty-state">
            <span className="empty-state-icon">📈</span>
            <p>No interviews yet — complete a session to start tracking your progress.</p>
            <Link to="/sessions/new" className="button primary start-cta">
              + Start New Interview
            </Link>
          </div>
        )}

        {progress.totalSessions > 0 && (
          <>
            <div className="stats-overview">
              <div className="stat-box">
                <span className="stat-value">{progress.totalSessions}</span>
                <span className="stat-label">Total sessions</span>
              </div>
              <div className="stat-box">
                <span className="stat-value">{progress.completedSessions}</span>
                <span className="stat-label">Completed</span>
              </div>
              <div className="stat-box">
                <span className="stat-value">
                  {progress.overallAverageScore !== null ? Math.round(progress.overallAverageScore) : "—"}
                </span>
                <span className="stat-label">Overall avg score</span>
              </div>
            </div>

            <h2>Score trend</h2>
            {progress.scoreTrend.length === 0 ? (
              <div className="empty-state">
                <span className="empty-state-icon">📊</span>
                <p>No completed reports yet — your score trend will appear here once you finish a session.</p>
              </div>
            ) : (
              <div className="card score-trend-card">
                <div className="score-trend">
                  {progress.scoreTrend.map((sp) => (
                    <div
                      key={sp.sessionId}
                      className="score-trend-bar-wrap"
                      title={`${topicLabel(sp.topic)} — ${new Date(sp.completedAt).toLocaleDateString()} — ${sp.overallScore}/100`}
                    >
                      <span className="score-trend-bar-value">{sp.overallScore}</span>
                      <div
                        className={`score-trend-bar score-trend-bar-${scoreTier(sp.overallScore)}`}
                        style={{ height: `${Math.max(4, sp.overallScore)}%` }}
                      />
                    </div>
                  ))}
                </div>
                <p className="progress-label">Hover over a bar for the topic, date and score.</p>
              </div>
            )}

            <h2>Average score by topic</h2>
            {topicAverages.length === 0 ? (
              <div className="empty-state">
                <span className="empty-state-icon">🎯</span>
                <p>No completed reports yet — per-topic averages will appear here once you finish a session.</p>
              </div>
            ) : (
              <ul className="topic-average-list">
                {topicAverages.map(([topic, avg]) => (
                  <li key={topic} className="card topic-average-row">
                    <span className="topic-average-label">{topicLabel(topic)}</span>
                    <div className="topic-average-bar-track">
                      <div className="topic-average-bar-fill" style={{ width: `${Math.max(2, avg)}%` }} />
                    </div>
                    <span className={`score-badge score-badge-${scoreTier(avg)}`}>{Math.round(avg)}/100</span>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </div>
      <Footer />
    </>
  );
}
