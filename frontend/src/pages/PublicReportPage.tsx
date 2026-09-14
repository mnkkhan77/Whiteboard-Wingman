import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { Footer } from "../components/Footer";
import { ReportView } from "../components/ReportView";
import { getPublicReport } from "../api/sessions";
import { ApiError } from "../api/client";
import { useTopicCatalog } from "../hooks/useTopicCatalog";
import { CATEGORY_META } from "../constants/topicCategories";
import type { ReportResponse } from "../types/api";

/** Unauthenticated view of a report reached via its public share link (see ReportPage's "Share
 *  report" button) — no Authorization header is sent, and no account-specific chrome (Navbar,
 *  "practice again") is shown since the viewer isn't necessarily signed in. */
export default function PublicReportPage() {
  const { token: shareToken } = useParams<{ token: string }>();
  const [report, setReport] = useState<ReportResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { byTopic, label: topicLabel } = useTopicCatalog(null);

  useEffect(() => {
    if (!shareToken) return;
    getPublicReport(shareToken)
      .then(setReport)
      .catch((err) => setError(err instanceof ApiError ? err.message : "Could not load this report."));
  }, [shareToken]);

  if (error) {
    return (
      <>
        <div className="page">
          <div className="card state-card state-card-error">
            <span className="state-icon" aria-hidden>⚠️</span>
            <p className="state-message">{error}</p>
            <Link to="/" className="state-card-link">Go to Prepwise</Link>
          </div>
        </div>
        <Footer />
      </>
    );
  }

  if (!report) {
    return (
      <>
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
      <header className="public-report-header">
        <span className="navbar-brand">
          <span className="navbar-logo">◆</span>
          Prepwise
        </span>
      </header>
      <div className="page">
        <ReportView
          report={report}
          topicLabel={topicLabel}
          categoryIcon={categoryMeta?.icon}
          categoryClass={category?.toLowerCase()}
        />

        <div className="report-actions">
          <button type="button" className="button secondary" onClick={() => window.print()}>
            Export PDF
          </button>
          <Link to="/register" className="button primary start-cta">
            Try Prepwise yourself
          </Link>
        </div>
      </div>
      <Footer />
    </>
  );
}
