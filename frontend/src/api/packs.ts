import { apiFetch, apiUpload } from "./client";
import type { PackDto, PackLimitsDto } from "../types/api";

export function getPackLimits(token: string) {
  return apiFetch<PackLimitsDto>("/packs/limits", { token });
}

/** Current user's packs, newest first. */
export function listPacks(token: string) {
  return apiFetch<PackDto[]>("/packs", { token });
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
