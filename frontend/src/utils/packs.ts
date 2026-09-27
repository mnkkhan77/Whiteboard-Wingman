// Pure helpers for the Study Packs page — formatting, error-code → friendly text, and the
// client-side pre-upload checks. Kept framework-free so they're trivially testable.
import type { PackDto, PackLimitsDto, PackStatus } from "../types/api";

const BYTE_UNITS = ["B", "KB", "MB", "GB", "TB"];

/** 1024-based, e.g. 10485760 → "10 MB", 1536 → "1.5 KB". */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
  const exp = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), BYTE_UNITS.length - 1);
  const value = bytes / 1024 ** exp;
  const rounded = exp === 0 || value >= 10 ? Math.round(value) : Math.round(value * 10) / 10;
  return `${rounded} ${BYTE_UNITS[exp]}`;
}

/** maxPacks = -1 means unlimited. */
export function formatPackQuota(used: number, max: number): string {
  return `${used} / ${max < 0 ? "∞" : max}`;
}

export function isAtPackLimit(limits: PackLimitsDto): boolean {
  return limits.maxPacks >= 0 && limits.packsUsed >= limits.maxPacks;
}

/** Lower-case extension without the dot ("" when the name has none). */
export function fileExtension(fileName: string): string {
  const dot = fileName.lastIndexOf(".");
  return dot > 0 && dot < fileName.length - 1 ? fileName.slice(dot + 1).toLowerCase() : "";
}

export function isPackInFlight(status: PackStatus): boolean {
  return status === "QUEUED" || status === "EMBEDDING";
}

export function hasPacksInFlight(packs: PackDto[] | null): boolean {
  return !!packs?.some((p) => isPackInFlight(p.status));
}

/** ["pdf", "docx"] → ".pdf, .docx" (pass "," for an `<input accept>` value). */
export function formatExtensions(exts: string[], separator = ", "): string {
  return exts.map((e) => `.${e.toLowerCase()}`).join(separator);
}

/** Mirrors the backend's upload checks so the user gets an instant answer; the server stays the
 *  source of truth. Returns null when the file looks acceptable. */
export function validatePackFile(file: Pick<File, "name" | "size">, limits: PackLimitsDto): string | null {
  if (isAtPackLimit(limits)) {
    return `You've used all ${limits.maxPacks} study packs on the ${limits.tier} plan. Delete a pack or upgrade to add more.`;
  }
  const ext = fileExtension(file.name);
  const allowed = limits.allowedExtensions.map((e) => e.toLowerCase());
  if (!ext || !allowed.includes(ext)) {
    return `${ext ? `.${ext} files aren't` : "This file type isn't"} supported on the ${limits.tier} plan. Allowed: ${formatExtensions(allowed)}.`;
  }
  if (file.size === 0) {
    return "That file is empty.";
  }
  if (file.size > limits.maxFileBytes) {
    return `That file is ${formatBytes(file.size)} — the ${limits.tier} plan allows up to ${formatBytes(limits.maxFileBytes)} per file.`;
  }
  return null;
}

/** Friendly text for a FAILED pack's errorCode. Falls back to the server's message, then a generic line. */
export function packErrorMessage(pack: Pick<PackDto, "errorCode" | "errorMessage">, limits?: PackLimitsDto | null): string {
  switch (pack.errorCode) {
    case "OCR_REQUIRED":
      return "This looks like a scanned document — OCR is available on Pro and Max.";
    case "PAGE_LIMIT_EXCEEDED":
      return limits
        ? `This document has more pages than your plan allows (${limits.maxPages} pages on ${limits.tier}).`
        : "This document has more pages than your plan allows.";
    case "CHUNK_LIMIT_EXCEEDED":
      return "This document is too long to index on your plan — try splitting it into smaller files.";
    case "UNSUPPORTED_FORMAT":
      return "This file format isn't supported.";
    case "EMPTY_DOCUMENT":
      return "We couldn't find any text in this document.";
    case "PARSE_ERROR":
      return "We couldn't read this document — it may be corrupted or password-protected.";
    case "EMBEDDING_FAILED":
      return "Indexing failed on our side. Delete the pack and try uploading it again.";
    case "FILE_NOT_FOUND":
      return "The uploaded file went missing during processing. Delete the pack and upload it again.";
    default:
      return pack.errorMessage || "Processing failed. Delete the pack and try again.";
  }
}

/** Friendly text for a rejected upload (an ApiError from POST /api/packs; `code` per the contract). */
export function uploadErrorMessage(
  err: { status: number; message: string; code?: string },
  limits?: PackLimitsDto | null
): string {
  // A 413 can come from the servlet's multipart size cap before our handler sees it (no `code`).
  const code = err.code ?? (err.status === 413 ? "FILE_TOO_LARGE" : undefined);
  switch (code) {
    case "GUEST_UPLOAD_NOT_ALLOWED":
      return "Guests can't upload study packs — sign up for a free account to get started.";
    case "PACK_LIMIT_REACHED":
      return "You've reached your plan's study pack limit. Delete a pack or upgrade to add more.";
    case "FILE_TOO_LARGE":
      return limits
        ? `That file is too large — your plan allows up to ${formatBytes(limits.maxFileBytes)} per file.`
        : "That file is too large for your plan.";
    case "UNSUPPORTED_FORMAT":
      return limits
        ? `That file type isn't supported on your plan. Allowed: ${formatExtensions(limits.allowedExtensions)}.`
        : "That file type isn't supported on your plan.";
    case "EMPTY_FILE":
      return "That file is empty.";
    default:
      return err.message || "Upload failed. Please try again.";
  }
}
