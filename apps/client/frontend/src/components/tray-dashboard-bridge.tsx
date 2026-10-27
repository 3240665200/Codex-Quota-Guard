import { useEffect, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
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
    const contextMenu = (event: MouseEvent) => {
      if (
        !(event.target instanceof Element) ||
        event.target.closest(
          "input,textarea,select,[contenteditable]:not([contenteditable='false'])",
        ) ||
        window.getSelection()?.toString()
      )
        return;
      event.preventDefault();
      void invoke("tray_panel_show").catch(() => {});
    };
    document.addEventListener("contextmenu", contextMenu);
    return () => {
      disposed = true;
      document.removeEventListener("contextmenu", contextMenu);
      void unlisten.then((stop) => stop()).catch(() => {});
      void publishTraySnapshot(null).catch(() => {});
    };
  }, []);
  useEffect(() => {
    void publishTraySnapshot(snapshot).catch(() => {});
  }, [snapshot]);
}
