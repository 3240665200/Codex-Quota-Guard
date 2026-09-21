import assert from "node:assert/strict";
import test from "node:test";

import { BackendClient, BackendClientError } from "../src/main/backend-client.mjs";

const TOKEN = "pJ8ybk_mqMDtQ4Qixl2f5rwcuKeB0VLtyQT6N48S9jc";

test("forwards the ephemeral token only to the loopback backend", async () => {
  let capturedUrl;
  let capturedOptions;
  const client = new BackendClient({
    baseUrl: "http://127.0.0.1:47821",
    token: TOKEN,
    fetchImpl: async (url, options) => {
      capturedUrl = url;
      capturedOptions = options;
      return jsonResponse({ service: "codex-quota-guard", state: "online" });
    }
  });

  const status = await client.status();

  assert.deepEqual(status, { service: "codex-quota-guard", state: "online" });
  assert.equal(capturedUrl, "http://127.0.0.1:47821/api/v1/status");
  assert.equal(capturedOptions.headers.Authorization, `Bearer ${TOKEN}`);
});

test("rejects non-loopback backend URLs", () => {
  assert.throws(
    () => new BackendClient({ baseUrl: "https://example.com", token: TOKEN, fetchImpl: fetch }),
    BackendClientError
  );
});

test("rejects malformed and unsuccessful backend responses", async () => {
  const unavailable = new BackendClient({
    baseUrl: "http://127.0.0.1:47821",
    token: TOKEN,
    fetchImpl: async () => new Response("", { status: 401 })
  });
  await assert.rejects(() => unavailable.status(), BackendClientError);

  const malformed = new BackendClient({
    baseUrl: "http://127.0.0.1:47821",
    token: TOKEN,
    fetchImpl: async () => jsonResponse({ service: "unexpected", state: "online" })
  });
  await assert.rejects(() => malformed.status(), BackendClientError);
});

test("reads a verified room overview without exposing credentials", async () => {
  const client = new BackendClient({
    baseUrl: "http://127.0.0.1:47821",
    token: TOKEN,
    fetchImpl: async (url) => {
      assert.equal(url, "http://127.0.0.1:47821/api/v1/room/overview");
      return jsonResponse({
        configured: true,
        roomId: "room_design",
        name: "Design",
        members: [{
          memberId: "member_alice",
          displayName: "Alice",
          managedLogin: true,
          enforcementConsent: true,
          snapshot: {
            capturedAt: "2026-09-08T00:00:00Z",
            authenticated: true,
            enforcement: { locked: false },
            buckets: [{
              limitId: "codex",
              primary: { usedPercent: 23, remainingPercent: 77, resetsAt: "2026-09-08T02:00:00Z" },
              secondary: { usedPercent: 33, remainingPercent: 67, resetsAt: "2026-09-12T00:00:00Z" }
            }]
          }
        }],
        warnings: []
      });
    }
  });

  const overview = await client.overview();

  assert.equal(overview.configured, true);
  assert.equal(overview.members[0].displayName, "Alice");
  assert.equal(overview.members[0].snapshot.buckets[0].primary.usedPercent, 23);
  assert.equal("email" in overview.members[0].snapshot, false);
});

test("rejects an invalid room overview shape", async () => {
  const client = new BackendClient({
    baseUrl: "http://127.0.0.1:47821",
    token: TOKEN,
    fetchImpl: async () => jsonResponse({ configured: "yes", members: [] })
  });

  await assert.rejects(() => client.overview(), BackendClientError);
});

function jsonResponse(value) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "content-type": "application/json" }
  });
}
