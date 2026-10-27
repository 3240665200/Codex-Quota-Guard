import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { z } from "zod";
import { usdWindowSchema } from "./usd";

const officialWindow = z.object({
  limitId: z.string(),
  slot: z.string(),
  windowDurationMins: z.number().positive(),
  usedPercent: z.number().min(0).max(100),
  resetsAt: z.string().nullable(),
});
export const traySnapshotSchema = z
  .object({
    userName: z.string(),
    bindingAllowed: z.boolean(),
    personal: z
      .object({ fiveHour: usdWindowSchema, weekly: usdWindowSchema })
      .nullable(),
    account: z.object({ label: z.string(), total: usdWindowSchema }).nullable(),
    official: z
      .object({
        planType: z.string().nullable(),
        windows: z.array(officialWindow),
        fetchedAt: z.string().nullable(),
        source: z.literal("SERVER").optional(),
        accountLabel: z.string().nullable().optional(),
        status: z
          .enum([
            "NOT_LINKED",
            "SUCCESS",
            "FETCHING",
            "PENDING",
            "AUTH_REQUIRED",
            "RETRY_WAIT",
            "DISABLED",
          ])
          .optional(),
        enabled: z.boolean().optional(),
        intervalSeconds: z.number().optional(),
        stale: z.boolean().optional(),
        nextAttemptAt: z.string().nullable().optional(),
      })
      .nullable(),
    updatedAt: z.string().nullable(),
    quotaFailed: z.boolean(),
    officialFailed: z.boolean(),
    meterRunning: z.boolean(),
    refreshing: z.boolean(),
    mode: z.enum(["used", "remaining"]),
    theme: z.enum(["dark", "light"]),
  })
  .strict();
export type TraySnapshot = z.infer<typeof traySnapshotSchema>;
export type TrayAction =
  | "show"
  | "hide"
  | "quota"
  | "ledger"
  | "diagnostics"
  | "settings"
  | "login-assistance"
  | "refresh"
  | "close"
  | "quit";

export function isDesktopShell() {
  return "__TAURI_INTERNALS__" in window;
}

/** Only display data crosses WebViews. The main renderer retains sole ownership of HTTP credentials. */
export async function publishTraySnapshot(snapshot: TraySnapshot | null) {
  if (isDesktopShell()) await invoke("tray_snapshot_update", { snapshot });
}
export async function readTraySnapshot(): Promise<TraySnapshot | null> {
  const value = await invoke("tray_panel_snapshot");
  return value === null ? null : traySnapshotSchema.parse(value);
}
export function trayAction(action: TrayAction) {
  return invoke<void>("tray_panel_action", { action });
}
export function listenTrayRequests(handler: (action: string) => void) {
  return listen<string>("tray-request", (event) => handler(event.payload));
}
export function listenTraySnapshots(
  handler: (snapshot: TraySnapshot | null) => void,
) {
  return listen<unknown>("tray-snapshot", (event) => {
    const parsed = traySnapshotSchema.nullable().safeParse(event.payload);
    if (parsed.success) handler(parsed.data);
  });
}
