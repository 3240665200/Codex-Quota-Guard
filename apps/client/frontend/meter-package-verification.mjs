import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { parse } from "smol-toml";

export function meterManifestVersion(manifestText) {
  const version = parse(manifestText).package?.version;
  if (typeof version !== "string" || !/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/.test(version)) {
    throw new Error("The Rust meter package version is missing or invalid in Cargo.toml.");
  }
  return version;
}

export function meterBinaryVersion(binaryPath, run = spawnSync) {
  const result = run(binaryPath, ["--version"], {
    encoding: "utf8",
    timeout: 5000,
    maxBuffer: 4096,
    windowsHide: true,
    shell: false,
  });
  if (result.error || result.signal || result.status !== 0) {
    throw new Error(`The bundled Rust meter could not report its version: ${binaryPath}`);
  }
  const version = String(result.stdout ?? "")
    .match(/^quota-guard-meter (\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?)\r?\n?$/)?.[1];
  if (!version) {
    throw new Error(`The bundled Rust meter reported an invalid version: ${binaryPath}`);
  }
  return version;
}

export function fileSha256(filePath) {
  return createHash("sha256").update(readFileSync(filePath)).digest("hex");
}

export function verifyMeterPackage(manifestPath, binaryPath, sourcePath, run) {
  const expectedVersion = meterManifestVersion(readFileSync(manifestPath, "utf8"));
  const actualVersion = meterBinaryVersion(binaryPath, run);
  if (actualVersion !== expectedVersion) {
    throw new Error(
      `Rust meter version mismatch: Cargo.toml=${expectedVersion}, resource=${actualVersion}.`,
    );
  }
  const resourceHash = fileSha256(binaryPath);
  if (sourcePath && fileSha256(sourcePath) !== resourceHash) {
    throw new Error("Rust meter resource differs from the just-built executable.");
  }
  return { version: actualVersion, sha256: resourceHash };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const [, , manifestPath, binaryPath, sourcePath] = process.argv;
    if (!manifestPath || !binaryPath) {
      throw new Error(
        "Usage: node meter-package-verification.mjs <Cargo.toml> <meter binary> [built binary]",
      );
    }
    const identity = verifyMeterPackage(manifestPath, binaryPath, sourcePath);
    process.stdout.write(
      `Verified bundled Rust meter ${identity.version} (SHA-256 ${identity.sha256}).\n`,
    );
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  }
}
