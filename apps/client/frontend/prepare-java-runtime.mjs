import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import {
  createReadStream,
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  renameSync,
  rmSync,
} from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const frontendRoot = path.dirname(fileURLToPath(import.meta.url));
const resourcesRoot = path.join(frontendRoot, "src-tauri", "resources");
const runtimeDestination = path.join(resourcesRoot, "java");

// Eclipse Temurin is redistributable. The checksum is published by Adoptium;
// the mirror is only a transport and cannot change the accepted bytes.
const temurin = {
  archive: "OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip",
  checksum: "bc21a93923103cdaac93ee337b0ae4365e739fde36df823dd456bc67c8a9d352",
  url: "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jre/x64/windows/OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip",
  version: "17.0.20.1",
};

function run(command, args) {
  const result = spawnSync(command, args, {
    stdio: "inherit",
    windowsHide: true,
  });
  if (result.error || result.status !== 0) {
    throw new Error(`${command} failed; the installer will not be built.`);
  }
}

async function sha256(file) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(file)) hash.update(chunk);
  return hash.digest("hex");
}

function validateRuntime(home, windows) {
  const launcher = path.join(home, "bin", windows ? "javaw.exe" : "java");
  const release = path.join(home, "release");
  const modules = path.join(home, "lib", "modules");
  if (!existsSync(launcher) || !existsSync(release) || !existsSync(modules)) {
    throw new Error(`Incomplete Java runtime: ${home}`);
  }
  if (!readFileSync(release, "utf8").includes('JAVA_VERSION="17.')) {
    throw new Error("The bundled Java runtime must be version 17.");
  }
}

async function windowsRuntime() {
  const target =
    process.env.CARGO_TARGET_DIR ??
    (existsSync("D:\\")
      ? "D:\\BuildCache\\pingche\\desktop-package-target"
      : path.join(frontendRoot, ".build-cache"));
  const cache = path.join(target, "java-runtime-cache");
  const archive = path.join(cache, temurin.archive);
  const extraction = path.join(cache, `temurin-${temurin.version}`);
  mkdirSync(cache, { recursive: true });

  if (!existsSync(archive)) {
    const download = `${archive}.download-${process.pid}`;
    const downloadUrl =
      process.env.QUOTA_GUARD_RUNTIME_DOWNLOAD_URL ?? temurin.url;
    if (new URL(downloadUrl).protocol !== "https:") {
      throw new Error("The Java runtime download URL must use HTTPS.");
    }
    try {
      run("curl.exe", [
        "--fail",
        "--location",
        "--silent",
        "--show-error",
        "--retry",
        "2",
        "--max-time",
        "180",
        "--output",
        download,
        downloadUrl,
      ]);
      if ((await sha256(download)) !== temurin.checksum) {
        throw new Error(
          "Temurin download checksum did not match; refusing to bundle it.",
        );
      }
      renameSync(download, archive);
    } finally {
      if (existsSync(download)) rmSync(download);
    }
  }
  if ((await sha256(archive)) !== temurin.checksum) {
    throw new Error(`Temurin cache checksum did not match: ${archive}`);
  }

  if (!existsSync(extraction)) {
    const staging = mkdtempSync(path.join(cache, ".temurin-extract-"));
    try {
      run("tar.exe", ["-xf", archive, "-C", staging]);
      renameSync(staging, extraction);
    } finally {
      if (existsSync(staging))
        rmSync(staging, { recursive: true, force: true });
    }
  }
  const homes = readdirSync(extraction, { withFileTypes: true })
    .filter((item) => item.isDirectory())
    .map((item) => path.join(extraction, item.name))
    .filter((home) => existsSync(path.join(home, "bin", "javaw.exe")));
  if (homes.length !== 1)
    throw new Error(`Expected one Temurin runtime in ${extraction}`);
  const release = readFileSync(path.join(homes[0], "release"), "utf8");
  if (
    !release.includes(temurin.version) ||
    !release.includes("Eclipse Adoptium")
  ) {
    throw new Error(
      "The extracted Java runtime is not the pinned Eclipse Temurin release.",
    );
  }
  return homes[0];
}

async function main() {
  const windows = process.platform === "win32";
  const source = windows
    ? await windowsRuntime()
    : process.env.QUOTA_GUARD_BUNDLE_JAVA_HOME;
  if (!source) {
    throw new Error(
      "Set QUOTA_GUARD_BUNDLE_JAVA_HOME to a redistributable Java 17 runtime for this platform.",
    );
  }
  validateRuntime(source, windows);
  if (path.resolve(source) === path.resolve(runtimeDestination)) {
    throw new Error(
      "The source runtime cannot be the generated resource directory.",
    );
  }

  const stagingRoot = mkdtempSync(path.join(resourcesRoot, ".java-staging-"));
  const stagedRuntime = path.join(stagingRoot, "java");
  try {
    cpSync(source, stagedRuntime, { recursive: true });
    validateRuntime(stagedRuntime, windows);
    if (existsSync(runtimeDestination))
      rmSync(runtimeDestination, { recursive: true, force: true });
    renameSync(stagedRuntime, runtimeDestination);
  } finally {
    rmSync(stagingRoot, { recursive: true, force: true });
  }
  process.stdout.write(`Bundled Java runtime: ${runtimeDestination}\n`);
}

main().catch((error) => {
  process.stderr.write(`${error.message}\n`);
  process.exitCode = 1;
});
