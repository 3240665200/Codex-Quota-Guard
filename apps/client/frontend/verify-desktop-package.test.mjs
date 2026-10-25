import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { verifyDesktopPackage } from "./verify-desktop-package.mjs";

const marker = "CQG_PACKAGE_PROBE_V1";
const webviewMarker = "CQG_WEBVIEW_PROBE_V1";
const validAssets = {
  ok: true,
  probe: marker,
  version: "0.3.12",
  dev: false,
  index: { path: "index.html", bytes: 300 },
  scripts: [{ path: "assets/app.js", bytes: 100 }],
  stylesheets: [{ path: "assets/app.css", bytes: 100 }],
};
const validWebview = {
  ok: true,
  probe: webviewMarker,
  version: "0.3.12",
  mode: "webview-smoke",
  custom_protocol: true,
  root_rendered: true,
  stylesheet_loaded: true,
  packaged_origin: true,
  central_http: true,
};

function fixture(body) {
  const directory = mkdtempSync(path.join(tmpdir(), "cqg-package-probe-"));
  const binary = path.join(directory, "desktop.exe");
  writeFileSync(binary, marker + webviewMarker);
  try {
    body(binary);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

test("legacy executables are rejected before they can start the real application", () =>
  fixture((binary) => {
    writeFileSync(binary, "old application");
    assert.throws(
      () =>
        verifyDesktopPackage(binary, "0.3.12", () =>
          assert.fail("must not execute"),
        ),
      /no safe package probes/,
    );
  }));

test("successful packaging requires both embedded assets and real WebView reports", () =>
  fixture((binary) => {
    const argumentsSeen = [];
    const result = verifyDesktopPackage(
      binary,
      "0.3.12",
      (_binary, args, options) => {
        argumentsSeen.push(args);
        assert.equal(options.windowsHide, true);
        return {
          status: 0,
          stdout: JSON.stringify(
            args[0] === "--verify-package" ? validAssets : validWebview,
          ),
        };
      },
    );
    assert.deepEqual(argumentsSeen, [
      ["--verify-package"],
      ["--smoke-package"],
    ]);
    assert.equal(result.version, "0.3.12");
  }));

test("development mode, wrong versions and missing assets stop before WebView startup", () =>
  fixture((binary) => {
    for (const bad of [
      { ...validAssets, dev: true },
      { ...validAssets, version: "0.3.10" },
      { ...validAssets, scripts: [] },
      { ...validAssets, stylesheets: [] },
      { ...validAssets, index: { path: "index.html", bytes: 0 } },
    ]) {
      let calls = 0;
      assert.throws(() =>
        verifyDesktopPackage(binary, "0.3.12", () => {
          calls++;
          return { status: 0, stdout: JSON.stringify(bad) };
        }),
      );
      assert.equal(calls, 1);
    }
  }));

test("WebView failures and timeouts prevent publication", () =>
  fixture((binary) => {
    for (const failure of [
      { status: 1, stdout: "" },
      { status: null, signal: "SIGTERM" },
      { status: 0, stdout: "not-json" },
    ]) {
      assert.throws(() =>
        verifyDesktopPackage(binary, "0.3.12", (_binary, args) =>
          args[0] === "--verify-package"
            ? { status: 0, stdout: JSON.stringify(validAssets) }
            : failure,
        ),
      );
    }
  }));

test("a rendered page with blocked central HTTP must not pass packaging", () =>
  fixture((binary) => {
    assert.throws(() => verifyDesktopPackage(binary, "0.3.12", (_binary, args) => ({
      status: 0,
      stdout: JSON.stringify(args[0] === "--verify-package" ? validAssets : {
        ...validWebview, central_http: false,
      }),
    })), /WebView/);
  }));
