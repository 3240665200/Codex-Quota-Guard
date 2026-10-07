import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const frontendRoot = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(frontendRoot, "../../..");
const manifest = path.join(repositoryRoot, "apps", "client", "meter", "Cargo.toml");
const env = { ...process.env };
if (process.platform === "win32" && !env.CARGO_TARGET_DIR && existsSync("D:\\")) {
  env.CARGO_TARGET_DIR = "D:\\BuildCache\\pingche\\meter-target";
}
const targetDir = env.CARGO_TARGET_DIR ?? path.join(repositoryRoot, "apps", "client", "meter", "target");
const build = spawnSync("cargo", ["build", "--locked", "--release", "--manifest-path", manifest], {
  cwd: repositoryRoot,
  env,
  stdio: "inherit",
  windowsHide: true,
});
if (build.error || build.status !== 0) {
  process.stderr.write("Rust meter build failed.\n");
  process.exit(1);
}

const suffix = process.platform === "win32" ? ".exe" : "";
const source = path.join(targetDir, "release", `quota-guard-meter${suffix}`);
const resources = path.join(frontendRoot, "src-tauri", "resources", "meter");
mkdirSync(resources, { recursive: true });
copyFileSync(source, path.join(resources, `quota-guard-meter${suffix}`));
if (process.platform === "win32") {
  for (const script of ["install-meter-service.ps1", "uninstall-meter-service.ps1"]) {
    copyFileSync(path.join(repositoryRoot, "scripts", "windows", script),
      path.join(resources, script));
  }
}
process.stdout.write(`Prepared Rust meter resource: ${resources}\n`);
