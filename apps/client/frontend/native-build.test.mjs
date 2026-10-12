import { strict as assert } from "node:assert";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { nativeBuildEnvironment } from "./native-build-env.mjs";
import {
  meterBinaryVersion,
  meterManifestVersion,
  verifyMeterPackage,
} from "./meter-package-verification.mjs";

test("the installer template and build CLI use the same pinned version", () => {
  const manifest = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8"));
  assert.equal(manifest.devDependencies["@tauri-apps/cli"], "2.11.5");
});

test("Windows packaging requests static CRT while preserving supplied Rust flags", () => {
  const existing = {
    RUSTFLAGS: "-C debuginfo=1",
    CARGO_TARGET_DIR: "D:/build",
  };
  const result = nativeBuildEnvironment(existing, "win32");
  assert.match(result.RUSTFLAGS, /-C target-feature=\+crt-static/);
  assert.match(result.RUSTFLAGS, /-C debuginfo=1/);
  assert.equal(result.CARGO_TARGET_DIR, existing.CARGO_TARGET_DIR);
  assert.equal(existing.RUSTFLAGS, "-C debuginfo=1");
});

test("encoded Cargo flags cannot silently bypass the static CRT requirement", () => {
  const result = nativeBuildEnvironment(
    { CARGO_ENCODED_RUSTFLAGS: "-C\x1fdebuginfo=1" },
    "win32",
  );
  assert.deepEqual(result.CARGO_ENCODED_RUSTFLAGS.split("\x1f"), [
    "-C",
    "debuginfo=1",
    "-C",
    "target-feature=+crt-static",
  ]);
});

test("non-Windows builders keep their own runtime flags", () => {
  assert.deepEqual(nativeBuildEnvironment({ RUSTFLAGS: "custom" }, "linux"), {
    RUSTFLAGS: "custom",
  });
});

test("nested packaging steps reuse the same static build flags", () => {
  const once = nativeBuildEnvironment({}, "win32");
  assert.deepEqual(nativeBuildEnvironment(once, "win32"), once);
});

test("meter package version is read from the Cargo package section", () => {
  assert.equal(
    meterManifestVersion('[dependencies]\nversion = "9.9.9"\n[package]\nversion = "0.2.0"\n'),
    "0.2.0",
  );
  assert.throws(() => meterManifestVersion("[package]\nname = 'meter'\n"), /version/);
});

test("meter version probe rejects stale or malformed program output", () => {
  assert.equal(
    meterBinaryVersion("meter.exe", () => ({
      status: 0,
      stdout: "quota-guard-meter 0.2.0\r\n",
    })),
    "0.2.0",
  );
  assert.throws(
    () => meterBinaryVersion("meter.exe", () => ({ status: 0, stdout: "other 0.2.0\n" })),
    /invalid version/,
  );
  assert.throws(
    () => meterBinaryVersion("meter.exe", () => ({ status: null, signal: "SIGTERM" })),
    /could not report/,
  );
});

test("packaging rejects a different meter version or copied binary", () => {
  const directory = mkdtempSync(path.join(tmpdir(), "meter-package-"));
  try {
    const manifest = path.join(directory, "Cargo.toml");
    const source = path.join(directory, "built.exe");
    const resource = path.join(directory, "resource.exe");
    writeFileSync(manifest, '[package]\nversion = "0.2.0"\n');
    writeFileSync(source, "new binary");
    writeFileSync(resource, "new binary");
    const runVersion = (version) => () => ({
      status: 0,
      stdout: `quota-guard-meter ${version}\n`,
    });
    const identity = verifyMeterPackage(manifest, resource, source, runVersion("0.2.0"));
    assert.equal(identity.version, "0.2.0");
    assert.match(identity.sha256, /^[0-9a-f]{64}$/);
    assert.throws(
      () => verifyMeterPackage(manifest, resource, source, runVersion("0.1.1")),
      /version mismatch/,
    );
    writeFileSync(resource, "old binary");
    assert.throws(
      () => verifyMeterPackage(manifest, resource, source, runVersion("0.2.0")),
      /differs/,
    );
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
