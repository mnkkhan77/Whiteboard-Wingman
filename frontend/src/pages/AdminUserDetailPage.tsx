import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { Navbar } from "../components/Navbar";
import { Footer } from "../components/Footer";
import { getUserDetail } from "../api/admin";
import { ApiError } from "../api/client";
import { useTopicCatalog } from "../hooks/useTopicCatalog";
import type { AdminUserDetail } from "../types/api";

export default function AdminUserDetailPage() {
  const { userId } = useParams<{ userId: string }>();
  const { token } = useAuth();
  const [detail, setDetail] = useState<AdminUserDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { label: topicLabel } = useTopicCatalog(token);

  useEffect(() => {
    if (!token || !userId) return;
    getUserDetail(token, Number(userId))
      .then(setDetail)
      .catch((err) => setError(err instanceof ApiError ? err.message : "Could not load this user."));
  }, [token, userId]);

  if (error) {
    return (
      <>
        <Navbar />
        <div className="page">
          <div className="card state-card state-card-error">
            <span className="state-icon" aria-hidden>⚠️</span>
            <p className="state-message">{error}</p>
            <Link to="/admin" className="state-card-link">Back to admin dashboard</Link>
          </div>
        </div>
        <Footer />
      </>
    );
  }

  if (!detail) {
    return (
      <>
        <Navbar />
        <div className="page">
          <div className="card state-card">
            <span className="spinner" aria-hidden />
            <p className="state-message">Loading user…</p>
          </div>
        </div>
        <Footer />
      </>
    );
  }

  return (
    <>
      <Navbar />
      <div className="page">
      <header className="page-header">
        <h1>{detail.displayName}</h1>
        <Link to="/admin">Back to admin dashboard</Link>
      </header>

      <div className="card">
        <p>
          <strong>Email:</strong> {detail.email}
        </p>
        <p>
          <strong>Role:</strong> {detail.role}
        </p>
        <p>
          <strong>Signed up:</strong> {new Date(detail.createdAt).toLocaleString()}
        </p>
        <p>
          <strong>Last active:</strong> {detail.lastActiveAt ? new Date(detail.lastActiveAt).toLocaleString() : "—"}
        </p>
      </div>

      <h2>Session history</h2>
      {detail.sessions.length === 0 && <p>No interviews yet.</p>}
      <table className="admin-table">
        <thead>
          <tr>
            <th>Topic</th>
            <th>Status</th>
            <th>Difficulty (start → current)</th>
            <th>Progress</th>
            <th>Score</th>
            <th>Started</th>
          </tr>
        </thead>
        <tbody>
          {detail.sessions.map((s) => (
            <tr key={s.id}>
              <td>{topicLabel(s.topic)}</td>
              <td>
                <span className={`status-badge status-${s.status.toLowerCase()}`}>{s.status}</span>
              </td>
              <td>
                {s.startingDifficulty} → {s.currentDifficulty}
              </td>
              <td>
                {s.questionsAsked}/{s.targetQuestionCount}
              </td>
              <td>{s.overallScore !== null ? `${s.overallScore}/100` : "—"}</td>
              <td>{new Date(s.createdAt).toLocaleDateString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>
      <Footer />
    </>
  );
}
