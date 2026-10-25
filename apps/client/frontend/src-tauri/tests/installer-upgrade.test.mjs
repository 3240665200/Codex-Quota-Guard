import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const root = fileURLToPath(new URL("../", import.meta.url));
const config = JSON.parse(
  readFileSync(path.join(root, "tauri.conf.json"), "utf8"),
);

function template() {
  const configured = config.bundle.windows.nsis.template;
  assert.equal(
    typeof configured,
    "string",
    "the upstream current-user installer only looks in HKCU64; the compatibility template must be configured",
  );
  return readFileSync(path.resolve(root, configured), "utf8");
}

test("legacy upgrade compatibility retains current-user installation and custom directories", () => {
  assert.equal(config.bundle.windows.nsis.installMode, "currentUser");
  const source = template();
  assert.match(source, /MUI_PAGE_DIRECTORY/);
  assert.match(source, /RequestExecutionLevel user/);
});

test("legacy detection executes during initialization before maintenance pages or payload writes", () => {
  const source = template();
  const initialization = source.slice(
    source.indexOf("Function .onInit"),
    source.indexOf("FunctionEnd", source.indexOf("Function .onInit")),
  );
  assert.match(
    initialization,
    /Call CqgDetectPreviousInstallation/,
    "HKLM and legacy registry views must be detected before silent and interactive installation diverge",
  );
  assert.ok(
    initialization.indexOf("Call CqgDetectPreviousInstallation") <
      initialization.indexOf("Call RestorePreviousInstallLocation"),
  );
});

test("migration waits for the selected destination and fails closed before installing dependencies", () => {
  const source = template();
  const directoryPage = source.indexOf("!insertmacro MUI_PAGE_DIRECTORY");
  const confirmationPage = source.indexOf(
    "Page custom CqgPageMigrationConfirm",
  );
  assert.ok(
    confirmationPage > directoryPage,
    "the migration confirmation must describe the directory the user actually selected",
  );
  const earlyChecks = source.slice(
    source.indexOf("Section EarlyChecks"),
    source.indexOf("Section WebView2"),
  );
  assert.match(
    earlyChecks,
    /Call CqgPrepareUpgrade/,
    "cancelled or failed migration must stop before WebView2 or new application files are installed",
  );
  assert.doesNotMatch(
    source,
    /ExecWait '\$R1'/,
    "registry UninstallString must not be executed as an arbitrary command",
  );
});

test("updates keep the statistics service and ordinary uninstall removes its registration", () => {
  const hook = readFileSync(
    path.join(root, config.bundle.windows.nsis.installerHooks),
    "utf8",
  );
  assert.doesNotMatch(hook, /--autostart-install|Stop-Service|schtasks/i);
  const uninstall = hook.slice(hook.indexOf("!macro NSIS_HOOK_PREUNINSTALL"));
  assert.match(uninstall, /\$\{If\} \$UpdateMode != 1/);
  assert.match(uninstall, /installer-meter\.ps1.*-Operation Uninstall/);
});

test(
  "the real compatibility policy resolves mocked installations and waits for mocked migration",
  {
    skip: process.platform !== "win32",
  },
  () => {
    const powershell = path.join(
      process.env.SystemRoot,
      "System32/WindowsPowerShell/v1.0/powershell.exe",
    );
    const result = spawnSync(
      powershell,
      [
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        path.join(root, "tests/installer-compatibility.test.ps1"),
      ],
      { encoding: "utf8", windowsHide: true },
    );
    assert.equal(result.error, undefined, result.error?.message);
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
    assert.match(result.stdout, /assertions passed using mocked OS adapters/);
  },
);
