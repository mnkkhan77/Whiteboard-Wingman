import type { CSSProperties } from "react";
import type { Correctness, Difficulty, ReportResponse, Topic } from "../types/api";

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

interface ReportViewProps {
  report: ReportResponse;
  /** Pass useTopicCatalog's `labelerFor(report)` so a pack quiz's STUDY_PACK topic shows the pack title. */
  topicLabel: (topic: Topic | null | undefined) => string;
  categoryIcon?: string;
  categoryClass?: string;
}

/** The read-only score/breakdown display shared by the owner's ReportPage and the public,
 *  token-based PublicReportPage — everything account-specific (share controls, nav, "practice
 *  again" actions) stays in the pages that wrap this.
 *
 *  A multi-topic "loop" session (topicBreakdown carries 2+ entries) additionally gets a generic
 *  header naming the topic chain, a per-topic score section, and a topic tag on every question;
 *  a single-topic report renders exactly as it always has, since every one of those extras is
 *  redundant when there's only one topic to attribute things to. */
export function ReportView({ report, topicLabel, categoryIcon, categoryClass }: ReportViewProps) {
  const isMultiTopic = report.topicBreakdown.length > 1;

  return (
    <>
      <p className="eyebrow">Report</p>
      <h1>
        {isMultiTopic ? (
          <>🔁 Multi-topic interview</>
        ) : (
          <>
            <span className={`topic-icon topic-icon-${categoryClass ?? "default"} report-hero-icon`} aria-hidden>
              {categoryIcon ?? "💡"}
            </span>
            {topicLabel(report.topic)} interview
          </>
        )}
      </h1>
      {isMultiTopic && (
        <p className="hint">
          {report.topicBreakdown.map((t) => topicLabel(t.topic)).join(" → ")}
        </p>
      )}

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

      {isMultiTopic && (
        <>
          <h2>Topic breakdown</h2>
          <div className="topic-breakdown-list">
            {report.topicBreakdown.map((t) => (
              <div key={t.topic} className="card topic-breakdown-item">
                <span className="topic-breakdown-name">{topicLabel(t.topic)}</span>
                <span className={`score-badge score-badge-${scoreTier(t.averageScore)}`}>{t.averageScore}/100</span>
                <span className="hint">{t.questionCount} question{t.questionCount === 1 ? "" : "s"}</span>
              </div>
            ))}
          </div>
        </>
      )}

      <h2>Question breakdown</h2>
      <div className="breakdown-list">
        {report.breakdown.map((q) => (
          <details key={q.sequenceNumber} className="card breakdown-item">
            <summary>
              <span className="breakdown-summary-row">
                <span>Q{q.sequenceNumber}</span>
                {isMultiTopic && <span className="breakdown-topic-tag">{topicLabel(q.topic)}</span>}
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
    </>
  );
}
