import { useEffect, useRef } from "react";
import {
  isDesktopShell,
  listenTrayRequests,
  publishTraySnapshot,
  type TraySnapshot,
} from "@/lib/tray-panel";

export function useTrayDashboard(
  snapshot: TraySnapshot,
  onRequest: (action: string) => void,
) {
  const request = useRef(onRequest);
  request.current = onRequest;
  useEffect(() => {
    if (!isDesktopShell()) return;
    let disposed = false;
    const unlisten = listenTrayRequests((action) => {
      if (!disposed) request.current(action);
    });
    return () => {
      disposed = true;
      void unlisten.then((stop) => stop()).catch(() => {});
      void publishTraySnapshot(null).catch(() => {});
    };
  }, []);
  useEffect(() => {
    void publishTraySnapshot(snapshot).catch(() => {});
  }, [snapshot]);
}
