import { invoke } from "@tauri-apps/api/core";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "./api";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));
const native = vi.mocked(invoke);
const id = "11111111-1111-4111-8111-111111111111";
const binding = {
  id,
  userId: id,
  userDisplayName: "Alice",
  userEmail: "alice@example.com",
  deviceId: id,
  enrollmentId: id,
  officialAccountId: id,
  sessionId: "official-session",
  status: "ACTIVE",
  matchMethod: "SERIAL_LOGIN_DIFF",
  verificationLevel: "USER_CONFIRMED_INFERENCE",
  createdAt: "2026-10-15T10:00:00Z",
  endedAt: null,
};
const attempt = {
  id,
  deviceId: id,
  enrollmentId: id,
  officialAccountId: id,
  status: "READY_FOR_LOGIN",
  queuePosition: 0,
  loginAllowed: true,
  candidateSessionId: null,
  candidateDisplayName: null,
  candidatePlatform: null,
  expiresAt: "2026-10-15T10:02:00Z",
  errorCode: null,
  quarantined: false,
  binding: null,
};
beforeEach(() => native.mockReset());
describe("desktop binding transport", () => {
  it("validates and restores a persisted safe binding", async () => {
    native.mockResolvedValue({ attempt: null, binding });
    expect(await api.sessionBindingCurrent()).toEqual({
      attempt: null,
      binding,
    });
    expect(native).toHaveBeenLastCalledWith("session_binding_current");
  });
  it("uses only a task ID for every native action and never takes a device or user override", async () => {
    native.mockResolvedValue(attempt);
    for (const action of [
      "status",
      "prepare",
      "detect",
      "confirm",
      "cancel",
    ] as const) {
      await api.sessionBindingAction(id, action);
      expect(native).toHaveBeenLastCalledWith(`session_binding_${action}`, {
        attemptId: id,
      });
    }
    await api.sessionBindingStart();
    expect(native).toHaveBeenLastCalledWith("session_binding_start");
  });
  it("rejects malformed and mismatched task IDs, unknown secret fields, and invalid browser choices", async () => {
    await expect(
      api.sessionBindingAction("https://evil.invalid", "confirm"),
    ).rejects.toThrow();
    expect(native).not.toHaveBeenCalled();
    native.mockResolvedValue({
      ...attempt,
      id: "22222222-2222-4222-8222-222222222222",
    });
    await expect(api.sessionBindingAction(id, "status")).rejects.toThrow();
    native.mockResolvedValue({
      ...attempt,
      accessToken: "must-never-reach-renderer",
    });
    await expect(api.sessionBindingStart()).rejects.toThrow();
    native.mockClear();
    await expect(
      api.officialLoginHelperStart(id, "CMD" as "EDGE"),
    ).rejects.toThrow();
    expect(native).not.toHaveBeenCalled();
  });
});
