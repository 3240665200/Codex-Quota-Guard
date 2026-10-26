import { spawnSync } from "node:child_process";
import { mkdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { nativeBuildEnvironment } from "./native-build-env.mjs";

const projectRoot = path.dirname(fileURLToPath(import.meta.url));
const environment = nativeBuildEnvironment();

// Keep generated project artifacts in the original checkout by default.
if (process.platform === "win32" && !environment.CARGO_TARGET_DIR) {
  environment.CARGO_TARGET_DIR = path.resolve(
    projectRoot,
    "../../../.cache/rust",
  );
}
if (environment.CARGO_TARGET_DIR) {
  mkdirSync(environment.CARGO_TARGET_DIR, { recursive: true });
}
if (environment.TEMP) mkdirSync(environment.TEMP, { recursive: true });

const tauriCli = path.join(
  projectRoot,
  "node_modules",
  "@tauri-apps",
  "cli",
  "tauri.js",
);
const result = spawnSync(
  process.execPath,
  [tauriCli, "build", ...process.argv.slice(2)],
  {
    cwd: projectRoot,
    env: environment,
    stdio: "inherit",
    windowsHide: true,
  },
);

if (result.error) {
  process.stderr.write("Unable to launch the Tauri build.\n");
  process.exitCode = 1;
} else {
  process.exitCode = result.status ?? 1;
}
