import { beforeEach, describe, expect, it, vi } from "vitest";

import { invoke } from "@tauri-apps/api/core";

import { api, getApiErrorMessage } from "@/lib/api";

const invokeMock = vi.mocked(invoke);

vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn(),
}));

const quotaOverview = {
  user: {
    id: "user-1",
    email: "alice@example.com",
    displayName: "Alice",
    role: "USER",
    status: "ACTIVE",
  },
  team: { id: "team-1", name: "Design" },
  policy: {
    fiveHourTokenLimit: 1_000,
    weeklyTokenLimit: 10_000,
    fiveHourSource: "TEAM",
    weeklySource: "TEAM",
  },
  usage: {
    measurementStatus: "CLIENT_REPORTED",
    fiveHourUsedTokens: 200,
    weeklyUsedTokens: 1_000,
    fiveHourRemainingTokens: 800,
    weeklyRemainingTokens: 9_000,
    fiveHourResetsAt: null,
    weeklyResetsAt: null,
    measuredAt: "2026-10-06T08:00:00Z",
  },
  allowed: true,
  blockReason: null,
};

describe("Tauri API boundary", () => {
  beforeEach(() => {
    invokeMock.mockReset();
  });

  it("validates a status response and calls the fixed command", async () => {
    invokeMock.mockResolvedValue({
      service: "codex-quota-guard",
      state: "online",
    });

    await expect(api.status()).resolves.toEqual({
      service: "codex-quota-guard",
      state: "online",
    });
    expect(invokeMock).toHaveBeenCalledWith("status");
  });

  it("validates the server address setting and uses fixed settings commands", async () => {
    invokeMock.mockResolvedValue({
      url: "http://192.168.0.102:8080",
      allowInsecureLanHttp: true,
    });

    await expect(api.serverConfig()).resolves.toEqual({
      url: "http://192.168.0.102:8080",
      allowInsecureLanHttp: true,
    });
    await api.saveServerConfig({
      url: "http://192.168.0.102:8080",
      allowInsecureLanHttp: true,
    });
    expect(invokeMock).toHaveBeenCalledWith("server_config_get");
    expect(invokeMock).toHaveBeenCalledWith("server_config_save", {
      config: { url: "http://192.168.0.102:8080", allowInsecureLanHttp: true },
    });
  });

  it("validates personal token values rather than account-level percentages", async () => {
    invokeMock.mockResolvedValue(quotaOverview);

    await expect(api.quotaOverview()).resolves.toMatchObject({
      team: { name: "Design" },
      policy: { fiveHourTokenLimit: 1_000 },
      usage: {
        measurementStatus: "CLIENT_REPORTED",
        fiveHourRemainingTokens: 800,
      },
    });
    expect(invokeMock).toHaveBeenCalledWith("quota_overview");
  });

  it("accepts a truthful absence of reported usage before the first event", async () => {
    invokeMock.mockResolvedValue({
      ...quotaOverview,
      usage: { ...quotaOverview.usage, measuredAt: null },
    });

    await expect(api.quotaOverview()).resolves.toMatchObject({
      usage: { measuredAt: null, fiveHourUsedTokens: 200 },
    });
  });

  it("validates safe Rust meter diagnostics and fixed scan commands", async () => {
    const status = {
      fallback: { state: "BASELINE_READY", lastScannedAt: "2026-10-07T00:00:00Z",
        lastReportedAt: null, lastReportedEvents: 0, issues: [] },
      service: { state: "NOT_INSTALLED", lastScanEpochSeconds: null,
        lastReportEpochSeconds: null, reportedEvents: 0, errorCode: null },
      pairingState: "NOT_INSTALLED",
    };
    invokeMock.mockResolvedValue(status);

    await expect(api.usageStatus()).resolves.toEqual(status);
    await expect(api.scanUsage()).resolves.toEqual(status);
    await expect(api.pairMeter()).resolves.toEqual(status);
    expect(invokeMock).toHaveBeenCalledWith("usage_status");
    expect(invokeMock).toHaveBeenCalledWith("usage_scan");
    expect(invokeMock).toHaveBeenCalledWith("usage_pair");
  });

  it("fails closed when an unconfigured personal limit claims usable quota", async () => {
    invokeMock.mockResolvedValue({
      ...quotaOverview,
      policy: {
        ...quotaOverview.policy,
        fiveHourTokenLimit: null,
        fiveHourSource: "UNCONFIGURED",
      },
      usage: { ...quotaOverview.usage, fiveHourRemainingTokens: null },
      allowed: true,
    });

    await expect(api.quotaOverview()).rejects.toThrow();
  });

  it("does not coerce unconfigured personal token limits to zero", async () => {
    invokeMock.mockResolvedValue({
      ...quotaOverview,
      policy: {
        fiveHourTokenLimit: null,
        weeklyTokenLimit: null,
        fiveHourSource: "UNCONFIGURED",
        weeklySource: "UNCONFIGURED",
      },
      usage: {
        ...quotaOverview.usage,
        fiveHourRemainingTokens: null,
        weeklyRemainingTokens: null,
      },
      allowed: false,
      blockReason: "TOKEN_QUOTA_UNCONFIGURED",
    });

    await expect(api.quotaOverview()).resolves.toMatchObject({
      policy: { fiveHourTokenLimit: null },
      usage: { fiveHourUsedTokens: 200, fiveHourRemainingTokens: null },
    });
  });

  it("does not expose a managed credential login command", () => {
    expect(api).not.toHaveProperty("codexLogin");
    expect(api).not.toHaveProperty("codexSession");
  });

  it("keeps system session credentials inside the bridge", async () => {
    invokeMock.mockResolvedValue(undefined);

    await api.systemLogout();

    expect(invokeMock).toHaveBeenCalledWith("system_logout");
    expect(invokeMock.mock.calls.flat().join(" ")).not.toMatch(/bearer|token/i);
  });

  it("uses the system account login command without exposing tokens", async () => {
    invokeMock.mockResolvedValue({
      authenticated: true,
      user: quotaOverview.user,
      accessToken: "must-be-stripped",
      refreshToken: "must-be-stripped",
    });

    await expect(
      api.systemLogin("alice@example.com", "password"),
    ).resolves.toEqual({
      authenticated: true,
      user: quotaOverview.user,
    });
    expect(invokeMock).toHaveBeenCalledWith("system_login", {
      email: "alice@example.com",
      password: "password",
    });
    expect(invokeMock.mock.calls.flat().join(" ")).not.toMatch(
      /accessToken|refreshToken|bearer/i,
    );
  });

  it("keeps official account windows separate from personal token quota", async () => {
    invokeMock.mockResolvedValue({
      planType: "pro",
      windows: [
        {
          limitId: "codex",
          slot: "primary",
          windowDurationMins: 300,
          usedPercent: 25,
          resetsAt: "2026-10-06T12:00:00Z",
        },
      ],
      fetchedAt: "2026-10-06T08:00:00Z",
    });

    await expect(api.codexRateLimits()).resolves.toMatchObject({
      planType: "pro",
      windows: [{ windowDurationMins: 300, usedPercent: 25 }],
    });
    expect(invokeMock).toHaveBeenCalledWith("codex_rate_limits");
  });

  it("does not render arbitrary URL or token-like transport errors", () => {
    expect(
      getApiErrorMessage(
        new Error("request failed at https://control.example/token=secret"),
      ),
    ).toBe("暂时无法连接本地服务，请确认应用仍在运行。");
    expect(
      getApiErrorMessage(
        "request failed at https://control.example/token=secret",
      ),
    ).toBe("暂时无法连接本地服务，请确认应用仍在运行。");
  });

  it("shows only allow-listed Tauri string errors", () => {
    expect(getApiErrorMessage("本地服务暂时不可用，请稍后重试。")).toBe(
      "本地服务暂时不可用，请稍后重试。",
    );
  });
});
