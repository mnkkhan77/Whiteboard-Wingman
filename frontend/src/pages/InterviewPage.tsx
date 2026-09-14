import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { useApiKey } from "../context/ApiKeyContext";
import { Navbar } from "../components/Navbar";
import { Footer } from "../components/Footer";
import { completeSession, getResumeState, startNextSection, submitAnswer } from "../api/sessions";
import { ApiError } from "../api/client";
import { CodeEditorPane } from "../components/CodeEditorPane";
import { MicButton } from "../components/MicButton";
import { RunCodePanel } from "../components/RunCodePanel";
import { useTopicCatalog } from "../hooks/useTopicCatalog";
import type { EvaluationResult, ProgressResponse, QuestionResponse, QuestionType, Topic } from "../types/api";

type Phase = "AWAITING_ANSWER" | "SUBMITTING" | "SHOWING_FEEDBACK" | "FINISHING";

interface LocationState {
  firstQuestion: QuestionResponse;
  targetQuestionCount: number;
  timedMode?: boolean;
  // The ordered topic loop as chosen on the start page — only available on a fresh navigation
  // (lost on a page refresh, same as the rest of location.state), used purely to show "Topic X of
  // Y"; the loop itself advances correctly either way since that's driven by the backend.
  topics?: Topic[];
}

const QUESTION_TYPE_LABEL: Record<QuestionType, string> = {
  CONCEPTUAL: "Verbal",
  MCQ: "Multiple Choice",
  CODING: "Live Coding",
};

const ROUND_ORDER: QuestionType[] = ["CONCEPTUAL", "MCQ", "CODING"];

// Per-question-type countdown budgets for timed mode, in seconds. Named constants so the pacing
// is easy to tweak without hunting through JSX.
const CONCEPTUAL_TIME_BUDGET_SECONDS = 180;
const MCQ_TIME_BUDGET_SECONDS = 45;
const CODING_TIME_BUDGET_SECONDS = 900; // 15 minutes

const QUESTION_TIME_BUDGET_SECONDS: Record<QuestionType, number> = {
  CONCEPTUAL: CONCEPTUAL_TIME_BUDGET_SECONDS,
  MCQ: MCQ_TIME_BUDGET_SECONDS,
  CODING: CODING_TIME_BUDGET_SECONDS,
};

// Timer turns urgent once this fraction (or less) of the budget remains.
const TIMER_URGENT_THRESHOLD = 0.2;

