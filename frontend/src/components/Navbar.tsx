import { Link, useLocation } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { ThemeToggle } from "./ThemeToggle";

export function Navbar() {
  const { displayName, role, guest, logout } = useAuth();
  const location = useLocation();

  const isActive = (path: string) => (path === "/" ? location.pathname === "/" : location.pathname.startsWith(path));

  return (
    <header className="navbar">
      <div className="navbar-inner">
        <Link to="/" className="navbar-brand">
          <span className="navbar-logo">◆</span>
          Prepwise
        </Link>

        <nav className="navbar-links">
          <Link to="/" className={isActive("/") ? "navbar-link active" : "navbar-link"}>
            Dashboard
          </Link>
          <Link to="/progress" className={isActive("/progress") ? "navbar-link active" : "navbar-link"}>
            Progress
          </Link>
          <Link to="/packs" className={isActive("/packs") ? "navbar-link active" : "navbar-link"}>
            Study Packs
          </Link>
          {role === "ADMIN" && (
            <Link to="/admin" className={isActive("/admin") ? "navbar-link active" : "navbar-link"}>
              Admin
            </Link>
          )}
        </nav>

        <div className="navbar-actions">
          <ThemeToggle />
          {guest && (
            <Link to="/register" className="navbar-link navbar-guest-cta">
              Sign up to save progress
            </Link>
          )}
          <div className="navbar-user">
            <span className="navbar-avatar">{displayName?.charAt(0).toUpperCase() ?? "?"}</span>
            <span className="navbar-name">{guest ? "Guest" : displayName}</span>
          </div>
          <button type="button" className="secondary navbar-logout" onClick={logout}>
            Log out
          </button>
        </div>
      </div>
    </header>
  );
}
