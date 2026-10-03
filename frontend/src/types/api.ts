// TS mirrors of the backend DTOs (backend/src/main/java/com/mockinterview/backend/dto/*).

// One of ~160 codes generated from topics/catalog.json on the backend (e.g. "JAVA_COLLECTIONS",
// "JAVA_C06", "REACT_C18", "AI_C042") — see GET /api/topics / useTopicCatalog for display labels.
export type Topic = string;
export type Category = "JAVA_BACKEND" | "REACT_FRONTEND" | "AI_ENGINEERING";
export type Difficulty = "EASY" | "MEDIUM" | "HARD";
export type QuestionType = "CONCEPTUAL" | "MCQ" | "CODING";
export type Correctness = "CORRECT" | "PARTIALLY_CORRECT" | "INCORRECT";
export type DifficultyDelta = "EASIER" | "SAME" | "HARDER";
export type SessionStatus = "IN_PROGRESS" | "COMPLETED" | "ABANDONED";
export type LlmProvider = "GROQ" | "OPENAI";
export type Tier = "FREE" | "PRO" | "MAX";

export interface AuthResponse {
  token: string;
  email: string;
  displayName: string;
  role: string;
  guest: boolean;
}

/** Nullable pack fields on session-shaped DTOs — set only on study-pack quiz sessions, whose `topic`
 *  is then the hidden "STUDY_PACK" (see utils/topics.ts: show `packTitle` instead of a topic label). */
export interface PackSessionFields {
  packId?: number | null;
  packTitle?: string | null;
}

export interface TestCase {
  input: string;
  expectedOutput: string;
}

export interface QuestionResponse extends PackSessionFields {
  id: number;
  sequenceNumber: number;
  topic: Topic;
  promptText: string;
  questionType: QuestionType;
  difficulty: Difficulty;
  options: string[];
  ioFormat: string | null;
  testCases: TestCase[];
}

export interface SessionStartResponse {
  sessionId: number;
  firstQuestion: QuestionResponse;
}

export interface EvaluationResult {
  score: number;
  correctness: Correctness;
  feedback: string;
  strengths: string[];
  weaknesses: string[];
  recommendedNextDifficulty: DifficultyDelta;
}

export interface ProgressResponse {
  current: number;
  total: number;
}

export interface AnswerSubmitResponse {
  evaluation: EvaluationResult;
  nextQuestion: QuestionResponse | null;
  sessionStatus: SessionStatus;
  progress: ProgressResponse;
  sectionComplete: boolean;
  nextSectionType: QuestionType | null;
  // Non-null only when the upcoming section also belongs to a different (queued) topic — a topic
  // transition, not just a section transition within the same topic.
  nextTopic: Topic | null;
}

export interface SessionResumeResponse extends PackSessionFields {
  status: SessionStatus;
  progress: ProgressResponse;
  currentQuestion: QuestionResponse | null;
  pendingSectionType: QuestionType | null;
  topic: Topic;
  topicsRemaining: number;
}

export interface CodeRunTestCaseResult {
  input: string;
  expectedOutput: string;
  actualOutput: string | null;
  passed: boolean;
  stderr: string | null;
}

export interface CodeRunResponse {
  results: CodeRunTestCaseResult[];
  compileError: string | null;
}

export interface SessionSummaryResponse extends PackSessionFields {
  id: number;
  topic: Topic;
  status: SessionStatus;
  questionsAsked: number;
  targetQuestionCount: number;
  createdAt: string;
  completedAt: string | null;
  overallScore: number | null;
}

export interface QuestionBreakdown {
  sequenceNumber: number;
  topic: Topic;
  promptText: string;
  difficulty: Difficulty;
  answerText: string;
  score: number;
  correctness: Correctness;
  feedback: string;
}

export interface TopicBreakdown {
  topic: Topic;
  averageScore: number;
  questionCount: number;
}

