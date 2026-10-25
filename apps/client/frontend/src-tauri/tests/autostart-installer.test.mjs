import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import test from "node:test";
import path from "node:path";

const root = fileURLToPath(new URL("../", import.meta.url));
const config = JSON.parse(
  readFileSync(path.join(root, "tauri.conf.json"), "utf8"),
);

test("Windows installation requires a working statistics service before success", () => {
  const hook = config.bundle.windows.nsis?.installerHooks;
  assert.equal(
    typeof hook,
    "string",
    "NSIS must register its autostart lifecycle hooks",
  );
  const source = readFileSync(path.join(root, hook), "utf8");
  assert.match(source, /!macro NSIS_HOOK_POSTINSTALL/);
  assert.match(source, /!macro NSIS_HOOK_PREUNINSTALL/);
  assert.doesNotMatch(source, /--autostart-install/);
  const installBlock = source
    .slice(source.indexOf("!macro NSIS_HOOK_POSTINSTALL"))
    .split("!macroend")[0];
  assert.match(installBlock, /installer-meter\.ps1.*-Operation Install/);
  assert.match(installBlock, /SetErrorLevel 2\s+Abort/);
  assert.match(source, /--autostart-uninstall/);
  assert.match(source, /\$UpdateMode/);
  assert.match(source, /\$INSTDIR\\\$\{MAINBINARYNAME\}\.exe/);
  assert.doesNotMatch(source, /[A-Z]:\\|schtasks|Start-Process/i);
  const template = readFileSync(
    path.resolve(root, config.bundle.windows.nsis.template),
    "utf8",
  );
  const install = template.slice(
    template.indexOf("Section Install"),
    template.indexOf("Function .onInstSuccess"),
  );
  assert.ok(
    install.indexOf("!insertmacro NSIS_HOOK_POSTINSTALL") <
      install.indexOf("WriteUninstaller"),
  );
});

test("headless helper launch failures are handled without using an undefined exit code", () => {
  const source = readFileSync(
    path.join(root, config.bundle.windows.nsis.installerHooks),
    "utf8",
  );
  for (const macro of ["NSIS_HOOK_PREUNINSTALL", "NSIS_HOOK_POSTINSTALL"]) {
    const block = source
      .slice(source.indexOf(`!macro ${macro}`))
      .split("!macroend")[0];
    assert.match(block, /ClearErrors\s+ExecWait/);
    assert.match(block, /\$\{If\} \$\{Errors\}\s+\$\{OrIf\} \$0 != 0/);
  }
});

test("Windows bundle uses the tested NSIS lifecycle instead of an unconfigured MSI", () => {
  const windows = JSON.parse(
    readFileSync(path.join(root, "tauri.windows.conf.json"), "utf8"),
  );
  assert.deepEqual(windows.bundle.targets, ["nsis"]);
  assert.equal(config.bundle.windows.nsis.installMode, "currentUser");
});

test(
  "NSIS compiles both lifecycle macros using the real hook file",
  { skip: process.platform !== "win32" },
  () => {
    const compiler =
      process.env.NSIS_MAKENSIS ??
      path.join(process.env.LOCALAPPDATA, "tauri/NSIS/makensis.exe");
    const directory = mkdtempSync(path.join(tmpdir(), "autostart-nsis-"));
    try {
      const result = spawnSync(
        compiler,
        [
          "/V2",
          `/DOUTPUTFILE=${path.join(directory, "hooks-test.exe")}`,
          `/DHOOKFILE=${path.join(root, config.bundle.windows.nsis.installerHooks)}`,
          path.join(root, "tests/installer-hooks-fixture.nsi"),
        ],
        { encoding: "utf8", windowsHide: true },
      );
      assert.equal(result.error, undefined, result.error?.message);
      assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  },
);

test("installer helper dispatch precedes Tauri and the startup guard", () => {
  const source = readFileSync(path.join(root, "src/main.rs"), "utf8");
  const entry = source.slice(source.indexOf("fn main()"));
  const helper = entry.indexOf("autostart::handle_launch_mode");
  assert.ok(
    helper >= 0,
    "headless installer must be dispatched before building Tauri",
  );
  assert.ok(helper < entry.indexOf("tauri::Builder"));
  assert.match(
    entry.slice(helper, entry.indexOf("tauri::Builder")),
    /std::process::exit/,
  );
});

test("desktop startup registration does not create a second service or scheduled task", () => {
  const source = readFileSync(
    path.join(root, "src/autostart/windows.rs"),
    "utf8",
  );
  assert.doesNotMatch(
    source,
    /RegisterTaskDefinition|TASK_CREATE_OR_UPDATE|SetRunLevel\(/,
  );
  assert.doesNotMatch(source, /FOLDERID_ProgramFiles|validate_installation/);
  assert.match(source, /HKEY_CURRENT_USER/);
});

test("production bundle and build pipeline contain no Java runtime or local backend", () => {
  assert.doesNotMatch(
    JSON.stringify(config.bundle.resources),
    /java|backend|ensure-meter-service/i,
  );
  const manifest = JSON.parse(
    readFileSync(path.join(root, "../package.json"), "utf8"),
  );
  assert.doesNotMatch(
    manifest.scripts["build:desktop"],
    /backend|runtime|java/i,
  );
  assert.doesNotMatch(config.build.beforeDevCommand, /backend|java/i);
  assert.ok(
    Object.values(config.bundle.resources).includes(
      "meter/installer-meter.ps1",
    ),
  );
  assert.ok(
    Object.values(config.bundle.resources).includes(
      "meter/configure-meter-server.ps1",
    ),
  );
});
