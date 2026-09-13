import { useState } from "react";
import { runCode } from "../api/sessions";
import { ApiError } from "../api/client";
import type { CodeRunResponse } from "../types/api";

interface RunCodePanelProps {
  token: string;
  questionId: number;
  language: string;
  code: string;
  disabled?: boolean;
}

/** Compiles/runs the candidate's code against the question's stored test cases (Piston-backed) —
 *  a separate "try it out" action from the final "Submit Answer", which still triggers the LLM
 *  evaluation regardless of whether Run Code was ever used. */
export function RunCodePanel({ token, questionId, language, code, disabled }: RunCodePanelProps) {
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<CodeRunResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function handleRun() {
    setRunning(true);
    setError(null);
    try {
      const res = await runCode(token, questionId, language, code);
      setResult(res);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not run your code. Please try again.");
    } finally {
      setRunning(false);
    }
  }

  return (
    <div className="run-code-panel">
      <button
        type="button"
        className="secondary run-code-button"
        onClick={handleRun}
        disabled={disabled || running || !code.trim()}
      >
        {running ? "Running..." : "▶ Run Code"}
      </button>

      {error && <p className="error-text">{error}</p>}

      {result?.compileError && (
        <div className="run-code-compile-error">
          <strong>Compile error</strong>
          <pre>{result.compileError}</pre>
        </div>
      )}

      {result && result.results.length > 0 && (
        <ul className="run-code-results">
          {result.results.map((r, i) => (
            <li key={i} className={r.passed ? "run-case pass" : "run-case fail"}>
              <div className="run-case-header">
                <span>{r.passed ? "✓ Passed" : "✗ Failed"}</span>
                <span className="run-case-number">Test {i + 1}</span>
              </div>
              <div className="run-case-io">
                <div>
                  <span className="run-case-label">Input</span>
                  <pre>{r.input || "(none)"}</pre>
                </div>
                <div>
                  <span className="run-case-label">Expected</span>
                  <pre>{r.expectedOutput}</pre>
                </div>
                {r.actualOutput !== null && (
                  <div>
                    <span className="run-case-label">Your output</span>
                    <pre>{r.actualOutput || "(empty)"}</pre>
                  </div>
                )}
                {r.stderr && (
                  <div>
                    <span className="run-case-label">stderr</span>
                    <pre>{r.stderr}</pre>
                  </div>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
