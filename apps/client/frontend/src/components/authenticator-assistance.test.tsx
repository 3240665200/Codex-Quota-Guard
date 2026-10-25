import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuthenticatorAssistance } from "./authenticator-assistance";
import { api, type AuthenticatorCode } from "@/lib/api";

const accountId = "11111111-1111-4111-8111-111111111111";
const info = {
  available: true,
  reasonCode: null,
  officialAccountId: accountId,
  accountLabel: "研发官方账户",
};
function sample(): AuthenticatorCode {
  return {
    code: "012345",
    serverTime: "2026-10-25T00:00:25Z",
    expiresAt: "2026-10-25T00:00:30Z",
    periodSeconds: 30,
    officialAccountId: accountId,
  };
}
async function mount() {
  const view = render(<AuthenticatorAssistance userId="user-one" />);
  await act(async () => {
    await Promise.resolve();
  });
  return view;
}
async function getCode() {
  fireEvent.click(screen.getByRole("button", { name: "获取验证器验证码" }));
  await act(async () => {
    await Promise.resolve();
  });
}
describe("Authenticator 登录辅助", () => {
  beforeEach(() => {
    vi.useFakeTimers({
      toFake: [
        "Date",
        "setInterval",
        "clearInterval",
        "setTimeout",
        "clearTimeout",
        "performance",
      ],
    });
    vi.setSystemTime(new Date("2036-01-01T00:00:00Z"));
    vi.spyOn(document, "hidden", "get").mockReturnValue(false);
    vi.spyOn(api, "authenticatorInfo").mockResolvedValue(info);
    vi.spyOn(api, "authenticatorCode").mockResolvedValue(sample());
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("fetches a code only on request and copies leading zeros without storing the secret or code", async () => {
    const write = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: write },
    });
    const store = vi.spyOn(Storage.prototype, "setItem");
    await mount();
    expect(api.authenticatorCode).not.toHaveBeenCalled();
    await getCode();
    expect(screen.getByLabelText("当前验证器验证码")).toHaveTextContent(
      "012345",
    );
    fireEvent.click(screen.getByRole("button", { name: "复制验证器验证码" }));
    await act(async () => {
      await Promise.resolve();
    });
    expect(write).toHaveBeenCalledWith("012345");
    expect(store).not.toHaveBeenCalled();
    expect(api.authenticatorCode).toHaveBeenCalledTimes(1);
  });
  it("uses server time despite a wrong PC clock and expires without polling the server", async () => {
    await mount();
    await getCode();
    expect(screen.getByText(/5 秒后失效/)).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5001);
    });
    expect(screen.queryByLabelText("当前验证器验证码")).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "获取验证器验证码" }),
    ).toBeEnabled();
    expect(api.authenticatorCode).toHaveBeenCalledTimes(1);
  });
  it("clears a displayed code when hidden and ignores a pending response after logout", async () => {
    await mount();
    await getCode();
    vi.spyOn(document, "hidden", "get").mockReturnValue(true);
    act(() => {
      document.dispatchEvent(new Event("visibilitychange"));
    });
    expect(screen.queryByLabelText("当前验证器验证码")).not.toBeInTheDocument();
    vi.spyOn(document, "hidden", "get").mockReturnValue(false);
    let resolve!: (value: AuthenticatorCode) => void;
    vi.mocked(api.authenticatorCode).mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    await getCode();
    const signal = vi.mocked(api.authenticatorCode).mock.calls.at(-1)?.[0];
    act(() => {
      window.dispatchEvent(new Event("quota-guard:assistance-clear"));
    });
    expect(signal?.aborted).toBe(true);
    await act(async () => {
      resolve(sample());
    });
    expect(screen.queryByLabelText("当前验证器验证码")).not.toBeInTheDocument();
  });
  it("rejects responses from a changed team account and shows configuration instructions", async () => {
    vi.mocked(api.authenticatorCode).mockResolvedValue({
      ...sample(),
      officialAccountId: "22222222-2222-4222-8222-222222222222",
    });
    const view = await mount();
    await getCode();
    expect(screen.queryByLabelText("当前验证器验证码")).not.toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("团队账户已变化");
    vi.mocked(api.authenticatorInfo).mockResolvedValue({
      ...info,
      available: false,
      reasonCode: "AUTHENTICATOR_UNCONFIGURED",
    });
    view.rerender(<AuthenticatorAssistance userId="another-user" />);
    await act(async () => {
      await Promise.resolve();
    });
    expect(
      screen.getByText(/管理员配置对应账户的 TOTP 密钥/),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "获取验证器验证码" }),
    ).not.toBeInTheDocument();
  });
});
