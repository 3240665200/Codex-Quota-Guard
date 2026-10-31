import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { invoke } from "@tauri-apps/api/core";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { api } from "@/lib/api";
import {
  DesktopUpdatesProvider,
  DesktopUpdateSettings,
  DesktopVersionButton,
} from "./desktop-updates";

vi.mock("@/lib/api", () => ({
  api: { desktopUpdateCheck: vi.fn() },
  getApiErrorMessage: (error: unknown) =>
    error instanceof Error ? error.message : String(error),
}));
vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn(),
  Channel: class {
    onmessage?: (event: unknown) => void;
  },
}));
const clients: QueryClient[] = [];
const data = {
  currentVersion: "0.4.10",
  latestVersion: "0.4.12",
  available: true,
  notes: "版本管理与在线更新",
  publishedAt: "2026-10-31T00:00:00Z",
  sizeBytes: 1024 * 1024,
  sha256: "a".repeat(64),
  target: "windows" as const,
  arch: "x86_64" as const,
  origin: "https://example.com",
};
beforeEach(() => {
  vi.mocked(api.desktopUpdateCheck).mockReset().mockResolvedValue(data);
  vi.mocked(invoke).mockReset().mockResolvedValue(true);
});
afterEach(() => {
  clients.splice(0).forEach((client) => client.clear());
});
function view() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  clients.push(client);
  return render(
    <QueryClientProvider client={client}>
      <DesktopUpdatesProvider>
        <DesktopVersionButton />
        <DesktopUpdateSettings />
      </DesktopUpdatesProvider>
    </QueryClientProvider>,
  );
}
it("checks at startup and asks native code to notify without installing", async () => {
  view();
  await waitFor(() =>
    expect(invoke).toHaveBeenCalledWith("desktop_update_notify", {
      version: "0.4.12",
      expectedOrigin: "https://example.com",
    }),
  );
  expect(
    screen.getByRole("button", { name: "客户端版本 v0.4.10，查看更新" }),
  ).toHaveTextContent("有更新");
  expect(invoke).not.toHaveBeenCalledWith(
    "desktop_update_install",
    expect.anything(),
  );
  window.dispatchEvent(new Event("focus"));
  expect(api.desktopUpdateCheck).toHaveBeenCalledTimes(1);
});
it("requires a user click and keeps the app open when download or verification fails", async () => {
  vi.mocked(invoke).mockImplementation(async (command, args) => {
    if (command !== "desktop_update_install") return true;
    const channel = (
      args as { onProgress: { onmessage: (value: unknown) => void } }
    ).onProgress;
    channel.onmessage({
      stage: "downloading",
      downloaded: 524288,
      total: 1048576,
    });
    throw Error("更新包签名或版本校验失败，已停止更新。");
  });
  view();
  await screen.findByRole("button", { name: "在线更新" });
  fireEvent.click(screen.getByRole("button", { name: "在线更新" }));
  const dialog = screen.getByRole("dialog", { name: "客户端更新" });
  expect(invoke).not.toHaveBeenCalledWith(
    "desktop_update_install",
    expect.anything(),
  );
  fireEvent.click(within(dialog).getByRole("button", { name: "下载并更新" }));
  expect(await within(dialog).findByRole("alert")).toHaveTextContent(
    "签名或版本校验失败",
  );
  expect(
    within(dialog).getByRole("button", { name: "重新下载更新" }),
  ).toBeEnabled();
  expect(
    within(dialog).getByRole("button", { name: "关闭更新窗口" }),
  ).toBeEnabled();
  fireEvent.click(within(dialog).getByRole("button", { name: "关闭更新窗口" }));
  expect(
    screen.getByRole("heading", { name: "版本与更新" }),
  ).toBeInTheDocument();
});
it("locks the update dialog while the verified installer is being prepared", async () => {
  let finish: () => void = () => {};
  vi.mocked(invoke).mockImplementation(async (command) =>
    command === "desktop_update_install"
      ? new Promise<void>((resolve) => {
          finish = resolve;
        })
      : true,
  );
  view();
  await screen.findByRole("button", { name: "在线更新" });
  fireEvent.click(screen.getByRole("button", { name: "在线更新" }));
  fireEvent.click(screen.getByRole("button", { name: "下载并更新" }));
  expect(screen.getByRole("button", { name: "关闭更新窗口" })).toBeDisabled();
  fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
  expect(screen.getByRole("dialog")).toBeVisible();
  await act(async () => finish());
});
it("does not notify about current versions and checks again after switching servers", async () => {
  vi.mocked(api.desktopUpdateCheck).mockResolvedValue({
    ...data,
    latestVersion: "0.4.10",
    available: false,
  });
  view();
  await waitFor(() => expect(api.desktopUpdateCheck).toHaveBeenCalledTimes(1));
  expect(invoke).not.toHaveBeenCalled();
  await act(async () => {
    window.dispatchEvent(new Event("quota-guard:server-changed"));
  });
  await waitFor(() => expect(api.desktopUpdateCheck).toHaveBeenCalledTimes(2));
});
it("leaves normal client settings usable when the server has no version endpoint", async () => {
  vi.mocked(api.desktopUpdateCheck).mockRejectedValue(Error("404"));
  view();
  expect(await screen.findByRole("alert")).toHaveTextContent("无法检查版本");
  expect(screen.getByRole("button", { name: "检查更新" })).toBeEnabled();
  expect(invoke).not.toHaveBeenCalled();
});
