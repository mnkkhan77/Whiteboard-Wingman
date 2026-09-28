import type { Difficulty } from "../types/api";

const DIFFICULTIES: { value: Difficulty; label: string }[] = [
  { value: "EASY", label: "Easy" },
  { value: "MEDIUM", label: "Medium" },
  { value: "HARD", label: "Hard" },
];

/** The Easy / Medium / Hard toggle used when starting a session (topic interview or pack quiz). */
export function DifficultyPicker({ value, onChange }: { value: Difficulty; onChange: (value: Difficulty) => void }) {
  return (
    <label>
      Starting difficulty
      <div className="difficulty-picker">
        {DIFFICULTIES.map((d) => (
          <button
            type="button"
            key={d.value}
            className={`difficulty-option difficulty-option-${d.value.toLowerCase()}${value === d.value ? " selected" : ""}`}
            aria-pressed={value === d.value}
            onClick={() => onChange(d.value)}
          >
            {d.label}
          </button>
        ))}
      </div>
    </label>
  );
}
