import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { invoke } from "@tauri-apps/api/core";
import { Dashboard } from "@/components/dashboard";
import i18n from "@/lib/i18n";
import { api, bindingRequiredMessage } from "@/lib/api";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));
const mock = vi.mocked(invoke);
const session = {
  authenticated: true,
  user: {
    id: "user-1",
    email: "alice@example.com",
    displayName: "Alice",
    role: "USER",
    status: "ACTIVE",
  },
};
const bindingId = "11111111-1111-4111-8111-111111111111";
const binding = {
  id: bindingId,
  userId: bindingId,
  userDisplayName: "Alice",
  userEmail: "alice@example.com",
  deviceId: bindingId,
  enrollmentId: bindingId,
  officialAccountId: bindingId,
  sessionId: "session",
  status: "ACTIVE",
  matchMethod: "SERIAL_LOGIN_DIFF",
  verificationLevel: "USER_CONFIRMED_INFERENCE",
  createdAt: "2026-10-15T10:00:00Z",
  endedAt: null,
};
let access: unknown;
let failAccess = false;
function show(role = "USER") {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const result = render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={client}>
        <Dashboard
          session={{ ...session, user: { ...session.user, role } }}
          onLogout={vi.fn()}
        />
      </QueryClientProvider>
    </I18nextProvider>,
  );
  return { ...result, client };
}
describe("device binding access gate", () => {
  beforeEach(() => {
    access = {
      allowed: false,
      bindingId: null,
      reason: "SESSION_BINDING_REQUIRED",
    };
    failAccess = false;
    mock.mockReset();
    mock.mockImplementation(async (command) => {
      if (command === "system_access") {
        if (failAccess) throw new Error("offline");
        return access;
      }
      if (command === "session_binding_current")
        return { attempt: null, binding: null };
      if (command === "login_assistance_info")
        return {
          available: false,
          reasonCode: "NO_TEAM",
          officialAccountId: null,
          accountLabel: null,
          recipientEmail: null,
        };
      if (command === "official_login_helper_jobs") return [];
      if (command === "official_login_helper_status")
        return {
          jobId: null,
          state: "IDLE",
          errorCode: null,
          accountName: null,
        };
      throw new Error("unsupported");
    });
  });
  it("starts closed and renders only assistance while protected menus and requests stay blocked", async () => {
    show();
    expect(screen.getByRole("button", { name: "额度总览" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "设置" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "设置" }));
    await screen.findByRole("heading", { name: "监听登录邮件" });
    expect(screen.queryByRole("heading", { name: "启动设置" })).toBeNull();
    expect(
      mock.mock.calls.some(([name]) =>
        ["quota_overview", "usage_status", "codex_rate_limits"].includes(name),
      ),
    ).toBe(false);
  });
  it("makes the admin official helper available before binding", async () => {
    show("ADMIN");
    await screen.findByRole("heading", { name: "官方登录助手" });
    expect(screen.getByRole("button", { name: "设置" })).toBeDisabled();
  });
  it("locks the current page immediately when access is revoked or cannot be checked", async () => {
    access = { allowed: true, bindingId, reason: null };
    const { client } = show();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "设置" })).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("button", { name: "设置" }));
    await screen.findByRole("heading", { name: "启动设置" });
    failAccess = true;
    await client.invalidateQueries({ queryKey: ["client-access"] });
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "设置" })).toBeDisabled(),
    );
    expect(screen.queryByRole("heading", { name: "启动设置" })).toBeNull();
    expect(screen.getByRole("heading", { name: "监听登录邮件" })).toBeVisible();
  });
  it("does not trust an ACTIVE binding projection when central access denies it", async () => {
    access = {
      allowed: false,
      bindingId: null,
      reason: "CLIENT_BINDING_INVALID",
    };
    const original = mock.getMockImplementation()!;
    mock.mockImplementation(async (command, args) =>
      command === "session_binding_current"
        ? { attempt: null, binding }
        : original(command, args),
    );
    show();
    await screen.findByRole("heading", { name: "监听登录邮件" });
    expect(screen.getByRole("button", { name: "额度总览" })).toBeDisabled();
    expect(screen.queryByRole("heading", { name: "官方登录助手" })).toBeNull();
  });
  it("rechecks central access after confirmation and keeps menus locked while it is still denied", async () => {
    const original = mock.getMockImplementation()!;
    const candidate = {
      id: bindingId,
      deviceId: bindingId,
      enrollmentId: bindingId,
      officialAccountId: bindingId,
      status: "AWAITING_CONFIRMATION",
      queuePosition: 0,
      loginAllowed: false,
      candidateSessionId: "session",
      candidateDisplayName: "This login",
      candidatePlatform: "Windows",
      expiresAt: new Date(Date.now() + 60000).toISOString(),
      errorCode: null,
      quarantined: false,
      queueState: "ACTIVE",
      retryAt: null,
      binding: null,
    };
    mock.mockImplementation(async (command, args) => {
      if (command === "session_binding_current")
        return { attempt: candidate, binding: null };
      if (command === "session_binding_confirm")
        return { ...candidate, status: "BOUND", binding };
      return original(command, args);
    });
    show();
    fireEvent.click(
      await screen.findByRole("button", { name: "确认绑定此会话" }),
    );
    await waitFor(() =>
      expect(
        mock.mock.calls.filter(([name]) => name === "system_access").length,
      ).toBeGreaterThan(1),
    );
    expect(screen.getByRole("button", { name: "设置" })).toBeDisabled();
    access = { allowed: true, bindingId, reason: null };
    fireEvent.click(screen.getByRole("button", { name: "重新核对绑定" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "设置" })).toBeEnabled(),
    );
  });
  it("immediately retreats on protected binding_required while preserving the product login", async () => {
    access = { allowed: true, bindingId, reason: null };
    show();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "设置" })).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("button", { name: "设置" }));
    await screen.findByRole("heading", { name: "启动设置" });
    const original = mock.getMockImplementation()!;
    mock.mockImplementation(async (command, args) => {
      if (command === "system_access") return new Promise(() => {});
      if (command === "usage_events") throw bindingRequiredMessage;
      return original(command, args);
    });
    await act(async () => {
      await expect(api.usageEvents()).rejects.toBe(bindingRequiredMessage);
    });
    expect(screen.getByRole("button", { name: "设置" })).toBeDisabled();
    expect(screen.queryByRole("heading", { name: "启动设置" })).toBeNull();
    expect(mock.mock.calls.some(([name]) => name === "system_logout")).toBe(
      false,
    );
  });
  it("rechecks from a closed state on re-entry and discards the previous page", async () => {
    access = { allowed: true, bindingId, reason: null };
    show();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "设置" })).toBeEnabled(),
    );
    fireEvent.click(screen.getByRole("button", { name: "设置" }));
    await screen.findByRole("heading", { name: "启动设置" });
    const original = mock.getMockImplementation()!;
    mock.mockImplementation(async (command, args) =>
      command === "system_access"
        ? new Promise(() => {})
        : original(command, args),
    );
    act(() => window.dispatchEvent(new Event("focus")));
    expect(screen.getByRole("button", { name: "设置" })).toBeDisabled();
    expect(screen.queryByRole("heading", { name: "启动设置" })).toBeNull();
  });
  it("expires a previously valid permission when the periodic check hangs", async () => {
    access = { allowed: true, bindingId, reason: null };
    show();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "设置" })).toBeEnabled(),
    );
    const clock = vi.spyOn(Date, "now").mockReturnValue(Date.now() + 11_000);
    const original = mock.getMockImplementation()!;
    mock.mockImplementation(async (command, args) =>
      command === "system_access"
        ? new Promise(() => {})
        : original(command, args),
    );
    try {
      await waitFor(() =>
        expect(screen.getByRole("button", { name: "设置" })).toBeDisabled(),
      );
    } finally {
      clock.mockRestore();
    }
  });
});
vi.mock("@/lib/assistance-events", () => ({
  subscribeToAssistanceEvents: () => () => {},
}));
