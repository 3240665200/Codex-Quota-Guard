import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const wrapper = fileURLToPath(new URL('../offline/start.sh', import.meta.url));
const bash = process.env.TEST_BASH || (process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash');
const posix = (value) => value.replaceAll('\\', '/').replace(/^([A-Za-z]):/, (_, drive) => `/${drive.toLowerCase()}`);
const images = ['codex-quota-guard/backend:0.3.10-offline', 'codex-quota-guard/web:0.3.10-offline', 'postgres:17-alpine'];

function fixture(t) {
  const directory = mkdtempSync(path.join(tmpdir(), 'quota-offline-test-'));
  t.after(() => {
    assert.equal(path.dirname(directory), path.resolve(tmpdir()));
    assert.ok(path.basename(directory).startsWith('quota-offline-test-'));
    rmSync(directory, { recursive: true, force: true });
  });
  const bundle = path.join(directory, 'offline bundle with spaces');
  const bin = path.join(directory, 'mock-bin');
  mkdirSync(bundle);
  mkdirSync(bin);
  if (existsSync(wrapper)) cpSync(wrapper, path.join(bundle, 'start.sh'));
  writeFileSync(path.join(bundle, 'images.tar'), 'synthetic-image-archive');
  writeFileSync(path.join(bundle, 'images.txt'), `${images.join('\n')}\n`);
  writeFileSync(path.join(bundle, 'compose.yaml'), 'name: codex-quota-guard\nservices: {}\n');
  const sentinel = path.join(directory, 'must-not-execute');
  const envFile = path.join(bundle, '.env');
  writeFileSync(envFile, `KEEP_ORIGINAL=synthetic\nINERT=$(touch '${posix(sentinel)}')\n`);
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
  load)
    [[ $# -eq 3 && "$2" = --input && "$3" = "$MOCK_BUNDLE/images.tar" ]] || exit 91
    exit "${'$'}{MOCK_LOAD_EXIT:-0}" ;;
  image)
    [[ $# -eq 5 && "$2" = inspect && "$3" = --format ]] || exit 92
    [[ "$5" != "${'$'}{MOCK_MISSING_IMAGE:-}" ]] || exit 1
    if [[ "$5" = "${'$'}{MOCK_WRONG_ARCH_IMAGE:-}" ]]; then
      printf '%s\n' 'linux/arm64'
    else
      printf '%s\n' 'linux/amd64'
    fi ;;
  compose)
    if [[ "${'$'}{2:-}" = version ]]; then printf '2.39.4\n'; exit 0; fi
    [[ $# -ge 8 && "$2" = --project-name && "$3" = codex-quota-guard \
      && "$4" = --env-file && "$5" = "$MOCK_BUNDLE/.env" \
      && "$6" = -f && "$7" = "$MOCK_BUNDLE/compose.yaml" ]] || exit 93
    shift 7
    case "$*" in
      'config --quiet') exit "${'$'}{MOCK_CONFIG_EXIT:-0}" ;;
      'config --images') printf '%s\n' "$MOCK_COMPOSE_IMAGES" ;;
      'up -d --no-build --pull never --wait --wait-timeout 240') exit "${'$'}{MOCK_UP_EXIT:-0}" ;;
      ps) exit 0 ;;
      *) exit 94 ;;
    esac ;;
  *) exit 95 ;;
