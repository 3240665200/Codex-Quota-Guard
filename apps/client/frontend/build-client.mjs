import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = path.dirname(fileURLToPath(import.meta.url));
const environment = { ...process.env };

// Tauri's Windows bundler needs the Cargo target directory in the process
// environment, not only in .cargo/config.toml. Keep large artifacts on D:.
if (process.platform === "win32" && !environment.CARGO_TARGET_DIR && existsSync("D:\\")) {
  environment.CARGO_TARGET_DIR = "D:\\BuildCache\\pingche\\desktop-package-target";
}
if (environment.CARGO_TARGET_DIR) {
  mkdirSync(environment.CARGO_TARGET_DIR, { recursive: true });
}

const tauriCli = path.join(projectRoot, "node_modules", "@tauri-apps", "cli", "tauri.js");
const result = spawnSync(process.execPath, [tauriCli, "build", ...process.argv.slice(2)], {
  cwd: projectRoot,
  env: environment,
  stdio: "inherit",
  windowsHide: true,
});

if (result.error) {
  process.stderr.write("Unable to launch the Tauri build.\n");
  process.exitCode = 1;
} else {
  process.exitCode = result.status ?? 1;
}
