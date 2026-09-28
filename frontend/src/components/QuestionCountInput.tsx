import { useId, useState } from "react";
import { clampInt, commitIntegerDraft, sanitizeIntegerDraft } from "../utils/numberInput";

interface QuestionCountInputProps {
  label: string;
  /** Always a whole number within [min, max]. */
  value: number;
  onChange: (value: number) => void;
  min: number;
  max: number;
  hint?: string;
}

/**
 * Whole-number field that can't hold an out-of-range or leading-zero value. It's a numeric text
 * input (not type="number") so the shown draft is fully controlled: sanitized on every keystroke,
 * settled into [min, max] on blur. `onChange` only ever receives a clamped number — while the draft
 * is mid-edit (e.g. "1" with min 2) the parent already holds the value it will settle to.
 */
export function QuestionCountInput({ label, value, onChange, min, max, hint }: QuestionCountInputProps) {
  const hintId = useId();
  const [draft, setDraft] = useState(String(value));
  const [syncedValue, setSyncedValue] = useState(value);

  // The parent changed the value itself (e.g. max shrank) — show it. Adjusting state during render
  // is React's recommended alternative to a syncing effect.
  if (value !== syncedValue) {
    setSyncedValue(value);
    if (commitIntegerDraft(draft, min, max, value) !== value) setDraft(String(value));
  }

  const emit = (next: number) => {
    setSyncedValue(next);
    if (next !== value) onChange(next);
  };

  return (
    <label>
      {label}
      <input
        type="text"
        inputMode="numeric"
        autoComplete="off"
        value={draft}
        aria-describedby={hintId}
        onChange={(e) => {
          const next = sanitizeIntegerDraft(e.target.value, max);
          setDraft(next);
          if (next !== "") emit(clampInt(Number(next), min, max));
        }}
        onBlur={() => {
          const settled = commitIntegerDraft(draft, min, max, value);
          setDraft(String(settled));
          emit(settled);
        }}
      />
      <span className="hint" id={hintId}>
        {hint ?? `Between ${min} and ${max}.`}
      </span>
    </label>
  );
}
