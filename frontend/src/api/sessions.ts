import { apiFetch } from "./client";
import type {
  AnswerSubmitResponse,
  CodeRunResponse,
  Difficulty,
  LlmProvider,
  PersonalProgressResponse,
  QuestionResponse,
  ReportResponse,
  SessionResumeResponse,
  ShareTokenResponse,
  SessionStartResponse,
  SessionSummaryResponse,
  Topic,
} from "../types/api";

export interface LlmCreds {
  token: string;
  llmKey: string;
  llmProvider: LlmProvider;
  llmModel?: string;
}

/** topics is the ordered "loop" list (1+ entries) — topics[0] also goes in the body's `topic`
 *  field since the backend keeps that required for backward compatibility with single-topic
 *  starts; the rest queue up behind it. */
export function startSession(
  creds: LlmCreds,
  topics: Topic[],
  startingDifficulty: Difficulty,
  questionCount: number
) {
  return apiFetch<SessionStartResponse>("/sessions", {
    method: "POST",
    token: creds.token,
    llmKey: creds.llmKey,
    llmProvider: creds.llmProvider,
    llmModel: creds.llmModel,
    body: { topic: topics[0], startingDifficulty, questionCount, topics },
  });
}

export function submitAnswer(
  creds: LlmCreds,
  sessionId: number,
  answerText: string,
  code?: string,
  language?: string,
  selectedOptionIndex?: number
) {
  return apiFetch<AnswerSubmitResponse>(`/sessions/${sessionId}/answers`, {
    method: "POST",
    token: creds.token,
    llmKey: creds.llmKey,
    llmProvider: creds.llmProvider,
    llmModel: creds.llmModel,
    body: { answerText, code, language, selectedOptionIndex },
  });
}

/** Fetches the first question of the next section, once the candidate is ready to move on from
 *  the "section complete, take a break" screen. */
export function startNextSection(token: string, sessionId: number) {
  return apiFetch<QuestionResponse>(`/sessions/${sessionId}/sections/next`, {
    method: "POST",
    token,
  });
}

export function completeSession(creds: LlmCreds, sessionId: number, tabSwitchCount?: number) {
  return apiFetch<ReportResponse>(`/sessions/${sessionId}/complete`, {
    method: "POST",
    token: creds.token,
    llmKey: creds.llmKey,
    llmProvider: creds.llmProvider,
    llmModel: creds.llmModel,
    body: { tabSwitchCount },
  });
}

/** Compiles/runs the candidate's code against a CODING question's stored test cases (Piston-backed). */
export function runCode(token: string, questionId: number, language: string, code: string) {
  return apiFetch<CodeRunResponse>(`/questions/${questionId}/run`, {
    method: "POST",
    token,
    body: { language, code },
  });
}

export function getReport(token: string, sessionId: number) {
  return apiFetch<ReportResponse>(`/sessions/${sessionId}/report`, { token });
}

/** Lazily mints (or re-fetches) the report's public share token — idempotent on the backend. */
export function shareReport(token: string, sessionId: number) {
  return apiFetch<ShareTokenResponse>(`/sessions/${sessionId}/report/share`, { method: "POST", token });
}

/** Unauthenticated lookup by share token, for the public report page — no auth token is sent. */
export function getPublicReport(shareToken: string) {
  return apiFetch<ReportResponse>(`/public/reports/${shareToken}`);
}

export function listSessions(token: string) {
  return apiFetch<SessionSummaryResponse[]>("/sessions", { token });
}

/** Reconstructs in-progress interview state from just the session id — used to resume after a
 *  page refresh, since React Router's in-memory navigation state doesn't survive a reload. */
export function getResumeState(token: string, sessionId: number) {
  return apiFetch<SessionResumeResponse>(`/sessions/${sessionId}/current`, { token });
}

/** A signed-in user's own score trend and per-topic averages, for the personal progress dashboard. */
export function getProgress(token: string) {
  return apiFetch<PersonalProgressResponse>("/sessions/progress", { token });
}
