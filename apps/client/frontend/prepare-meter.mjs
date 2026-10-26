import { spawnSync } from "node:child_process";
import { copyFileSync, mkdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { verifyNativeRuntime } from "./verify-native-runtime.mjs";
import { nativeBuildEnvironment } from "./native-build-env.mjs";
import { verifyMeterPackage } from "./meter-package-verification.mjs";

const frontendRoot = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(frontendRoot, "../../..");
const manifest = path.join(
  repositoryRoot,
  "apps",
  "client",
  "meter",
  "Cargo.toml",
);
const env = nativeBuildEnvironment();
if (env.TEMP) mkdirSync(env.TEMP, { recursive: true });
if (process.platform === "win32" && !env.CARGO_TARGET_DIR) {
  env.CARGO_TARGET_DIR = path.join(repositoryRoot, ".cache", "rust");
}
const targetDir =
  env.CARGO_TARGET_DIR ??
  path.join(repositoryRoot, "apps", "client", "meter", "target");
const build = spawnSync(
  "cargo",
  ["build", "--locked", "--release", "--manifest-path", manifest],
  {
    cwd: repositoryRoot,
    env,
    stdio: "inherit",
    windowsHide: true,
  },
);
if (build.error || build.status !== 0) {
  process.stderr.write("Rust meter build failed.\n");
  process.exit(1);
}

const suffix = process.platform === "win32" ? ".exe" : "";
const source = path.join(targetDir, "release", `quota-guard-meter${suffix}`);
if (process.platform === "win32") verifyNativeRuntime(source);
const resources = path.join(frontendRoot, "src-tauri", "resources", "meter");
mkdirSync(resources, { recursive: true });
const bundledBinary = path.join(resources, `quota-guard-meter${suffix}`);
copyFileSync(source, bundledBinary);
const meterIdentity = verifyMeterPackage(manifest, bundledBinary, source);
if (process.platform === "win32") {
  for (const script of [
    "install-meter-service.ps1",
    "uninstall-meter-service.ps1",
    "show-meter-logs.ps1",
    "installer-meter.ps1",
    "configure-meter-server.ps1",
    "repair-meter-service.ps1",
  ]) {
    copyFileSync(
      path.join(repositoryRoot, "scripts", "windows", script),
      path.join(resources, script),
    );
  }
}
process.stdout.write(
  `Prepared Rust meter ${meterIdentity.version} (SHA-256 ${meterIdentity.sha256}): ${resources}\n`,
);
