import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { Navbar } from "../components/Navbar";
import { Footer } from "../components/Footer";
import { ReportView } from "../components/ReportView";
import { getReport, shareReport } from "../api/sessions";
import { ApiError } from "../api/client";
import { useTopicCatalog } from "../hooks/useTopicCatalog";
import { CATEGORY_META } from "../constants/topicCategories";
import type { ReportResponse } from "../types/api";

export default function ReportPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const { token } = useAuth();
  const navigate = useNavigate();
  const [report, setReport] = useState<ReportResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { byTopic, label: topicLabel } = useTopicCatalog(token);

  const [shareUrl, setShareUrl] = useState<string | null>(null);
  const [sharing, setSharing] = useState(false);
  const [shareError, setShareError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!token || !sessionId) return;
    getReport(token, Number(sessionId))
      .then(setReport)
      .catch((err) => setError(err instanceof ApiError ? err.message : "Could not load the report."));
  }, [token, sessionId]);

  async function handleShare() {
    if (!token || !sessionId) return;
    setSharing(true);
    setShareError(null);
    try {
      const { shareToken } = await shareReport(token, Number(sessionId));
      setShareUrl(`${window.location.origin}/report/shared/${shareToken}`);
    } catch (err) {
      setShareError(err instanceof ApiError ? err.message : "Could not create a share link.");
    } finally {
      setSharing(false);
    }
  }

  async function handleCopyShareUrl() {
    if (!shareUrl) return;
    try {
      await navigator.clipboard.writeText(shareUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard API unavailable (e.g. insecure context) — the field is still selectable by hand.
    }
  }

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
        <ReportView
          report={report}
          topicLabel={topicLabel}
          categoryIcon={categoryMeta?.icon}
          categoryClass={category?.toLowerCase()}
        />

        <div className="card report-share-section">
          <div className="report-share-header">
            <h3>Share this report</h3>
            <button type="button" className="button secondary" onClick={() => window.print()}>
              Export PDF
            </button>
          </div>
          {!shareUrl ? (
            <button type="button" className="button primary" onClick={handleShare} disabled={sharing}>
              {sharing ? "Generating link…" : "Share report"}
            </button>
          ) : (
            <div className="share-link-row">
              <input type="text" readOnly value={shareUrl} onFocus={(e) => e.target.select()} />
              <button type="button" className="button secondary" onClick={handleCopyShareUrl}>
                {copied ? "Copied!" : "Copy link"}
              </button>
            </div>
          )}
          {shareError && <p className="error-text">{shareError}</p>}
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
