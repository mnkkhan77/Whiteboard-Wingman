import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { Navbar } from "../components/Navbar";
import { Footer } from "../components/Footer";
import { PackTierCard } from "../components/PackTierCard";
import { PackUploadForm } from "../components/PackUploadForm";
import { PackListItem } from "../components/PackListItem";
import { useStudyPacks } from "../hooks/useStudyPacks";

export default function PacksPage() {
  const { token, guest } = useAuth();
  // Guests can't upload (backend rejects with GUEST_UPLOAD_NOT_ALLOWED) — they get a sign-up CTA
  // instead, so the hook is given no token and makes no requests for them.
  const { limits, packs, loadError, reload, upload, remove } = useStudyPacks(guest ? null : token);

  let body: ReactNode;
  if (guest) {
    body = (
      <div className="empty-state">
        <span className="empty-state-icon">📚</span>
        <p>Study packs are for signed-up users — create a free account to upload your first document.</p>
        <Link to="/register" className="button primary">
          Sign up to upload
        </Link>
      </div>
    );
  } else if (loadError) {
    body = (
      <div className="card state-card state-card-error">
        <span className="state-icon" aria-hidden>
          ⚠️
        </span>
        <p className="state-message">{loadError}</p>
        <button type="button" className="secondary" onClick={reload}>
          Try again
        </button>
      </div>
    );
  } else if (!limits || !packs) {
    body = (
      <div className="card state-card">
        <span className="spinner" aria-hidden />
        <p className="state-message">Loading your study packs…</p>
      </div>
    );
  } else {
    body = (
      <>
        <PackTierCard limits={limits} />
        <PackUploadForm limits={limits} onUpload={upload} />

        <h2>Your packs</h2>
        {packs.length === 0 ? (
          <div className="empty-state">
            <span className="empty-state-icon">📚</span>
            <p>No study packs yet — upload a document above to create your first one.</p>
          </div>
        ) : (
          <ul className="session-list">
            {packs.map((p) => (
              <PackListItem key={p.id} pack={p} limits={limits} onDelete={remove} />
            ))}
          </ul>
        )}
      </>
    );
  }

  return (
    <>
      <Navbar />
      <div className="page">
        <p className="eyebrow">Study Packs</p>
        <h1>Your study packs</h1>
        <p className="hint">
          Upload your notes, slides or textbooks. We'll index them so you can chat with them, quiz yourself and
          generate flashcards.
        </p>
        {body}
      </div>
      <Footer />
    </>
  );
}
