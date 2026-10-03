import { apiFetch, apiUpload } from "./client";
import type {
  CourseDto,
  CourseLessonDto,
  FlashcardDeckDto,
  PackDto,
  PackFlashcardDto,
  PackLimitsDto,
  ReviewQuality,
} from "../types/api";

export function getPackLimits(token: string) {
  return apiFetch<PackLimitsDto>("/packs/limits", { token });
}

/** Current user's packs, newest first. */
export function listPacks(token: string, signal?: AbortSignal) {
  return apiFetch<PackDto[]>("/packs", { token, signal });
}

/** Owner only — 404 for someone else's pack. */
export function getPack(token: string, packId: number, signal?: AbortSignal) {
  return apiFetch<PackDto>(`/packs/${packId}`, { token, signal });
}

/** Multipart upload — `title` is optional (backend defaults it to the file name without extension). */
export function uploadPack(token: string, file: File, title?: string, onProgress?: (percent: number) => void) {
  const form = new FormData();
  form.append("file", file);
  const trimmed = title?.trim();
  if (trimmed) form.append("title", trimmed);
  return apiUpload<PackDto>("/packs", form, { token, onProgress });
}

/** Deletes the pack's file, chunks and vectors. */
export function deletePack(token: string, packId: number) {
  return apiFetch<void>(`/packs/${packId}`, { method: "DELETE", token });
}

/** Starts (or restarts — the new bank replaces the old one) async question-bank generation.
 *  Resolves with the pack in quizStatus GENERATING (202); poll getPack until READY / FAILED. */
export function generateQuiz(token: string, packId: number) {
  return apiFetch<PackDto>(`/packs/${packId}/quiz/generate`, { method: "POST", token });
}

/** Starts (or restarts — the new deck replaces the old one) async flashcard-deck generation.
 *  Resolves with the pack in flashcardStatus GENERATING (202); poll getPack until READY / FAILED. */
export function generateFlashcards(token: string, packId: number) {
  return apiFetch<PackDto>(`/packs/${packId}/flashcards/generate`, { method: "POST", token });
}

/** The pack's whole flashcard deck plus how many cards are due now. Deck must be READY. */
export function getFlashcards(token: string, packId: number, signal?: AbortSignal) {
  return apiFetch<FlashcardDeckDto>(`/packs/${packId}/flashcards`, { token, signal });
}

/** Records one review and returns the card's updated SM-2 schedule. */
export function reviewFlashcard(token: string, packId: number, cardId: number, quality: ReviewQuality) {
  return apiFetch<PackFlashcardDto>(`/packs/${packId}/flashcards/${cardId}/review`, {
    method: "POST",
    token,
    body: { quality },
  });
}

/** Starts (or restarts — the new outline replaces the old one, including every lesson's written
 *  content) async course generation. Resolves with the pack in courseStatus GENERATING (202); poll
 *  getPack until READY / FAILED. */
export function generateCourse(token: string, packId: number) {
  return apiFetch<PackDto>(`/packs/${packId}/course/generate`, { method: "POST", token });
}

/** The pack's whole course outline grouped into modules, plus completion progress. Outline must be READY. */
export function getCourse(token: string, packId: number, signal?: AbortSignal) {
  return apiFetch<CourseDto>(`/packs/${packId}/course`, { token, signal });
}

/** One lesson's full detail. The backend writes and caches its content the first time this is
 *  called, so this call can be slower (and can fail with LLM_RATE_LIMITED/LLM_ERROR) than a plain read. */
export function getCourseLesson(token: string, packId: number, lessonId: number, signal?: AbortSignal) {
  return apiFetch<CourseLessonDto>(`/packs/${packId}/course/lessons/${lessonId}`, { token, signal });
}

export function setCourseLessonCompleted(token: string, packId: number, lessonId: number, completed: boolean) {
  return apiFetch<CourseLessonDto>(`/packs/${packId}/course/lessons/${lessonId}/complete`, {
    method: "PUT",
    token,
    body: { completed },
  });
}
