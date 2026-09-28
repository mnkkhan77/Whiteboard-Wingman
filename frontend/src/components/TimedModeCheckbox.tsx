/** Opt-in per-question countdown; the interview page reads it from its navigation state. */
export function TimedModeCheckbox({ checked, onChange }: { checked: boolean; onChange: (checked: boolean) => void }) {
  return (
    <label className="checkbox-label">
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      ⏱ Timed mode <span className="hint">(add a per-question countdown for realistic interview pressure)</span>
    </label>
  );
}