export interface ReportResponse extends PackSessionFields {
  sessionId: number;
  topic: Topic;
  overallScore: number;
  strongTopics: string[];
  weakTopics: string[];
  summaryText: string | null;
  questionCount: number;
  averageDifficultyReached: number;
  breakdown: QuestionBreakdown[];
  tabSwitchCount: number;
  topicBreakdown: TopicBreakdown[];
}

export interface ShareTokenResponse {
  shareToken: string;
}

export interface AdminUserSummary {
  id: number;
  email: string;
  displayName: string;
  role: string;
  createdAt: string;
  lastActiveAt: string | null;
  sessionCount: number;
  averageScore: number | null;
  mostPracticedTopic: Topic | null;
  tier: Tier;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface AdminSessionSummary {
  id: number;
  topic: Topic;
  status: SessionStatus;
  startingDifficulty: Difficulty;
  currentDifficulty: Difficulty;
  questionsAsked: number;
  targetQuestionCount: number;
  createdAt: string;
  completedAt: string | null;
  overallScore: number | null;
}

export interface AdminUserDetail {
  id: number;
  email: string;
  displayName: string;
  role: string;
  createdAt: string;
  lastActiveAt: string | null;
  tier: Tier;
  sessions: AdminSessionSummary[];
}

export interface WeeklySignupCount {
  weekStart: string;
  count: number;
}

export interface AdminStats {
  totalUsers: number;
  totalSessions: number;
  sessionsByTopic: Partial<Record<Topic, number>>;
  averageScoreByTopic: Partial<Record<Topic, number>>;
  averageScoreByDifficulty: Partial<Record<Difficulty, number>>;
  signupsOverTime: WeeklySignupCount[];
}

export interface TopicSummary {
  topic: Topic;
  category: Category;
  label: string;
}

export type TopicsByCategory = Partial<Record<Category, TopicSummary[]>>;

export interface TopicRecommendationResponse {
  topic: Topic;
  startingDifficulty: Difficulty;
  rationale: string;
}

export interface ScorePoint extends PackSessionFields {
  sessionId: number;
  topic: Topic;
  completedAt: string;
  overallScore: number;
}

export interface PersonalProgressResponse {
  scoreTrend: ScorePoint[];
  averageScoreByTopic: Partial<Record<Topic, number>>;
  totalSessions: number;
  completedSessions: number;
  overallAverageScore: number | null;
}

// --- Study packs (docs/study-packs-contract.md) ---

export type PackStatus = "QUEUED" | "EMBEDDING" | "READY" | "FAILED";

/** State of a pack's LLM-generated question bank (Phase 4). */
export type QuizStatus = "NONE" | "GENERATING" | "READY" | "FAILED";

/** Set on a FAILED pack — doc-processor codes plus CHUNK_LIMIT_EXCEEDED / EMBEDDING_FAILED (backend). */
export type PackErrorCode =
  | "UNSUPPORTED_FORMAT"
  | "PAGE_LIMIT_EXCEEDED"
  | "OCR_REQUIRED"
  | "FILE_NOT_FOUND"
  | "EMPTY_DOCUMENT"
  | "PARSE_ERROR"
  | "CHUNK_LIMIT_EXCEEDED"
  | "EMBEDDING_FAILED";

/** State of a pack's LLM-generated flashcard deck (Phase 5). */
export type FlashcardStatus = "NONE" | "GENERATING" | "READY" | "FAILED";

export interface PackDto {
  id: number;
  title: string;
  fileName: string;
  status: PackStatus;
  sizeBytes: number;
  pageCount: number | null;
  chunkCount: number | null;
  parser: "docling" | "unstructured" | null;
  ocrUsed: boolean | null;
  errorCode: PackErrorCode | null;
  errorMessage: string | null;
  quizStatus: QuizStatus;
  /** Questions in the bank (0 / null until one has been generated). */
  quizQuestionCount: number | null;
  quizErrorMessage: string | null;
  flashcardStatus: FlashcardStatus;
  /** Cards in the deck (0 / null until one has been generated). */
  flashcardCount: number | null;
  flashcardErrorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PackLimitsDto {
  tier: Tier;
  maxFileBytes: number;
  maxPages: number;
  /** -1 = unlimited */
  maxPacks: number;
  maxChunksPerPack: number;
  ocrEnabled: boolean;
  /** Lower-case, no leading dot, e.g. ["pdf", "docx"] */
  allowedExtensions: string[];
  packsUsed: number;
  /** Monthly LLM token budget for pack chat (prompt + completion, calendar month UTC). */
  chatTokensPerMonth: number;
  chatTokensUsed: number;
}

// --- Chat with a pack (Phase 3) ---

export type ChatRole = "USER" | "ASSISTANT";

/** One retrieved chunk; `n` is its 1-based citation number (`[n]` in the answer). */
export interface ChatSourceDto {
  n: number;
  page: number | null;
  pageEnd: number | null;
  section: string | null;
  snippet: string;
}

/** `sources` / `citedSources` are empty arrays on USER messages. */
export interface ChatMessageDto {
  id: number;
  role: ChatRole;
  content: string;
  sources: ChatSourceDto[];
  citedSources: number[];
  createdAt: string;
}

export interface ChatQuotaDto {
  used: number;
  limit: number;
}

export interface ChatHistoryDto {
  /** Last 50 messages, oldest first. */
  messages: ChatMessageDto[];
  quota: ChatQuotaDto;
}

export interface ChatUsageDto {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
}

/** `data` of the SSE `sources` event. */
export interface ChatSourcesEvent {
  sources: ChatSourceDto[];
}

/** `data` of the SSE `delta` event. */
export interface ChatDeltaEvent {
  text: string;
}

/** `data` of the SSE `done` event. */
export interface ChatDoneEvent {
  messageId: number;
  citedSources: number[];
  usage: ChatUsageDto;
  quota: ChatQuotaDto;
}

/** `data` of the SSE `error` event (sent instead of `done` when the LLM fails mid-stream). */
export interface ChatErrorEvent {
  code: "LLM_RATE_LIMITED" | "LLM_ERROR";
  message: string;
}

/** Every `code` the chat endpoints can produce (pre-stream JSON errors + mid-stream `error` events). */
export type ChatErrorCode =
  | "PACK_NOT_READY"
  | "CHAT_QUOTA_EXCEEDED"
  | "CHAT_UNAVAILABLE"
  | "LLM_RATE_LIMITED"
  | "LLM_ERROR";

// --- Quiz from a pack (Phase 4) ---

/** Every `code` the quiz endpoints (bank generation + pack-session start) can produce. */
export type QuizErrorCode =
  | "PACK_NOT_READY"
  | "QUIZ_NOT_READY"
  | "QUIZ_ALREADY_GENERATING"
  | "CHAT_QUOTA_EXCEEDED"
  | "CHAT_UNAVAILABLE";

// --- Flashcards from a pack (Phase 5) ---

/** The four review buttons, mapped to the backend's SM-2 quality scale. */
export type ReviewQuality = "AGAIN" | "HARD" | "GOOD" | "EASY";

/** One flashcard with its SM-2 schedule. */
export interface PackFlashcardDto {
  id: number;
  front: string;
  back: string;
  sourcePage: number | null;
  sourceSection: string | null;
  easeFactor: number;
  intervalDays: number;
  repetitions: number;
  dueAt: string;
  lastReviewedAt: string | null;
}

export interface FlashcardDeckDto {
  cards: PackFlashcardDto[];
  dueCount: number;
}

/** Every `code` the flashcard endpoints (deck generation, deck read, review) can produce. */
export type FlashcardErrorCode =
  | "PACK_NOT_READY"
  | "FLASHCARDS_NOT_READY"
  | "FLASHCARDS_ALREADY_GENERATING"
  | "CHAT_QUOTA_EXCEEDED"
  | "CHAT_UNAVAILABLE";
