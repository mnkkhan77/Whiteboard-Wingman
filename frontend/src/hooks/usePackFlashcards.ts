import { useCallback, useEffect, useRef, useState } from "react";
import { generateFlashcards, getFlashcards, getPack, getPackLimits, reviewFlashcard } from "../api/packs";
import { ApiError } from "../api/client";
import { usePolling } from "./usePolling";
import { chatErrorMessage } from "../utils/chat";
import { flashcardErrorMessage } from "../utils/flashcards";
import { packTokenQuota } from "../utils/quiz";
import type { ChatQuotaDto, PackDto, PackFlashcardDto, ReviewQuality } from "../types/api";

export interface PackFlashcardsLoadError {
  message: string;
  notFound: boolean;
}

function toApiError(err: unknown): ApiError {
  return err instanceof ApiError ? err : new ApiError(0, "Network error — could not reach the server.");
}

function dueCountOf(cards: PackFlashcardDto[], now: Date): number {
  return cards.filter((c) => new Date(c.dueAt).getTime() <= now.getTime()).length;
}

/**
 * State for one pack's flashcards page: loads the pack (+ the monthly token budget), generates the
 * deck and polls every 3s — only while it's GENERATING — until READY / FAILED, then loads the deck
 * once it's READY. Reviewing a card updates it in place (no full reload). Mirrors usePackQuiz.
 *
 * Mount once per pack (the page keys its component by packId). Pass `null` (logged out or guest)
 * to skip every request.
 */
export function usePackFlashcards(token: string | null, packId: number) {
  const [pack, setPack] = useState<PackDto | null>(null);
  const [quota, setQuota] = useState<ChatQuotaDto | null>(null);
  const [loadError, setLoadError] = useState<PackFlashcardsLoadError | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [generating, setGenerating] = useState(false);
  const [generateError, setGenerateError] = useState<string | null>(null);

  const [cards, setCards] = useState<PackFlashcardDto[] | null>(null);
  const [deckError, setDeckError] = useState<string | null>(null);
  const [reviewError, setReviewError] = useState<string | null>(null);
  const [reviewingId, setReviewingId] = useState<number | null>(null);

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

  // Re-reads the pack after a refusal that means our copy is stale (PACK_NOT_READY / FLASHCARDS_NOT_READY).
  const refreshPack = useCallback(() => {
    if (!token) return;
    getPack(token, packId)
      .then((p) => {
        if (mountedRef.current) setPack(p);
      })
      .catch(() => {});
  }, [token, packId]);

  const loadDeck = useCallback(() => {
    if (!token) return;
    setDeckError(null);
    getFlashcards(token, packId)
      .then((res) => {
        if (mountedRef.current) setCards(res.cards);
      })
      .catch((err) => {
        if (mountedRef.current) setDeckError(flashcardErrorMessage(toApiError(err)));
      });
  }, [token, packId]);

  // Loads the deck once it becomes READY (including right after generation finishes).
  useEffect(() => {
    if (pack?.flashcardStatus === "READY" && cards === null) loadDeck();
  }, [pack?.flashcardStatus, cards, loadDeck]);

  usePolling(!!token && pack?.flashcardStatus === "GENERATING", async (signal) => {
    if (!token) return;
    const next = await getPack(token, packId, signal);
    if (signal.aborted) return;
    setPack(next);
    // Generation just finished and spent tokens — update the budget shown, and drop the stale deck
    // so the effect above reloads it.
    if (next.flashcardStatus !== "GENERATING") {
      refreshQuota();
      setCards(null);
    }
  });

  /** Reacts to the refusals generation and review share; returns the friendly message. */
  const handleRefusal = useCallback(
    (err: unknown): string => {
      const e = toApiError(err);
      switch (e.code) {
        case "CHAT_QUOTA_EXCEEDED":
          // The server says we're out — reflect it even if our last reading was stale.
          setQuota((q) => (q ? { ...q, used: Math.max(q.used, q.limit) } : q));
          break;
        case "FLASHCARDS_ALREADY_GENERATING":
          // Another tab (or an earlier click) started it — show it and let polling pick it up.
          setPack((p) => (p ? { ...p, flashcardStatus: "GENERATING", flashcardErrorMessage: null } : p));
          break;
        case "PACK_NOT_READY":
        case "FLASHCARDS_NOT_READY":
          refreshPack();
          break;
      }
      return flashcardErrorMessage(e);
    },
    [refreshPack]
  );

  /** Generates (or regenerates, replacing) the deck. Never throws — failures land in generateError. */
  const generate = useCallback(async () => {
    if (!token || generating) return;
    setGenerating(true);
    setGenerateError(null);
    try {
      const next = await generateFlashcards(token, packId);
      if (mountedRef.current) {
        setPack(next);
        setCards(null); // the old deck (if any) is about to be replaced
      }
    } catch (err) {
      if (mountedRef.current) setGenerateError(handleRefusal(err));
    } finally {
      if (mountedRef.current) setGenerating(false);
    }
  }, [token, packId, generating, handleRefusal]);

  /** Reviews one card and updates it in place. Never throws — failures land in reviewError. */
  const review = useCallback(
    async (cardId: number, quality: ReviewQuality) => {
      if (!token || reviewingId !== null) return;
      setReviewingId(cardId);
      setReviewError(null);
      try {
        const updated = await reviewFlashcard(token, packId, cardId, quality);
        if (mountedRef.current) setCards((prev) => prev?.map((c) => (c.id === cardId ? updated : c)) ?? prev);
      } catch (err) {
        if (mountedRef.current) setReviewError(handleRefusal(err));
      } finally {
        if (mountedRef.current) setReviewingId(null);
      }
    },
    [token, packId, reviewingId, handleRefusal]
  );

  const reload = useCallback(() => {
    setLoadError(null);
    setReloadKey((k) => k + 1);
  }, []);

  const dueCount = cards ? dueCountOf(cards, new Date()) : 0;

  return {
    pack,
    quota,
    loadError,
    reload,
    generating,
    generateError,
    generate,
    cards,
    dueCount,
    deckError,
    reloadDeck: loadDeck,
    review,
    reviewingId,
    reviewError,
  };
}
