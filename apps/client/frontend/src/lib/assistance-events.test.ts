import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { invoke } from "@tauri-apps/api/core";
import { subscribeToAssistanceEvents } from "./assistance-events";

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
const native = vi.mocked(invoke);
const starts = () =>
  native.mock.calls.filter(
    ([name]) => name === "login_assistance_events_start",
  );
const stops = () =>
  native.mock.calls.filter(([name]) => name === "login_assistance_events_stop");
let cleanups: (() => void)[];
beforeEach(() => {
  vi.useFakeTimers();
  channels.length = 0;
  native.mockReset();
  native.mockResolvedValue(undefined);
  cleanups = [];
});
afterEach(() => {
  cleanups.forEach((stop) => stop());
  vi.useRealTimers();
});
function subscribe(owner: string, callback = vi.fn()) {
  const stop = subscribeToAssistanceEvents(owner, callback);
  cleanups.push(stop);
  return { stop, callback };
}
describe("shared native assistance SSE subscription", () => {
  it("shares one authenticated native channel and never forwards OTP-shaped payloads", async () => {
    const first = subscribe("alice");
    const second = subscribe("alice");
    await Promise.resolve();
    expect(starts()).toHaveLength(1);
    channels[0].onmessage({ type: "connected" });
    channels[0].onmessage({ type: "state_changed", code: "synthetic-otp" });
    expect(first.callback).toHaveBeenCalledTimes(1);
    expect(second.callback).toHaveBeenCalledTimes(1);
    first.stop();
    expect(stops()).toHaveLength(0);
    second.stop();
    await vi.advanceTimersByTimeAsync(0);
    expect(stops()).toHaveLength(1);
    expect(
      native.mock.calls.some(([name]) => name === "login_assistance_cancel"),
    ).toBe(false);
  });
  it("backs off reconnect attempts instead of polling HTTP or refreshing every two seconds", async () => {
    subscribe("alice");
    await Promise.resolve();
    channels[0].onmessage({ type: "disconnected" });
    await vi.advanceTimersByTimeAsync(999);
    expect(starts()).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(starts()).toHaveLength(2);
    channels[1].onmessage({ type: "connected" });
    channels[1].onmessage({ type: "disconnected" });
    await vi.advanceTimersByTimeAsync(1999);
    expect(starts()).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(starts()).toHaveLength(3);
    expect(
      native.mock.calls.every(
        ([name]) =>
          name === "login_assistance_events_start" ||
          name === "login_assistance_events_stop",
      ),
    ).toBe(true);
  });
  it("ignores a disposed StrictMode channel and stops only its own late native start", async () => {
    let finish!: () => void;
    native.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    const first = subscribe("alice");
    first.stop();
    const second = subscribe("bob");
    await Promise.resolve();
    channels[0].onmessage({ type: "state_changed" });
    expect(second.callback).not.toHaveBeenCalled();
    finish();
    await Promise.resolve();
    await Promise.resolve();
    const oldId = (starts()[0][1] as { subscriptionId: string }).subscriptionId;
    expect(
      stops().some(
        ([, args]) =>
          (args as { subscriptionId: string }).subscriptionId === oldId,
      ),
    ).toBe(true);
    channels[1].onmessage({ type: "connected" });
    expect(second.callback).toHaveBeenCalledOnce();
  });
});
