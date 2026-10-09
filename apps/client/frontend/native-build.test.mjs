import { strict as assert } from "node:assert";
import test from "node:test";
import { nativeBuildEnvironment } from "./native-build-env.mjs";

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
