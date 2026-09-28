// Pure helpers for integer text inputs. `<input type="number">` min/max are advisory only, and React
// won't overwrite a DOM value that's numerically equal to state ("05" vs 5), so leading-zero garbage
// sticks. Inputs using these keep a digits-only draft string and clamp it on change and on blur.

/** Rounds and clamps into [min, max]; NaN/±Infinity → min. */
export function clampInt(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  return Math.min(max, Math.max(min, Math.round(value)));
}

/** What the input may show while typing: digits only, no leading zeros, never above `max`.
 *  May be "" or below min (the user is mid-edit) — commitIntegerDraft settles that on blur. */
export function sanitizeIntegerDraft(raw: string, max: number): string {
  const digits = raw.replace(/\D/g, "").replace(/^0+(?=\d)/, "");
  if (!digits) return "";
  return Number(digits) > max ? String(max) : digits;
}

/** The value a draft stands for: clamped into [min, max]; an empty draft keeps `fallback` (clamped). */
export function commitIntegerDraft(draft: string, min: number, max: number, fallback: number): number {
  return clampInt(draft === "" ? fallback : Number(draft), min, max);
}
