import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { Navbar } from "../components/Navbar";
import { Footer } from "../components/Footer";
import { getStats, listUsers } from "../api/admin";
import { ApiError } from "../api/client";
import { useTopicCatalog } from "../hooks/useTopicCatalog";
import type { AdminStats, AdminUserSummary, Page } from "../types/api";

type Tab = "USERS" | "STATS";

const PAGE_SIZE = 20;

export default function AdminDashboardPage() {
  const { token } = useAuth();
  const { label: topicLabel } = useTopicCatalog(token);
  const [tab, setTab] = useState<Tab>("USERS");
  const [usersPage, setUsersPage] = useState<Page<AdminUserSummary> | null>(null);
  const [pageIndex, setPageIndex] = useState(0);
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token || tab !== "USERS") return;
    listUsers(token, pageIndex, PAGE_SIZE)
      .then(setUsersPage)
      .catch((err) => setError(err instanceof ApiError ? err.message : "Could not load users."));
  }, [token, tab, pageIndex]);

  useEffect(() => {
    if (!token || tab !== "STATS" || stats) return;
    getStats(token)
      .then(setStats)
      .catch((err) => setError(err instanceof ApiError ? err.message : "Could not load stats."));
  }, [token, tab, stats]);

  return (
    <>
      <Navbar />
      <div className="page">
      <p className="eyebrow">Admin</p>
      <h1>Admin Dashboard</h1>

      <div className="tab-bar">
        <button className={tab === "USERS" ? "tab active" : "tab"} onClick={() => setTab("USERS")}>
          Users
        </button>
        <button className={tab === "STATS" ? "tab active" : "tab"} onClick={() => setTab("STATS")}>
          Stats
        </button>
      </div>

      {error && <p className="error-text">{error}</p>}

      {tab === "USERS" && (
        <div className="card">
          {!usersPage && !error && (
            <div className="state-card-inline">
              <span className="spinner" aria-hidden />
              <p className="state-message">Loading users…</p>
            </div>
          )}
          {usersPage && (
            <>
              <table className="admin-table">
                <thead>
                  <tr>
                    <th>Email</th>
                    <th>Name</th>
                    <th>Signed up</th>
                    <th>Last active</th>
                    <th>Sessions</th>
                    <th>Avg score</th>
                    <th>Top topic</th>
                  </tr>
                </thead>
                <tbody>
                  {usersPage.content.map((u) => (
                    <tr key={u.id}>
                      <td>
                        <Link to={`/admin/users/${u.id}`}>{u.email}</Link>
                      </td>
                      <td>{u.displayName}</td>
                      <td>{new Date(u.createdAt).toLocaleDateString()}</td>
                      <td>{u.lastActiveAt ? new Date(u.lastActiveAt).toLocaleDateString() : "—"}</td>
                      <td>{u.sessionCount}</td>
                      <td>{u.averageScore !== null ? u.averageScore.toFixed(0) : "—"}</td>
                      <td>{u.mostPracticedTopic ? topicLabel(u.mostPracticedTopic) : "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <div className="pagination">
                <button className="secondary" disabled={pageIndex === 0} onClick={() => setPageIndex((p) => p - 1)}>
                  Previous
                </button>
                <span>
                  Page {usersPage.number + 1} of {Math.max(usersPage.totalPages, 1)}
                </span>
                <button
                  className="secondary"
                  disabled={pageIndex + 1 >= usersPage.totalPages}
                  onClick={() => setPageIndex((p) => p + 1)}
                >
                  Next
                </button>
              </div>
            </>
          )}
        </div>
      )}

      {tab === "STATS" && (
        <div className="card">
          {!stats && !error && (
            <div className="state-card-inline">
              <span className="spinner" aria-hidden />
              <p className="state-message">Loading stats…</p>
            </div>
          )}
          {stats && (
            <>
              <div className="stats-overview">
                <div className="stat-box">
                  <span className="stat-value">{stats.totalUsers}</span>
                  <span className="stat-label">Total users</span>
                </div>
                <div className="stat-box">
                  <span className="stat-value">{stats.totalSessions}</span>
                  <span className="stat-label">Total sessions</span>
                </div>
              </div>

              <h3>Sessions by topic</h3>
              <table className="admin-table">
                <thead>
                  <tr>
                    <th>Topic</th>
                    <th>Sessions</th>
                    <th>Avg score</th>
                  </tr>
                </thead>
                <tbody>
                  {Object.entries(stats.sessionsByTopic).map(([topic, count]) => (
                    <tr key={topic}>
                      <td>{topicLabel(topic)}</td>
                      <td>{count}</td>
                      <td>{stats.averageScoreByTopic[topic as keyof typeof stats.averageScoreByTopic]?.toFixed(0) ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <h3>Average score by difficulty</h3>
              <table className="admin-table">
                <thead>
                  <tr>
                    <th>Difficulty</th>
                    <th>Avg score</th>
                  </tr>
                </thead>
                <tbody>
                  {Object.entries(stats.averageScoreByDifficulty).map(([difficulty, avg]) => (
                    <tr key={difficulty}>
                      <td>{difficulty}</td>
                      <td>{avg?.toFixed(0)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <h3>Signups by week</h3>
              <table className="admin-table">
                <thead>
                  <tr>
                    <th>Week of</th>
                    <th>Signups</th>
                  </tr>
                </thead>
                <tbody>
                  {stats.signupsOverTime.map((w) => (
                    <tr key={w.weekStart}>
                      <td>{w.weekStart}</td>
                      <td>{w.count}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </div>
      )}
      </div>
      <Footer />
    </>
  );
}