function formatCountdown(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, "0")}`;
}

function scoreTier(score: number): "good" | "mid" | "poor" {
  if (score >= 80) return "good";
  if (score >= 50) return "mid";
  return "poor";
}

export default function InterviewPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const location = useLocation();
  const navigate = useNavigate();
  const { token } = useAuth();
  const { apiKey, provider, model } = useApiKey();
  const { label: topicLabel } = useTopicCatalog(token);

  const locationState = location.state as LocationState | null;
  const numericSessionId = sessionId ? Number(sessionId) : NaN;
  // Defaults to false for both an older link (no timedMode in state) and a mid-interview refresh
  // (React Router's in-memory location.state doesn't survive a reload).
  const timedMode = locationState?.timedMode ?? false;
  const topics = locationState?.topics;

  const [question, setQuestion] = useState<QuestionResponse | null>(locationState?.firstQuestion ?? null);
  const [progress, setProgress] = useState<ProgressResponse>({
    current: 0,
    total: locationState?.targetQuestionCount ?? 0,
  });
  const [loading, setLoading] = useState(!locationState);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [phase, setPhase] = useState<Phase>("AWAITING_ANSWER");
  const [answerText, setAnswerText] = useState("");
  const [code, setCode] = useState("");
  const [codeLanguage, setCodeLanguage] = useState("java");
  const [selectedOption, setSelectedOption] = useState<number | null>(null);
  const [evaluation, setEvaluation] = useState<EvaluationResult | null>(null);
  const [isLastQuestion, setIsLastQuestion] = useState(false);
  const [sectionComplete, setSectionComplete] = useState(false);
  const [nextSectionType, setNextSectionType] = useState<QuestionType | null>(null);
  const [nextTopic, setNextTopic] = useState<Topic | null>(null);
  const [currentTopicIndex, setCurrentTopicIndex] = useState(0);
  const [completedRounds, setCompletedRounds] = useState<Set<QuestionType>>(new Set());
  const [sectionTransitionLoading, setSectionTransitionLoading] = useState(false);
  const [tabSwitchCount, setTabSwitchCount] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [secondsLeft, setSecondsLeft] = useState<number | null>(null);

  // Guards against a slow initial resume-fetch resolving after the user has already answered and
  // moved on (e.g. a fast reply on a slow connection) — it must not clobber newer client state.
  const hasSubmittedRef = useRef(false);

  const isCoding = question?.questionType === "CODING";
  const isMcq = question?.questionType === "MCQ";
  const canSubmit = isMcq
    ? selectedOption !== null
    : isCoding
      ? code.trim().length > 0 || answerText.trim().length > 0
      : answerText.trim().length > 0;

  // A lightweight integrity signal, not an enforcement mechanism — nothing blocks on it, it's just
  // recorded on the final report so the candidate (or whoever reviews it) knows how focused the
  // session was.
  useEffect(() => {
    function handleVisibilityChange() {
      if (document.hidden) {
        setTabSwitchCount((c) => c + 1);
      }
    }
    document.addEventListener("visibilitychange", handleVisibilityChange);
    return () => document.removeEventListener("visibilitychange", handleVisibilityChange);
  }, []);

  useEffect(() => {
    if (!token || Number.isNaN(numericSessionId)) return;
    getResumeState(token, numericSessionId)
      .then((res) => {
        if (hasSubmittedRef.current) return;
        if (res.status === "COMPLETED") {
          navigate(`/sessions/${numericSessionId}/report`, { replace: true });
          return;
        }
        if (res.currentQuestion) {
          setQuestion(res.currentQuestion);
          setPhase("AWAITING_ANSWER");
        } else if (res.pendingSectionType) {
          setSectionComplete(true);
          setNextSectionType(res.pendingSectionType);
          setPhase("SHOWING_FEEDBACK");
        }
        setProgress(res.progress);
        setLoading(false);
      })
      .catch((err) => {
        setLoadError(err instanceof ApiError ? err.message : "Could not load this interview.");
        setLoading(false);
      });
    // Deliberately runs once per session id — subsequent progress comes from client-driven
    // submit/next actions, not repeated polling.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [numericSessionId, token]);

  // Resets (and starts) the per-question countdown whenever a new question is shown while awaiting
  // an answer. No timer at all while timed mode is off or while reviewing feedback (there's nothing
  // to race against then) — re-running this effect always clears the previous interval first, so
  // nothing leaks across question changes.
  useEffect(() => {
    if (!timedMode || phase !== "AWAITING_ANSWER" || !question) {
      setSecondsLeft(null);
      return;
    }
    setSecondsLeft(QUESTION_TIME_BUDGET_SECONDS[question.questionType]);
    const intervalId = setInterval(() => {
      setSecondsLeft((prev) => {
        if (prev === null || prev <= 1) {
          clearInterval(intervalId);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
    return () => clearInterval(intervalId);
  }, [question, phase, timedMode]);

  // When the countdown reaches zero: auto-submit only if the current answer is already
  // submittable (mirrors clicking Submit); otherwise leave the form open and just show "Time's up".
  useEffect(() => {
    if (!timedMode || secondsLeft !== 0 || phase !== "AWAITING_ANSWER") return;
    if (canSubmit) {
      submitCurrentAnswer();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [secondsLeft]);

  if (loadError) {
    return (
      <>
        <Navbar />
        <div className="page">
          <p className="error-text">{loadError}</p>
          <Link to="/">Back to dashboard</Link>
        </div>
        <Footer />
      </>
    );
  }

  if (loading || !question) {
    return (
      <>
        <Navbar />
        <div className="page">
          <p>Loading interview...</p>
        </div>
        <Footer />
      </>
    );
  }

  async function submitCurrentAnswer() {
    if (!token || Number.isNaN(numericSessionId)) return;
    hasSubmittedRef.current = true;
    setError(null);
    setPhase("SUBMITTING");
    try {
      const res = await submitAnswer(
        { token, llmKey: apiKey, llmProvider: provider, llmModel: model || undefined },
        numericSessionId,
        answerText,
        code || undefined,
        isCoding ? codeLanguage : undefined,
        isMcq ? selectedOption ?? undefined : undefined
      );
      setEvaluation(res.evaluation);
      setProgress(res.progress);
      setIsLastQuestion(res.sessionStatus === "COMPLETED");
      setSectionComplete(res.sectionComplete);
      setNextSectionType(res.nextSectionType);
      setNextTopic(res.nextTopic);
      if (res.sectionComplete && question) {
        setCompletedRounds((prev) => new Set(prev).add(question.questionType));
      }
      if (res.nextQuestion) setQuestion(res.nextQuestion);
      setPhase("SHOWING_FEEDBACK");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not submit your answer. Please try again.");
      setPhase("AWAITING_ANSWER");
    }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    await submitCurrentAnswer();
  }

  async function handleNext() {
    setAnswerText("");
    setCode("");
    setSelectedOption(null);
    setEvaluation(null);
    setPhase("AWAITING_ANSWER");
  }

  async function handleStartNextSection() {
    if (!token || Number.isNaN(numericSessionId)) return;
    setSectionTransitionLoading(true);
    setError(null);
    try {
      if (question) {
        setCompletedRounds((prev) => new Set(prev).add(question.questionType));
      }
      if (nextTopic) {
        setCurrentTopicIndex((i) => i + 1);
        setCompletedRounds(new Set()); // a fresh topic starts its own verbal/MCQ/coding rounds over
      }
      const next = await startNextSection(token, numericSessionId);
      setQuestion(next);
      setAnswerText("");
      setCode("");
      setSelectedOption(null);
      setEvaluation(null);
      setSectionComplete(false);
      setNextSectionType(null);
      setNextTopic(null);
      setPhase("AWAITING_ANSWER");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not start the next section. Please try again.");
    } finally {
      setSectionTransitionLoading(false);
    }
  }

  async function handleFinish() {
    if (!token || Number.isNaN(numericSessionId)) return;
    setPhase("FINISHING");
    setError(null);
    try {
      await completeSession(
        { token, llmKey: apiKey, llmProvider: provider, llmModel: model || undefined },
        numericSessionId,
        tabSwitchCount
      );
      navigate(`/sessions/${numericSessionId}/report`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not generate the report. Please try again.");
      setPhase("SHOWING_FEEDBACK");
    }
  }

  return (
    <>
      <Navbar />
      <div className="page">
        <div className="progress-bar">
          <div className="progress-bar-fill" style={{ width: `${(progress.current / Math.max(progress.total, 1)) * 100}%` }} />
        </div>
        <p className="progress-label">
          Question {Math.min(progress.current + 1, progress.total)} of {progress.total}
        </p>

        {topics && topics.length > 1 && (
          <p className="topic-progress-label">
            Topic {currentTopicIndex + 1} of {topics.length}: {topicLabel(question.topic)}
          </p>
        )}

        <div className="round-stepper">
          {ROUND_ORDER.map((type, i) => {
            const status = completedRounds.has(type)
              ? "completed"
              : type === question.questionType
                ? "current"
                : "upcoming";
            return (
              <div key={type} className={`round-step round-step-${status}`}>
                <span className="round-step-marker">{status === "completed" ? "✓" : i + 1}</span>
                <span className="round-step-label">{QUESTION_TYPE_LABEL[type]}</span>
              </div>
            );
          })}
        </div>

        {tabSwitchCount > 0 && (
          <p className="tab-switch-warning">
            ⚠ Tab switch detected {tabSwitchCount > 1 ? `${tabSwitchCount} times` : "once"} — staying on this tab keeps your practice realistic.
          </p>
        )}

        <div className="card">
          <div className="question-card-header">
            <span className={`difficulty-badge difficulty-${question.difficulty.toLowerCase()}`}>{question.difficulty}</span>
            <span className={`type-badge type-${question.questionType.toLowerCase()}`}>{QUESTION_TYPE_LABEL[question.questionType]}</span>
            {timedMode && secondsLeft !== null && (
              <span
                className={
                  secondsLeft === 0
                    ? "timer-badge timer-badge-urgent"
                    : secondsLeft <= QUESTION_TIME_BUDGET_SECONDS[question.questionType] * TIMER_URGENT_THRESHOLD
                      ? "timer-badge timer-badge-urgent"
                      : "timer-badge"
                }
              >
                {secondsLeft === 0 ? "⏰ Time's up" : formatCountdown(secondsLeft)}
              </span>
            )}
          </div>
          <p className="question-text">{question.promptText}</p>

          {phase !== "SHOWING_FEEDBACK" && phase !== "FINISHING" ? (
            <form onSubmit={handleSubmit}>
              {isMcq ? (
                <div className="mcq-options" role="radiogroup" aria-label="Answer options">
                  {question.options.map((opt, i) => (
                    <label key={i} className={selectedOption === i ? "mcq-option selected" : "mcq-option"}>
                      <input
                        type="radio"
                        name="mcq-option"
                        checked={selectedOption === i}
                        onChange={() => setSelectedOption(i)}
                        disabled={phase === "SUBMITTING"}
                      />
                      <span>{opt}</span>
                    </label>
                  ))}
                </div>
              ) : isCoding ? (
                <>
                  <p className="live-coding-hint">Live coding round — write your solution below.</p>
                  {question.ioFormat && <p className="io-format-hint">{question.ioFormat}</p>}
                  <CodeEditorPane
                    value={code}
                    onChange={setCode}
                    language={codeLanguage}
                    onLanguageChange={setCodeLanguage}
                    disabled={phase === "SUBMITTING"}
                    label="Your solution"
                    height="320px"
                  />
                  {question.testCases.length > 0 && token && (
                    <RunCodePanel
                      key={question.id}
                      token={token}
                      questionId={question.id}
                      language={codeLanguage}
                      code={code}
                      disabled={phase === "SUBMITTING"}
                    />
                  )}
                  <label>
                    Notes <span className="hint">(optional — briefly explain your approach)</span>
                    <textarea
                      value={answerText}
                      onChange={(e) => setAnswerText(e.target.value)}
                      rows={3}
                      disabled={phase === "SUBMITTING"}
                    />
                  </label>
                </>
              ) : (
                <label>
                  <span className="answer-label-row">
                    Your answer
                    <MicButton onTranscript={(text) => setAnswerText((prev) => (prev ? `${prev} ${text}` : text))} disabled={phase === "SUBMITTING"} />
                  </span>
                  <textarea
                    value={answerText}
                    onChange={(e) => setAnswerText(e.target.value)}
                    rows={6}
                    required
                    disabled={phase === "SUBMITTING"}
                  />
                </label>
              )}

              {error && <p className="error-text">{error}</p>}

              <button type="submit" className="primary" disabled={phase === "SUBMITTING" || !canSubmit}>
                {phase === "SUBMITTING" ? "Evaluating..." : "Submit Answer"}
              </button>
            </form>
          ) : (
            <div className="feedback">
              {evaluation && (
                <>
                  <div className="feedback-header">
                    <span className={`score-badge score-badge-${scoreTier(evaluation.score)} feedback-score-badge`}>
                      {evaluation.score}/100
                    </span>
                    <span className="feedback-correctness">{evaluation.correctness.replaceAll("_", " ")}</span>
                  </div>
                  <p>{evaluation.feedback}</p>

                  {(evaluation.strengths.length > 0 || evaluation.weaknesses.length > 0) && (
                    <div className="eval-columns">
                      {evaluation.strengths.length > 0 && (
                        <div className="eval-column">
                          <h4 className="eval-column-title eval-column-title-good">Strengths</h4>
                          <ul className="eval-list">
                            {evaluation.strengths.map((s, i) => (
                              <li key={i}>
                                <span className="eval-list-icon">✓</span>
                                {s}
                              </li>
                            ))}
                          </ul>
                        </div>
                      )}

                      {evaluation.weaknesses.length > 0 && (
                        <div className="eval-column">
                          <h4 className="eval-column-title eval-column-title-poor">Weaknesses</h4>
                          <ul className="eval-list">
                            {evaluation.weaknesses.map((w, i) => (
                              <li key={i}>
                                <span className="eval-list-icon">△</span>
                                {w}
                              </li>
                            ))}
                          </ul>
                        </div>
                      )}
                    </div>
                  )}
                </>
              )}

              {error && <p className="error-text">{error}</p>}

              {isLastQuestion ? (
                <button className="primary" onClick={handleFinish} disabled={phase === "FINISHING"}>
                  {phase === "FINISHING" ? "Generating report..." : "Finish Interview"}
                </button>
              ) : sectionComplete ? (
                <div className="section-break">
                  {nextTopic ? (
                    <>
                      <p className="section-break-title">🎉 {topicLabel(question.topic)} complete!</p>
                      <p className="hint">
                        Next up
                        {topics && topics.length > 1 ? ` — topic ${currentTopicIndex + 2} of ${topics.length}` : ""}:{" "}
                        <strong>{topicLabel(nextTopic)}</strong>. Take a breather, then start whenever you're ready.
                      </p>
                      <button className="primary" onClick={handleStartNextSection} disabled={sectionTransitionLoading}>
                        {sectionTransitionLoading ? "Loading..." : `Start ${topicLabel(nextTopic)} →`}
                      </button>
                    </>
                  ) : (
                    <>
                      <p className="section-break-title">🎉 {QUESTION_TYPE_LABEL[question.questionType]} round complete</p>
                      <p className="hint">
                        Take a breather — start the {nextSectionType ? QUESTION_TYPE_LABEL[nextSectionType] : "next"} round whenever you're ready.
                      </p>
                      <button className="primary" onClick={handleStartNextSection} disabled={sectionTransitionLoading}>
                        {sectionTransitionLoading ? "Loading..." : `Start ${nextSectionType ? QUESTION_TYPE_LABEL[nextSectionType] : "next"} round →`}
                      </button>
                    </>
                  )}
                </div>
              ) : (
                <button className="primary" onClick={handleNext}>
                  Next Question
                </button>
              )}
            </div>
          )}
        </div>
      </div>
      <Footer />
    </>
  );
}
