import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { invoke } from "@tauri-apps/api/core";
import { Dashboard } from "@/components/dashboard";
import i18n from "@/lib/i18n";
import { api, bindingRequiredMessage } from "@/lib/api";

const channels = vi.hoisted(
  () => [] as { onmessage: (event: unknown) => void }[],
);
vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn(),
  Channel: class {
    onmessage = (_event: unknown) => {};
    constructor() {
      channels.push(this);
    }
  },
}));
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
const boundAttempt = () => ({
  id: bindingId,
  deviceId: bindingId,
  enrollmentId: bindingId,
  officialAccountId: bindingId,
  status: "BOUND",
  queuePosition: 0,
  loginAllowed: false,
  candidateSessionId: "session",
  candidateDisplayName: "Alice",
  candidatePlatform: "Windows",
  expiresAt: new Date(Date.now() + 60_000).toISOString(),
  errorCode: null,
  quarantined: false,
  queueState: "IDLE",
  retryAt: null,
  binding,
});
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
  afterEach(async () => {
    cleanup();
    await Promise.resolve();
    await Promise.resolve();
  });
  beforeEach(() => {
    channels.length = 0;
    access = {
      allowed: false,
      bindingId: null,
      reason: "SESSION_BINDING_REQUIRED",
    };
    failAccess = false;
    mock.mockReset();
    mock.mockImplementation(async (command) => {
      if (
        command === "login_assistance_events_start" ||
        command === "login_assistance_events_stop"
      )
        return;
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
      if (command === "autostart_status")
        return { supported: true, enabled: false };
      throw new Error("unsupported");
    });
  });
  it("keeps mail and session binding available to an unbound admin without the retired helper", async () => {
    show("ADMIN");
    await screen.findByRole("heading", { name: "监听登录邮件" });
    expect(mock).toHaveBeenCalledWith("session_binding_current");
    expect(mock).toHaveBeenCalledWith("login_assistance_info");
    expect(screen.getByRole("button", { name: "设置" })).toBeDisabled();
    expect(screen.queryByRole("region", { name: "官方登录助手" })).toBeNull();
    expect(
      mock.mock.calls.some(([name]) =>
        name.startsWith("official_login_helper_"),
      ),
    ).toBe(false);
  });
  it("does not reset an already verified gate when opening its saved binding record", async () => {
    access = { allowed: true, bindingId, reason: null };
    const original = mock.getMockImplementation()!;
    mock.mockImplementation(async (command, args) =>
      command === "session_binding_current"
        ? { attempt: boundAttempt(), binding }
        : original(command, args),
    );
    show();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "设置" })).toBeEnabled(),
    );
    const accessReads = mock.mock.calls.filter(
      ([name]) => name === "system_access",
    ).length;
    fireEvent.click(screen.getByRole("button", { name: "登录辅助" }));
    await screen.findByText(/绑定账户：Alice/);
    expect(
      mock.mock.calls.filter(([name]) => name === "system_access"),
    ).toHaveLength(accessReads);
    expect(screen.getByRole("button", { name: "设置" })).toBeEnabled();
    await act(async () => channels[0].onmessage({ type: "state_changed" }));
    await waitFor(() =>
      expect(
        mock.mock.calls.filter(([name]) => name === "session_binding_current")
          .length,
      ).toBeGreaterThan(1),
    );
    expect(
      mock.mock.calls.filter(([name]) => name === "system_access"),
    ).toHaveLength(accessReads);
  });
  it("keeps one native assistance stream across workspace navigation and stops it on logout unmount", async () => {
    access = { allowed: true, bindingId, reason: null };
    const view = show();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "设置" })).toBeEnabled(),
    );
    expect(
      mock.mock.calls.filter(
        ([name]) => name === "login_assistance_events_start",
      ),
    ).toHaveLength(1);
    for (let visit = 0; visit < 3; visit += 1) {
      fireEvent.click(screen.getByRole("button", { name: "登录辅助" }));
      await screen.findByRole("heading", { name: "监听登录邮件" });
      fireEvent.click(screen.getByRole("button", { name: "设置" }));
      await screen.findByRole("heading", { name: "启动设置" });
    }
    expect(
      mock.mock.calls.filter(
        ([name]) => name === "login_assistance_events_start",
      ),
    ).toHaveLength(1);
    expect(
      mock.mock.calls.filter(
        ([name]) => name === "login_assistance_events_stop",
      ),
    ).toHaveLength(0);
    view.unmount();
    await waitFor(() =>
      expect(
        mock.mock.calls.filter(
          ([name]) => name === "login_assistance_events_stop",
        ),
      ).toHaveLength(1),
    );
    expect(
      mock.mock.calls.some(
        ([name]) =>
          name === "login_assistance_cancel" ||
          name === "session_binding_cancel",
      ),
    ).toBe(false);
  });
  it.each(["transport", "schema"])(
    "explains a %s gate failure without denying the saved record or leaking raw errors",
    async (failure) => {
      access = { allowed: true, bindingId, reason: null };
      let failed = false;
      const original = mock.getMockImplementation()!;
      mock.mockImplementation(async (command, args) => {
        if (command === "session_binding_current")
          return { attempt: boundAttempt(), binding };
        if (command === "system_access" && failed) {
          if (failure === "schema")
            return { private: "private-upstream-detail" };
          throw new Error("private-upstream-detail");
        }
        return original(command, args);
      });
      const { client } = show();
      await waitFor(() =>
        expect(screen.getByRole("button", { name: "设置" })).toBeEnabled(),
      );
      fireEvent.click(screen.getByRole("button", { name: "登录辅助" }));
      await screen.findByText("本设备官方会话绑定记录已保存。");
      failed = true;
      await act(async () => {
        await client.invalidateQueries({ queryKey: ["client-access"] });
      });
      await screen.findByText("绑定状态核验失败");
      expect(
        screen.getByText(/已保存的绑定记录不代表当前访问权限已核验通过/),
      ).toBeVisible();
      expect(
        screen.getByText(
          failure === "schema"
            ? "本地接口返回的数据格式不完整，请重启应用后重试。"
            : "暂时无法连接本地服务，请确认应用仍在运行。",
        ),
      ).toBeVisible();
      expect(screen.getByText("本设备官方会话绑定记录已保存。")).toBeVisible();
      expect(screen.getByRole("button", { name: "设置" })).toBeDisabled();
      expect(screen.queryByText(/private-upstream-detail/)).toBeNull();
    },
  );
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
  it("rechecks central access after the server completes binding and keeps menus locked while it is still denied", async () => {
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
    let currentCandidate: unknown = candidate;
    mock.mockImplementation(async (command, args) => {
      if (command === "session_binding_current")
        return { attempt: currentCandidate, binding: null };
      return original(command, args);
    });
    show();
    await screen.findByText("This login");
    currentCandidate = { ...candidate, status: "BOUND", binding };
    act(() =>
      window.dispatchEvent(new Event("quota-guard:assistance-refresh")),
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
  it.each(["focus", "visibilitychange"])(
    "keeps the authorized page mounted but inert while %s rechecks access",
    async (eventName) => {
      access = { allowed: true, bindingId, reason: null };
      show();
      await waitFor(() =>
        expect(screen.getByRole("button", { name: "设置" })).toBeEnabled(),
      );
      fireEvent.click(screen.getByRole("button", { name: "设置" }));
      const heading = await screen.findByRole("heading", { name: "启动设置" });
      const toggle = await screen.findByRole("switch", { name: "开机自启动" });
      await waitFor(() => expect(toggle).toBeEnabled());
      let resolveAccess!: (value: unknown) => void;
      const pendingAccess = new Promise((resolve) => {
        resolveAccess = resolve;
      });
      const original = mock.getMockImplementation()!;
      mock.mockImplementation(async (command, args) =>
        command === "system_access" ? pendingAccess : original(command, args),
      );
      const target = eventName === "focus" ? window : document;
      act(() => {
        target.dispatchEvent(new Event(eventName));
        target.dispatchEvent(new Event(eventName));
      });
      expect(screen.getByRole("heading", { name: "启动设置" })).toBe(heading);
      expect(screen.getByRole("button", { name: "设置" })).toBeDisabled();
      expect(screen.getByRole("region", { name: "设置内容" })).toHaveAttribute(
        "aria-busy",
        "true",
      );
      expect(toggle.closest("[inert]")).not.toBeNull();
      // The settings read also refreshes on focus. Let it finish so the access
      // boundary, rather than that independent request, must block the action.
      await waitFor(() => expect(toggle).toBeEnabled());
      fireEvent.click(toggle);
      fireEvent.click(screen.getByRole("button", { name: "登录辅助" }));
      expect(mock.mock.calls.some(([name]) => name === "autostart_set")).toBe(
        false,
      );
      expect(screen.getByRole("heading", { name: "启动设置" })).toBe(heading);

      await act(async () => resolveAccess(access));
      await waitFor(() =>
        expect(screen.getByRole("button", { name: "设置" })).toBeEnabled(),
      );
      expect(screen.getByRole("heading", { name: "启动设置" })).toBe(heading);
      expect(toggle.closest("[inert]")).toBeNull();
    },
  );
  it.each(["denied", "failed"])(
    "discards the preserved page when the return check is %s",
    async (outcome) => {
      access = { allowed: true, bindingId, reason: null };
      show();
      await waitFor(() =>
        expect(screen.getByRole("button", { name: "设置" })).toBeEnabled(),
      );
      fireEvent.click(screen.getByRole("button", { name: "设置" }));
      const heading = await screen.findByRole("heading", { name: "启动设置" });
      let complete!: () => void;
      const pendingAccess = new Promise((resolve, reject) => {
        complete = () =>
          outcome === "denied"
            ? resolve({
                allowed: false,
                bindingId: null,
                reason: "CLIENT_BINDING_INVALID",
              })
            : reject(new Error("offline"));
      });
      const original = mock.getMockImplementation()!;
      mock.mockImplementation(async (command, args) =>
        command === "system_access" ? pendingAccess : original(command, args),
      );
      act(() => window.dispatchEvent(new Event("focus")));
      expect(screen.getByRole("heading", { name: "启动设置" })).toBe(heading);
      await act(async () => complete());
      await waitFor(() =>
        expect(screen.queryByRole("heading", { name: "启动设置" })).toBeNull(),
      );
      expect(screen.getByRole("button", { name: "设置" })).toBeDisabled();
      expect(mock.mock.calls.some(([name]) => name === "system_logout")).toBe(
        false,
      );
    },
  );
  it("immediately discards an expired workspace when returning after timer suspension", async () => {
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
    const clock = vi.spyOn(Date, "now").mockReturnValue(Date.now() + 11_000);
    try {
      act(() => window.dispatchEvent(new Event("focus")));
      expect(screen.queryByRole("heading", { name: "启动设置" })).toBeNull();
      expect(screen.getByRole("button", { name: "设置" })).toBeDisabled();
    } finally {
      clock.mockRestore();
    }
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
