import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, rmdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const wrapper = fileURLToPath(new URL('../registry/start.sh', import.meta.url));
const bash = process.env.TEST_BASH || (process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash');
const posix = (value) => value.replaceAll('\\', '/').replace(/^([A-Za-z]):/, (_, drive) => `/${drive.toLowerCase()}`);

function fixture(t) {
  const directory = mkdtempSync(path.join(tmpdir(), 'quota-registry-test-'));
  t.after(() => {
    assert.equal(path.dirname(directory), path.resolve(tmpdir()));
    assert.ok(path.basename(directory).startsWith('quota-registry-test-'));
    rmSync(directory, { recursive: true, force: true });
  });
  const bundle = path.join(directory, 'registry deployment with spaces');
  const bin = path.join(directory, 'mock-bin');
  mkdirSync(bundle);
  mkdirSync(bin);
  if (existsSync(wrapper)) cpSync(wrapper, path.join(bundle, 'start.sh'));
  const composeFile = path.join(bundle, 'compose.yaml');
  writeFileSync(composeFile, 'name: codex-quota-guard\nservices: {}\n');
  const sentinel = path.join(directory, 'must-not-execute');
  const envFile = path.join(bundle, '.env');
  writeFileSync(envFile, `PRIVATE_VALUE='Synthetic$Secret#123'\nINERT=$(touch '${posix(sentinel)}')\n`);
  writeFileSync(path.join(bundle, 'deploy.sh'), String.raw`#!/usr/bin/env bash
printf '%s\t' "$@" >> "$MOCK_DELEGATE_LOG"
printf '\n' >> "$MOCK_DELEGATE_LOG"
exit "${'$'}{MOCK_DELEGATE_EXIT:-0}"
`, { mode: 0o755 });
  writeFileSync(path.join(bin, 'docker'), String.raw`#!/usr/bin/env bash
printf '%s\t' "$@" >> "$MOCK_LOG"
printf '\n' >> "$MOCK_LOG"
case "${'$'}{1:-}" in
  info) exit "${'$'}{MOCK_INFO_EXIT:-0}" ;;
  compose)
    if [[ "${'$'}{2:-}" = version ]]; then
      printf '2.39.4\n'; exit "${'$'}{MOCK_VERSION_EXIT:-0}"
    fi
    [[ $# -ge 8 && "$2" = --project-name && "$3" = codex-quota-guard \
      && "$4" = --env-file && "$5" = "$MOCK_BUNDLE/.env" \
      && "$6" = -f && "$7" = "$MOCK_BUNDLE/compose.yaml" ]] || exit 91
    shift 7
    case "$*" in
      'config --quiet') exit "${'$'}{MOCK_CONFIG_EXIT:-0}" ;;
      'up -d --no-build --pull always --wait --wait-timeout 240')
        [[ "${'$'}{MOCK_PULL_EXIT:-0}" = 0 ]] || exit "$MOCK_PULL_EXIT"
        exit "${'$'}{MOCK_HEALTH_EXIT:-0}" ;;
      ps) exit 0 ;;
      *) exit 92 ;;
    esac ;;
  *) exit 93 ;;
esac
`, { mode: 0o755 });
  const dockerLog = path.join(directory, 'docker.log');
  const delegateLog = path.join(directory, 'delegate.log');
  const lines = (file) => existsSync(file)
    ? readFileSync(file, 'utf8').trimEnd().split('\n').filter(Boolean).map((line) => line.replace(/\t$/, '').split('\t'))
    : [];
  function run(args, extraEnv = {}) {
    return spawnSync(bash, ['-c', 'export PATH="$TEST_BIN:$PATH"; exec bash "$@"', 'registry-test', posix(path.join(bundle, 'start.sh')), ...args], {
      cwd: directory,
      encoding: 'utf8',
      timeout: 15000,
      env: { ...process.env, TEST_BIN: posix(bin), MOCK_BUNDLE: posix(bundle), MOCK_LOG: posix(dockerLog),
        MOCK_DELEGATE_LOG: posix(delegateLog), MSYS2_ARG_CONV_EXCL: '*', ...extraEnv },
    });
  }
  return { bundle, envFile, composeFile, sentinel, run, calls: () => lines(dockerLog), delegates: () => lines(delegateLog) };
}

test('up validates quietly and pulls fixed Compose images without building from a path with spaces', (t) => {
  const f = fixture(t);
  const before = readFileSync(f.envFile, 'utf8');
  const result = f.run(['up']);
  assert.equal(result.status, 0, result.stderr);
  const actions = f.calls().filter((call) => call[0] === 'compose' && call[1] === '--project-name').map((call) => call.slice(7));
  assert.deepEqual(actions, [['config', '--quiet'], ['up', '-d', '--no-build', '--pull', 'always', '--wait', '--wait-timeout', '240'], ['ps']]);
  assert.equal(f.calls().some((call) => ['build', 'load', 'image'].includes(call[0]) || call.includes('--build')), false);
  assert.equal(f.delegates().length, 0);
  assert.equal(readFileSync(f.envFile, 'utf8'), before);
  assert.equal(existsSync(f.sentinel), false);
  assert.doesNotMatch(result.stdout + result.stderr, /Synthetic\$Secret|PRIVATE_VALUE/);
  assert.equal(existsSync(path.join(f.bundle, 'images.txt')), false);
});

test('up rejects missing or non-file configuration before contacting Docker', (t) => {
  const f = fixture(t);
  for (const file of [f.envFile, f.composeFile]) {
    const before = readFileSync(file, 'utf8');
    rmSync(file);
    assert.notEqual(f.run(['up']).status, 0);
    mkdirSync(file);
    assert.notEqual(f.run(['up']).status, 0);
    rmdirSync(file);
    writeFileSync(file, before);
  }
  assert.equal(f.calls().length, 0);
});

test('up propagates daemon, Compose availability and configuration errors without startup', (t) => {
  const f = fixture(t);
  for (const name of ['MOCK_INFO_EXIT', 'MOCK_VERSION_EXIT', 'MOCK_CONFIG_EXIT'])
    assert.notEqual(f.run(['up'], { [name]: '7' }).status, 0);
  assert.equal(f.calls().some((call) => call.includes('up')), false);
});

for (const [stage, variable] of [['pull', 'MOCK_PULL_EXIT'], ['health', 'MOCK_HEALTH_EXIT']]) {
  test(`up propagates ${stage} failure without reporting successful startup`, (t) => {
    const f = fixture(t);
    const result = f.run(['up'], { [variable]: '7' });
    assert.notEqual(result.status, 0);
    assert.equal(f.calls().filter((call) => call.includes('up')).length, 1);
    assert.equal(f.calls().some((call) => call.at(-1) === 'ps'), false);
  });
}

test('init status logs backup and stop delegate unchanged and preserve existing environment', (t) => {
  const f = fixture(t);
  const before = readFileSync(f.envFile, 'utf8');
  const commands = [['init', 'http://192.0.2.10:8080', 'admin@example.com'], ['status'], ['logs', 'backend'], ['backup'], ['stop']];
  for (const args of commands) {
    const result = f.run(args);
    assert.equal(result.status, 0, result.stderr);
  }
  assert.deepEqual(f.delegates(), commands);
  assert.equal(f.calls().length, 0);
  assert.equal(readFileSync(f.envFile, 'utf8'), before);
  assert.notEqual(f.run(['status'], { MOCK_DELEGATE_EXIT: '9' }).status, 0);
});

test('unknown commands load and extra up flags are rejected without any Docker action', (t) => {
  const f = fixture(t);
  for (const args of [['destroy'], ['load'], ['up', '--build'], ['up', '--pull', 'never']])
    assert.notEqual(f.run(args).status, 0);
  assert.equal(f.calls().length, 0);
  assert.equal(f.delegates().length, 0);
});

test('help is available without Docker or an environment file', (t) => {
  const f = fixture(t);
  rmSync(f.envFile);
  const result = f.run(['--help']);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /up/);
  assert.equal(f.calls().length, 0);
});
