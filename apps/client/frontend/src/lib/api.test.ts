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
  beforeEach(() => invokeMock.mockReset());

  it("restores current only through the fixed authenticated command and rejects secret extras", async () => {
    invokeMock.mockResolvedValue({ listener: null });
    expect(await api.loginAssistanceCurrent()).toEqual({ listener: null });
    expect(invokeMock).toHaveBeenLastCalledWith("login_assistance_current");
    invokeMock.mockResolvedValue({ listener: loginListener });
    expect(await api.loginAssistanceCurrent()).toEqual({
      listener: loginListener,
    });
    for (const result of [
      { listener: loginListener, accessToken: "synthetic" },
      { listener: { ...loginListener, password: "synthetic" } },
    ]) {
      invokeMock.mockResolvedValue(result);
      await expect(api.loginAssistanceCurrent()).rejects.toThrow();
    }
  });

  it("uses only the four fixed commands with validated listener IDs", async () => {
    invokeMock.mockResolvedValue(loginAssistanceInfo);
    await expect(api.loginAssistanceInfo()).resolves.toEqual(
      loginAssistanceInfo,
    );
    expect(invokeMock).toHaveBeenLastCalledWith("login_assistance_info");
    invokeMock.mockResolvedValue(loginListener);
    await expect(api.loginAssistanceStart()).resolves.toEqual(loginListener);
    expect(invokeMock).toHaveBeenLastCalledWith("login_assistance_start");
    await expect(api.loginAssistanceStatus(loginListener.id)).resolves.toEqual(
      loginListener,
    );
    expect(invokeMock).toHaveBeenLastCalledWith("login_assistance_status", {
      listenerId: loginListener.id,
    });
    await expect(api.loginAssistanceCancel(loginListener.id)).resolves.toEqual(
      loginListener,
    );
    expect(invokeMock).toHaveBeenLastCalledWith("login_assistance_cancel", {
      listenerId: loginListener.id,
    });
    invokeMock.mockClear();
    for (const id of [
      "",
      "someone-else",
      "https://mail.example.com/listen",
      `${loginListener.id}/other`,
    ]) {
      await expect(api.loginAssistanceStatus(id)).rejects.toThrow();
      await expect(api.loginAssistanceCancel(id)).rejects.toThrow();
    }
    expect(invokeMock).not.toHaveBeenCalled();
  });

  it("rejects additional mailbox credentials instead of retaining or silently accepting them", async () => {
    for (const extra of [
      { password: "synthetic-secret" },
      { imapPassword: "synthetic-secret" },
      { accessToken: "synthetic-token" },
      { serverUrl: "https://mail.example.com" },
    ]) {
      invokeMock.mockResolvedValue({ ...loginAssistanceInfo, ...extra });
      await expect(api.loginAssistanceInfo()).rejects.toThrow();
      invokeMock.mockResolvedValue({ ...loginListener, ...extra });
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
    invokeMock.mockResolvedValue(received);
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
      invokeMock.mockResolvedValue({ ...received, ...change });
      await expect(
        api.loginAssistanceStatus(loginListener.id),
      ).rejects.toThrow();
    }
    invokeMock.mockResolvedValue({ ...loginListener, code: "123456" });
    await expect(api.loginAssistanceStart()).rejects.toThrow();
    invokeMock.mockResolvedValue({
      ...loginListener,
      expiresAt: loginListener.startedAt,
    });
    await expect(api.loginAssistanceStart()).rejects.toThrow();
  });
});

describe("Tauri API boundary", () => {
  beforeEach(() => {
    invokeMock.mockReset();
  });

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
    for (const days of [1, 7, 30] as const) {
      await expect(api.usageAnalytics(days)).resolves.toEqual(data);
      expect(invokeMock).toHaveBeenLastCalledWith("usage_analytics", { days });
    }
    invokeMock.mockClear();
    for (const invalid of [0, 2, 31, NaN]) {
      await expect(api.usageAnalytics(invalid as 1)).rejects.toThrow();
    }
    expect(invokeMock).not.toHaveBeenCalled();
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

  it("accepts first-run service installation and server validation outcomes", async () => {
    invokeMock
      .mockResolvedValueOnce("INSTALLED")
      .mockResolvedValueOnce("SERVER_UNAVAILABLE");
    await expect(api.retryMeterService()).resolves.toBe("INSTALLED");
    await expect(api.retryMeterService()).resolves.toBe("SERVER_UNAVAILABLE");
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
    const sessionStorageMessage =
      "本机登录存储不可用，请关闭其他客户端并检查用户目录权限后重试。";
    expect(getApiErrorMessage(sessionStorageMessage)).toBe(
      sessionStorageMessage,
    );
    expect(getApiErrorMessage("本地服务暂时不可用，请稍后重试。")).toBe(
      "本地服务暂时不可用，请稍后重试。",
    );
  });

  it("shows the precise autostart ownership warning without exposing arbitrary task details", () => {
    const warning =
      "开机自启动任务与当前用户或安装目录不匹配，已阻止自动修改，请检查旧版本残留任务。";
    expect(getApiErrorMessage(warning)).toBe(warning);
    expect(getApiErrorMessage(new Error(warning))).toBe(warning);
    expect(getApiErrorMessage(`${warning} token=secret`)).toBe(
      "暂时无法连接本地服务，请确认应用仍在运行。",
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
      "暂时无法连接本地服务，请确认应用仍在运行。",
    );
  });
});
