import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { I18nextProvider } from "react-i18next";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { invoke } from "@tauri-apps/api/core";

import { Dashboard } from "@/components/dashboard";
import type { AccountOverview, RoomOverview, StatusResponse } from "@/lib/api";
import i18n from "@/lib/i18n";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));

const invokeMock = vi.mocked(invoke);

const snapshot = {
  schemaVersion: 1,
  roomId: "room_design",
  memberId: "member_alice",
  displayName: "Alice",
  capturedAt: "2026-09-21T08:00:00Z",
  publishedAt: "2026-09-21T08:00:00Z",
  source: "codex-app-server",
  authenticated: true,
  planType: "plus",
  managedLogin: true,
  buckets: [
    {
      limitId: "codex",
      limitName: "Codex",
      planType: "plus",
      spendControlReached: false,
      primary: {
        usedPercent: 20,
        remainingPercent: 80,
        windowDurationMins: 300,
        resetsAt: "2026-09-21T12:00:00Z",
      },
      secondary: null,
    },
  ],
};

const status: StatusResponse = {
  service: "codex-quota-guard",
  state: "online",
};
const account: AccountOverview = {
  authenticated: true,
  planType: "plus",
  snapshot,
};
const room: RoomOverview = {
  configured: true,
  roomId: "room_design",
  name: "Design",
  warnings: ["Bob 的额度签名待验证"],
  members: [
    {
      memberId: "member_alice",
      displayName: "Alice",
      managedLogin: true,
      enforcementConsent: true,
      snapshot,
    },
  ],
};

function renderDashboard() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <Dashboard />
      </QueryClientProvider>
    </I18nextProvider>,
  );
}

describe("Dashboard", () => {
  beforeEach(() => {
    invokeMock.mockImplementation(async (command) => {
      switch (command) {
        case "status":
          return status;
        case "room_overview":
          return room;
        case "account_overview":
          return account;
        case "start_device_login":
          return {
            type: "device",
            loginId: "login-1",
            verificationUrl: "https://auth.example.com/device",
            userCode: "ABCD-1234",
          };
        case "logout":
          return undefined;
        default:
          throw new Error(`Unexpected command: ${command}`);
      }
    });
  });

  it("renders account, room members, quota windows, and warnings", async () => {
    renderDashboard();

    expect(
      await screen.findByRole("heading", { name: "额度仪表盘" }),
    ).toBeVisible();
    expect(await screen.findByText("Design")).toBeVisible();
    expect(await screen.findByText("Alice")).toBeVisible();
    expect(await screen.findAllByText("80% 剩余")).toHaveLength(2);
    expect(await screen.findByText("Bob 的额度签名待验证")).toBeVisible();
  });

  it("shows the device login entry point after the account action", async () => {
    const user = userEvent.setup();
    renderDashboard();

    await user.click(await screen.findByRole("button", { name: "退出登录" }));
    expect(invokeMock).toHaveBeenCalledWith("logout");
  });

  it("shows a useful error state when the local service is unavailable", async () => {
    invokeMock.mockRejectedValue(new Error("service unavailable"));

    renderDashboard();

    await waitFor(() => {
      const serviceAlert = screen
        .getAllByRole("alert")
        .find((alert) => alert.textContent?.includes("本地服务不可用"));
      expect(serviceAlert).toBeDefined();
      expect(serviceAlert).toHaveTextContent("本地服务不可用");
    });
    expect(screen.getByRole("button", { name: "重试" })).toBeVisible();
  });
});
