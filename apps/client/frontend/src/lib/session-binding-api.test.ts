import { beforeEach, describe, expect, it, vi } from "vitest";
import { invoke } from "@tauri-apps/api/core";
import { api } from "./api";
import { ApiHttpHarness, apiTestDeviceId } from "@/test/api-http-harness";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));
const native = vi.mocked(invoke);
const http = new ApiHttpHarness();
const id = "11111111-1111-4111-8111-111111111111";
const binding = {
  id,
  userId: id,
  userDisplayName: "Alice",
  userEmail: "alice@example.com",
  deviceId: apiTestDeviceId,
  enrollmentId: apiTestDeviceId,
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
  deviceId: apiTestDeviceId,
  enrollmentId: apiTestDeviceId,
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
  queueState: "ACTIVE",
  retryAt: null,
  binding: null,
};

beforeEach(async () => http.prepare(api));

describe("central session binding transport", () => {
  it("accepts queue metadata states and sends only installation identity", async () => {
    for (const queueState of [
      "IDLE",
      "SETTLING",
      "RETRYING",
      "PREPARING",
      "ACTIVE",
    ]) {
      http.respond({ ...attempt, queueState, retryAt: "2026-10-15T10:00:30Z" });
      expect((await api.sessionBindingStart()).queueState).toBe(queueState);
    }
    const request = http
      .requestsFor("POST", "/api/v1/client/session-bindings/attempts")
      .at(-1)!;
    expect(JSON.parse(String(request.data))).toEqual({
      deviceId: apiTestDeviceId,
      enrollmentId: apiTestDeviceId,
    });
    expect(
      (request.headers as Record<string, string>)["X-CQG-Queue-Version"],
    ).toBe("2");
    http.respond({ ...attempt, queueState: "MANUAL_UNLOCK" });
    await expect(api.sessionBindingStart()).rejects.toThrow();
    http.respond({ ...attempt, accessToken: "must-not-be-accepted" });
    await expect(api.sessionBindingStart()).rejects.toThrow();
  });

  it("restores a binding with installation identity and queue metadata v2", async () => {
    http.respond({ attempt: null, binding });
    expect(await api.sessionBindingCurrent()).toEqual({
      attempt: null,
      binding,
    });
    const request = http
      .requestsFor("GET", "/api/v1/client/session-bindings/current")
      .at(-1)!;
    expect(request.params).toEqual({
      deviceId: apiTestDeviceId,
      enrollmentId: apiTestDeviceId,
    });
    expect(
      (request.headers as Record<string, string>)["X-CQG-Queue-Version"],
    ).toBe("2");
    http.respond({ attempt: { ...attempt, deviceId: id }, binding: null });
    await expect(api.sessionBindingCurrent()).rejects.toThrow();
  });

  it("preflights task ownership and state before each binding action", async () => {
    http.respond({ ...attempt, status: "QUEUED", loginAllowed: false });
    await expect(api.sessionBindingAction(id, "detect")).rejects.toThrow();
    expect(
      http.requestsFor(
        "POST",
        `/api/v1/client/session-bindings/attempts/${id}/detect`,
      ),
    ).toHaveLength(0);

    http.respond({ ...attempt, status: "READY_FOR_LOGIN", loginAllowed: true });
    await api.sessionBindingAction(id, "detect");
    expect(
      http.requestsFor("GET", `/api/v1/client/session-bindings/attempts/${id}`),
    ).toHaveLength(2);
    const detect = http
      .requestsFor(
        "POST",
        `/api/v1/client/session-bindings/attempts/${id}/detect`,
      )
      .at(-1)!;
    expect(
      (detect.headers as Record<string, string>)["X-CQG-Queue-Version"],
    ).toBe("2");

    http.respond({
      ...attempt,
      status: "AWAITING_CONFIRMATION",
      candidateSessionId: "server-selected-session",
    });
    await api.sessionBindingAction(id, "confirm");
    const confirm = http
      .requestsFor(
        "POST",
        `/api/v1/client/session-bindings/attempts/${id}/confirm`,
      )
      .at(-1)!;
    expect(JSON.parse(String(confirm.data))).toEqual({
      sessionId: "server-selected-session",
    });

    http.respond({
      ...attempt,
      deviceId: "22222222-2222-4222-8222-222222222222",
    });
    await expect(api.sessionBindingAction(id, "cancel")).rejects.toThrow();
    expect(
      http.requestsFor(
        "POST",
        `/api/v1/client/session-bindings/attempts/${id}/cancel`,
      ),
    ).toHaveLength(0);
  });

  it("rejects malformed attempt IDs before sending a request", async () => {
    const requestCount = http.requests.length;
    await expect(
      api.sessionBindingAction("https://evil.invalid", "confirm"),
    ).rejects.toThrow();
    expect(http.requests).toHaveLength(requestCount);
    expect(native).not.toHaveBeenCalledWith("session_binding_current");
  });
});
