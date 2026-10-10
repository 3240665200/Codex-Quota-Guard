import { listen, type EventCallback } from "@tauri-apps/api/event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { subscribeToTrayFeedback, trayFeedbackNotice } from "@/lib/tray-events";

vi.mock("@tauri-apps/api/event", () => ({ listen: vi.fn() }));

const listenMock = vi.mocked(listen);

describe("native tray feedback", () => {
  beforeEach(() => {
    listenMock.mockReset();
  });

  it("listens only for the fixed native feedback event", async () => {
    listenMock.mockResolvedValue(vi.fn());
    const handler = vi.fn();
    subscribeToTrayFeedback(handler);
    expect(listenMock).toHaveBeenCalledWith(
      "cqg-tray-feedback",
      expect.any(Function),
    );
    const callback = listenMock.mock.calls[0][1] as EventCallback<unknown>;
    callback({
      event: "cqg-tray-feedback",
      id: 1,
      payload: { action: "scan", outcome: "success" },
    });
    expect(handler).toHaveBeenCalledWith({
      action: "scan",
      outcome: "success",
    });
  });

  it("ignores malformed, unexpected or credential-like payloads", async () => {
    listenMock.mockResolvedValue(vi.fn());
    const handler = vi.fn();
    subscribeToTrayFeedback(handler);
    const callback = listenMock.mock.calls[0][1] as EventCallback<unknown>;
    for (const payload of [
      null,
      { action: "scan", outcome: "installed" },
      { action: "meter", outcome: "secret-token" },
      { action: "scan", outcome: "error", message: "secret-token" },
    ]) {
      callback({ event: "cqg-tray-feedback", id: 1, payload });
    }
    expect(handler).not.toHaveBeenCalled();
  });

  it("removes a listener that finishes subscribing after unmount", async () => {
    const unlisten = vi.fn();
    let resolve!: (cleanup: () => void) => void;
    listenMock.mockReturnValue(
      new Promise((done) => {
        resolve = done;
      }),
    );
    const unsubscribe = subscribeToTrayFeedback(vi.fn());
    unsubscribe();
    resolve(unlisten);
    await Promise.resolve();
    expect(unlisten).toHaveBeenCalledOnce();
  });

  it("unsubscribes once and ignores events after disposal", async () => {
    const unlisten = vi.fn();
    const handler = vi.fn();
    listenMock.mockResolvedValue(unlisten);
    const unsubscribe = subscribeToTrayFeedback(handler);
    await Promise.resolve();
    unsubscribe();
    unsubscribe();
    const callback = listenMock.mock.calls[0][1] as EventCallback<unknown>;
    callback({
      event: "cqg-tray-feedback",
      id: 1,
      payload: { action: "meter", outcome: "running" },
    });
    expect(unlisten).toHaveBeenCalledOnce();
    expect(handler).not.toHaveBeenCalled();
  });

  it("tolerates a non-native browser or failed registration", async () => {
    listenMock.mockImplementation(async () => {
      throw new Error("native event bridge unavailable");
    });
    const unsubscribe = subscribeToTrayFeedback(vi.fn());
    expect(unsubscribe).toBeTypeOf("function");
    unsubscribe();
    await Promise.resolve();
    await Promise.resolve();
  });

  it("maps outcomes to fixed safe notices", () => {
    expect(
      trayFeedbackNotice({ action: "scan", outcome: "signed_out" }),
    ).toEqual({ kind: "warning", message: "请先登录系统账户，再扫描用量。" });
    expect(trayFeedbackNotice({ action: "scan", outcome: "error" }).kind).toBe(
      "error",
    );
    expect(
      trayFeedbackNotice({ action: "meter", outcome: "needs_admin" }).message,
    ).toContain("管理员");
    expect(
      trayFeedbackNotice({ action: "meter", outcome: "running" }).kind,
    ).toBe("success");
  });

  it("provides a bounded notice for every meter outcome", () => {
    for (const outcome of [
      "success",
      "running",
      "started",
      "installed",
    ] as const) {
      const notice = trayFeedbackNotice({ action: "meter", outcome });
      expect(notice.kind).toBe("success");
      expect(notice.message.length).toBeGreaterThan(0);
    }
    for (const outcome of [
      "error",
      "not_installed",
      "server_unavailable",
      "install_failed",
    ] as const) {
      expect(trayFeedbackNotice({ action: "meter", outcome }).kind).toBe(
        "error",
      );
    }
    expect(
      trayFeedbackNotice({ action: "meter", outcome: "unsupported" }).kind,
    ).toBe("warning");
    expect(
      trayFeedbackNotice({ action: "scan", outcome: "success" }).kind,
    ).toBe("success");
  });
});
