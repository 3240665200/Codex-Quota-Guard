import path from 'node:path';

export function nativeBuildEnvironment(
  environment = process.env,
  platform = process.platform,
) {
  const result = { ...environment };
  if (platform === "win32") {
    const cache = result.CQG_BUILD_CACHE_DIR ?? 'D:\\BuildCache\\pingche\\cache';
    result.CARGO_TARGET_DIR ??= path.win32.join(cache, 'rust');
    result.CARGO_HOME ??= path.win32.join(cache, 'cargo');
    result.TEMP = result.TMP = result.TMPDIR = result.CQG_BUILD_TEMP_DIR ?? path.win32.join(cache, 'tmp');
    const flags = result.CARGO_ENCODED_RUSTFLAGS
      ? result.CARGO_ENCODED_RUSTFLAGS.split("\x1f")
      : (result.RUSTFLAGS ?? "").split(/\s+/);
    if (
      flags.filter((flag) => flag.startsWith("target-feature=")).at(-1) ===
      "target-feature=+crt-static"
    ) {
      return result;
    }
    // Apply in the build entry points, independently of a developer's local Cargo config.
    if (result.CARGO_ENCODED_RUSTFLAGS) {
      result.CARGO_ENCODED_RUSTFLAGS += "\x1f-C\x1ftarget-feature=+crt-static";
    } else {
      result.RUSTFLAGS =
        `${result.RUSTFLAGS ?? ""} -C target-feature=+crt-static`.trim();
    }
  }
  return result;
}
