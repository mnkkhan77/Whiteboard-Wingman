import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider } from "./context/AuthContext";
import { ApiKeyProvider } from "./context/ApiKeyContext";
import { ThemeProvider } from "./context/ThemeContext";
import { ThemeToggle } from "./components/ThemeToggle";
import { ProtectedRoute } from "./components/ProtectedRoute";
import { AdminRoute } from "./components/AdminRoute";
import LoginPage from "./pages/LoginPage";
import RegisterPage from "./pages/RegisterPage";
import DashboardPage from "./pages/DashboardPage";
import ProgressPage from "./pages/ProgressPage";
import SessionStartPage from "./pages/SessionStartPage";
import InterviewPage from "./pages/InterviewPage";
import ReportPage from "./pages/ReportPage";
import PublicReportPage from "./pages/PublicReportPage";
import PacksPage from "./pages/PacksPage";
import PackChatPage from "./pages/PackChatPage";
import PackQuizPage from "./pages/PackQuizPage";
import PackFlashcardsPage from "./pages/PackFlashcardsPage";
import AdminDashboardPage from "./pages/AdminDashboardPage";
import AdminUserDetailPage from "./pages/AdminUserDetailPage";

export default function App() {
  return (
    <ThemeProvider>
      <AuthProvider>
        <ApiKeyProvider>
          <BrowserRouter>
            <Routes>
              <Route
                path="/login"
                element={
                  <>
                    <ThemeToggle className="theme-toggle theme-toggle-floating" />
                    <LoginPage />
                  </>
                }
              />
              <Route
                path="/register"
                element={
                  <>
                    <ThemeToggle className="theme-toggle theme-toggle-floating" />
                    <RegisterPage />
                  </>
                }
              />

              <Route path="/report/shared/:token" element={<PublicReportPage />} />

              <Route element={<ProtectedRoute />}>
                <Route path="/" element={<DashboardPage />} />
                <Route path="/progress" element={<ProgressPage />} />
                <Route path="/sessions/new" element={<SessionStartPage />} />
                <Route path="/interview/:sessionId" element={<InterviewPage />} />
                <Route path="/sessions/:sessionId/report" element={<ReportPage />} />
                <Route path="/packs" element={<PacksPage />} />
                <Route path="/packs/:packId/chat" element={<PackChatPage />} />
                <Route path="/packs/:packId/quiz" element={<PackQuizPage />} />
                <Route path="/packs/:packId/flashcards" element={<PackFlashcardsPage />} />

                <Route element={<AdminRoute />}>
                  <Route path="/admin" element={<AdminDashboardPage />} />
                  <Route path="/admin/users/:userId" element={<AdminUserDetailPage />} />
                </Route>
              </Route>

              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </BrowserRouter>
        </ApiKeyProvider>
      </AuthProvider>
    </ThemeProvider>
  );
}
