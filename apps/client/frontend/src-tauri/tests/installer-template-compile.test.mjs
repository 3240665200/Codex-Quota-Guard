import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const root = fileURLToPath(new URL("../", import.meta.url));
const config = JSON.parse(readFileSync(path.join(root, "tauri.conf.json"), "utf8"));

test("the pinned Tauri CLI renders and compiles the complete Windows installer template", {
  skip: process.platform !== "win32",
  timeout: 120_000,
}, () => {
  const cliPackagePath = require.resolve("@tauri-apps/cli/package.json");
  const cliPackage = JSON.parse(readFileSync(cliPackagePath, "utf8"));
  assert.equal(cliPackage.version, "2.11.5", "the fixture must use the CLI matching the vendored template");
  const cliScript = path.join(path.dirname(cliPackagePath), "tauri.js");
  const nsis = config.bundle.windows.nsis;
  assert.equal(nsis.installMode, "currentUser");
  assert.equal(typeof nsis.template, "string", "the production bundle must use the vendored template");
  const template = path.resolve(root, nsis.template);
  assert.ok(existsSync(template), "the configured production template must exist");
  const toolset = path.join(process.env.LOCALAPPDATA, "tauri/NSIS");
  const compiler = path.join(toolset, "makensis.exe");
  // Tauri repairs missing or mismatched tools automatically. Fail before invoking
  // it instead, so fixture compilation cannot download or replace shared tools.
  const requiredTools = [
    "makensis.exe", "Bin/makensis.exe", "Stubs/lzma-x86-unicode", "Stubs/lzma_solid-x86-unicode",
    "Plugins/x86-unicode/additional/nsis_tauri_utils.dll", "Include/MUI2.nsh", "Include/FileFunc.nsh",
    "Include/x64.nsh", "Include/nsDialogs.nsh", "Include/WinMessages.nsh", "Include/Win/COM.nsh",
    "Include/Win/Propkey.nsh", "Include/Win/RestartManager.nsh",
  ];
  for (const entry of requiredTools) {
    assert.ok(existsSync(path.join(toolset, entry)), `the existing NSIS toolset is missing ${entry}`);
  }
  const plugin = readFileSync(path.join(toolset, "Plugins/x86-unicode/additional/nsis_tauri_utils.dll"));
  assert.equal(createHash("sha1").update(plugin).digest("hex"), "75197fee3c6a814fe035788d1c34ead39349b860",
    "the cached plugin must match the pinned CLI's expected hash");

  const directory = mkdtempSync(path.join(tmpdir(), "installer-template-nsis-"));
  try {
    const fixtureRoot = path.join(directory, "src-tauri");
    const target = path.join(directory, "target");
    const release = path.join(target, "release");
    const mainBinary = "codex-quota-guard";
    const payload = path.join(release, `${mainBinary}.exe`);
    mkdirSync(path.join(fixtureRoot, "src"), { recursive: true });
    mkdirSync(path.join(directory, "dist"), { recursive: true });
    mkdirSync(release, { recursive: true });
    writeFileSync(path.join(directory, "dist/index.html"), "<!doctype html><title>Compile-only fixture</title>\n");
    writeFileSync(path.join(fixtureRoot, "src/main.rs"), "fn main() {}\n");
    writeFileSync(path.join(fixtureRoot, "Cargo.toml"), `[package]\nname = "${mainBinary}"\nversion = "${config.version}"\nedition = "2021"\ndescription = "Compile-only installer fixture"\n\n[workspace]\n`);
    // This is a small existing EXE, used only as bytes for NSIS's File instruction.
    copyFileSync(compiler, payload);

    const fixtureConfig = {
      productName: config.productName,
      version: config.version,
      identifier: config.identifier,
      build: { frontendDist: "../dist" },
      bundle: {
        active: true,
        targets: ["nsis"],
        resources: [],
        externalBin: [],
        icon: [],
        createUpdaterArtifacts: false,
        ...(config.bundle.publisher ? { publisher: config.bundle.publisher } : {}),
        windows: {
          webviewInstallMode: { type: "skip" },
          nsis: {
            installMode: nsis.installMode,
            languages: nsis.languages,
            template,
            installerHooks: path.resolve(root, nsis.installerHooks),
            compression: "none",
          },
        },
      },
    };
    writeFileSync(path.join(fixtureRoot, "tauri.conf.json"), JSON.stringify(fixtureConfig, null, 2));
    const environment = { ...process.env, CARGO_TARGET_DIR: target, CARGO_NET_OFFLINE: "true" };
    delete environment.CARGO_BUILD_TARGET;
    delete environment.TAURI_CONFIG;
    // `bundle` renders with Tauri's actual Rust Handlebars helpers and runs makensis.
    // It does not run beforeBuildCommand, compile Rust, or execute the resulting EXE.
    const result = spawnSync(process.execPath, [cliScript, "bundle", "--bundles", "nsis", "--ci", "--no-sign", "--verbose"], {
      cwd: directory,
      env: environment,
      encoding: "utf8",
      maxBuffer: 8 * 1024 * 1024,
      timeout: 110_000,
      windowsHide: true,
    });
    assert.equal(result.error, undefined, result.error?.message);
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
    assert.doesNotMatch(`${result.stdout}\n${result.stderr}`, /unknown variable/i,
      "the complete template must not silently compile undefined NSIS variables");

    const generatedPath = path.join(release, "nsis/x64/installer.nsi");
    assert.ok(existsSync(generatedPath), "Tauri must retain the real rendered NSIS source");
    const generated = readFileSync(generatedPath, "utf8");
    assert.doesNotMatch(generated, /\{\{[\s\S]*?\}\}/, "all Handlebars expressions must be rendered by Tauri");
    assert.ok(generated.includes(`!define MAINBINARYSRCPATH "${payload}"`), "the fixture must package its isolated small payload");
    assert.match(generated, /!define INSTALLMODE "currentUser"/);
    assert.match(generated, /RequestExecutionLevel user/);
    assert.doesNotMatch(generated, /resources\\(?:java|meter|backend)/i);
    assert.match(generated, /!define INSTALLWEBVIEW2MODE ""/);
    assert.match(generated, /!define WEBVIEW2INSTALLERPATH ""/);
    const installers = readdirSync(path.join(release, "bundle/nsis")).filter((name) => name.endsWith("-setup.exe"));
    assert.equal(installers.length, 1, "makensis must produce one compile-only setup artifact");
    assert.ok(statSync(path.join(release, "bundle/nsis", installers[0])).size > statSync(payload).size);
  } finally {
    // All generated source, payload, and setup files are confined to this mkdtemp directory.
    assert.equal(path.dirname(path.resolve(directory)), path.resolve(tmpdir()));
    assert.ok(path.basename(directory).startsWith("installer-template-nsis-"));
    rmSync(directory, { recursive: true, force: true });
  }
});
