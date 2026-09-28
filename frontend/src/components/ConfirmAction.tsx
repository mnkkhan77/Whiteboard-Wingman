import { useState, type ReactNode } from "react";

interface ConfirmActionProps {
  /** The trigger button's content. */
  label: ReactNode;
  /** Shown beside the confirm/cancel buttons, e.g. "Clear this conversation?". */
  prompt: string;
  confirmLabel: string;
  busyLabel: string;
  /** Rejecting keeps the confirm row open and shows `errorMessage(err)`. */
  onConfirm: () => Promise<void>;
  errorMessage?: (err: unknown) => string;
  disabled?: boolean;
  /** Class of the confirm button (defaults to the destructive style). */
  confirmClassName?: string;
}

/** A small button that asks "are you sure?" inline before running an async action. */
export function ConfirmAction({
  label,
  prompt,
  confirmLabel,
  busyLabel,
  onConfirm,
  errorMessage = () => "Something went wrong. Please try again.",
  disabled = false,
  confirmClassName = "danger",
}: ConfirmActionProps) {
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleConfirm = async () => {
    setBusy(true);
    setError(null);
    try {
      await onConfirm();
      setConfirming(false);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      {confirming ? (
        <div className="pack-button-row">
          <span className="hint">{prompt}</span>
          <button type="button" className={`${confirmClassName} pack-small-button`} disabled={busy} onClick={handleConfirm}>
            {busy ? busyLabel : confirmLabel}
          </button>
          <button
            type="button"
            className="secondary pack-small-button"
            disabled={busy}
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
          disabled={disabled}
          onClick={() => {
            setError(null);
            setConfirming(true);
          }}
        >
          {label}
        </button>
      )}
      {error && (
        <p className="error-text" role="alert">
          {error}
        </p>
      )}
    </>
  );
}
