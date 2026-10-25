import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, type AssistanceEvent } from "./api";
import { subscribeToAssistanceEvents } from "./assistance-events";

vi.mock("./api", () => ({ api: { streamLoginAssistanceEvents: vi.fn() } }));
const openStream = vi.mocked(api.streamLoginAssistanceEvents);
interface Stream {
  notify: (event: AssistanceEvent) => void;
  signal: AbortSignal;
  finish: () => void;
  fail: (error: Error) => void;
}
let streams: Stream[];
let cleanups: (() => void)[];
beforeEach(() => {
  window.dispatchEvent(new Event("quota-guard:assistance-clear"));
  vi.useFakeTimers();
  streams = [];
  cleanups = [];
  openStream.mockReset();
  openStream.mockImplementation(
    (notify, signal) =>
      new Promise<void>((finish, fail) => {
        streams.push({ notify, signal, finish, fail });
        signal.addEventListener("abort", () => finish(), { once: true });
      }),
  );
});
afterEach(async () => {
  cleanups.forEach((stop) => stop());
  window.dispatchEvent(new Event("quota-guard:assistance-clear"));
  await vi.advanceTimersByTimeAsync(0);
  vi.useRealTimers();
});
function subscribe(userId: string, callback = vi.fn()) {
  const stop = subscribeToAssistanceEvents(userId, callback);
  cleanups.push(stop);
  return { stop, callback };
}

describe("shared authenticated Axios assistance stream", () => {
  it("reuses a stream across StrictMode cleanup and immediate remount", async () => {
    const first = subscribe("alice");
    first.stop();
    const next = subscribe("alice");
    await vi.advanceTimersByTimeAsync(0);
    expect(openStream).toHaveBeenCalledOnce();
    expect(streams[0].signal.aborted).toBe(false);
    streams[0].notify("state_changed");
    expect(first.callback).not.toHaveBeenCalled();
    expect(next.callback.mock.calls).toEqual([[{ type: "state_changed" }]]);
    next.stop();
    await vi.advanceTimersByTimeAsync(0);
    expect(streams[0].signal.aborted).toBe(true);
  });

  it("shares one connection and forwards only the API's invalidation events", async () => {
    const first = subscribe("alice");
    const second = subscribe("alice");
    expect(openStream).toHaveBeenCalledOnce();
    streams[0].notify("connected");
    streams[0].notify("state_changed");
    expect(first.callback.mock.calls).toEqual([
      [{ type: "connected" }],
      [{ type: "state_changed" }],
    ]);
    expect(second.callback.mock.calls).toEqual(first.callback.mock.calls);
    first.stop();
    expect(streams[0].signal.aborted).toBe(false);
    second.stop();
    await vi.advanceTimersByTimeAsync(0);
    expect(streams[0].signal.aborted).toBe(true);
  });

  it("reconnects on an ended stream with exponential backoff", async () => {
    const current = subscribe("alice");
    streams[0].finish();
    await vi.advanceTimersByTimeAsync(999);
    expect(openStream).toHaveBeenCalledOnce();
    expect(current.callback).toHaveBeenCalledWith({ type: "disconnected" });
    await vi.advanceTimersByTimeAsync(1);
    expect(openStream).toHaveBeenCalledTimes(2);
    streams[1].fail(new Error("synthetic transport failure"));
    await vi.advanceTimersByTimeAsync(1999);
    expect(openStream).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(openStream).toHaveBeenCalledTimes(3);
  });

  it("resets backoff only after a connection has remained healthy", async () => {
    subscribe("alice");
    streams[0].fail(new Error("offline"));
    await vi.advanceTimersByTimeAsync(1000);
    streams[1].notify("connected");
    await vi.advanceTimersByTimeAsync(15_000);
    streams[1].finish();
    await vi.advanceTimersByTimeAsync(999);
    expect(openStream).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(openStream).toHaveBeenCalledTimes(3);
  });

  it("aborts the previous user's connection and ignores late events and completion", async () => {
    const first = subscribe("alice");
    const next = subscribe("bob");
    expect(streams[0].signal.aborted).toBe(true);
    streams[0].notify("state_changed");
    streams[0].finish();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(openStream).toHaveBeenCalledTimes(2);
    expect(first.callback).not.toHaveBeenCalled();
    expect(next.callback).not.toHaveBeenCalled();
    streams[1].notify("connected");
    expect(next.callback.mock.calls).toEqual([[{ type: "connected" }]]);
  });

  it("clears connections and pending reconnects on logout or server change", async () => {
    const current = subscribe("alice");
    streams[0].finish();
    await vi.advanceTimersByTimeAsync(0);
    window.dispatchEvent(new Event("quota-guard:assistance-clear"));
    current.callback.mockClear();
    streams[0].notify("state_changed");
    await vi.advanceTimersByTimeAsync(60_000);
    expect(streams[0].signal.aborted).toBe(true);
    expect(openStream).toHaveBeenCalledOnce();
    expect(current.callback).not.toHaveBeenCalled();
    subscribe("alice");
    expect(openStream).toHaveBeenCalledTimes(2);
  });
});
