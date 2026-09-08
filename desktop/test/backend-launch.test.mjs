import assert from "node:assert/strict";
import test from "node:test";

import { BackendLaunchError, buildBackendLaunch } from "../src/main/backend-launch.mjs";

const TOKEN = "pJ8ybk_mqMDtQ4Qixl2f5rwcuKeB0VLtyQT6N48S9jc";

test("passes the ephemeral token through the child environment rather than command arguments", () => {
  const launch = buildBackendLaunch({
    jarPath: "C:\\Program Files\\Codex Quota Guard\\backend.jar",
    token: TOKEN,
    port: 47821,
    parentEnvironment: { PATH: "C:\\Java\\bin" }
  });

  assert.equal(launch.command, "java");
  assert.deepEqual(launch.args, [
    "-jar",
    "C:\\Program Files\\Codex Quota Guard\\backend.jar",
    "--server.address=127.0.0.1",
    "--server.port=47821"
  ]);
  assert.equal(launch.options.env.CODEX_QUOTA_GUARD_API_TOKEN, TOKEN);
  assert.equal(launch.options.env.PATH, "C:\\Java\\bin");
  assert.equal(launch.args.join(" ").includes(TOKEN), false);
  assert.equal(launch.options.windowsHide, true);
});

test("rejects unsafe JAR paths, ports, and tokens", () => {
  assert.throws(
    () => buildBackendLaunch({ jarPath: "backend.jar", token: TOKEN, port: 47821 }),
    BackendLaunchError
  );
  assert.throws(
    () => buildBackendLaunch({ jarPath: "C:\\backend.jar", token: TOKEN, port: 80 }),
    BackendLaunchError
  );
  assert.throws(
    () => buildBackendLaunch({ jarPath: "C:\\backend.jar", token: "not-a-token", port: 47821 }),
    BackendLaunchError
  );
});
