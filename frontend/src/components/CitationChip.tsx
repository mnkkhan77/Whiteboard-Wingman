import { useRef, type KeyboardEvent, type MouseEvent } from "react";
import { describeSource } from "../utils/chat";
import type { ChatSourceDto } from "../types/api";

interface CitationChipProps {
  source: ChatSourceDto;
  active: boolean;
  /** id of the source panel this chip opens (aria-controls). */
  panelId: string;
  onActivate: (n: number, chip: HTMLButtonElement) => void;
  onClose: () => void;
}

/**
 * Inline `[n]` marker. Focusing it (so keyboard users can Tab through the citations) or clicking
 * it shows that source in the message's source panel; clicking / Enter on the open chip, or
 * Escape, closes it again.
 */
export function CitationChip({ source, active, panelId, onActivate, onClose }: CitationChipProps) {
  // Pressing a chip focuses it (which opens the panel) before `click` fires, so remember whether
  // it was already open at pointer-down to tell "open" from "close" apart.
  const openAtPointerDown = useRef(false);

  const handleClick = (e: MouseEvent<HTMLButtonElement>) => {
    // detail === 0 → keyboard-activated click (no pointer-down happened).
    const wasOpen = e.detail === 0 ? active : openAtPointerDown.current;
    openAtPointerDown.current = false;
    if (wasOpen) onClose();
    else onActivate(source.n, e.currentTarget);
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key === "Escape" && active) {
      e.preventDefault();
      onClose();
    }
  };

  return (
    <button
      type="button"
      className={active ? "citation-chip citation-chip-active" : "citation-chip"}
      aria-label={describeSource(source)}
      aria-expanded={active}
      aria-controls={panelId}
      onPointerDown={() => {
        openAtPointerDown.current = active;
      }}
      onClick={handleClick}
      onFocus={(e) => onActivate(source.n, e.currentTarget)}
      onKeyDown={handleKeyDown}
    >
      {source.n}
    </button>
  );
}
