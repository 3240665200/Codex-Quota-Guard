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

  it("only queries bounded numeric pagination and validates consumption records", async () => {
    invokeMock.mockResolvedValue({
      items: [],
      page: 0,
      size: 10,
      totalElements: 0,
      totalPages: 0,
    });
    await expect(api.usageEvents(0, 10)).resolves.toHaveProperty("items", []);
    expect(invokeMock).toHaveBeenCalledWith("usage_events", {
      page: 0,
      size: 10,
    });
    invokeMock.mockClear();
    await expect(api.usageEvents(-1, 10)).rejects.toThrow();
    await expect(api.usageEvents(0, 101)).rejects.toThrow();
    expect(invokeMock).not.toHaveBeenCalled();
  });

  it("normalizes optional usage metadata without dropping valid ledger events", async () => {
    const efforts = [
      "none",
      "minimal",
      "low",
      "medium",
      "high",
      "xhigh",
      "max",
      "ultra",
    ];
    const event = (id: string, metadata?: Record<string, unknown>) => ({
      id,
      occurredAt: "2026-10-09T00:00:00Z",
      receivedAt: "2026-10-09T00:00:01Z",
      totalTokens: 42,
      source: "TOKEN_USAGE_RECORD",
      ...metadata,
    });
    const longestModel = `m${"x".repeat(95)}`;
    const items = [
      event("known", { model: "gpt-5-codex", reasoningEffort: "xhigh" }),
      event("explicit-null", { model: null, reasoningEffort: null }),
      event("missing-metadata"),
      event("invalid-metadata", {
        model: "m".repeat(97),
        reasoningEffort: "experimental",
      }),
      event("longest-model", { model: longestModel, reasoningEffort: "high" }),
      ...efforts.map((reasoningEffort, index) =>
        event(`effort-${index}`, { model: null, reasoningEffort }),
      ),
    ];
    invokeMock.mockResolvedValue({
      items,
      page: 4,
      size: 10,
      totalElements: 57,
      totalPages: 6,
    });

    const result = await api.usageEvents(4, 10);

    expect(result.items).toHaveLength(items.length);
    expect(result.items[0]).toMatchObject({
      id: "known",
      model: "gpt-5-codex",
      reasoningEffort: "xhigh",
    });
    expect(result.items[1]).toMatchObject({
      id: "explicit-null",
      model: null,
      reasoningEffort: null,
    });
    expect(result.items[2]).toMatchObject({
      id: "missing-metadata",
      model: null,
      reasoningEffort: null,
    });
    expect(result.items[3]).toMatchObject({
      id: "invalid-metadata",
      totalTokens: 42,
      source: "TOKEN_USAGE_RECORD",
      model: null,
      reasoningEffort: null,
    });
    expect(result.items[4]).toMatchObject({
      id: "longest-model",
      model: longestModel,
      reasoningEffort: "high",
    });
    expect(result.items.slice(5).map((item) => item.reasoningEffort)).toEqual(
      efforts,
    );
    expect(result).toMatchObject({
      page: 4,
      size: 10,
      totalElements: 57,
      totalPages: 6,
    });
  });

  it("rejects arbitrary text masquerading as service diagnostics", async () => {
    invokeMock.mockResolvedValue({
      state: "READY",
      skippedLines: 0,
      entries: [
        {
          atEpochSeconds: 1,
          kind: "ERROR",
          state: "ERROR",
          reportedEvents: 0,
          reportedTokens: 0,
          heartbeatSent: false,
          errorCode: "refresh_token=private-canary",
        },
      ],
    });
    await expect(api.meterRuntimeLogs()).rejects.toThrow();
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

  it("uses one fixed native command to ensure the installed meter service is running", async () => {
    invokeMock.mockResolvedValue("STARTED");
    await expect(api.ensureMeterService()).resolves.toBe("STARTED");
    await expect(api.ensureMeterService()).resolves.toBe("STARTED");
    expect(invokeMock).toHaveBeenCalledWith("meter_service_ensure");
    expect(invokeMock).toHaveBeenCalledTimes(1);
    invokeMock.mockResolvedValue("SOME_UNKNOWN_SERVICE_RESULT");
    await expect(api.retryMeterService()).rejects.toThrow();
    expect(invokeMock).toHaveBeenCalledWith("meter_service_retry");
    await expect(api.ensureMeterService()).rejects.toThrow();
  });

  it("accepts first-run service installation and server validation outcomes", async () => {
    invokeMock
      .mockResolvedValueOnce("INSTALLED")
      .mockResolvedValueOnce("SERVER_UNAVAILABLE");
    await expect(api.retryMeterService()).resolves.toBe("INSTALLED");
    await expect(api.retryMeterService()).resolves.toBe("SERVER_UNAVAILABLE");
  });

  it("validates the server address setting and uses fixed settings commands", async () => {
    invokeMock.mockResolvedValue({
      url: "http://192.168.0.102:8080",
    });

    await expect(api.serverConfig()).resolves.toEqual({
      url: "http://192.168.0.102:8080",
    });
    await api.saveServerConfig({
      url: "http://192.168.0.102:8080",
    });
    expect(invokeMock).toHaveBeenCalledWith("server_config_get");
    expect(invokeMock).toHaveBeenCalledWith("server_config_save", {
      config: { url: "http://192.168.0.102:8080" },
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
      fallback: {
        state: "BASELINE_READY",
        lastScannedAt: "2026-10-07T00:00:00Z",
        lastReportedAt: null,
        lastReportedEvents: 0,
        issues: [],
      },
      service: {
        state: "NOT_INSTALLED",
        lastScanEpochSeconds: null,
        lastReportEpochSeconds: null,
        reportedEvents: 0,
        errorCode: null,
      },
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

  it("accepts bounded scanner issue counts and rejects unsafe diagnostic keys", async () => {
    const status = {
      fallback: {
        state: "UP_TO_DATE",
        lastScannedAt: "2026-10-07T00:00:00Z",
        lastReportedAt: null,
        lastReportedEvents: 0,
        issues: ["OVERSIZED_JSONL_RECORD"],
        issueCounts: { OVERSIZED_JSONL_RECORD: 500 },
      },
      service: {
        state: "NOT_INSTALLED",
        lastScanEpochSeconds: null,
        lastReportEpochSeconds: null,
        reportedEvents: 0,
        errorCode: null,
      },
      pairingState: "NOT_INSTALLED",
    };
    invokeMock.mockResolvedValue(status);

    await expect(api.usageStatus()).resolves.toMatchObject({
      fallback: { issueCounts: { OVERSIZED_JSONL_RECORD: 500 } },
    });

    invokeMock.mockResolvedValue({
      ...status,
      fallback: { ...status.fallback, issueCounts: { refresh_token: 1 } },
    });
    await expect(api.usageStatus()).rejects.toThrow();
  });

  it("allows only the fixed safe message for a rejected control-plane request", () => {
    const safeMessage = "中央后台拒绝请求，请检查请求内容后重试。";
    expect(getApiErrorMessage(safeMessage)).toBe(safeMessage);
    expect(getApiErrorMessage("refresh_token=private-canary")).not.toContain(
      "private-canary",
    );
  });

  it("accepts a missing five-hour personal limit when weekly quota remains usable", async () => {
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

    await expect(api.quotaOverview()).resolves.toMatchObject({ allowed: true });
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
