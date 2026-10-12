import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import test from "node:test";
import path from "node:path";

const root = fileURLToPath(new URL("../", import.meta.url));
const config = JSON.parse(readFileSync(path.join(root, "tauri.conf.json"), "utf8"));

test("Windows current-user installation defers registration until app permission is granted", () => {
  const hook = config.bundle.windows.nsis?.installerHooks;
  assert.equal(typeof hook, "string", "NSIS must register its autostart lifecycle hooks");
  const source = readFileSync(path.join(root, hook), "utf8");
  assert.match(source, /!macro NSIS_HOOK_POSTINSTALL/);
  assert.match(source, /!macro NSIS_HOOK_PREUNINSTALL/);
  assert.doesNotMatch(source, /--autostart-install/);
  const installBlock = source.slice(source.indexOf("!macro NSIS_HOOK_POSTINSTALL")).split("!macroend")[0];
  assert.doesNotMatch(installBlock, /ExecWait|Exec|schtasks/);
  assert.match(source, /--autostart-uninstall/);
  assert.match(source, /\$UpdateMode/);
  assert.match(source, /\$INSTDIR\\\$\{MAINBINARYNAME\}\.exe/);
  assert.doesNotMatch(source, /[A-Z]:\\|schtasks|ExecutionPolicy|Start-Process/i);
});

test("headless helper launch failures are handled without using an undefined exit code", () => {
  const source = readFileSync(path.join(root, config.bundle.windows.nsis.installerHooks), "utf8");
  for (const macro of ["NSIS_HOOK_PREUNINSTALL"]) {
    const block = source.slice(source.indexOf(`!macro ${macro}`)).split("!macroend")[0];
    assert.match(block, /ClearErrors\s+ExecWait/);
    assert.match(block, /\$\{If\} \$\{Errors\}\s+\$\{OrIf\} \$0 != 0/);
  }
});

test("Windows bundle uses the tested NSIS lifecycle instead of an unconfigured MSI", () => {
  const windows = JSON.parse(readFileSync(path.join(root, "tauri.windows.conf.json"), "utf8"));
  assert.deepEqual(windows.bundle.targets, ["nsis"]);
  assert.equal(config.bundle.windows.nsis.installMode, "currentUser");
});

test("NSIS compiles both lifecycle macros using the real hook file", { skip: process.platform !== "win32" }, () => {
  const compiler = process.env.NSIS_MAKENSIS ?? path.join(process.env.LOCALAPPDATA, "tauri/NSIS/makensis.exe");
  const directory = mkdtempSync(path.join(tmpdir(), "autostart-nsis-"));
  try {
    const result = spawnSync(compiler, ["/V2", `/DOUTPUTFILE=${path.join(directory, "hooks-test.exe")}`,
      `/DHOOKFILE=${path.join(root, config.bundle.windows.nsis.installerHooks)}`,
      path.join(root, "tests/installer-hooks-fixture.nsi")], { encoding: "utf8", windowsHide: true });
    assert.equal(result.error, undefined, result.error?.message);
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("installer helper dispatch precedes Tauri and the startup guard", () => {
  const source = readFileSync(path.join(root, "src/main.rs"), "utf8");
  const entry = source.slice(source.indexOf("fn main()"));
  const helper = entry.indexOf("autostart::handle_launch_mode");
  assert.ok(helper >= 0, "headless installer must be dispatched before building Tauri");
  assert.ok(helper < entry.indexOf("tauri::Builder"));
  assert.match(entry.slice(helper, entry.indexOf("tauri::Builder")), /std::process::exit/);
});

test("ordinary startup registration never creates another scheduled task and keeps normal UAC", () => {
  const source = readFileSync(path.join(root, "src/autostart/windows.rs"), "utf8");
  assert.doesNotMatch(source, /RegisterTaskDefinition|TASK_CREATE_OR_UPDATE|SetRunLevel\(/);
  assert.match(source, /ShellExecuteExW/);
  assert.match(source, /SEE_MASK_NOASYNC/);
  assert.match(source, /"runas"/);
  assert.match(source, /"--autostart-elevated"/);
  assert.doesNotMatch(source, /FOLDERID_ProgramFiles|validate_installation/);
  assert.match(source, /HKEY_CURRENT_USER/);
  assert.match(source, /MessageBoxW/);
});

test("UAC cancellation exits the launcher before Tauri without retrying elevation", () => {
  const source = readFileSync(path.join(root, "src/autostart/windows.rs"), "utf8");
  assert.match(source, /if error == ERROR_CANCELLED\s*\{\s*return Ok\(\(\)\);\s*\}/);
  const elevation = source.slice(source.indexOf("StartupAction::Elevate =>"),
    source.indexOf("StartupAction::Uninstall =>"));
  assert.match(elevation, /Ok\(\(\)\) => Some\(0\)/);
  assert.doesNotMatch(elevation, /initialize_default|tauri::Builder|StartupGate|loop\s*\{/);
});

test("release elevated Java cannot load a user-supplied JAR or injected agent options", () => {
  const source = readFileSync(path.join(root, "src/backend.rs"), "utf8");
  assert.equal(/#\[cfg\(debug_assertions\)\]\s*if let Some\(path\) = env::var_os\("CODEX_QUOTA_GUARD_BACKEND_JAR"\)/.test(source), true, "release JAR overrides must be disabled");
  for (const variable of ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"]) {
    assert.ok(source.includes(`.env_remove("${variable}")`), `${variable} must be removed from bundled Java's environment`);
  }
});
