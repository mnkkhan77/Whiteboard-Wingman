import { useState, type FormEvent } from "react";
import { DifficultyPicker } from "./DifficultyPicker";
import { QuestionCountInput } from "./QuestionCountInput";
import { TimedModeCheckbox } from "./TimedModeCheckbox";
import { clampInt } from "../utils/numberInput";
import { defaultQuizQuestionCount, QUIZ_MIN_QUESTIONS, quizQuestionLimit } from "../utils/quiz";
import type { Difficulty, PackDto } from "../types/api";

export interface QuizStartValues {
  startingDifficulty: Difficulty;
  questionCount: number;
  timedMode: boolean;
}

interface QuizStartFormProps {
  pack: PackDto;
  starting: boolean;
  error: string | null;
  /** e.g. out of monthly tokens. */
  disabled: boolean;
  onStart: (values: QuizStartValues) => void;
}

/** Difficulty / question count / timed mode for a pack quiz — shown once the bank is READY. */
export function QuizStartForm({ pack, starting, error, disabled, onStart }: QuizStartFormProps) {
  const [startingDifficulty, setStartingDifficulty] = useState<Difficulty>("MEDIUM");
  const [count, setCount] = useState(() => defaultQuizQuestionCount(pack));
  const [timedMode, setTimedMode] = useState(false);

  // A regenerated (smaller) bank can lower the limit under the chosen count — clamp at render time.
  const max = quizQuestionLimit(pack);
  const questionCount = clampInt(count, QUIZ_MIN_QUESTIONS, max);

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    onStart({ startingDifficulty, questionCount, timedMode });
  };

  return (
    <form className="session-start-form" onSubmit={handleSubmit}>
      <section className="card">
        <h2>Start a quiz</h2>
        <p className="hint">
          Answers are graded against your document, and difficulty adapts as you go. Grading uses your monthly
          token budget.
        </p>
        <DifficultyPicker value={startingDifficulty} onChange={setStartingDifficulty} />
        <QuestionCountInput
          label="Number of questions"
          value={questionCount}
          onChange={setCount}
          min={QUIZ_MIN_QUESTIONS}
          max={max}
          hint={`Between ${QUIZ_MIN_QUESTIONS} and ${max} — about half verbal, half multiple choice.`}
        />
        <TimedModeCheckbox checked={timedMode} onChange={setTimedMode} />
      </section>

      {error && (
        <p className="error-text" role="alert">
          {error}
        </p>
      )}

      <button type="submit" className="primary start-cta" disabled={starting || disabled}>
        {starting ? "Starting…" : "Start quiz →"}
      </button>
    </form>
  );
}
