import { usdFixture, moneyScope, moneyWindow } from "@/test/usd-fixtures";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api, getApiErrorMessage } from "@/lib/api";
import { ApiHttpHarness } from "@/test/api-http-harness";

const harness = new ApiHttpHarness();
const invokeMock = harness.native;

vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn(),
}));

const quotaOverview = {
  billing: usdFixture(),
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

const loginAssistanceInfo = {
  available: true,
  reasonCode: null,
  officialAccountId: "official-1",
  accountLabel: "团队官方账户",
  recipientEmail: "team@example.com",
};
const loginListener = {
  id: "11111111-1111-4111-8111-111111111111",
  state: "LISTENING",
  startedAt: "2026-10-12T10:00:00Z",
  expiresAt: "2026-10-12T10:02:00Z",
  listeningAt: "2026-10-12T10:00:01Z",
  receivedAt: null,
  code: null,
  errorCode: null,
};

describe("login assistance API boundary", () => {
  beforeEach(async () => harness.prepare(api));

  it("restores current over the authenticated central route and rejects secret extras", async () => {
    harness.respond({ listener: null });
    expect(await api.loginAssistanceCurrent()).toEqual({ listener: null });
    expect(
      harness.requestsFor(
        "GET",
        "/api/v1/client/login-assistance/listeners/current",
      ),
    ).toHaveLength(1);
    harness.respond({ listener: loginListener });
    expect(await api.loginAssistanceCurrent()).toEqual({
      listener: loginListener,
    });
    for (const result of [
      { listener: loginListener, accessToken: "synthetic" },
      { listener: { ...loginListener, password: "synthetic" } },
    ]) {
      harness.respond(result);
      await expect(api.loginAssistanceCurrent()).rejects.toThrow();
    }
  });

  it("uses the fixed central listener routes with validated listener IDs", async () => {
    harness.respond(loginAssistanceInfo);
    await expect(api.loginAssistanceInfo()).resolves.toEqual(
      loginAssistanceInfo,
    );
    expect(
      harness.requestsFor("GET", "/api/v1/client/login-assistance"),
    ).toHaveLength(1);
    harness.respond(loginListener);
    await expect(api.loginAssistanceStart()).resolves.toEqual(loginListener);
    expect(
      harness.requestsFor("POST", "/api/v1/client/login-assistance/listeners"),
    ).toHaveLength(1);
    await expect(api.loginAssistanceStatus(loginListener.id)).resolves.toEqual(
      loginListener,
    );
    expect(
      harness.requestsFor(
        "GET",
        `/api/v1/client/login-assistance/listeners/${loginListener.id}`,
      ),
    ).toHaveLength(1);
    await expect(api.loginAssistanceCancel(loginListener.id)).resolves.toEqual(
      loginListener,
    );
    expect(
      harness.requestsFor(
        "DELETE",
        `/api/v1/client/login-assistance/listeners/${loginListener.id}`,
      ),
    ).toHaveLength(1);
    const requestCount = harness.requests.length;
    for (const id of [
      "",
      "someone-else",
      "https://mail.example.com/listen",
      `${loginListener.id}/other`,
    ]) {
      await expect(api.loginAssistanceStatus(id)).rejects.toThrow();
      await expect(api.loginAssistanceCancel(id)).rejects.toThrow();
    }
    expect(harness.requests).toHaveLength(requestCount);
  });

  it("rejects additional mailbox credentials instead of retaining or silently accepting them", async () => {
    for (const extra of [
      { password: "synthetic-secret" },
      { imapPassword: "synthetic-secret" },
      { accessToken: "synthetic-token" },
      { serverUrl: "https://mail.example.com" },
    ]) {
      harness.respond({ ...loginAssistanceInfo, ...extra });
      await expect(api.loginAssistanceInfo()).rejects.toThrow();
      harness.respond({ ...loginListener, ...extra });
      await expect(api.loginAssistanceStart()).rejects.toThrow();
    }
  });

  it("rejects malformed listener states and a code received before this listener became ready", async () => {
    const received = {
      ...loginListener,
      state: "RECEIVED",
      receivedAt: "2026-10-12T10:00:02Z",
      code: "123456",
    };
    harness.respond(received);
    await expect(api.loginAssistanceStatus(loginListener.id)).resolves.toEqual(
      received,
    );
    for (const change of [
      { code: "12345" },
      { code: "1234567" },
      { code: 123456 },
      { receivedAt: "not-an-instant" },
      { receivedAt: "2026-10-12T09:59:59Z" },
      { listeningAt: null },
      { id: "22222222-2222-4222-8222-222222222222" },
      { state: "HISTORICAL" },
    ]) {
      harness.respond({ ...received, ...change });
      await expect(
        api.loginAssistanceStatus(loginListener.id),
      ).rejects.toThrow();
    }
    harness.respond({ ...loginListener, code: "123456" });
    await expect(api.loginAssistanceStart()).rejects.toThrow();
    harness.respond({
      ...loginListener,
      expiresAt: loginListener.startedAt,
    });
    await expect(api.loginAssistanceStart()).rejects.toThrow();
  });
});

describe("Tauri API boundary", () => {
  beforeEach(async () => harness.prepare(api));

  it("validates autostart status and passes only a boolean to the fixed setter", async () => {
    invokeMock.mockResolvedValue({ supported: true, enabled: true });
    await expect(api.autostartStatus()).resolves.toEqual({
      supported: true,
      enabled: true,
    });
    expect(invokeMock).toHaveBeenCalledWith("autostart_status");
    await expect(api.setAutostart(true)).resolves.toEqual({
      supported: true,
      enabled: true,
    });
    expect(invokeMock).toHaveBeenCalledWith("autostart_set", { enabled: true });
    invokeMock.mockResolvedValue({ supported: true, enabled: "true" });
    await expect(api.autostartStatus()).rejects.toThrow();
  });

  it("requests only fixed personal statistics ranges with USD and UTC data", async () => {
    const summary = {
      eventCount: 1,
      totalTokens: 1,
      inputTokens: 1,
      cachedInputTokens: 0,
      cacheWriteInputTokens: 0,
      outputTokens: 0,
      reasoningOutputTokens: 0,
      breakdownEventCount: 1,
      costedEventCount: 1,
      estimatedCostUsd: 0.000000000001,
      cacheHitRatePercent: 0,
    };
    const data = {
      from: "2026-10-09T00:00:00Z",
      to: "2026-10-10T00:00:00Z",
      currency: "USD",
      timezone: "UTC",
      granularity: "HOUR",
      summary,
      trend: [],
    };
    invokeMock.mockResolvedValue(data);
    const requestStart = harness.requests.length;
    for (const days of [1, 7, 30] as const) {
      await expect(api.usageAnalytics(days)).resolves.toEqual(data);
      expect(
        harness.requestsFor("GET", "/api/v1/client/usage/analytics").at(-1)
          ?.params,
      ).toEqual({ days });
    }
    for (const invalid of [0, 2, 31, NaN]) {
      await expect(api.usageAnalytics(invalid as 1)).rejects.toThrow();
    }
    expect(harness.requests).toHaveLength(requestStart + 3);
    for (const invalid of [
      { currency: "CNY" },
      { timezone: "local" },
      { to: data.from },
      { summary: { ...summary, costedEventCount: 2 } },
      { summary: { ...summary, estimatedCostUsd: -1 } },
    ]) {
      invokeMock.mockResolvedValue({ ...data, ...invalid });
      await expect(api.usageAnalytics()).rejects.toThrow();
    }
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
    expect(
      harness.requestsFor("GET", "/api/v1/client/usage/events").at(-1)?.params,
    ).toEqual({
      page: 0,
      size: 10,
      includeMetadata: true,
      includeTokenBreakdown: true,
    });
    const requestCount = harness.requests.length;
    invokeMock.mockClear();
    await expect(api.usageEvents(-1, 10)).rejects.toThrow();
    await expect(api.usageEvents(0, 101)).rejects.toThrow();
    expect(harness.requests).toHaveLength(requestCount);
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

  it("keeps missing breakdown unknown and validates new USD and token fields", async () => {
    const row = {
      id: "breakdown",
      occurredAt: "2026-10-09T00:00:00Z",
      receivedAt: "2026-10-09T00:00:01Z",
      totalTokens: 1200,
      source: "TOKEN_USAGE_RECORD",
    };
    const result = {
      items: [row],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
    };
    invokeMock.mockResolvedValue(result);
    expect((await api.usageEvents()).items[0]).toMatchObject({
      inputTokens: null,
      outputTokens: null,
      estimatedCostUsd: null,
    });
    invokeMock.mockResolvedValue({
      ...result,
      items: [
        {
          ...row,
          inputTokens: 1000,
          cachedInputTokens: 300,
          cacheWriteInputTokens: 100,
          outputTokens: 200,
          reasoningOutputTokens: 150,
          estimatedCostUsd: 0.00325,
          costStatus: "PRICED",
        },
      ],
    });
    expect((await api.usageEvents()).items[0]).toMatchObject({
      inputTokens: 1000,
      cachedInputTokens: 300,
      reasoningOutputTokens: 150,
      estimatedCostUsd: 0.00325,
      costStatus: "PRICED",
    });
    for (const invalid of [
      { estimatedCostUsd: -1 },
      { outputTokens: -1 },
      { cachedInputTokens: 0.1 },
    ]) {
      invokeMock.mockResolvedValue({
        ...result,
        items: [{ ...row, ...invalid }],
      });
      await expect(api.usageEvents()).rejects.toThrow();
    }
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

  it("accepts only fixed CC Switch guard diagnostics", async () => {
    const data = {
      state: "READY",
      skippedLines: 0,
      entries: [
        {
          atEpochSeconds: 1,
          kind: "GUARD_TERMINATED",
          state: null,
          reportedEvents: 0,
          reportedTokens: 0,
          heartbeatSent: false,
          errorCode: null,
          ccSwitchGuard: "TERMINATED",
        },
      ],
    };
    invokeMock.mockResolvedValue(data);
    await expect(api.meterRuntimeLogs()).resolves.toEqual(data);
    invokeMock.mockResolvedValue({
      ...data,
      entries: [{ ...data.entries[0], ccSwitchGuard: "untrusted" }],
    });
    await expect(api.meterRuntimeLogs()).rejects.toThrow();
  });

  it("accepts only fixed native startup guard outcomes", async () => {
    invokeMock.mockResolvedValue({ state: "READY", ccSwitchTerminated: true });
    await expect(api.startupGateCheck()).resolves.toEqual({
      state: "READY",
      ccSwitchTerminated: true,
    });
    expect(invokeMock).toHaveBeenCalledWith("startup_gate_check");
    invokeMock.mockResolvedValue({
      state: "GUARD_ERROR",
      ccSwitchTerminated: false,
    });
    await expect(api.startupGuardStatus()).resolves.toEqual({
      state: "GUARD_ERROR",
      ccSwitchTerminated: false,
    });
    invokeMock.mockResolvedValue({
      state: "SKIPPED",
      ccSwitchTerminated: false,
    });
    await expect(api.startupGuardStatus()).rejects.toThrow();
  });

  it("validates installed and bundled statistics program versions", async () => {
    const version = {
      installedVersion: "0.1.0",
      bundledVersion: "0.1.1",
      needsUpgrade: true,
      status: "UPGRADE_REQUIRED",
    };
    invokeMock.mockResolvedValue(version);
    await expect(api.meterServiceVersion()).resolves.toEqual(version);
    expect(invokeMock).toHaveBeenCalledWith("meter_service_version");
    invokeMock.mockResolvedValue({ ...version, status: "arbitrary" });
    await expect(api.meterServiceVersion()).rejects.toThrow();
    invokeMock.mockResolvedValue({
      ...version,
      installedVersion: "x".repeat(65),
    });
    await expect(api.meterServiceVersion()).rejects.toThrow();
  });

  it("strips response metadata when saving the server address again", async () => {
    invokeMock.mockImplementation(async (command) => {
      if (command === "server_config_get") {
        return { url: "http://192.168.0.102:8080" };
      }
      if (command === "server_config_save") {
        return { url: "http://192.168.0.102:8080", meterSync: "UPDATED" };
      }
      return null;
    });

    await expect(api.serverConfig()).resolves.toMatchObject({
      url: "http://192.168.0.102:8080",
    });
    const saved = await api.saveServerConfig({
      url: "http://192.168.0.102:8080",
      meterSync: "UPDATED",
    });
    await api.saveServerConfig(saved);
    expect(invokeMock).toHaveBeenCalledWith("server_config_get");
    expect(invokeMock).toHaveBeenCalledWith("server_config_save", {
      config: { url: "http://192.168.0.102:8080" },
    });
    expect(
      invokeMock.mock.calls.filter(
        ([command]) => command === "server_config_save",
      ),
    ).toHaveLength(2);
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
    expect(
      harness.requestsFor("GET", "/api/v1/client/quota/overview").at(-1)
        ?.params,
    ).toEqual({ includeTeamMembers: true });
  });

  it("validates all current team members including zero usage and disabled members", async () => {
    const members = [
      {
        userId: "11111111-1111-4111-8111-111111111111",
        displayName: "Alice",
        status: "ACTIVE",
        fiveHourUsedTokens: 200,
        weeklyUsedTokens: 1000,
        totalUsedTokens: 5000,
        fiveHourTokenLimit: 1_000,
        weeklyTokenLimit: 10_000,
        fiveHourRemainingTokens: 800,
        weeklyRemainingTokens: 9_000,
        measuredAt: "2026-10-06T08:00:00Z",
      },
      {
        userId: "22222222-2222-4222-8222-222222222222",
        displayName: "Alice",
        status: "DISABLED",
        fiveHourUsedTokens: 0,
        weeklyUsedTokens: 0,
        totalUsedTokens: 0,
        fiveHourTokenLimit: null,
        weeklyTokenLimit: 0,
        fiveHourRemainingTokens: null,
        weeklyRemainingTokens: 0,
        measuredAt: null,
      },
    ];
    invokeMock.mockResolvedValue({ ...quotaOverview, teamMembers: members });
    expect((await api.quotaOverview()).teamMembers).toEqual(members);
    expect(
      harness.requestsFor("GET", "/api/v1/client/quota/overview").at(-1)
        ?.params,
    ).toEqual({ includeTeamMembers: true });
  });

  it("preserves older-server member data absence as null and an explicit empty list as empty", async () => {
    for (const payload of [
      quotaOverview,
      { ...quotaOverview, teamMembers: null },
    ]) {
      invokeMock.mockResolvedValue(payload);
      expect((await api.quotaOverview()).teamMembers).toBeNull();
    }
    invokeMock.mockResolvedValue({
      ...quotaOverview,
      team: null,
      teamMembers: [],
    });
    expect((await api.quotaOverview()).teamMembers).toEqual([]);
  });

  it.each([
    { userId: "not-a-uuid" },
    { status: "UNKNOWN" },
    { fiveHourUsedTokens: -1 },
    { weeklyUsedTokens: 0.5 },
    { totalUsedTokens: Number.MAX_SAFE_INTEGER + 1 },
    { fiveHourTokenLimit: -1 },
    { weeklyRemainingTokens: 0.5 },
    { fiveHourTokenLimit: null, fiveHourRemainingTokens: 1 },
    { fiveHourTokenLimit: 100, fiveHourRemainingTokens: null },
    { weeklyTokenLimit: 100, weeklyRemainingTokens: null },
    { fiveHourTokenLimit: 100, fiveHourRemainingTokens: 101 },
    { weeklyTokenLimit: 10, weeklyRemainingTokens: 11 },
    { measuredAt: "not-a-timestamp" },
  ])("rejects malformed team member usage: %j", async (invalid) => {
    invokeMock.mockResolvedValue({
      ...quotaOverview,
      teamMembers: [
        {
          userId: "11111111-1111-4111-8111-111111111111",
          displayName: "Alice",
          status: "ACTIVE",
          fiveHourUsedTokens: 0,
          weeklyUsedTokens: 0,
          totalUsedTokens: 0,
          fiveHourTokenLimit: null,
          weeklyTokenLimit: null,
          fiveHourRemainingTokens: null,
          weeklyRemainingTokens: null,
          measuredAt: null,
          ...invalid,
        },
      ],
    });
    await expect(api.quotaOverview()).rejects.toThrow();
  });

  it.each([
    "fiveHourTokenLimit",
    "weeklyTokenLimit",
    "fiveHourRemainingTokens",
    "weeklyRemainingTokens",
  ] as const)(
    "rejects a member with missing required personal quota field %s",
    async (field) => {
      const member = {
        userId: "11111111-1111-4111-8111-111111111111",
        displayName: "Alice",
        status: "ACTIVE",
        fiveHourUsedTokens: 0,
        weeklyUsedTokens: 0,
        totalUsedTokens: 0,
        fiveHourTokenLimit: null,
        weeklyTokenLimit: null,
        fiveHourRemainingTokens: null,
        weeklyRemainingTokens: null,
        measuredAt: null,
      };
      const { [field]: _missing, ...partialMember } = member;
      invokeMock.mockResolvedValue({
        ...quotaOverview,
        teamMembers: [partialMember],
      });
      await expect(api.quotaOverview()).rejects.toThrow();
    },
  );

  it("accepts a truthful absence of reported usage before the first event", async () => {
    invokeMock.mockResolvedValue({
      ...quotaOverview,
      usage: { ...quotaOverview.usage, measuredAt: null },
    });

    await expect(api.quotaOverview()).resolves.toMatchObject({
      usage: { measuredAt: null, fiveHourUsedTokens: 200 },
    });
  });

  it("reads the fixed native meter status DTO", async () => {
    const status = {
      state: "NOT_INSTALLED",
      lastScanEpochSeconds: null,
      lastReportEpochSeconds: null,
      reportedEvents: 0,
      errorCode: null,
      version: null,
    };
    invokeMock.mockResolvedValue(status);

    await expect(api.usageStatus()).resolves.toEqual(status);
    expect(invokeMock).toHaveBeenCalledWith("meter_status");
  });

  it("accepts historical review warnings independently of a healthy meter status", async () => {
    const status = {
      state: "UP_TO_DATE",
      lastScanEpochSeconds: 1792909500,
      lastReportEpochSeconds: 1792909490,
      reportedEvents: 0,
      errorCode: null,
      version: "0.2.7",
      warningCode: "METER_EVENT_OUTSIDE_REPORT_WINDOW",
      deferredEvents: 1194,
    };
    invokeMock.mockResolvedValue(status);
    await expect(api.usageStatus()).resolves.toEqual(status);
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

  const unlimitedOverview = {
    ...quotaOverview,
    teamQuota: {
      id: "team-1",
      name: "Design",
      status: "ACTIVE",
      memberCount: 1,
      fiveHourTokenLimit: null,
      weeklyTokenLimit: null,
      totalTokenLimit: 7_600_000_000,
      fiveHourUsedTokens: 200,
      weeklyUsedTokens: 1_000,
      totalUsedTokens: 5_000,
      fiveHourRemainingTokens: null,
      weeklyRemainingTokens: null,
      totalRemainingTokens: 7_599_995_000,
      measuredAt: "2026-10-06T08:00:00Z",
    },
    teamMembers: [
      {
        userId: "11111111-1111-4111-8111-111111111111",
        displayName: "Alice",
        status: "ACTIVE",
        fiveHourTokenLimit: null,
        weeklyTokenLimit: 630_000_000,
        fiveHourUsedTokens: 200,
        weeklyUsedTokens: 1_000,
        totalUsedTokens: 5_000,
        fiveHourRemainingTokens: null,
        weeklyRemainingTokens: 629_999_000,
        measuredAt: "2026-10-06T08:00:00Z",
      },
    ],
    policy: {
      fiveHourTokenLimit: null,
      weeklyTokenLimit: 630_000_000,
      fiveHourSource: "UNCONFIGURED",
      weeklySource: "USER",
    },
    usage: {
      ...quotaOverview.usage,
      fiveHourRemainingTokens: null,
      weeklyRemainingTokens: 629_999_000,
    },
  };

  it.each([
    ["team windows with a capped personal week", unlimitedOverview],
    [
      "all personal and team windows",
      {
        ...unlimitedOverview,
        policy: {
          fiveHourTokenLimit: null,
          weeklyTokenLimit: null,
          fiveHourSource: "UNCONFIGURED",
          weeklySource: "UNCONFIGURED",
        },
        usage: { ...unlimitedOverview.usage, weeklyRemainingTokens: null },
      },
    ],
    [
      "personal windows without a team",
      {
        ...unlimitedOverview,
        team: null,
        teamQuota: null,
        teamMembers: [],
        policy: {
          fiveHourTokenLimit: null,
          weeklyTokenLimit: null,
          fiveHourSource: "UNCONFIGURED",
          weeklySource: "UNCONFIGURED",
        },
        usage: { ...unlimitedOverview.usage, weeklyRemainingTokens: null },
      },
    ],
  ])(
    "accepts unlimited %s through the actual quota HTTP boundary",
    async (_label, response) => {
      harness.respond(response);
      await expect(api.quotaOverview()).resolves.toMatchObject({
        allowed: true,
        blockReason: null,
        policy: { fiveHourTokenLimit: null },
        usage: { fiveHourUsedTokens: 200, fiveHourRemainingTokens: null },
      });
      expect(
        harness.requestsFor("GET", "/api/v1/client/quota/overview").at(-1)
          ?.params,
      ).toEqual({ includeTeamMembers: true });
    },
  );

  it.each([
    ['fiveHour', '0', '0'],
    ['weekly', '1000', '1000'],
    ['total', '5000', '5000'],
  ])(
    "rejects an allowed response with an exhausted USD team window %s",
    async (key, limit, used) => {
      harness.respond({
        ...unlimitedOverview,
        billing: usdFixture({ team: { ...moneyScope(), [key]: moneyWindow(limit, used, '0') } }),
      });
      await expect(api.quotaOverview()).rejects.toThrow();
    },
  );

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
      allowed: true,
      blockReason: null,
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

  it("clears the native vault and sends logout credentials only in the HTTPS body", async () => {
    invokeMock.mockResolvedValue(undefined);

    await api.systemLogout();

    const request = harness.requestsFor("POST", "/api/v1/auth/logout").at(-1)!;
    expect(request.url).not.toMatch(/token/i);
    expect(request.headers).toMatchObject({
      Authorization: "Bearer access-test-token",
    });
    expect(JSON.parse(String(request.data))).toEqual({
      refreshToken: "refresh-test-token",
    });
    expect(invokeMock).toHaveBeenCalledWith("session_vault_clear");
    expect(
      invokeMock.mock.calls.some(([command]) => command === "system_logout"),
    ).toBe(false);
  });

  it("sends account credentials in the login body and keeps tokens out of the URL", async () => {
    await expect(
      api.systemLogin("alice@example.com", "password"),
    ).resolves.toEqual({
      authenticated: true,
      user: expect.objectContaining({ email: "alice@example.com" }),
    });
    const request = harness.requestsFor("POST", "/api/v1/auth/login").at(-1)!;
    expect(JSON.parse(String(request.data))).toEqual({
      email: "alice@example.com",
      password: "password",
    });
    expect(request.url).not.toMatch(/password|token/i);
    expect(request.headers).not.toHaveProperty("Authorization");
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
    const safeNetworkMessage =
      "无法连接中央服务器，请检查地址、网络及服务器是否允许桌面访问。";
    expect(
      getApiErrorMessage(
        new Error("request failed at https://control.example/token=secret"),
      ),
    ).toBe(safeNetworkMessage);
    expect(
      getApiErrorMessage(
        "request failed at https://control.example/token=secret",
      ),
    ).toBe(safeNetworkMessage);
  });

  it("shows only allow-listed Tauri string errors", () => {
    expect(getApiErrorMessage("无法安全保存登录状态")).toBe(
      "本机加密登录存储不可用，请检查用户目录权限后重试。",
    );
    expect(getApiErrorMessage("本地服务暂时不可用，请稍后重试。")).toBe(
      "无法连接中央服务器，请检查地址、网络及服务器是否允许桌面访问。",
    );
  });

  it.each([
    {
      message: "服务器地址未保存。已取消管理员授权，服务器地址未保存。",
      expected: "已取消管理员授权，服务器地址未保存。",
    },
    {
      message: "服务器地址未保存。统计程序地址更新失败，服务器地址未保存。",
      expected: "统计程序地址更新失败，服务器地址未保存。",
    },
    {
      message: "服务器地址未保存。统计程序地址更新超时，服务器地址未保存。",
      expected: "统计程序地址更新超时，服务器地址未保存。",
    },
    {
      message: "服务器地址未保存。无法读取 Windows 统计服务状态。",
      expected: "无法读取 Windows 统计服务状态，请稍后重试。",
    },
    {
      message: "服务器地址未保存。统计程序地址更新脚本未随安装包提供。",
      expected: "安装包缺少统计程序地址更新脚本，请重新运行完整安装器。",
    },
    {
      message: "无法保存服务器设置，请检查目录权限后重试。",
      expected: "无法保存服务器设置，请检查目录权限后重试。",
    },
    {
      message: "无法同步统计程序地址，请重启客户端后重试。",
      expected: "无法同步统计程序地址，请重启客户端后重试。",
    },
    {
      message: "无法保存服务器地址，请重试。",
      expected: "无法保存服务器地址，请重试。",
    },
  ])(
    "maps server configuration failures to fixed safe guidance: $message",
    ({ message, expected }) => {
      expect(getApiErrorMessage(message)).toBe(expected);
      expect(getApiErrorMessage(new Error(message))).toBe(expected);
      expect(getApiErrorMessage(`${message} token=secret`)).toBe(
        message.startsWith("服务器地址未保存。")
          ? expected
          : "无法连接中央服务器，请检查地址、网络及服务器是否允许桌面访问。",
      );
    },
  );

  it("shows the precise autostart ownership warning without exposing arbitrary task details", () => {
    const warning =
      "开机自启动任务与当前用户或安装目录不匹配，已阻止自动修改，请检查旧版本残留任务。";
    expect(getApiErrorMessage(warning)).toBe(warning);
    expect(getApiErrorMessage(new Error(warning))).toBe(warning);
    expect(getApiErrorMessage(`${warning} token=secret`)).toBe(
      "无法连接中央服务器，请检查地址、网络及服务器是否允许桌面访问。",
    );
  });

  it.each([
    "无法读取 Windows 开机自启动状态，请检查当前用户的启动应用设置。",
    "Windows 启动应用的审批数据无法识别，已保留原设置，请在任务管理器中检查。",
    "开机自启动项与当前安装目录不匹配，已阻止自动修改，请检查旧版本残留启动项。",
    "客户端安装路径无法用于 Windows 启动应用，请安装到较短的本地路径后重试。",
    "开机自启动迁移未完成且恢复失败，请在任务管理器和任务计划程序中检查本软件的启动项。",
    "普通启动项已保存，但无法确认旧启动任务已清理，请重新启动客户端后重试。",
    "Windows 启动应用设置在操作期间发生变化，已停止修改，请重新读取后重试。",
  ])("preserves the bounded startup migration error: %s", (message) => {
    expect(getApiErrorMessage(message)).toBe(message);
    expect(getApiErrorMessage(new Error(message))).toBe(message);
    expect(getApiErrorMessage(`${message} token=secret`)).toBe(
      "无法连接中央服务器，请检查地址、网络及服务器是否允许桌面访问。",
    );
  });
});
