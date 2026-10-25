import { invoke } from "@tauri-apps/api/core";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));

const native = vi.mocked(invoke);

describe("device identity errors", () => {
  beforeEach(async () => {
    await vi.resetModules();
    native.mockReset();
  });

  it("reports missing installation identity without presenting it as a login expiry", async () => {
    native.mockResolvedValue({ deviceId: null, enrollmentId: null });
    const { api, getApiErrorMessage } = await import("./api");
    const errorMessage =
      "本设备绑定信息不可用，请检查统计程序设备登记或联系管理员。";

    await expect(api.sessionBindingCurrent()).rejects.toThrow(errorMessage);
    expect(getApiErrorMessage(new Error(errorMessage))).toBe(errorMessage);
  });
});
