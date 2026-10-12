import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import test from "node:test";
import path from "node:path";

const root = fileURLToPath(new URL("../", import.meta.url));
const config = JSON.parse(readFileSync(path.join(root, "tauri.conf.json"), "utf8"));

test("Windows NSIS has installer hooks for default-on and uninstall cleanup", () => {
  const hook = config.bundle.windows.nsis?.installerHooks;
  assert.equal(typeof hook, "string", "NSIS must register its autostart lifecycle hooks");
  const source = readFileSync(path.join(root, hook), "utf8");
  assert.match(source, /!macro NSIS_HOOK_POSTINSTALL/);
  assert.match(source, /!macro NSIS_HOOK_PREUNINSTALL/);
  assert.match(source, /--autostart-install/);
  assert.match(source, /--autostart-uninstall/);
  assert.match(source, /\$UpdateMode/);
  assert.match(source, /\$INSTDIR\\\$\{MAINBINARYNAME\}\.exe/);
  assert.doesNotMatch(source, /[A-Z]:\\|schtasks|ExecutionPolicy|Start-Process/i);
});

test("installer helper dispatch precedes Tauri and the startup guard", () => {
  const source = readFileSync(path.join(root, "src/main.rs"), "utf8");
  const entry = source.slice(source.indexOf("fn main()"));
  const helper = entry.indexOf("autostart::handle_launch_mode");
  assert.ok(helper >= 0, "headless installer must be dispatched before building Tauri");
  assert.ok(helper < entry.indexOf("tauri::Builder"));
  assert.match(entry.slice(helper, entry.indexOf("tauri::Builder")), /std::process::exit/);
});

test("release elevated Java cannot load a user-supplied JAR or injected agent options", () => {
  const source = readFileSync(path.join(root, "src/backend.rs"), "utf8");
  assert.match(source, /#\[cfg\(debug_assertions\)\]\s*if let Some\(path\) = env::var_os\("CODEX_QUOTA_GUARD_BACKEND_JAR"\)/);
  for (const variable of ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"]) {
    assert.ok(source.includes(`.env_remove("${variable}")`), `${variable} must be removed from bundled Java's environment`);
  }
});
