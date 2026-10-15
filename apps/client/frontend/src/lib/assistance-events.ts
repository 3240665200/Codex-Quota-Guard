import { Channel, invoke } from "@tauri-apps/api/core";
import { z } from "zod";

const signalSchema = z.strictObject({
  type: z.enum(["connected", "state_changed", "disconnected"]),
});
export type AssistanceSignal = z.infer<typeof signalSchema>;
type Handler = (signal: AssistanceSignal) => void;
interface Owner {
  userId: string;
  active: boolean;
  handlers: Set<Handler>;
  subscriptionId?: string;
  channel?: Channel<unknown>;
  started?: Promise<unknown>;
  timer?: number;
  failures: number;
  connectedAt?: number;
}
let owner: Owner | undefined;

function closeNative(current: Owner) {
  const id = current.subscriptionId;
  const started = current.started;
  current.subscriptionId = undefined;
  current.started = undefined;
  if (current.channel) current.channel.onmessage = () => {};
  if (id)
    void Promise.resolve(started)
      .catch(() => {})
      .then(() =>
        invoke("login_assistance_events_stop", { subscriptionId: id }),
      )
      .catch(() => {});
}

function connect(current: Owner) {
  if (!current.active || current.handlers.size === 0) return;
  const id = crypto.randomUUID();
  const channel = new Channel<unknown>();
  current.subscriptionId = id;
  current.channel = channel;
  channel.onmessage = (payload) => {
    if (!current.active || current.subscriptionId !== id) return;
    const parsed = signalSchema.safeParse(payload);
    if (!parsed.success) return;
    if (parsed.data.type === "disconnected") {
      reconnect(current);
      return;
    }
    if (parsed.data.type === "connected") current.connectedAt = Date.now();
    current.handlers.forEach((handler) => handler(parsed.data));
  };
  current.started = invoke("login_assistance_events_start", {
    subscriptionId: id,
    channel,
  });
  void current.started.catch(() => {
    if (current.active && current.subscriptionId === id) reconnect(current);
  });
}

function reconnect(current: Owner) {
  if (!current.active || current.timer !== undefined) return;
  if (
    current.connectedAt !== undefined &&
    Date.now() - current.connectedAt >= 15_000
  )
    current.failures = 0;
  current.connectedAt = undefined;
  closeNative(current);
  current.handlers.forEach((handler) => handler({ type: "disconnected" }));
  const delay = Math.min(30_000, 1_000 * 2 ** Math.min(current.failures++, 5));
  current.timer = window.setTimeout(() => {
    current.timer = undefined;
    connect(current);
  }, delay);
}

function dispose(current: Owner) {
  current.active = false;
  window.clearTimeout(current.timer);
  closeNative(current);
  current.handlers.clear();
  if (owner === current) owner = undefined;
}

/** One owner-bound channel; it carries invalidations only, never a code or credential. */
export function subscribeToAssistanceEvents(
  userId: string,
  handler: Handler,
): () => void {
  if (owner && owner.userId !== userId) dispose(owner);
  const current = owner ?? {
    userId,
    active: true,
    handlers: new Set<Handler>(),
    failures: 0,
  };
  owner = current;
  current.handlers.add(handler);
  if (!current.subscriptionId && current.timer === undefined) connect(current);
  let stopped = false;
  return () => {
    if (stopped) return;
    stopped = true;
    current.handlers.delete(handler);
    if (current.handlers.size === 0) dispose(current);
  };
}
