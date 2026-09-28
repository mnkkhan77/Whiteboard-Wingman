import { apiFetch, apiUpload } from "./client";
import type { PackDto, PackLimitsDto } from "../types/api";

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
