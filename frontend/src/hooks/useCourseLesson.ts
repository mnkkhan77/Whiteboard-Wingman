import { useCallback, useEffect, useRef, useState } from "react";
import { getCourseLesson, setCourseLessonCompleted } from "../api/packs";
import { ApiError } from "../api/client";
import { courseErrorMessage } from "../utils/course";
import type { CourseLessonDto } from "../types/api";

function toApiError(err: unknown): ApiError {
  return err instanceof ApiError ? err : new ApiError(0, "Network error — could not reach the server.");
}

/**
 * One lesson's detail. The first load can be slow (the backend writes and caches the lesson's
 * content then) and can fail the way a pack quiz's grading can (quota / rate limit / LLM error) —
 * `reload` simply asks again, same as retrying any other pack-quiz failure.
 *
 * Mount once per lesson (the page should key its component by lessonId). Pass `null` (logged out
 * or guest) to skip every request.
 */
export function useCourseLesson(token: string | null, packId: number, lessonId: number) {
  const [lesson, setLesson] = useState<CourseLessonDto | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [completing, setCompleting] = useState(false);
  const [completeError, setCompleteError] = useState<string | null>(null);

  const mountedRef = useRef(false);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const load = useCallback(() => {
    if (!token) return;
    setLoading(true);
    setLoadError(null);
    const controller = new AbortController();
    getCourseLesson(token, packId, lessonId, controller.signal)
      .then((res) => {
        if (mountedRef.current) setLesson(res);
      })
      .catch((err) => {
        if (controller.signal.aborted || !mountedRef.current) return;
        setLoadError(courseErrorMessage(toApiError(err)));
      })
      .finally(() => {
        if (mountedRef.current) setLoading(false);
      });
    return () => controller.abort();
  }, [token, packId, lessonId]);

  useEffect(() => {
    return load();
  }, [load]);

  const setCompleted = useCallback(
    async (completed: boolean) => {
      if (!token || completing) return;
      setCompleting(true);
      setCompleteError(null);
      try {
        const updated = await setCourseLessonCompleted(token, packId, lessonId, completed);
        if (mountedRef.current) setLesson(updated);
      } catch (err) {
        if (mountedRef.current) setCompleteError(courseErrorMessage(toApiError(err)));
      } finally {
        if (mountedRef.current) setCompleting(false);
      }
    },
    [token, packId, lessonId, completing]
  );

  return { lesson, loading, loadError, reload: load, completing, completeError, setCompleted };
}
