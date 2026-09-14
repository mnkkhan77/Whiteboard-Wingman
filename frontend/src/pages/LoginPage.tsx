import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { ApiError } from "../api/client";

export default function LoginPage() {
  const { login, guestLogin } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [guestLoading, setGuestLoading] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      await login(email, password);
      navigate("/", { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Login failed. Please try again.");
    } finally {
      setLoading(false);
    }
  }

  async function handleGuest() {
    setError(null);
    setGuestLoading(true);
    try {
      await guestLogin();
      navigate("/sessions/new", { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not start a guest session. Please try again.");
    } finally {
      setGuestLoading(false);
    }
  }

  return (
    <div className="auth-layout">
      <div className="auth-visual">
        <div className="auth-visual-brand">
          <span className="auth-visual-logo">◆</span>
          Prepwise
        </div>

        <div className="auth-visual-content">
          <h1>Ace your next technical interview.</h1>
          <p>
            Practice live, adaptive mock interviews across DSA, Spring, Java Collections and System
            Design — scored and coached by an LLM using your own API key.
          </p>

          <div className="auth-feature-list">
            <div className="auth-feature">
              <span className="auth-feature-icon">🎯</span>
              <div className="auth-feature-text">
                <strong>Adaptive difficulty</strong>
                <span>Questions get harder or easier based on how you're doing.</span>
              </div>
            </div>
            <div className="auth-feature">
              <span className="auth-feature-icon">🧩</span>
              <div className="auth-feature-text">
                <strong>Verbal, MCQ &amp; live coding</strong>
                <span>Three distinct rounds, each with its own screen — with breaks in between.</span>
              </div>
            </div>
            <div className="auth-feature">
              <span className="auth-feature-icon">📊</span>
              <div className="auth-feature-text">
                <strong>Detailed reports</strong>
                <span>Strengths, weaknesses and a narrative summary after every session.</span>
              </div>
            </div>
          </div>
        </div>

        <p className="auth-visual-footer">Your LLM API key is used directly from your browser — never stored on our server.</p>
      </div>

      <div className="auth-form-side">
        <div className="auth-form-shell">
          <div className="auth-form-shell-header">
            <p className="eyebrow">Welcome back</p>
            <h1>Log in</h1>
            <p className="hint">Pick up your practice where you left off.</p>
          </div>

          <form className="card auth-card" onSubmit={handleSubmit}>
            {error && <p className="error-text">{error}</p>}
            <label>
              Email
              <span className="input-icon-group">
                <span className="input-icon">✉️</span>
                <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
              </span>
            </label>
            <label>
              Password
              <span className="input-icon-group">
                <span className="input-icon">🔒</span>
                <input
                  type={showPassword ? "text" : "password"}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  data-has-toggle="true"
                  required
                />
                <button
                  type="button"
                  className="input-visibility-toggle"
                  onClick={() => setShowPassword((v) => !v)}
                  aria-label={showPassword ? "Hide password" : "Show password"}
                >
                  {showPassword ? "🙈" : "👁️"}
                </button>
              </span>
            </label>
            <button type="submit" className="primary auth-submit" disabled={loading}>
              {loading ? "Logging in..." : "Log in"}
            </button>
            <p className="auth-switch">
              No account? <Link to="/register">Register</Link>
            </p>

            <div className="auth-divider">
              <span>or</span>
            </div>
            <button type="button" className="secondary auth-submit" onClick={handleGuest} disabled={guestLoading}>
              {guestLoading ? "Starting..." : "Try it free — no signup (1 attempt)"}
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}
