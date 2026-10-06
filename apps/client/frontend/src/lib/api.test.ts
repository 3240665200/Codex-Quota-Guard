import { beforeEach, describe, expect, it, vi } from "vitest";

import { invoke } from "@tauri-apps/api/core";

import { api, getApiErrorMessage } from "@/lib/api";

const invokeMock = vi.mocked(invoke);

vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn(),
}));

const quotaOverview = {
  assigned: true,
  user: {
    id: "user-1",
    email: "alice@example.com",
    displayName: "Alice",
    role: "USER",
    status: "ACTIVE",
  },
  codexAccount: {
    id: "codex-1",
    displayName: "Managed Codex",
    status: "ACTIVE",
    updatedAt: "2026-09-21T08:00:00Z",
  },
  team: { id: "team-1", name: "Design" },
  policy: {
    fiveHourLimitPercent: 80,
    weeklyLimitPercent: 90,
    fiveHourSource: "TEAM",
    weeklySource: "DEFAULT",
  },
  usage: {
    measurementStatus: "CURRENT",
    fiveHourUsedPercent: 20,
    weeklyUsedPercent: 10,
    fiveHourRemainingPercent: 80,
    weeklyRemainingPercent: 90,
    fiveHourResetsAt: "2026-09-21T12:00:00Z",
    weeklyResetsAt: "2026-09-28T08:00:00Z",
    measuredAt: "2026-09-21T08:00:00Z",
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

  it("only calls the fixed personal quota command and validates measured values", async () => {
    invokeMock.mockResolvedValue(quotaOverview);

    await expect(api.quotaOverview()).resolves.toMatchObject({
      assigned: true,
      team: { name: "Design" },
      usage: { measurementStatus: "CURRENT", fiveHourRemainingPercent: 80 },
    });
    expect(invokeMock).toHaveBeenCalledWith("quota_overview");
  });

  it("preserves unmeasured quota values as null instead of coercing them to zero", async () => {
    invokeMock.mockResolvedValue({
      ...quotaOverview,
      usage: {
        measurementStatus: "NOT_MEASURED",
        fiveHourUsedPercent: null,
        weeklyUsedPercent: null,
        fiveHourRemainingPercent: null,
        weeklyRemainingPercent: null,
        fiveHourResetsAt: null,
        weeklyResetsAt: null,
        measuredAt: null,
      },
      allowed: false,
      blockReason: "USAGE_NOT_MEASURED",
    });

    await expect(api.quotaOverview()).resolves.toMatchObject({
      usage: { measurementStatus: "NOT_MEASURED", fiveHourUsedPercent: null },
    });
  });

  it("fails closed when an unmeasured response claims usable quota or supplies numbers", async () => {
    invokeMock.mockResolvedValue({
      ...quotaOverview,
      usage: {
        measurementStatus: "NOT_MEASURED",
        fiveHourUsedPercent: 0,
        weeklyUsedPercent: null,
        fiveHourRemainingPercent: null,
        weeklyRemainingPercent: null,
        fiveHourResetsAt: null,
        weeklyResetsAt: null,
        measuredAt: null,
      },
      allowed: true,
      blockReason: null,
    });

    await expect(api.quotaOverview()).rejects.toThrow();
  });

  it("fails closed when a current response omits any required usage value", async () => {
    invokeMock.mockResolvedValue({
      ...quotaOverview,
      usage: { ...quotaOverview.usage, weeklyRemainingPercent: null },
    });

    await expect(api.quotaOverview()).rejects.toThrow();
  });

  it("rejects malformed data before it reaches the dashboard", async () => {
    invokeMock.mockResolvedValue({ ...quotaOverview, codexAccount: null });

    await expect(api.quotaOverview()).rejects.toThrow();
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
    expect(getApiErrorMessage("邮箱或密码不正确，请检查后重试。")).toBe(
      "邮箱或密码不正确，请检查后重试。",
    );
    expect(getApiErrorMessage("本地服务暂时不可用，请稍后重试。")).toBe(
      "本地服务暂时不可用，请稍后重试。",
    );
  });
});
