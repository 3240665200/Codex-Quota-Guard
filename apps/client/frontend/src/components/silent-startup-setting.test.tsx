import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { invoke } from "@tauri-apps/api/core";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { SilentStartupSetting } from "./silent-startup-setting";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));
let silent = false;
let clients: QueryClient[] = [];
const native = vi.mocked(invoke);
beforeEach(() => {
  silent = false;
  native.mockReset();
  native.mockImplementation(async (command, args) => {
    if (command === "launch_preferences_get") return { silentStartup: silent };
    if (command === "launch_preferences_set") {
      silent = (args as { silentStartup: boolean }).silentStartup;
      return { silentStartup: silent };
    }
    throw Error("Unexpected native command");
  });
});
afterEach(() => {
  clients.forEach((client) => client.clear());
  clients = [];
});
function view() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  clients.push(client);
  return render(
    <QueryClientProvider client={client}>
      <SilentStartupSetting />
    </QueryClientProvider>,
  );
}
it("loads and saves a native startup preference without changing Windows autostart", async () => {
  const first = view();
  const toggle = await screen.findByRole("switch", { name: "静默启动" });
  await waitFor(() => expect(toggle).toBeEnabled());
  expect(toggle).toHaveAttribute("aria-checked", "false");
  fireEvent.click(toggle);
  await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "true"));
  expect(native).toHaveBeenCalledWith("launch_preferences_set", {
    silentStartup: true,
  });
  expect(screen.getByText("已开启，下次启动时仅显示托盘图标。")).toBeVisible();
  first.unmount();
  view();
  expect(
    await screen.findByRole("switch", { name: "静默启动" }),
  ).toHaveAttribute("aria-checked", "true");
  expect(
    native.mock.calls.some(([command]) => command === "autostart_set"),
  ).toBe(false);
});
it("shows a failed write without claiming silent startup was enabled", async () => {
  const normal = native.getMockImplementation()!;
  native.mockImplementation(async (command, args) => {
    if (command === "launch_preferences_set") throw Error("设置目录不可写");
    return normal(command, args);
  });
  view();
  const toggle = await screen.findByRole("switch", { name: "静默启动" });
  await waitFor(() => expect(toggle).toBeEnabled());
  fireEvent.click(toggle);
  await screen.findByRole("alert");
  expect(toggle).toHaveAttribute("aria-checked", "false");
  expect(
    screen.queryByText("已开启，下次启动时仅显示托盘图标。"),
  ).not.toBeInTheDocument();
});
it("can restore normal startup when the persisted preference cannot be read", async () => {
  native.mockRejectedValueOnce(Error("设置文件损坏"));
  view();
  await screen.findByRole("button", { name: "恢复正常启动" });
  fireEvent.click(screen.getByRole("button", { name: "恢复正常启动" }));
  await waitFor(() =>
    expect(screen.getByRole("switch", { name: "静默启动" })).toHaveAttribute(
      "aria-checked",
      "false",
    ),
  );
  expect(native).toHaveBeenCalledWith("launch_preferences_set", {
    silentStartup: false,
  });
});
