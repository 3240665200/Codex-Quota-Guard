import { beforeEach, describe, expect, it, vi } from "vitest";
import { api, getApiErrorMessage } from "./api";
import { ApiHttpHarness } from "@/test/api-http-harness";

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
