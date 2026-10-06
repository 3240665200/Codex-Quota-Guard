import { copyFileSync, mkdirSync } from "node:fs";
import { spawnSync } from "node:child_process";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const frontendRoot = path.dirname(fileURLToPath(import.meta.url));
const clientRoot = path.resolve(frontendRoot, "..");
const pom = path.join(clientRoot, "backend", "pom.xml");
const escapedPom = pom.replaceAll('"', process.platform === "win32" ? '""' : '\\"');
const build = spawnSync(`mvn -f "${escapedPom}" clean package`, {
  cwd: frontendRoot,
  stdio: "inherit",
  shell: true,
});

if (build.status !== 0) {
  process.exit(build.status ?? 1);
}

const source = path.join(
  clientRoot,
  "backend",
  "target",
  "backend-0.1.0-SNAPSHOT.jar",
);
const destinationDirectory = path.join(
  frontendRoot,
  "src-tauri",
  "resources",
  "backend",
);
const destination = path.join(
  destinationDirectory,
  "codex-quota-guard-backend.jar",
);

mkdirSync(destinationDirectory, { recursive: true });
copyFileSync(source, destination);
console.log(`Prepared Java backend resource: ${destination}`);
