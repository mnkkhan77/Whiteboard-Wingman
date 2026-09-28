import type { ReactNode } from "react";

/** Centered loading (spinner, no icon) or message (with icon) card for a whole page body. */
export function StateCard({ icon, children }: { icon?: string; children: ReactNode }) {
  return (
    <div className={icon ? "card state-card state-card-error" : "card state-card"}>
      {icon ? (
        <span className="state-icon" aria-hidden>
          {icon}
        </span>
      ) : (
        <span className="spinner" aria-hidden />
      )}
      {children}
    </div>
  );
}
