import { useEffect, useState, type CSSProperties } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { Navbar } from "../components/Navbar";
import { Footer } from "../components/Footer";
import { getReport } from "../api/sessions";
import { ApiError } from "../api/client";
import { useTopicCatalog } from "../hooks/useTopicCatalog";
import { CATEGORY_META } from "../constants/topicCategories";
import type { Correctness, Difficulty, ReportResponse } from "../types/api";

const DIFFICULTY_LABELS: Difficulty[] = ["EASY", "MEDIUM", "HARD"];

function difficultyReachedLabel(avg: number) {
  const idx = Math.min(2, Math.max(0, Math.round(avg)));
  return `${DIFFICULTY_LABELS[idx].charAt(0)}${DIFFICULTY_LABELS[idx].slice(1).toLowerCase()}`;
}

function scoreTier(score: number): "good" | "mid" | "poor" {
  if (score >= 80) return "good";
  if (score >= 50) return "mid";
  return "poor";
}

const CORRECTNESS_LABEL: Record<Correctness, string> = {
  CORRECT: "Correct",
  PARTIALLY_CORRECT: "Partially correct",
  INCORRECT: "Incorrect",
};

export default function ReportPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const { token } = useAuth();
  const navigate = useNavigate();
  const [report, setReport] = useState<ReportResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { byTopic, label: topicLabel } = useTopicCatalog(token);

  useEffect(() => {
    if (!token || !sessionId) return;
    getReport(token, Number(sessionId))
      .then(setReport)
      .catch((err) => setError(err instanceof ApiError ? err.message : "Could not load the report."));
  }, [token, sessionId]);

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

  if (!report) {
    return (
      <>
        <Navbar />
        <div className="page">
          <div className="card state-card">
            <span className="spinner" aria-hidden />
            <p className="state-message">Loading report…</p>
          </div>
        </div>
        <Footer />
      </>
    );
  }

  const category = byTopic[report.topic]?.category;
  const categoryMeta = category ? CATEGORY_META[category] : null;

  return (
    <>
      <Navbar />
      <div className="page">
        <p className="eyebrow">Report</p>
        <h1>
          <span className={`topic-icon topic-icon-${category?.toLowerCase() ?? "default"} report-hero-icon`} aria-hidden>
            {categoryMeta?.icon ?? "💡"}
          </span>
          {topicLabel(report.topic)} interview
        </h1>

        <div className="card report-hero">
          <div className="score-ring" style={{ "--pct": Math.max(0, Math.min(100, report.overallScore)) } as CSSProperties}>
            <div className="score-ring-inner">
              <span className="score-ring-value">{report.overallScore}</span>
              <span className="score-ring-label">out of 100</span>
            </div>
          </div>

          <div className="report-hero-details">
            {report.summaryText && <p className="summary-text">"{report.summaryText}"</p>}

            {report.strongTopics.length > 0 && (
              <div className="tag-row">
                <span className="tag-row-label">Strong:</span>
                <div className="tag-list">
                  {report.strongTopics.map((t) => (
                    <span key={t} className="tag tag-strong">{topicLabel(t)}</span>
                  ))}
                </div>
              </div>
            )}
            {report.weakTopics.length > 0 && (
              <div className="tag-row">
                <span className="tag-row-label">Needs work:</span>
                <div className="tag-list">
                  {report.weakTopics.map((t) => (
                    <span key={t} className="tag tag-weak">{topicLabel(t)}</span>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>

        <div className="stats-overview">
          <div className="stat-box">
            <span className="stat-value">{report.questionCount}</span>
            <span className="stat-label">Questions answered</span>
          </div>
          <div className="stat-box">
            <span className="stat-value">{difficultyReachedLabel(report.averageDifficultyReached)}</span>
            <span className="stat-label">Avg. difficulty reached</span>
          </div>
          <div className="stat-box">
            <span className="stat-value">{report.tabSwitchCount}</span>
            <span className="stat-label">Tab switches</span>
          </div>
        </div>

        <h2>Question breakdown</h2>
        <div className="breakdown-list">
          {report.breakdown.map((q) => (
            <details key={q.sequenceNumber} className="card breakdown-item">
              <summary>
                <span className="breakdown-summary-row">
                  <span>Q{q.sequenceNumber}</span>
                  <span className={`difficulty-badge difficulty-${q.difficulty.toLowerCase()}`}>{q.difficulty}</span>
                  <span className={`score-badge score-badge-${scoreTier(q.score)}`}>{q.score}/100</span>
                  <span className="breakdown-correctness">{CORRECTNESS_LABEL[q.correctness]}</span>
                </span>
              </summary>
              <p className="question-text">{q.promptText}</p>
              <div className="breakdown-answer-columns">
                <div className="breakdown-answer-column">
                  <h4 className="breakdown-answer-title">Your answer</h4>
                  <p>{q.answerText}</p>
                </div>
                <div className="breakdown-answer-column">
                  <h4 className="breakdown-answer-title">Feedback</h4>
                  <p>{q.feedback}</p>
                </div>
              </div>
            </details>
          ))}
        </div>

        <div className="report-actions">
          {report.overallScore < 70 && (
            <button
              type="button"
              className="button secondary"
              onClick={() => navigate("/sessions/new", { state: { prefillTopic: report.topic } })}
            >
              Practice this topic again
            </button>
          )}
          <Link to="/sessions/new" className="button primary start-cta">
            Start Another Interview
          </Link>
          <Link to="/">Back to dashboard</Link>
        </div>
      </div>
      <Footer />
    </>
  );
}
