import { useCallback, useEffect, useRef, useState } from "react";
import { generateQuiz, getPack, getPackLimits } from "../api/packs";
import { startSession } from "../api/sessions";
import { ApiError } from "../api/client";
import { usePolling } from "./usePolling";
import { chatErrorMessage } from "../utils/chat";
import { packTokenQuota, quizErrorMessage } from "../utils/quiz";
import type { ChatQuotaDto, Difficulty, PackDto, SessionStartResponse } from "../types/api";

export interface PackQuizLoadError {
  message: string;
  notFound: boolean;
}

export interface QuizStartOptions {
  startingDifficulty: Difficulty;
  questionCount: number;
}

function toApiError(err: unknown): ApiError {
  return err instanceof ApiError ? err : new ApiError(0, "Network error — could not reach the server.");
}

/**
 * State for one pack's quiz page: loads the pack (+ the monthly token budget), generates the
 * question bank and polls every 3s — only while it's GENERATING — until READY / FAILED, and starts
 * a pack session. Leaving the page aborts the load and any poll in flight.
 *
 * Mount once per pack (the page keys its component by packId). Pass `null` (logged out or guest)
 * to skip every request.
 */
export function usePackQuiz(token: string | null, packId: number) {
  const [pack, setPack] = useState<PackDto | null>(null);
  const [quota, setQuota] = useState<ChatQuotaDto | null>(null);
  const [loadError, setLoadError] = useState<PackQuizLoadError | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [generating, setGenerating] = useState(false);
  const [generateError, setGenerateError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [startError, setStartError] = useState<string | null>(null);

  const mountedRef = useRef(false);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  // The budget is informational (the server enforces it), so failing to load it never blocks the page.
  const refreshQuota = useCallback(() => {
    if (!token) return;
    getPackLimits(token)
      .then((limits) => {
        if (mountedRef.current) setQuota(packTokenQuota(limits));
      })
      .catch(() => {});
  }, [token]);

  useEffect(() => {
    if (!token) return;
    const controller = new AbortController();
    const { signal } = controller;
    getPack(token, packId, signal)
      .then((p) => {
        if (!signal.aborted) setPack(p);
      })
      .catch((err) => {
        if (signal.aborted) return;
        const e = toApiError(err);
        setLoadError({
          notFound: e.status === 404,
          message: e.status === 404 || e.status === 0 ? chatErrorMessage(e) : e.message || "Could not load this pack.",
        });
      });
    refreshQuota();
    return () => controller.abort();
  }, [token, packId, reloadKey, refreshQuota]);

  // Re-reads the pack after a refusal that means our copy is stale (PACK_NOT_READY / QUIZ_NOT_READY).
  const refreshPack = useCallback(() => {
    if (!token) return;
    getPack(token, packId)
      .then((p) => {
        if (mountedRef.current) setPack(p);
      })
      .catch(() => {});
  }, [token, packId]);

  usePolling(!!token && pack?.quizStatus === "GENERATING", async (signal) => {
    if (!token) return;
    const next = await getPack(token, packId, signal);
    if (signal.aborted) return;
    setPack(next);
    // Generation just finished and spent tokens — update the budget shown.
    if (next.quizStatus !== "GENERATING") refreshQuota();
  });

  /** Reacts to the refusals both actions share; returns the friendly message. */
  const handleRefusal = useCallback(
    (err: unknown): string => {
      const e = toApiError(err);
      switch (e.code) {
        case "CHAT_QUOTA_EXCEEDED":
          // The server says we're out — reflect it even if our last reading was stale.
          setQuota((q) => (q ? { ...q, used: Math.max(q.used, q.limit) } : q));
          break;
        case "QUIZ_ALREADY_GENERATING":
          // Another tab (or an earlier click) started it — show it and let polling pick it up.
          setPack((p) => (p ? { ...p, quizStatus: "GENERATING", quizErrorMessage: null } : p));
          break;
        case "PACK_NOT_READY":
        case "QUIZ_NOT_READY":
          refreshPack();
          break;
      }
      return quizErrorMessage(e);
    },
    [refreshPack]
  );

  /** Generates (or regenerates, replacing) the bank. Never throws — failures land in generateError. */
  const generate = useCallback(async () => {
    if (!token || generating) return;
    setGenerating(true);
    setGenerateError(null);
    setStartError(null);
    try {
      const next = await generateQuiz(token, packId);
      if (mountedRef.current) setPack(next);
    } catch (err) {
      if (mountedRef.current) setGenerateError(handleRefusal(err));
    } finally {
      if (mountedRef.current) setGenerating(false);
    }
  }, [token, packId, generating, handleRefusal]);

  /** Starts a pack session; resolves null (with startError set) on failure or if the page went away. */
  const start = useCallback(
    async ({ startingDifficulty, questionCount }: QuizStartOptions): Promise<SessionStartResponse | null> => {
      if (!token || starting) return null;
      setStarting(true);
      setStartError(null);
      try {
        const res = await startSession({ kind: "pack", packId, token }, startingDifficulty, questionCount);
        return mountedRef.current ? res : null;
      } catch (err) {
        if (mountedRef.current) setStartError(handleRefusal(err));
        return null;
      } finally {
        if (mountedRef.current) setStarting(false);
      }
    },
    [token, packId, starting, handleRefusal]
  );

  const reload = useCallback(() => {
    setLoadError(null);
    setReloadKey((k) => k + 1);
  }, []);

  return { pack, quota, loadError, reload, generating, generateError, generate, starting, startError, start };
}
