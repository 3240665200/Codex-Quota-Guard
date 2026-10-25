import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { verifyNativeRuntime } from "./verify-native-runtime.mjs";

const root = path.dirname(fileURLToPath(import.meta.url));
const assetMarker = "CQG_PACKAGE_PROBE_V1";
const webviewMarker = "CQG_WEBVIEW_PROBE_V1";

export function verifyDesktopPackage(binary, expectedVersion, run = spawnSync) {
  const bytes = readFileSync(binary);
  // Older executables do not recognize these flags and would start the real
  // application. Refuse to execute them before any process is created.
  if (
    !bytes.includes(Buffer.from(assetMarker)) ||
    !bytes.includes(Buffer.from(webviewMarker))
  ) {
    throw new Error(
      "The desktop executable has no safe package probes; rebuild before bundling.",
    );
  }
  const invoke = (argument, marker) => {
    const result = run(binary, [argument], {
      encoding: "utf8",
      timeout: 35_000,
      maxBuffer: 32_768,
      windowsHide: true,
      shell: false,
    });
    if (result.error || result.signal || result.status !== 0) {
      throw new Error(`Desktop package probe failed: ${argument}`);
    }
    let report;
    try {
      report = JSON.parse(result.stdout);
    } catch {
      throw new Error("Desktop package probe did not return a valid report.");
    }
    if (
      report.ok !== true ||
      report.probe !== marker ||
      report.version !== expectedVersion
    ) {
      throw new Error(
        "Desktop package probe identity or version did not match.",
      );
    }
    return report;
  };
  const assets = invoke("--verify-package", assetMarker);
  const nonempty = (items) =>
    Array.isArray(items) &&
    items.length > 0 &&
    items.every(
      (item) =>
        typeof item.path === "string" &&
        item.path.startsWith("assets/") &&
        item.bytes > 0,
    );
  if (
    assets.dev !== false ||
    assets.index?.path !== "index.html" ||
    !(assets.index.bytes > 0) ||
    !nonempty(assets.scripts) ||
    !nonempty(assets.stylesheets)
  ) {
    throw new Error(
      "The executable does not contain a complete production frontend.",
    );
  }
  const webview = invoke("--smoke-package", webviewMarker);
  if (
    webview.mode !== "webview-smoke" ||
    webview.custom_protocol !== true ||
    webview.root_rendered !== true ||
    webview.stylesheet_loaded !== true ||
    webview.packaged_origin !== true ||
    webview.central_http !== true
  ) {
    throw new Error(
      "The packaged WebView failed to render or connect to the central HTTP probe.",
    );
  }
  return { version: expectedVersion, assets, webview };
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  try {
    const version = JSON.parse(
      readFileSync(path.join(root, "package.json"), "utf8"),
    ).version;
    // The supported build entry points set CARGO_TARGET_DIR explicitly.
    if (
      !process.env.CARGO_TARGET_DIR ||
      !path.isAbsolute(process.env.CARGO_TARGET_DIR)
    ) {
      throw new Error(
        "Set an absolute CARGO_TARGET_DIR before verifying the desktop package.",
      );
    }
    const binary = path.join(
      process.env.CARGO_TARGET_DIR,
      "release",
      "codex-quota-guard.exe",
    );
    const imports = verifyNativeRuntime(binary);
    if (imports.some((name) => /^sqlite3\.dll$/i.test(name))) {
      throw new Error(
        "Desktop package unexpectedly depends on an external SQLite runtime.",
      );
    }
    console.log(JSON.stringify(verifyDesktopPackage(binary, version)));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
