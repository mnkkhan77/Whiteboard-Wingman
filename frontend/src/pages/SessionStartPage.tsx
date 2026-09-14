import { useEffect, useState, type FormEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { useApiKey } from "../context/ApiKeyContext";
import { Navbar } from "../components/Navbar";
import { Footer } from "../components/Footer";
import { startSession } from "../api/sessions";
import { recommendTopic } from "../api/topics";
import { ApiError } from "../api/client";
import { useTopicCatalog } from "../hooks/useTopicCatalog";
import { CATEGORY_META } from "../constants/topicCategories";
import type { Category, Difficulty, Topic, TopicRecommendationResponse } from "../types/api";

const CATEGORIES: Category[] = ["JAVA_BACKEND", "REACT_FRONTEND", "AI_ENGINEERING"];

const DIFFICULTIES: { value: Difficulty; label: string }[] = [
  { value: "EASY", label: "Easy" },
  { value: "MEDIUM", label: "Medium" },
  { value: "HARD", label: "Hard" },
];

const MODEL_PRESETS: Record<"GROQ" | "OPENAI", { value: string; label: string }[]> = {
  GROQ: [
    { value: "llama-3.3-70b-versatile", label: "Llama 3.3 70B Versatile" },
    { value: "llama-3.1-8b-instant", label: "Llama 3.1 8B Instant" },
    { value: "mixtral-8x7b-32768", label: "Mixtral 8x7B" },
    { value: "gemma2-9b-it", label: "Gemma 2 9B" },
  ],
  OPENAI: [
    { value: "gpt-4o", label: "GPT-4o" },
    { value: "gpt-4o-mini", label: "GPT-4o Mini" },
    { value: "gpt-4-turbo", label: "GPT-4 Turbo" },
    { value: "gpt-3.5-turbo", label: "GPT-3.5 Turbo" },
  ],
};
const CUSTOM_MODEL_VALUE = "__custom__";
const DEFAULT_MODEL_VALUE = "__default__";

const API_KEY_HELP: Record<"GROQ" | "OPENAI", { name: string; url: string }> = {
  GROQ: { name: "Groq", url: "https://console.groq.com/keys" },
  OPENAI: { name: "OpenAI", url: "https://platform.openai.com/api-keys" },
};

export default function SessionStartPage() {
  const { token, guest } = useAuth();
  const { apiKey, provider, model, setApiKey, setProvider, setModel } = useApiKey();
  const navigate = useNavigate();
  const location = useLocation();
  const prefillTopic = (location.state as { prefillTopic?: Topic } | null)?.prefillTopic;

  const { data: topicsByCategory, byTopic } = useTopicCatalog(token);
  const [topicCategory, setTopicCategory] = useState<Category>("JAVA_BACKEND");
  const [topicFilter, setTopicFilter] = useState("");
  const [selectedTopic, setSelectedTopic] = useState<Topic>(prefillTopic ?? "");
  const [difficulty, setDifficulty] = useState<Difficulty>("EASY");
  const [questionCount, setQuestionCount] = useState(8);
  const [timedMode, setTimedMode] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [guestLimitReached, setGuestLimitReached] = useState(false);
  const [loading, setLoading] = useState(false);
  const [resumeOrJdText, setResumeOrJdText] = useState("");
  const [recommendation, setRecommendation] = useState<TopicRecommendationResponse | null>(null);
  const [recommendError, setRecommendError] = useState<string | null>(null);
  const [recommending, setRecommending] = useState(false);
  const [useCustomModel, setUseCustomModel] = useState(
    () => model !== "" && !MODEL_PRESETS[provider].some((m) => m.value === model)
  );

  // Once the catalog loads, default to its first topic — derived at render time rather than
  // set from an effect, so picking a topic and loading the catalog both just update state once.
  const defaultTopic = CATEGORIES.map((c) => topicsByCategory?.[c]?.[0]).find(Boolean)?.topic ?? "";
  const topic = selectedTopic || defaultTopic;

  // If we arrived here via a "Practice this topic again" link, switch to the prefilled topic's
  // own category once the (asynchronously loaded) catalog resolves it, so the right tab is active.
  const prefillCategory = prefillTopic ? byTopic[prefillTopic]?.category : undefined;
  useEffect(() => {
    if (prefillCategory) setTopicCategory(prefillCategory);
  }, [prefillCategory]);

  // Same idea, but for a topic the LLM just recommended from a pasted resume/JD instead of a
  // router-state prefill — switch to its category once the catalog resolves it.
  const recommendedCategory = recommendation ? byTopic[recommendation.topic]?.category : undefined;
  useEffect(() => {
    if (recommendedCategory) setTopicCategory(recommendedCategory);
  }, [recommendedCategory]);

  const topicsInCategory = topicsByCategory?.[topicCategory] ?? [];
  const filteredTopics = topicFilter.trim()
    ? topicsInCategory.filter((t) => t.label.toLowerCase().includes(topicFilter.trim().toLowerCase()))
    : topicsInCategory;

  function handleProviderChange(nextProvider: "GROQ" | "OPENAI") {
    setProvider(nextProvider);
    setModel("");
    setUseCustomModel(false);
  }

  function handleModelSelect(value: string) {
    if (value === CUSTOM_MODEL_VALUE) {
      setUseCustomModel(true);
      setModel("");
    } else if (value === DEFAULT_MODEL_VALUE) {
      setUseCustomModel(false);
      setModel("");
    } else {
      setUseCustomModel(false);
      setModel(value);
    }
  }

  async function handleRecommend() {
    if (!token) return;
    setRecommendError(null);
    setRecommending(true);
    try {
      const res = await recommendTopic({ token, llmKey: apiKey, llmProvider: provider, llmModel: model || undefined }, resumeOrJdText);
      setRecommendation(res);
      setSelectedTopic(res.topic);
      setDifficulty(res.startingDifficulty);
    } catch (err) {
      setRecommendError(err instanceof ApiError ? err.message : "Could not generate a recommendation. Please try again.");
    } finally {
      setRecommending(false);
    }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!token) return;
    setError(null);
    setGuestLimitReached(false);
    setLoading(true);
    try {
      const res = await startSession({ token, llmKey: apiKey, llmProvider: provider, llmModel: model || undefined }, topic, difficulty, questionCount);
      navigate(`/interview/${res.sessionId}`, {
        state: { firstQuestion: res.firstQuestion, targetQuestionCount: questionCount, timedMode },
      });
    } catch (err) {
      if (err instanceof ApiError && err.code === "GUEST_LIMIT_REACHED") {
        setGuestLimitReached(true);
        setError(err.message);
      } else {
        setError(err instanceof ApiError ? err.message : "Could not start the interview. Please try again.");
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <Navbar />
      <div className="page">
        <p className="eyebrow">New session</p>
        <h1>Start a mock interview</h1>
        <p className="hint">
          A verbal round comes first — a multiple-choice round, and a live-coding round for topics with
          coding content, follow automatically with a break in between.
        </p>

        {guest && (
          <p className="keyless-warning">
            👋 You're trying this as a guest — this is your one free attempt. <Link to="/register">Sign up</Link> for
            unlimited practice and saved reports.
          </p>
        )}

        <form className="session-start-form" onSubmit={handleSubmit}>
          <details className="card">
            <summary>Tailor to a resume or job description <span className="hint">(optional)</span></summary>
            <p className="hint">
              Paste your resume or a job description below and we'll suggest which topic and starting
              difficulty to practice.
            </p>
            <textarea
              value={resumeOrJdText}
              onChange={(e) => setResumeOrJdText(e.target.value)}
              rows={6}
              placeholder="Paste your resume or a job description here…"
            />
            {!apiKey.trim() && (
              <p className="keyless-warning">
                ⚠ Add your LLM API key in section 3 below to enable a recommendation — this feature
                always needs one.
              </p>
            )}
            {recommendError && <p className="error-text">{recommendError}</p>}
            <button
              type="button"
              className="secondary"
              disabled={recommending || !apiKey.trim() || !resumeOrJdText.trim()}
              onClick={handleRecommend}
            >
              {recommending ? "Analyzing…" : "Recommend a topic"}
            </button>
          </details>

          <section className="card">
            <h2>1. Choose a topic</h2>
            {recommendation && (
              <p className="hint">
                💡 Recommended based on what you pasted: <strong>{byTopic[recommendation.topic]?.label ?? recommendation.topic}</strong>{" "}
                ({recommendation.startingDifficulty.toLowerCase()}) — {recommendation.rationale}
              </p>
            )}
            <div className="tab-bar topic-category-bar">
              {CATEGORIES.map((c) => (
                <button
                  type="button"
                  key={c}
                  className={topicCategory === c ? "tab active" : "tab"}
                  onClick={() => {
                    setTopicCategory(c);
                    setTopicFilter("");
                  }}
                >
                  {CATEGORY_META[c].icon} {CATEGORY_META[c].label}
                </button>
              ))}
            </div>
            <input
              type="search"
              value={topicFilter}
              onChange={(e) => setTopicFilter(e.target.value)}
              placeholder={`Filter ${CATEGORY_META[topicCategory].label} topics…`}
            />
            <div className="topic-picker-list">
              {filteredTopics.length === 0 && <p className="hint">No topics match "{topicFilter}".</p>}
              {filteredTopics.map((t) => (
                <button
                  type="button"
                  key={t.topic}
                  className={topic === t.topic ? "topic-option selected" : "topic-option"}
                  onClick={() => setSelectedTopic(t.topic)}
                >
                  <span className={`topic-icon topic-icon-${topicCategory.toLowerCase()}`} aria-hidden>
                    {CATEGORY_META[topicCategory].icon}
                  </span>
                  <span className="topic-option-text">
                    <strong>{t.label}</strong>
                  </span>
                </button>
              ))}
            </div>
          </section>

          <section className="card">
            <h2>2. Set the pace</h2>
            <label>
              Starting difficulty
              <div className="difficulty-picker">
                {DIFFICULTIES.map((d) => (
                  <button
                    type="button"
                    key={d.value}
                    className={
                      difficulty === d.value
                        ? `difficulty-option difficulty-option-${d.value.toLowerCase()} selected`
                        : `difficulty-option difficulty-option-${d.value.toLowerCase()}`
                    }
                    onClick={() => setDifficulty(d.value)}
                  >
                    {d.label}
                  </button>
                ))}
              </div>
            </label>

            <label>
              Number of verbal questions
              <input
                type="number"
                min={1}
                max={20}
                value={questionCount}
                onChange={(e) => {
                  const parsed = Number(e.target.value);
                  setQuestionCount(Number.isNaN(parsed) ? 1 : Math.min(20, Math.max(1, parsed)));
                }}
              />
            </label>

            <label className="checkbox-label">
              <input type="checkbox" checked={timedMode} onChange={(e) => setTimedMode(e.target.checked)} />
              ⏱ Timed mode <span className="hint">(add a per-question countdown for realistic interview pressure)</span>
            </label>
          </section>

          <section className="card">
            <h2>3. Your LLM key (optional)</h2>
            <p className="hint">Used directly from your browser for this session only — never stored on our server.</p>
            <label>
              Provider
              <select value={provider} onChange={(e) => handleProviderChange(e.target.value as "GROQ" | "OPENAI")}>
                <option value="GROQ">Groq</option>
                <option value="OPENAI">OpenAI</option>
              </select>
            </label>

            <label>
              Your {API_KEY_HELP[provider].name} API key <span className="hint">(optional — see below if left blank)</span>
              <span className="input-icon-group">
                <span className="input-icon">🔑</span>
                <input
                  type="password"
                  value={apiKey}
                  onChange={(e) => setApiKey(e.target.value)}
                  placeholder="Never sent anywhere but this app"
                />
              </span>
              <span className="hint">
                Don't have one? Get a free key from{" "}
                <a href={API_KEY_HELP.GROQ.url} target="_blank" rel="noreferrer">
                  Groq
                </a>{" "}
                or{" "}
                <a href={API_KEY_HELP.OPENAI.url} target="_blank" rel="noreferrer">
                  OpenAI
                </a>
                .
              </span>
            </label>

            {!apiKey.trim() && (
              <p className="keyless-warning">
                ⚠ No API key — the verbal round will be skipped. You'll only get Multiple Choice and Live Coding questions
                (if the topic has any), and coding answers are graded from test results only, not AI feedback.
              </p>
            )}

            <label>
              Model <span className="hint">(optional — sensible default used if left blank)</span>
              <select value={useCustomModel ? CUSTOM_MODEL_VALUE : model || DEFAULT_MODEL_VALUE} onChange={(e) => handleModelSelect(e.target.value)}>
                <option value={DEFAULT_MODEL_VALUE}>Default (recommended)</option>
                {MODEL_PRESETS[provider].map((m) => (
                  <option key={m.value} value={m.value}>
                    {m.label}
                  </option>
                ))}
                <option value={CUSTOM_MODEL_VALUE}>Custom model name…</option>
              </select>
              {useCustomModel && (
                <input value={model} onChange={(e) => setModel(e.target.value)} placeholder="e.g. llama-3.3-70b-versatile" />
              )}
            </label>
          </section>

          {error && (
            <p className="error-text">
              {error}
              {guestLimitReached && (
                <>
                  {" "}
                  <Link to="/register">Sign up</Link>.
                </>
              )}
            </p>
          )}

          <button type="submit" className="primary start-cta" disabled={loading || !topic || guestLimitReached}>
            {loading ? "Starting..." : "Start Interview →"}
          </button>
        </form>
      </div>
      <Footer />
    </>
  );
}
