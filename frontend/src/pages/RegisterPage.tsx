import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { ApiError } from "../api/client";

export default function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      await register(email, password, displayName);
      navigate("/", { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Registration failed. Please try again.");
    } finally {
      setLoading(false);
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
          <h1>Interview practice that adapts to you.</h1>
          <p>
            Create a free account and start a mock interview in under a minute — bring your own LLM
            API key, we never see or store it.
          </p>

          <div className="auth-feature-list">
            <div className="auth-feature">
              <span className="auth-feature-icon">⚡</span>
              <div className="auth-feature-text">
                <strong>Run real code</strong>
                <span>Compile and test your solutions against hidden test cases.</span>
              </div>
            </div>
            <div className="auth-feature">
              <span className="auth-feature-icon">🛡️</span>
              <div className="auth-feature-text">
                <strong>Privacy-first by design</strong>
                <span>Your API key never touches our database or logs.</span>
              </div>
            </div>
            <div className="auth-feature">
              <span className="auth-feature-icon">📈</span>
              <div className="auth-feature-text">
                <strong>Track your progress</strong>
                <span>Every past session and score, all in one dashboard.</span>
              </div>
            </div>
          </div>
        </div>

        <p className="auth-visual-footer">Free to use — you only pay your LLM provider for what you use.</p>
      </div>

      <div className="auth-form-side">
        <div className="auth-form-shell">
          <div className="auth-form-shell-header">
            <p className="eyebrow">Get started</p>
            <h1>Create account</h1>
            <p className="hint">Takes less than a minute.</p>
          </div>

          <form className="card auth-card" onSubmit={handleSubmit}>
            {error && <p className="error-text">{error}</p>}
            <label>
              Display name
              <span className="input-icon-group">
                <span className="input-icon">🧑</span>
                <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required autoFocus />
              </span>
            </label>
            <label>
              Email
              <span className="input-icon-group">
                <span className="input-icon">✉️</span>
                <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
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
                  minLength={8}
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
              <span className="hint">At least 8 characters</span>
            </label>
            <button type="submit" className="primary auth-submit" disabled={loading}>
              {loading ? "Creating account..." : "Register"}
            </button>
            <p className="auth-switch">
              Already have an account? <Link to="/login">Log in</Link>
            </p>
          </form>
        </div>
      </div>
    </div>
  );
}
