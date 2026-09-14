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

export interface AuthResponse {
  token: string;
  email: string;
  displayName: string;
  role: string;
  guest: boolean;
}

export interface TestCase {
  input: string;
  expectedOutput: string;
}

export interface QuestionResponse {
  id: number;
  sequenceNumber: number;
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
}

export interface SessionResumeResponse {
  status: SessionStatus;
  progress: ProgressResponse;
  currentQuestion: QuestionResponse | null;
  pendingSectionType: QuestionType | null;
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

export interface SessionSummaryResponse {
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
  promptText: string;
  difficulty: Difficulty;
  answerText: string;
  score: number;
  correctness: Correctness;
  feedback: string;
}

export interface ReportResponse {
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
