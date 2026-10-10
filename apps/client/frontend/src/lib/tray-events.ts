import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { z } from "zod";

const feedbackSchema = z.discriminatedUnion("action", [
  z
    .object({
      action: z.literal("scan"),
      outcome: z.enum(["success", "error", "signed_out"]),
    })
    .strict(),
  z
    .object({
      action: z.literal("meter"),
      outcome: z.enum([
        "success",
        "error",
        "needs_admin",
        "running",
        "started",
        "installed",
        "not_installed",
        "server_unavailable",
        "install_failed",
        "unsupported",
      ]),
    })
    .strict(),
]);

export type TrayFeedback = z.infer<typeof feedbackSchema>;
type Notice = { kind: "success" | "warning" | "error"; message: string };

/** Only fixed outcomes cross the native boundary; no transport errors or credentials. */
export function trayFeedbackNotice(feedback: TrayFeedback): Notice {
  if (feedback.action === "scan") {
    switch (feedback.outcome) {
      case "success":
        return { kind: "success", message: "扫描已完成，已刷新消耗记录。" };
      case "signed_out":
        return { kind: "warning", message: "请先登录系统账户，再扫描用量。" };
      case "error":
        return { kind: "error", message: "扫描或上报未完成，请查看运行诊断。" };
    }
  }
  switch (feedback.outcome) {
    case "success":
    case "running":
      return { kind: "success", message: "计费服务正在运行。" };
    case "started":
      return { kind: "success", message: "计费服务已启动。" };
    case "installed":
      return { kind: "success", message: "计费服务已安装并启动。" };
    case "needs_admin":
      return {
        kind: "warning",
        message:
          "请先从托盘退出客户端，再以管理员身份运行以安装或更新计费服务。",
      };
    case "not_installed":
      return {
        kind: "error",
        message: "安装包缺少计费服务文件，请重新安装最新版客户端。",
      };
    case "server_unavailable":
      return {
        kind: "error",
        message: "无法连接后台，请检查服务器设置和后台运行状态。",
      };
    case "unsupported":
      return { kind: "warning", message: "当前平台暂不支持自动管理计费服务。" };
    case "install_failed":
    case "error":
      return {
        kind: "error",
        message: "计费服务检查失败，请查看运行诊断及安装提示。",
      };
  }
}

/** Handles StrictMode/unmount racing the async native registration. */
export function subscribeToTrayFeedback(
  handler: (feedback: TrayFeedback) => void,
): UnlistenFn {
  let disposed = false;
  let unlisten: UnlistenFn | undefined;
  void listen<unknown>("cqg-tray-feedback", ({ payload }) => {
    if (disposed) return;
    const parsed = feedbackSchema.safeParse(payload);
    if (parsed.success) handler(parsed.data);
  })
    .then((cleanup) => {
      if (disposed) cleanup();
      else unlisten = cleanup;
    })
    .catch(() => {
      // Renderer-only development has no native event bridge.
    });
  return () => {
    if (disposed) return;
    disposed = true;
    unlisten?.();
  };
}
