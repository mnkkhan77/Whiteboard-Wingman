import { useState } from "react";
import { Link } from "react-router-dom";
import { ApiError } from "../api/client";
import { formatBytes, isPackInFlight, packErrorMessage } from "../utils/packs";
import type { PackDto, PackLimitsDto } from "../types/api";

// Next-phase features, shown disabled on READY packs so the roadmap is visible.
const COMING_SOON_ACTIONS = [
  { label: "Quiz", icon: "📝" },
  { label: "Flashcards", icon: "🗂️" },
  { label: "Course", icon: "🎓" },
];

interface PackListItemProps {
  pack: PackDto;
  limits: PackLimitsDto;
  onDelete: (packId: number) => Promise<void>;
}

export function PackListItem({ pack, limits, onDelete }: PackListItemProps) {
  const [confirming, setConfirming] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const processing = isPackInFlight(pack.status);

  // On success the parent drops this item, so there's no state to reset afterwards.
  const handleDelete = async () => {
    setDeleting(true);
    setError(null);
    try {
      await onDelete(pack.id);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not delete this pack.");
      setDeleting(false);
    }
  };

  return (
    <li className="card pack-row">
      <div className="pack-row-top">
        <div className="session-row-info">
          <div className="session-row-title">
            <strong className="pack-wrap">{pack.title}</strong>
            <span className={`status-badge pack-status-${pack.status.toLowerCase()}`}>
              {processing && <span className="spinner spinner-inline" aria-hidden />}
              {pack.status}
            </span>
          </div>
          <div className="session-meta pack-meta">
            <span className="pack-wrap">{pack.fileName}</span>
            <span>{formatBytes(pack.sizeBytes)}</span>
            {pack.pageCount !== null && <span>{pack.pageCount} pages</span>}
            {pack.chunkCount !== null && <span>{pack.chunkCount} chunks</span>}
            <span>{new Date(pack.createdAt).toLocaleString()}</span>
          </div>
        </div>

        {confirming ? (
          <div className="pack-button-row">
            <span className="hint">Delete this pack?</span>
            <button type="button" className="danger pack-small-button" disabled={deleting} onClick={handleDelete}>
              {deleting ? "Deleting…" : "Yes, delete"}
            </button>
            <button
              type="button"
              className="secondary pack-small-button"
              disabled={deleting}
              autoFocus
              onClick={() => setConfirming(false)}
            >
              Cancel
            </button>
          </div>
        ) : (
          <button
            type="button"
            className="secondary pack-small-button"
            aria-label={`Delete ${pack.title}`}
            onClick={() => {
              setError(null);
              setConfirming(true);
            }}
          >
            Delete
          </button>
        )}
      </div>

      {error && (
        <p className="error-text" role="alert">
          {error}
        </p>
      )}

      {processing && (
        <p className="progress-label">
          {pack.status === "QUEUED" ? "Waiting to be parsed…" : "Indexing your document…"} This page updates
          automatically.
        </p>
      )}

      {pack.status === "FAILED" && <p className="pack-error">{packErrorMessage(pack, limits)}</p>}

      {pack.status === "READY" && (
        <div className="pack-button-row">
          <Link
            to={`/packs/${pack.id}/chat`}
            className="button primary pack-small-button"
            aria-label={`Chat with ${pack.title}`}
          >
            <span aria-hidden>💬</span> Chat
          </Link>
          {COMING_SOON_ACTIONS.map((a) => (
            <button
              key={a.label}
              type="button"
              className="secondary pack-small-button"
              disabled
              title={`${a.label} — coming soon`}
              aria-label={`${a.label} (coming soon)`}
            >
              <span aria-hidden>{a.icon}</span> {a.label}
            </button>
          ))}
          <span className="pack-coming-soon">Coming soon</span>
        </div>
      )}
    </li>
  );
}
