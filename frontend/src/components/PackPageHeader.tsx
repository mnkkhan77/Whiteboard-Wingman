import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import type { PackDto } from "../types/api";

interface PackPageHeaderProps {
  /** Small label above the title, e.g. "Chat" or "Quiz". */
  eyebrow: string;
  /** null while loading — the title falls back to "Study pack". */
  pack: PackDto | null;
  /** Buttons beside the title. */
  actions?: ReactNode;
  /** Extra rows under the title (e.g. the token-budget meter). */
  children?: ReactNode;
}

/** Back link + title block shared by the per-pack pages (chat, quiz). */
export function PackPageHeader({ eyebrow, pack, actions, children }: PackPageHeaderProps) {
  return (
    <div className="chat-header">
      <Link to="/packs" className="chat-back">
        <span aria-hidden>←</span> Study packs
      </Link>
      <div className="chat-header-main">
        <div className="chat-header-title">
          <p className="eyebrow">{eyebrow}</p>
          <h1 className="pack-wrap">{pack?.title ?? "Study pack"}</h1>
          {pack && (
            <div className="session-meta pack-meta">
              <span className="pack-wrap">{pack.fileName}</span>
              {pack.pageCount !== null && <span>{pack.pageCount} pages</span>}
            </div>
          )}
        </div>
        {actions}
      </div>
      {children}
    </div>
  );
}
