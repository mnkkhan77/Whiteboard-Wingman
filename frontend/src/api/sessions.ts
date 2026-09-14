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

export function startSession(
  creds: LlmCreds,
  topic: Topic,
  startingDifficulty: Difficulty,
  questionCount: number
) {
  return apiFetch<SessionStartResponse>("/sessions", {
    method: "POST",
    token: creds.token,
    llmKey: creds.llmKey,
    llmProvider: creds.llmProvider,
    llmModel: creds.llmModel,
    body: { topic, startingDifficulty, questionCount },
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
