import { beforeEach, describe, expect, it, vi } from "vitest";
import { api, assistanceAccessRevokedEvent, getApiErrorMessage } from "./api";
import { ApiHttpHarness, apiTestDeviceId } from "@/test/api-http-harness";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));
const harness = new ApiHttpHarness();
const account = "11111111-1111-4111-8111-111111111111";
const sample = {
  code: "012345",
  serverTime: "2026-10-25T00:00:25Z",
  expiresAt: "2026-10-25T00:00:30Z",
  periodSeconds: 30,
  officialAccountId: account,
};
describe("Authenticator direct Axios boundary", () => {
  beforeEach(async () => {
    await harness.prepare(api);
  });
  it("reads only validated grant metadata with this device's authenticated request", async () => {
    const context = {
      allowed: true,
      reasonCode: null,
      bindingAttemptId: account,
      expiresAt: "2026-10-25T00:10:00Z",
      serverTime: sample.serverTime,
      mailEnabled: true,
      authenticatorEnabled: false,
    };
    const path = "/api/v1/client/login-assistance/context";
    harness.route("GET", path, () => ({ data: context }));
    expect(await api.loginAssistanceContext()).toEqual(context);
    expect(harness.requestsFor("GET", path)[0].headers).toMatchObject({
      "X-Quota-Guard-Device-ID": apiTestDeviceId,
      Authorization: "Bearer access-test-token",
    });
    for (const bad of [
      { ...context, bindingAttemptId: null },
      { ...context, expiresAt: context.serverTime },
      { ...context, allowed: false },
      { ...context, secret: "must-not-accept" },
    ]) {
      harness.route("GET", path, () => ({ data: bad }));
      await expect(api.loginAssistanceContext()).rejects.toThrow();
    }
  });
  it("revokes displayed assistance on a denied grant without exposing server errors or logging out", async () => {
    const revoked = vi.fn();
    window.addEventListener(assistanceAccessRevokedEvent, revoked);
    try {
      for (const [path, read] of [
        ["/authenticator/code", () => api.authenticatorCode()],
        ["/listeners", () => api.loginAssistanceStart()],
      ] as const) {
        harness.route("POST", `/api/v1/client/login-assistance${path}`, () => ({
          status: 403,
          data: {
            code: "LOGIN_ASSISTANCE_NOT_ALLOWED",
            message: "private-secret-must-not-echo",
          },
        }));
        await expect(read()).rejects.toThrow(
          "登录辅助仅在取得本次绑定名额后开放，绑定完成后自动关闭。",
        );
      }
      expect(revoked).toHaveBeenCalledTimes(2);
      expect(harness.requestsFor("POST", "/api/v1/auth/logout")).toHaveLength(
        0,
      );
    } finally {
      window.removeEventListener(assistanceAccessRevokedEvent, revoked);
    }
  });
  it("uses central authenticated routes and accepts only credential-free current-code responses", async () => {
    const info = {
      available: true,
      reasonCode: null,
      officialAccountId: account,
      accountLabel: "Team",
    };
    harness.respond(info);
    expect(await api.authenticatorInfo()).toEqual(info);
    expect(
      harness.requestsFor(
        "GET",
        "/api/v1/client/login-assistance/authenticator",
      ),
    ).toHaveLength(1);
    harness.respond(sample);
    expect(await api.authenticatorCode()).toEqual(sample);
    expect(
      harness.requestsFor(
        "POST",
        "/api/v1/client/login-assistance/authenticator/code",
      ),
    ).toHaveLength(1);
    for (const bad of [
      { ...sample, secret: "must-not-accept" },
      { ...sample, code: "12345" },
      { ...sample, expiresAt: sample.serverTime },
      { ...sample, expiresAt: "2027-10-25T00:00:30Z" },
    ]) {
      harness.respond(bad);
      await expect(api.authenticatorCode()).rejects.toThrow();
    }
  });
  it("maps fixed error codes without displaying provider bodies or credentials", async () => {
    harness.route(
      "POST",
      "/api/v1/client/login-assistance/authenticator/code",
      () => ({
        status: 409,
        data: {
          code: "AUTHENTICATOR_UNAVAILABLE",
          message: "private-secret-must-not-echo",
        },
      }),
    );
    const error = await api
      .authenticatorCode()
      .catch((failure: unknown) => failure);
    expect(getApiErrorMessage(error)).toBe(
      "团队验证器不可用，请重新读取配置或联系管理员。",
    );
    expect(getApiErrorMessage(error)).not.toContain("private-secret");
  });
});
