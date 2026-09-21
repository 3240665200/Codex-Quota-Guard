import { beforeEach, describe, expect, it, vi } from "vitest";

import { invoke } from "@tauri-apps/api/core";

import { api } from "@/lib/api";

const invokeMock = vi.mocked(invoke);

vi.mock("@tauri-apps/api/core", () => ({
  invoke: vi.fn(),
}));

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

  it("rejects malformed data before it reaches the dashboard", async () => {
    invokeMock.mockResolvedValue({ service: "codex-quota-guard" });

    await expect(api.status()).rejects.toThrow();
  });

  it("never requires a bearer token from the renderer", async () => {
    invokeMock.mockResolvedValue(undefined);

    await api.logout();

    expect(invokeMock).toHaveBeenCalledWith("logout");
    expect(invokeMock.mock.calls.flat().join(" ")).not.toMatch(/bearer|token/i);
  });
});
