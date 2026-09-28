import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { deletePack, getPackLimits, listPacks, uploadPack } from "../api/packs";
import { ApiError } from "../api/client";
import { usePolling } from "./usePolling";
import { hasPacksInFlight } from "../utils/packs";
import type { PackDto, PackLimitsDto } from "../types/api";

/**
 * Loads the current user's study packs + tier limits, polls the list while any pack is still
 * QUEUED/EMBEDDING, and exposes upload/delete that keep the local list in sync.
 * Pass `null` (logged out or guest) to skip every request.
 */
export function useStudyPacks(token: string | null) {
  const [limits, setLimits] = useState<PackLimitsDto | null>(null);
  const [packs, setPacks] = useState<PackDto[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  // Bumped on every local add/remove so a poll response requested *before* that change is
  // dropped instead of resurrecting a just-deleted pack or hiding a just-uploaded one.
  const mutationRef = useRef(0);

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    Promise.all([getPackLimits(token), listPacks(token)])
      .then(([l, p]) => {
        if (cancelled) return;
        setLimits(l);
        setPacks(p);
      })
      .catch((err) => {
        if (!cancelled) setLoadError(err instanceof ApiError ? err.message : "Could not load your study packs.");
      });
    return () => {
      cancelled = true;
    };
  }, [token, reloadKey]);

  // Poll only while something is still processing (the chain stops as soon as nothing is in flight).
  usePolling(!!token && hasPacksInFlight(packs), async (signal) => {
    if (!token) return;
    const version = mutationRef.current;
    const next = await listPacks(token, signal);
    if (!signal.aborted && version === mutationRef.current) setPacks(next);
  });

  // The backend's packsUsed is a count of every pack the user owns — the same set GET /packs
  // returns — so derive it from the list instead of re-fetching limits after each upload/delete.
  const liveLimits = useMemo(
    () => (limits && packs ? { ...limits, packsUsed: packs.length } : null),
    [limits, packs]
  );

  const upload = useCallback(
    async (file: File, title: string, onProgress: (percent: number) => void) => {
      if (!token) return;
      const created = await uploadPack(token, file, title, onProgress);
      mutationRef.current++;
      setPacks((prev) => [created, ...(prev ?? []).filter((p) => p.id !== created.id)]);
    },
    [token]
  );

  const remove = useCallback(
    async (packId: number) => {
      if (!token) return;
      await deletePack(token, packId);
      mutationRef.current++;
      setPacks((prev) => prev?.filter((p) => p.id !== packId) ?? prev);
    },
    [token]
  );

  const reload = useCallback(() => {
    setLoadError(null);
    setReloadKey((k) => k + 1);
  }, []);

  return { limits: liveLimits, packs, loadError, reload, upload, remove };
}
