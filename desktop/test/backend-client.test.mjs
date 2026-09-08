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

function jsonResponse(value) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "content-type": "application/json" }
  });
}
