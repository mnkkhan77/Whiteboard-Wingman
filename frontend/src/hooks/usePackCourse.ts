import { useCallback, useEffect, useRef, useState } from "react";
import { generateCourse, getCourse, getPack, getPackLimits } from "../api/packs";
import { ApiError } from "../api/client";
import { usePolling } from "./usePolling";
import { chatErrorMessage } from "../utils/chat";
import { courseErrorMessage } from "../utils/course";
import { packTokenQuota } from "../utils/quiz";
import type { ChatQuotaDto, CourseDto, PackDto } from "../types/api";

export interface PackCourseLoadError {
  message: string;
  notFound: boolean;
}

function toApiError(err: unknown): ApiError {
  return err instanceof ApiError ? err : new ApiError(0, "Network error — could not reach the server.");
}

/**
 * State for one pack's course page: loads the pack (+ the monthly token budget), generates the
 * outline and polls every 3s — only while it's GENERATING — until READY / FAILED, then loads the
 * outline once it's READY. Mirrors usePackQuiz / usePackFlashcards.
 *
 * Mount once per pack (the page keys its component by packId). Pass `null` (logged out or guest)
 * to skip every request.
 */
export function usePackCourse(token: string | null, packId: number) {
  const [pack, setPack] = useState<PackDto | null>(null);
  const [quota, setQuota] = useState<ChatQuotaDto | null>(null);
  const [loadError, setLoadError] = useState<PackCourseLoadError | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [generating, setGenerating] = useState(false);
  const [generateError, setGenerateError] = useState<string | null>(null);

  const [course, setCourse] = useState<CourseDto | null>(null);
  const [courseError, setCourseError] = useState<string | null>(null);

  const mountedRef = useRef(false);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

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

  const refreshPack = useCallback(() => {
    if (!token) return;
    getPack(token, packId)
      .then((p) => {
        if (mountedRef.current) setPack(p);
      })
      .catch(() => {});
  }, [token, packId]);

  const loadCourse = useCallback(() => {
    if (!token) return;
    setCourseError(null);
    getCourse(token, packId)
      .then((res) => {
        if (mountedRef.current) setCourse(res);
      })
      .catch((err) => {
        if (mountedRef.current) setCourseError(courseErrorMessage(toApiError(err)));
      });
  }, [token, packId]);

  // Loads the outline once it becomes READY (including right after generation finishes).
  useEffect(() => {
    if (pack?.courseStatus === "READY" && course === null) loadCourse();
  }, [pack?.courseStatus, course, loadCourse]);

  usePolling(!!token && pack?.courseStatus === "GENERATING", async (signal) => {
    if (!token) return;
    const next = await getPack(token, packId, signal);
    if (signal.aborted) return;
    setPack(next);
    if (next.courseStatus !== "GENERATING") {
      refreshQuota();
      setCourse(null); // drop the stale outline so the effect above reloads it
    }
  });

  const handleRefusal = useCallback(
    (err: unknown): string => {
      const e = toApiError(err);
      switch (e.code) {
        case "CHAT_QUOTA_EXCEEDED":
          setQuota((q) => (q ? { ...q, used: Math.max(q.used, q.limit) } : q));
          break;
        case "COURSE_ALREADY_GENERATING":
          setPack((p) => (p ? { ...p, courseStatus: "GENERATING", courseErrorMessage: null } : p));
          break;
        case "PACK_NOT_READY":
        case "COURSE_NOT_READY":
          refreshPack();
          break;
      }
      return courseErrorMessage(e);
    },
    [refreshPack]
  );

  /** Generates (or regenerates, replacing) the outline. Never throws — failures land in generateError. */
  const generate = useCallback(async () => {
    if (!token || generating) return;
    setGenerating(true);
    setGenerateError(null);
    try {
      const next = await generateCourse(token, packId);
      if (mountedRef.current) {
        setPack(next);
        setCourse(null); // the old outline (if any) is about to be replaced
      }
    } catch (err) {
      if (mountedRef.current) setGenerateError(handleRefusal(err));
    } finally {
      if (mountedRef.current) setGenerating(false);
    }
  }, [token, packId, generating, handleRefusal]);

  const reload = useCallback(() => {
    setLoadError(null);
    setReloadKey((k) => k + 1);
  }, []);

  return { pack, quota, loadError, reload, generating, generateError, generate, course, courseError, reloadCourse: loadCourse };
}
