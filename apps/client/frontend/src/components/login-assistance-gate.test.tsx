import { act, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  api,
  assistanceAccessRevokedEvent,
  type LoginAssistanceContext,
} from "@/lib/api";
import { LoginAssistance } from "./login-assistance-gate";

vi.mock("@/components/authenticator-assistance", () => ({
  AuthenticatorAssistance: () => <div>验证码辅助项</div>,
}));
vi.mock("./login-assistance", () => ({
  MailAssistance: () => <div>邮箱辅助项</div>,
}));
vi.mock("@/lib/assistance-events", () => ({
  subscribeToAssistanceEvents: () => () => {},
}));
const allowed = (): LoginAssistanceContext => ({
  allowed: true,
  reasonCode: null,
  bindingAttemptId: "11111111-1111-4111-8111-111111111111",
  serverTime: new Date().toISOString(),
  expiresAt: new Date(Date.now() + 60_000).toISOString(),
  mailEnabled: true,
  authenticatorEnabled: true,
});
beforeEach(() => {
  vi.spyOn(api, "loginAssistanceContext").mockResolvedValue(allowed());
});
afterEach(() => {
  vi.restoreAllMocks();
});
describe("queue-scoped login assistance", () => {
  it.each([
    [false, false],
    [true, false],
    [false, true],
    [true, true],
  ])(
    "shows exactly enabled channels (mail=%s, authenticator=%s)",
    async (mailEnabled, authenticatorEnabled) => {
      vi.mocked(api.loginAssistanceContext).mockResolvedValue({
        ...allowed(),
        mailEnabled,
        authenticatorEnabled,
      });
      render(<LoginAssistance userId="owner" />);
      await waitFor(() =>
        expect(api.loginAssistanceContext).toHaveBeenCalled(),
      );
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 15));
      });
      expect(!!screen.queryByText("邮箱辅助项")).toBe(mailEnabled);
      expect(!!screen.queryByText("验证码辅助项")).toBe(authenticatorEnabled);
    },
  );
  it.each(["BINDING_GRANT_REQUIRED", "ALREADY_BOUND"])(
    "never mounts helpers without a current grant: %s",
    async (reasonCode) => {
      vi.mocked(api.loginAssistanceContext).mockResolvedValue({
        ...allowed(),
        allowed: false,
        reasonCode,
        bindingAttemptId: null,
        expiresAt: null,
      });
      render(<LoginAssistance userId="owner" />);
      await screen.findByText(
        reasonCode === "ALREADY_BOUND" ? /会话已绑定/ : /请先点击/,
      );
      expect(screen.queryByText("验证码辅助项")).not.toBeInTheDocument();
      expect(screen.queryByText("邮箱辅助项")).not.toBeInTheDocument();
    },
  );
  it("clears helpers immediately on revocation and stays closed when the read fails", async () => {
    render(<LoginAssistance userId="owner" />);
    await screen.findByText("验证码辅助项");
    vi.mocked(api.loginAssistanceContext).mockRejectedValue(
      new Error("network failure"),
    );
    act(() => window.dispatchEvent(new Event(assistanceAccessRevokedEvent)));
    expect(screen.queryByText("验证码辅助项")).not.toBeInTheDocument();
    await screen.findByRole("button", { name: "重新核对登录辅助" });
    expect(screen.queryByText("邮箱辅助项")).not.toBeInTheDocument();
  });
  it("does not carry an old grant into a different signed-in user", async () => {
    const view = render(<LoginAssistance userId="owner" />);
    await screen.findByText("验证码辅助项");
    vi.mocked(api.loginAssistanceContext).mockReturnValue(
      new Promise(() => {}),
    );
    view.rerender(<LoginAssistance userId="other" />);
    expect(screen.queryByText("验证码辅助项")).not.toBeInTheDocument();
  });
  it("expires the grant using server-relative time instead of the PC clock", async () => {
    vi.mocked(api.loginAssistanceContext).mockResolvedValue({
      ...allowed(),
      expiresAt: new Date(Date.now() + 350).toISOString(),
    });
    render(<LoginAssistance userId="owner" />);
    await screen.findByText("验证码辅助项");
    vi.mocked(api.loginAssistanceContext).mockReturnValue(
      new Promise(() => {}),
    );
    await waitFor(
      () => expect(screen.queryByText("验证码辅助项")).not.toBeInTheDocument(),
      { timeout: 1500 },
    );
  });
});
