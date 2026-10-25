import { api, type AssistanceEvent } from "./api";

type Handler = (signal: { type: AssistanceEvent }) => void;
interface Owner {
  userId: string;
  active: boolean;
  handlers: Set<Handler>;
  subscriptionId?: string;
  controller?: AbortController;
  started?: Promise<void>;
  timer?: number;
  failures: number;
  connectedAt?: number;
}
let owner: Owner | undefined;

function closeStream(current: Owner) {
  current.subscriptionId = undefined;
  current.started = undefined;
  current.controller?.abort();
  current.controller = undefined;
}

function connect(current: Owner) {
  if (!current.active || current.handlers.size === 0) return;
  const id = crypto.randomUUID();
  const controller = new AbortController();
  current.subscriptionId = id;
  current.controller = controller;
  current.started = api.streamLoginAssistanceEvents((type) => {
    if (!current.active || current.subscriptionId !== id) return;
    if (type === "connected") current.connectedAt = Date.now();
    current.handlers.forEach((handler) => handler({ type }));
  }, controller.signal);
  void current.started
    .catch(() => {
      if (current.active && current.subscriptionId === id) reconnect(current);
    })
    .then(() => {
      if (current.active && current.subscriptionId === id) reconnect(current);
    });
}

function reconnect(current: Owner) {
  if (!current.active || current.timer !== undefined) return;
  if (
    current.connectedAt !== undefined &&
    Date.now() - current.connectedAt >= 15_000
  ) {
    current.failures = 0;
  }
  current.connectedAt = undefined;
  closeStream(current);
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
  closeStream(current);
  current.handlers.clear();
  if (owner === current) owner = undefined;
}

function clearCurrentOwner() {
  if (owner) dispose(owner);
}

window.addEventListener("quota-guard:assistance-clear", clearCurrentOwner);

/** One authenticated SSE stream per signed-in user, with owner-bound reconnects. */
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
    if (current.handlers.size === 0) {
      // StrictMode may remount this same owner in the same effect turn.
      queueMicrotask(() => {
        if (current.active && current.handlers.size === 0) dispose(current);
      });
    }
  };
}
