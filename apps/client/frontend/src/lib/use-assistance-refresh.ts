import { useCallback, useEffect, useRef } from "react";
import { subscribeToAssistanceEvents } from "./assistance-events";

/** Coalesce bursts, with at most one current read in flight and one trailing read. */
export function useAssistanceRefresh(
  userId: string,
  read: () => Promise<void>,
) {
  const readRef = useRef(read);
  readRef.current = read;
  const requestRef = useRef<() => void>(() => {});
  useEffect(() => {
    let disposed = false;
    let running = false;
    let dirty = false;
    let timer: number | undefined;
    const request = () => {
      if (disposed) return;
      dirty = true;
      if (running || timer !== undefined) return;
      timer = window.setTimeout(() => {
        timer = undefined;
        if (disposed) return;
        running = true;
        dirty = false;
        void readRef.current().finally(() => {
          running = false;
          if (dirty) request();
        });
      }, 0);
    };
    requestRef.current = request;
    request();
    const stop = subscribeToAssistanceEvents(userId, (signal) => {
      if (signal.type !== "disconnected") request();
    });
    const visible = () => {
      if (document.visibilityState === "visible") request();
    };
    window.addEventListener("quota-guard:assistance-refresh", request);
    document.addEventListener("visibilitychange", visible);
    return () => {
      disposed = true;
      window.clearTimeout(timer);
      stop();
      window.removeEventListener("quota-guard:assistance-refresh", request);
      document.removeEventListener("visibilitychange", visible);
    };
  }, [userId]);
  return useCallback(() => requestRef.current(), []);
}
