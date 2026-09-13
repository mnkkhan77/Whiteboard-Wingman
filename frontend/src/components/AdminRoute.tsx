import { Navigate, Outlet } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

/**
 * The backend is the real gate (every /api/admin/** route already rejects a non-ADMIN JWT with
 * 403 — PLAN.md §7/§12); this is just UX so a USER-role visitor doesn't land on a dashboard that
 * will fail every request.
 */
export function AdminRoute() {
  const { isAuthenticated, role } = useAuth();
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  if (role !== "ADMIN") return <Navigate to="/" replace />;
  return <Outlet />;
}
