import { useEffect, useRef } from "react";

export const POLL_INTERVAL_MS = 3000;

/**
 * Calls `poll` every `intervalMs` while `active` is true. A setTimeout chain (the next poll is
 * scheduled after the previous one settles) means a slow server never gets overlapping requests.
 * Turning `active` off or unmounting stops the chain and aborts the request in flight — `poll`
 * should pass the signal to its fetch and skip state updates once `signal.aborted` is set.
 * A rejected poll is treated as transient: the next tick simply tries again.
 */
export function usePolling(active: boolean, poll: (signal: AbortSignal) => Promise<unknown>, intervalMs = POLL_INTERVAL_MS) {
  // Latest callback without restarting the chain on every render.
  const pollRef = useRef(poll);
  useEffect(() => {
    pollRef.current = poll;
  });

  useEffect(() => {
    if (!active) return;
    const controller = new AbortController();
    let timer = 0;
    const tick = () => {
      pollRef.current(controller.signal)
        .catch(() => {
          // transient failure — try again on the next tick
        })
        .finally(() => {
          if (!controller.signal.aborted) timer = window.setTimeout(tick, intervalMs);
        });
    };
    timer = window.setTimeout(tick, intervalMs);
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [active, intervalMs]);
}