esac
`, { mode: 0o755 });
  const dockerLog = path.join(directory, 'docker.log');
  const delegateLog = path.join(directory, 'delegate.log');
  const lines = (file) => existsSync(file)
    ? readFileSync(file, 'utf8').trimEnd().split('\n').filter(Boolean).map((line) => line.replace(/\t$/, '').split('\t'))
    : [];
  function checksum(names = ['images.tar', 'images.txt', 'compose.yaml', 'deploy.sh']) {
    writeFileSync(path.join(bundle, 'SHA256SUMS'), names.map((name) =>
      `${createHash('sha256').update(readFileSync(path.join(bundle, name))).digest('hex')}  ${name}\n`).join(''));
  }
  checksum();
  function run(args, extraEnv = {}) {
    return spawnSync(bash, ['-c', 'export PATH="$TEST_BIN:$PATH"; exec bash "$@"', 'offline-test', posix(path.join(bundle, 'start.sh')), ...args], {
      cwd: directory,
      encoding: 'utf8',
      timeout: 15000,
      env: { ...process.env, TEST_BIN: posix(bin), MOCK_BUNDLE: posix(bundle), MOCK_LOG: posix(dockerLog),
        MOCK_DELEGATE_LOG: posix(delegateLog), MOCK_COMPOSE_IMAGES: images.join('\n'), MSYS2_ARG_CONV_EXCL: '*', ...extraEnv },
    });
  }
  return { bundle, envFile, sentinel, run, checksum, calls: () => lines(dockerLog), delegates: () => lines(delegateLog) };
}

test('load validates the archive and accompanying files before importing from a path with spaces', (t) => {
  const f = fixture(t);
  rmSync(f.envFile);
  const result = f.run(['load']);
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(f.calls().filter((call) => call[0] === 'load'), [['load', '--input', `${posix(f.bundle)}/images.tar`]]);
  assert.equal(f.delegates().length, 0);
});

test('load rejects a damaged archive or accompanying file without importing', (t) => {
  const f = fixture(t);
  for (const name of ['images.tar', 'compose.yaml']) {
    f.checksum();
    writeFileSync(path.join(f.bundle, name), 'tampered');
    assert.notEqual(f.run(['load']).status, 0);
  }
  assert.equal(f.calls().some((call) => call[0] === 'load'), false);
});

test('load requires images.tar in the checksum list and rejects paths outside the bundle', (t) => {
  const f = fixture(t);
  f.checksum(['images.txt']);
  assert.notEqual(f.run(['load']).status, 0);
  const safe = readFileSync(path.join(f.bundle, 'SHA256SUMS'), 'utf8');
  writeFileSync(path.join(f.bundle, 'SHA256SUMS'), safe.replace('images.txt', '../outside.txt'));
  assert.notEqual(f.run(['load']).status, 0);
  assert.equal(f.calls().some((call) => call[0] === 'load'), false);
});

test('load propagates Docker import failure', (t) => {
  const f = fixture(t);
  assert.notEqual(f.run(['load'], { MOCK_LOAD_EXIT: '7' }).status, 0);
  assert.equal(f.calls().filter((call) => call[0] === 'load').length, 1);
});

test('up verifies exactly three loaded linux/amd64 images and starts without building or pulling', (t) => {
  const f = fixture(t);
  const before = readFileSync(f.envFile, 'utf8');
  const result = f.run(['up']);
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(f.calls().filter((call) => call[0] === 'image').map((call) => call.at(-1)), images);
  assert.ok(f.calls().some((call) => call.slice(-2).join(' ') === 'config --quiet'));
  assert.ok(f.calls().some((call) => call.slice(-2).join(' ') === 'config --images'));
  const startup = f.calls().filter((call) => call.includes('up'));
  assert.equal(startup.length, 1);
  assert.deepEqual(startup[0].slice(7), ['up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '240']);
  assert.equal(f.calls().some((call) => ['pull', 'build', 'load'].includes(call[0]) || call.includes('--build')), false);
  assert.equal(f.delegates().length, 0);
  assert.equal(readFileSync(f.envFile, 'utf8'), before);
  assert.equal(existsSync(f.sentinel), false);
});

test('up refuses missing images or the wrong architecture before starting', (t) => {
  const f = fixture(t);
  for (const failure of [{ MOCK_MISSING_IMAGE: images[1] }, { MOCK_WRONG_ARCH_IMAGE: images[2] }]) {
    assert.notEqual(f.run(['up'], failure).status, 0);
  }
  assert.equal(f.calls().some((call) => call.includes('up')), false);
});

test('up rejects malformed, duplicate, missing or extra image references without evaluating them', (t) => {
  const f = fixture(t);
  const invalid = [images.slice(0, 2), [...images, 'extra/example:test'], [images[0], images[0], images[2]],
    ['--help', images[1], images[2]], [`$(touch '${posix(f.sentinel)}')`, images[1], images[2]],
    ['codex-quota-guard/backend', images[1], images[2]]];
  for (const list of invalid) {
    writeFileSync(path.join(f.bundle, 'images.txt'), `${list.join('\n')}\n`);
    assert.notEqual(f.run(['up']).status, 0);
  }
  assert.equal(existsSync(f.sentinel), false);
  assert.equal(f.calls().some((call) => call[0] === 'image' || call.includes('up')), false);
});

test('up refuses missing environment files, Compose errors, a mismatched image list and daemon failures', (t) => {
  const f = fixture(t);
  for (const failure of [{ MOCK_CONFIG_EXIT: '7' }, { MOCK_INFO_EXIT: '7' },
    { MOCK_COMPOSE_IMAGES: [images[0], images[1], 'unexpected/db:17'].join('\n') }]) {
    assert.notEqual(f.run(['up'], failure).status, 0);
  }
  rmSync(f.envFile);
  assert.notEqual(f.run(['up']).status, 0);
  assert.equal(f.calls().some((call) => call.includes('up')), false);
});

test('up propagates container health failure', (t) => {
  const f = fixture(t);
  assert.notEqual(f.run(['up'], { MOCK_UP_EXIT: '7' }).status, 0);
  assert.equal(f.calls().filter((call) => call.includes('up')).length, 1);
});

test('init status logs backup and stop delegate unchanged to the adjacent deploy script', (t) => {
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

test('unknown commands and load or up flags are rejected without Docker changes', (t) => {
  const f = fixture(t);
  for (const args of [['destroy'], ['up', '--build'], ['load', 'other.tar']])
    assert.notEqual(f.run(args).status, 0);
  assert.equal(f.calls().length, 0);
  assert.equal(f.delegates().length, 0);
});
