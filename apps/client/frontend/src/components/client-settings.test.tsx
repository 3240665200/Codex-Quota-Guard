import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { invoke } from "@tauri-apps/api/core";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ClientSettings } from "./client-settings";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));

const invokeMock = vi.mocked(invoke);
const autostartQueryKey = ["desktop-autostart"];
type AutostartStatus = { supported: boolean; enabled: boolean };
let enabled = true;
let clients: QueryClient[] = [];

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

function createClient() {
  const client = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        gcTime: 0,
        staleTime: 15_000,
        refetchOnWindowFocus: false,
      },
    },
  });
  clients.push(client);
  return client;
}

function renderSettings(client = createClient()) {
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <ClientSettings />
      </QueryClientProvider>,
    ),
  };
}

function statusReadCount() {
  return invokeMock.mock.calls.filter(
    ([command]) => command === "autostart_status",
  ).length;
}

function expectNoAutostartWrites() {
  expect(
    invokeMock.mock.calls.some(([command]) => command === "autostart_set"),
  ).toBe(false);
}

async function loadedSwitch() {
  const toggle = await screen.findByRole("switch", { name: "开机自启动" });
  await waitFor(() => expect(toggle).toBeEnabled());
  return toggle;
}

describe("ClientSettings autostart synchronization", () => {
  beforeEach(() => {
    enabled = true;
    clients = [];
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("visible");
    invokeMock.mockReset();
    invokeMock.mockImplementation(async (command, args) => {
      if (command === "autostart_status") return { supported: true, enabled };
      if (command === "autostart_set") {
        enabled = (args as { enabled: boolean }).enabled;
        return { supported: true, enabled };
      }
      throw new Error(`Unexpected command: ${command}`);
    });
  });

  afterEach(() => {
    cleanup();
    clients.forEach((client) => client.clear());
    vi.restoreAllMocks();
  });

  it("reads actual disabled state when settings opens despite a fresh enabled cache", async () => {
    const client = createClient();
    client.setQueryData(autostartQueryKey, { supported: true, enabled: true });
    enabled = false;

    renderSettings(client);

    await waitFor(() =>
      expect(
        screen.getByRole("switch", { name: "开机自启动" }),
      ).toHaveAttribute("aria-checked", "false"),
    );
    expect(statusReadCount()).toBe(1);
    expectNoAutostartWrites();
  });

  it("refreshes Task Manager changes immediately when the window regains focus", async () => {
    renderSettings();
    const toggle = await loadedSwitch();
    expect(toggle).toHaveAttribute("aria-checked", "true");

    enabled = false;
    fireEvent.focus(window);

    await waitFor(() =>
      expect(toggle).toHaveAttribute("aria-checked", "false"),
    );
    expect(statusReadCount()).toBe(2);
    expectNoAutostartWrites();
  });

  it("refreshes external changes on becoming visible and skips hidden focus events", async () => {
    const visibility = vi.spyOn(document, "visibilityState", "get");
    renderSettings();
    const toggle = await loadedSwitch();
    enabled = false;

    visibility.mockReturnValue("hidden");
    fireEvent(document, new Event("visibilitychange"));
    fireEvent.focus(window);
    await act(async () => {});
    expect(statusReadCount()).toBe(1);

    visibility.mockReturnValue("visible");
    fireEvent(document, new Event("visibilitychange"));
    await waitFor(() =>
      expect(toggle).toHaveAttribute("aria-checked", "false"),
    );
    expect(statusReadCount()).toBe(2);
    expectNoAutostartWrites();
  });

  it("does not launch background reads while a toggle is being saved", async () => {
    const saved = deferred<AutostartStatus>();
    invokeMock.mockImplementation(async (command) => {
      if (command === "autostart_status") return { supported: true, enabled };
      if (command === "autostart_set") return saved.promise;
      throw new Error(`Unexpected command: ${command}`);
    });
    renderSettings();
    const toggle = await loadedSwitch();
    await userEvent.click(toggle);
    await waitFor(() => expect(toggle).toHaveAttribute("aria-busy", "true"));

    fireEvent.focus(window);
    fireEvent(document, new Event("visibilitychange"));
    await act(async () => {});
    expect(statusReadCount()).toBe(1);
    expect(toggle).toBeDisabled();

    enabled = false;
    await act(async () => saved.resolve({ supported: true, enabled: false }));
    await waitFor(() =>
      expect(toggle).toHaveAttribute("aria-checked", "false"),
    );
    await waitFor(() => expect(statusReadCount()).toBe(2));
    expect(invokeMock).toHaveBeenCalledWith("autostart_set", {
      enabled: false,
    });
  });

  it("rechecks actual system state after a toggle instead of retaining only the setter snapshot", async () => {
    invokeMock.mockImplementation(async (command) => {
      if (command === "autostart_status")
        return { supported: true, enabled: true };
      if (command === "autostart_set")
        return { supported: true, enabled: false };
      throw new Error(`Unexpected command: ${command}`);
    });
    renderSettings();
    const toggle = await loadedSwitch();

    await userEvent.click(toggle);

    await waitFor(() => expect(statusReadCount()).toBe(2));
    await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "true"));
    expect(invokeMock).toHaveBeenCalledWith("autostart_set", {
      enabled: false,
    });
  });

  it("keeps the saved choice when an earlier read finishes after the toggle", async () => {
    const earlierRead = deferred<AutostartStatus>();
    let reads = 0;
    invokeMock.mockImplementation(async (command) => {
      if (command === "autostart_status") {
        if (++reads === 1) return { supported: true, enabled: true };
        if (reads === 2) return earlierRead.promise;
        return { supported: true, enabled: false };
      }
      if (command === "autostart_set")
        return { supported: true, enabled: false };
      throw new Error(`Unexpected command: ${command}`);
    });
    const { client } = renderSettings();
    const toggle = await loadedSwitch();

    // A read can begin before React has rendered the disabled switch.
    act(() => {
      void client.refetchQueries({ queryKey: autostartQueryKey });
      toggle.click();
    });
    await waitFor(() =>
      expect(invokeMock).toHaveBeenCalledWith("autostart_set", {
        enabled: false,
      }),
    );
    await waitFor(() =>
      expect(toggle).toHaveAttribute("aria-checked", "false"),
    );

    await act(async () => {
      earlierRead.resolve({ supported: true, enabled: true });
      await earlierRead.promise;
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    expect(toggle).toHaveAttribute("aria-checked", "false");
    expect(client.getQueryData(autostartQueryKey)).toEqual({
      supported: true,
      enabled: false,
    });
  });

  it("stops automatic refresh when the system does not support autostart", async () => {
    invokeMock.mockResolvedValue({ supported: false, enabled: false });
    renderSettings();
    await screen.findByText("当前系统暂不支持开机自启动设置");

    fireEvent.focus(window);
    fireEvent(document, new Event("visibilitychange"));
    await act(async () => {});

    expect(statusReadCount()).toBe(1);
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
    expectNoAutostartWrites();
  });

  it("stops listening when settings closes", async () => {
    const { unmount } = renderSettings();
    await loadedSwitch();
    unmount();

    fireEvent.focus(window);
    fireEvent(document, new Event("visibilitychange"));
    await act(async () => {});

    expect(statusReadCount()).toBe(1);
    expectNoAutostartWrites();
  });

  it("allows an automatic read to recover from a status error", async () => {
    invokeMock.mockRejectedValueOnce(new Error("unavailable"));
    renderSettings();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "无法读取自启动状态",
    );
    enabled = false;

    fireEvent.focus(window);

    await waitFor(() =>
      expect(
        screen.getByRole("switch", { name: "开机自启动" }),
      ).toHaveAttribute("aria-checked", "false"),
    );
    expectNoAutostartWrites();
  });

  it("explains Task Manager control, independent Rust service startup and UAC", async () => {
    renderSettings();
    await loadedSwitch();

    expect(
      screen.getByText(/任务管理器.*启动应用.*Codex Quota Guard/),
    ).toBeVisible();
    expect(
      screen.getByText(/关闭.*自启动不会影响.*统计服务.*后台运行/),
    ).toBeVisible();
    expect(screen.getByText(/首次以管理员身份启动时默认开启/)).toBeVisible();
    expect(screen.getByText(/关闭后不会自动重开/)).toBeVisible();
    expect(screen.getByText(/未提权时需确认管理员授权/)).toBeVisible();
    expect(
      screen.getByText(/取消管理员授权时，本次不启动客户端/),
    ).toBeVisible();
    expectNoAutostartWrites();
  });
});
