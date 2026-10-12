// Runs the packaged Java sidecar, never Tauri, the meter, or a real account.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { randomBytes, randomUUID } from "node:crypto";
import { once } from "node:events";
import { mkdtemp, rm } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import test from "node:test";

const root = path.dirname(fileURLToPath(import.meta.url));
const jar = path.join(root, "src-tauri/resources/backend/codex-quota-guard-backend.jar");
const java = path.join(root, "src-tauri/resources/java/bin/java.exe");

test("bundled sidecar preserves a login across processes and rejects unavailable storage", {
  skip: process.platform !== "win32",
  timeout: 180_000,
}, async () => {
  const temporary = await mkdtemp(path.join(tmpdir(), "cqg-session-smoke-"));
  const children = new Set();
  const user = {
    id: randomUUID(), email: "session-test@example.invalid", displayName: "Synthetic test",
    role: "USER", status: "ACTIVE",
  };
  let refresh = null;
  let access = null;
  let loginCount = 0;
  let refreshCount = 0;
  const central = createServer(async (request, response) => {
    let body = "";
    for await (const chunk of request) body += chunk;
    const data = body ? JSON.parse(body) : {};
    const reply = (status, value) => {
      response.writeHead(status, { "content-type": "application/json" });
      response.end(JSON.stringify(value));
    };
    const issue = () => {
      access = randomUUID();
      refresh = randomUUID();
      reply(200, { accessToken: access, refreshToken: refresh, tokenType: "Bearer",
        expiresIn: 900, refreshExpiresIn: 2592000, user });
    };
    if (request.url === "/api/v1/auth/login") { loginCount++; issue(); }
    else if (request.url === "/api/v1/auth/refresh" && data.refreshToken === refresh) {
      refreshCount++; issue();
    } else if (request.url === "/api/v1/users/me" && request.headers.authorization === `Bearer ${access}`) {
      reply(200, user);
    } else if (request.url === "/api/v1/auth/logout") {
      refresh = null; reply(200, {});
    } else reply(401, { code: "TEST_UNAUTHORIZED" });
  });
  central.listen(0, "127.0.0.1");
  await once(central, "listening");
  const centralUrl = `http://127.0.0.1:${central.address().port}`;
  const key = randomBytes(32).toString("hex");

  async function launch(storageKey = key) {
    const reservation = createServer();
    reservation.listen(0, "127.0.0.1");
    await once(reservation, "listening");
    const port = reservation.address().port;
    await new Promise((resolve) => reservation.close(resolve));
    const bridge = randomUUID();
    const environment = { ...process.env,
      QUOTA_GUARD_API_TOKEN: bridge, QUOTA_GUARD_CONTROL_PLANE_URL: centralUrl,
      QUOTA_GUARD_SESSION_STORAGE_DIR: path.join(temporary, "vault"),
      QUOTA_GUARD_SESSION_STORAGE_KEY: storageKey, QUOTA_GUARD_SESSION_STORAGE_REQUIRED: "true",
      QUOTA_GUARD_CODEX_HOME_ROOT: path.join(temporary, "scan"),
      QUOTA_GUARD_OFFICIAL_CODEX_HOME: path.join(temporary, "empty-codex"),
      QUOTA_GUARD_METER_DEVICE_ID_FILE: path.join(temporary, "absent-device"),
      QUOTA_GUARD_METER_PAIRING_INBOX: path.join(temporary, "pairing.json"),
      QUOTA_GUARD_METER_STATUS_FILE: path.join(temporary, "status.json"),
      QUOTA_GUARD_USAGE_SCAN_INITIAL_DELAY_MS: "3600000",
      SERVER_ADDRESS: "127.0.0.1", SERVER_PORT: String(port),
    };
    for (const name of ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "SPRING_APPLICATION_JSON"]) delete environment[name];
    const child = spawn(java, [`-Duser.home=${temporary}`, "-jar", jar], {
      env: environment, cwd: temporary, windowsHide: true, stdio: "ignore",
    });
    children.add(child);
    const ended = new Promise((resolve) => {
      child.once("exit", resolve);
      child.once("error", resolve);
    });
    const request = async (endpoint, data) => {
      const response = await fetch(`http://127.0.0.1:${port}/api/v1/${endpoint}`, {
        method: data === undefined ? "GET" : "POST",
        headers: { Authorization: `Bearer ${bridge}`, "Content-Type": "application/json" },
        body: data === undefined ? undefined : JSON.stringify(data), signal: AbortSignal.timeout(3000),
      });
      const text = await response.text();
      return { status: response.status, body: text ? JSON.parse(text) : null };
    };
    let ready = false;
    for (let attempt = 0; attempt < 100; attempt++) {
      try { ready = (await request("status")).status === 200; } catch { /* startup */ }
      if (ready || child.exitCode !== null) break;
      await delay(200);
    }
    assert.equal(ready, true, "isolated bundled Java sidecar must become ready");
    return { request, async stop() { child.kill(); await ended; children.delete(child); } };
  }

  try {
    const broken = await launch("");
    assert.equal((await broken.request("session/current")).status, 423);
    assert.equal((await broken.request("session/login", { email: user.email, password: "synthetic-only" })).status, 423);
    assert.equal(loginCount, 0, "storage failure must not send credentials to the server");
    await broken.stop();

    const first = await launch();
    assert.equal((await first.request("session/current")).body.authenticated, false);
    const loggedIn = await first.request("session/login", { email: user.email, password: "synthetic-only" });
    assert.equal(loggedIn.status, 200);
    assert.equal(loggedIn.body.authenticated, true);
    assert.equal(JSON.stringify(loggedIn.body).includes(refresh), false);
    const concurrent = await launch();
    assert.equal((await concurrent.request("session/current")).status, 423);
    await concurrent.stop();
    await first.stop();

    const restored = await launch();
    const current = await restored.request("session/current");
    assert.equal(current.status, 200);
    assert.equal(current.body.authenticated, true);
    assert.equal(current.body.user.id, user.id);
    assert.equal(loginCount, 1);
    assert.equal(refreshCount, 1);
    assert.equal((await restored.request("session/logout", {})).status, 204);
    await restored.stop();

    const signedOut = await launch();
    assert.equal((await signedOut.request("session/current")).body.authenticated, false);
    assert.equal(refreshCount, 1, "explicit logout must not restore from disk again");
    await signedOut.stop();
  } finally {
    for (const child of children) {
      const ended = once(child, "exit");
      child.kill(); await ended;
    }
    await new Promise((resolve) => central.close(resolve));
    // mkdtemp returned this exact test-owned directory; never target user data.
    assert.equal(path.dirname(temporary), path.resolve(tmpdir()));
    assert.ok(path.basename(temporary).startsWith("cqg-session-smoke-"));
    await rm(temporary, { recursive: true, force: true });
  }
});
