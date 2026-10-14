import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cpSync, existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const source = process.env.TEST_DEPLOY_SOURCE || fileURLToPath(new URL('../', import.meta.url));
const bash = process.env.TEST_BASH || (process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash');
const posix = (value) => value.replaceAll('\\', '/').replace(/^([A-Za-z]):/, (_, drive) => `/${drive.toLowerCase()}`);
const password = 'Synthetic$Password#123';

function fixture(t) {
  assert.ok(existsSync(path.join(source, 'deploy.sh')), 'Docker deployment entry point must exist');
  const dir = mkdtempSync(path.join(tmpdir(), 'quota-docker-test-'));
  t.after(() => {
    assert.equal(path.dirname(dir), path.resolve(tmpdir()));
    assert.ok(path.basename(dir).startsWith('quota-docker-test-'));
    rmSync(dir, { recursive: true, force: true });
  });
  const deployment = path.join(dir, 'deployment with spaces');
  mkdirSync(deployment);
  for (const name of ['deploy.sh', 'compose.yaml', '.env.example']) {
    cpSync(path.join(source, name), path.join(deployment, name));
  }
  const bin = path.join(dir, 'bin');
  mkdirSync(bin);
  const openssl = spawnSync(bash, ['-c', 'command -v openssl'], { encoding: 'utf8' }).stdout.trim();
  assert.ok(openssl, 'test prerequisite: openssl');
  writeFileSync(path.join(bin, 'openssl'), `#!/usr/bin/env bash
if [[ "${'$'}{MOCK_RAND_FAIL:-0}" = 1 ]]; then exit 1; fi
exec '${openssl}' "$@"
`, { mode: 0o755 });
  writeFileSync(path.join(bin, 'docker'), `#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$MOCK_LOG"
case "$*" in
  'compose version --short') echo 2.30.3 ;;
  info*) exit "${'$'}{MOCK_INFO_EXIT:-0}" ;;
  *'config --quiet'*) exit "${'$'}{MOCK_CONFIG_EXIT:-0}" ;;
  *' up '*) exit "${'$'}{MOCK_UP_EXIT:-0}" ;;
  *pg_dump*) printf 'synthetic-dump'; exit "${'$'}{MOCK_DUMP_EXIT:-0}" ;;
esac
`, { mode: 0o755 });
  const log = path.join(dir, 'docker-commands.log');
  const envPath = path.join(deployment, '.env');
  function run(args, options = {}) {
    return spawnSync(bash, ['-c', 'export PATH="$TEST_BIN:$PATH"; exec bash "$@"', 'deploy-test', posix(path.join(deployment, 'deploy.sh')), ...args], {
      cwd: dir,
      input: options.input ?? '',
      encoding: 'utf8',
      timeout: 15000,
      env: { ...process.env, TEST_BIN: posix(bin), MOCK_LOG: posix(log), MSYS2_ARG_CONV_EXCL: '*', ...options.env },
    });
  }
  function init() {
    const result = run(['init', 'http://192.0.2.10:8080', 'admin@example.com'], { input: `${password}\n${password}\n` });
    assert.equal(result.status, 0, result.stderr);
    assert.doesNotMatch(result.stdout + result.stderr, /Synthetic\$Password/);
    return readFileSync(envPath, 'utf8');
  }
  return { dir, deployment, envPath, run, init, commands: () => existsSync(log) ? readFileSync(log, 'utf8') : '' };
}

test('init generates independent secrets, an explicit HTTP origin and literal password', (t) => {
  const f = fixture(t);
  const config = f.init();
  assert.match(config, /^PUBLIC_ORIGIN=http:\/\/192\.0\.2\.10:8080$/m);
  assert.match(config, /^HTTP_PORT=8080$/m);
  assert.match(config, /^ADMIN_BOOTSTRAP_PASSWORD='Synthetic\$Password#123'$/m);
  const jwt = config.match(/^JWT_SECRET=([a-f0-9]{64})$/m)?.[1];
  const db = config.match(/^DATABASE_PASSWORD=([a-f0-9]{64})$/m)?.[1];
  const adminDb = config.match(/^POSTGRES_PASSWORD=([a-f0-9]{64})$/m)?.[1];
  assert.ok(jwt && db && adminDb);
  assert.equal(new Set([jwt, db, adminDb]).size, 3);
  const mail = config.match(/^MAIL_ASSISTANCE_ENCRYPTION_KEY=(.+)$/m)?.[1];
  assert.equal(Buffer.from(mail, 'base64').length, 32);
  const official = config.match(/^OFFICIAL_SESSION_ENCRYPTION_KEY=(.+)$/m)?.[1];
  const proxy = config.match(/^MANAGED_PROXY_ENCRYPTION_KEY=(.+)$/m)?.[1];
  assert.equal(Buffer.from(official ?? '', 'base64').length, 32);
  assert.equal(Buffer.from(proxy ?? '', 'base64').length, 32);
  assert.equal(new Set([mail, official, proxy]).size, 3);
  assert.equal(f.commands(), '', 'init must not contact a Docker daemon');
});

test('init is idempotent and cannot replace an existing configuration', (t) => {
  const f = fixture(t);
  const before = f.init();
  assert.equal(f.run(['init']).status, 0);
  assert.equal(readFileSync(f.envPath, 'utf8'), before);
});

test('init aborts random generator failure without publishing empty secrets', (t) => {
  const f = fixture(t);
  const result = f.run(['init', 'http://192.0.2.10:8080', 'admin@example.com'], {
    input: `${password}\n${password}\n`, env: { MOCK_RAND_FAIL: '1' },
  });
  assert.notEqual(result.status, 0);
  assert.equal(existsSync(f.envPath), false);
});

test('init refuses invalid origins, emails, short or mismatched passwords without leaving .env', (t) => {
  const f = fixture(t);
  for (const [origin, email, input] of [
    ['http://0.0.0.0:8080', 'admin@example.com', ''],
    ['http://192.0.2.10:0', 'admin@example.com', ''],
    ['http://192.0.2.10:99999', 'admin@example.com', ''],
    ['http://192.0.2.10/path', 'admin@example.com', ''],
    ['http://192.0.2.10:8080', 'not-email', ''],
    ['http://192.0.2.10:8080', 'admin@example.com', 'short\nshort\n'],
    ['http://192.0.2.10:8080', 'admin@example.com', 'LongEnoughPassword\nDifferentPassword\n'],
  ]) {
    assert.notEqual(f.run(['init', origin, email], { input }).status, 0);
    assert.equal(existsSync(f.envPath), false);
  }
});

test('up validates config quietly, waits for health, uses a stable project and never sources .env', (t) => {
  const f = fixture(t);
  f.init();
  const sentinel = posix(path.join(f.dir, 'must-not-execute'));
  writeFileSync(f.envPath, `${readFileSync(f.envPath, 'utf8')}\nINERT=$(touch '${sentinel}')\n`);
  const result = f.run(['up']);
  assert.equal(result.status, 0, result.stderr);
  assert.match(f.commands(), /--project-name codex-quota-guard/);
  assert.match(f.commands(), /--env-file .*deployment with spaces\/\.env/);
  assert.match(f.commands(), /config --quiet/);
  assert.match(f.commands(), /up .*--build.*--wait/);
  assert.equal(existsSync(path.join(f.dir, 'must-not-execute')), false);
});

test('up propagates Docker, config and health failures', (t) => {
  const f = fixture(t);
  assert.notEqual(f.run(['up']).status, 0, 'missing .env must fail');
  f.init();
  for (const name of ['MOCK_INFO_EXIT', 'MOCK_CONFIG_EXIT', 'MOCK_UP_EXIT']) {
    const result = f.run(['up'], { env: { [name]: '7' } });
    assert.notEqual(result.status, 0, name);
  }
});

test('stop retains volumes, logs accept only known services', (t) => {
  const f = fixture(t);
  f.init();
  assert.equal(f.run(['stop']).status, 0);
  assert.match(f.commands(), / stop/);
  assert.doesNotMatch(f.commands(), /down|volume rm/);
  assert.equal(f.run(['logs', 'backend']).status, 0);
  assert.notEqual(f.run(['logs', '--timestamps']).status, 0);
  assert.notEqual(f.run(['destroy']).status, 0);
});

test('backup preserves both database and encryption configuration; failed dumps are not successful backups', (t) => {
  const f = fixture(t);
  const config = f.init();
  const result = f.run(['backup']);
  assert.equal(result.status, 0, result.stderr);
  const backups = path.join(f.deployment, 'backups');
  const good = readdirSync(backups).filter((entry) => !entry.startsWith('.'));
  assert.equal(good.length, 1);
  assert.equal(readFileSync(path.join(backups, good[0], 'database.dump'), 'utf8'), 'synthetic-dump');
  assert.equal(readFileSync(path.join(backups, good[0], 'deployment.env'), 'utf8'), config);
  assert.notEqual(f.run(['backup'], { env: { MOCK_DUMP_EXIT: '2' } }).status, 0);
  assert.equal(readdirSync(backups).filter((entry) => !entry.startsWith('.')).length, 1);
});

test('container contract: one public port, persistent DB, non-superuser app and explicit HTTP override', () => {
  const compose = readFileSync(path.join(source, 'compose.yaml'), 'utf8');
  assert.equal((compose.match(/\n    ports:/g) || []).length, 1);
  assert.match(compose, /APP_REQUIRE_HTTPS: ['"]false['"]/);
  assert.match(compose, /SPRING_PROFILES_ACTIVE: prod/);
  assert.match(compose, /DATABASE_USERNAME: quota_guard/);
  assert.match(compose, /pgdata:\/var\/lib\/postgresql\/data/);
  assert.match(compose, /condition: service_healthy/);
  assert.match(compose, /MAIL_ASSISTANCE_ENCRYPTION_KEY:.*\$\{/);
  assert.match(compose, /OFFICIAL_SESSION_ENCRYPTION_KEY:.*\$\{/);
  assert.match(compose, /MANAGED_PROXY_ENCRYPTION_KEY:.*\$\{/);
  assert.doesNotMatch(compose, /network_mode: host|privileged: true|:latest/);
  const sql = readFileSync(path.join(source, 'init-db.sql'), 'utf8');
  assert.match(sql, /CREATE ROLE quota_guard LOGIN/);
  assert.doesNotMatch(sql, /LOGIN SUPERUSER/);
});

test('backend bundles a pinned, independently executable proxy core without privileges', () => {
  const dockerfile = readFileSync(path.join(source, 'backend.Dockerfile'), 'utf8');
  assert.match(dockerfile, /FROM ghcr\.io\/sagernet\/sing-box:1\.13\.14(?:@sha256:[a-f0-9]+)? AS proxy-core/);
  assert.match(dockerfile, /COPY --from=proxy-core \/usr\/local\/bin\/sing-box \/usr\/local\/bin\/sing-box/);
  assert.match(dockerfile, /MANAGED_PROXY_SING_BOX_PATH=\/usr\/local\/bin\/sing-box/);
  assert.match(dockerfile, /USER 10001:10001/);
});

test('Nginx preserves /api, sanitizes forwarding and disables buffering for streaming', () => {
  const config = readFileSync(path.join(source, 'nginx.conf'), 'utf8');
  assert.match(config, /location \/api\//);
  assert.match(config, /proxy_pass http:\/\/.*;/);
  assert.doesNotMatch(config, /proxy_pass http:\/\/[^;\s]+\/;/);
  assert.match(config, /proxy_set_header Forwarded "";/);
  assert.match(config, /proxy_set_header X-Forwarded-For \$remote_addr;/);
  assert.match(config, /proxy_set_header X-Forwarded-Proto \$scheme;/);
  assert.match(config, /proxy_buffering off;/);
  assert.doesNotMatch(config.replace(/#.*$/gm, ''), /location = \/index\.html\s*\{[^}]*add_header/,
    'index cache control must preserve server-level security headers');
});

if (process.env.COMPOSE_BINARY) {
  test('official Compose parser validates generated credentials and isolated service topology', (t) => {
    const f = fixture(t);
    f.init();
    const cleanEnv = { ...process.env };
    for (const key of ['HTTP_BIND', 'HTTP_PORT', 'PUBLIC_ORIGIN', 'POSTGRES_PASSWORD', 'DATABASE_PASSWORD', 'JWT_SECRET', 'JWT_ISSUER', 'MAIL_ASSISTANCE_ENCRYPTION_KEY', 'ADMIN_BOOTSTRAP_EMAIL', 'ADMIN_BOOTSTRAP_PASSWORD']) delete cleanEnv[key];
    const parsed = spawnSync(process.env.COMPOSE_BINARY, ['--project-name', 'codex-quota-guard', '--env-file', f.envPath, '-f', path.join(source, 'compose.yaml'), 'config', '--format', 'json'], {
      encoding: 'utf8', timeout: 15000, env: cleanEnv,
    });
    assert.equal(parsed.status, 0, parsed.stderr);
    // Compose config escapes every $ as $$ for safe re-use of its rendered model.
    // Undo only this serialization escape; the actual runtime variable stays literal.
    const config = JSON.parse(parsed.stdout.replaceAll('$$', '$'));
    assert.equal(config.services.backend.environment.ADMIN_BOOTSTRAP_PASSWORD, password);
    assert.equal(config.services.backend.environment.APP_REQUIRE_HTTPS, 'false');
    assert.equal(config.services.backend.environment.CORS_ALLOWED_ORIGINS, 'http://192.0.2.10:8080');
    assert.equal(config.services.backend.ports, undefined);
    assert.equal(config.services.postgres.ports, undefined);
    assert.equal(config.services.web.ports[0].published, '8080');
    assert.equal(config.networks.database.internal, true);
    assert.notEqual(config.networks.application.internal, true);
    assert.equal(config.volumes.pgdata.name, 'codex-quota-guard_pgdata');
  });
}

test('build contexts exclude credentials, private mail fixtures, local runtimes and unrelated projects', () => {
  const ignore = readFileSync(path.resolve(source, '../../.dockerignore'), 'utf8');
  for (const item of ['**/.env', '**/*.clixml', '**/*.eml', '**/target', '**/node_modules', 'warm-dashboard-template', 'deploy/docker/backups']) {
    assert.ok(ignore.includes(item), item);
  }
  for (const file of ['backend.Dockerfile', 'web.Dockerfile']) {
    const content = readFileSync(path.join(source, file), 'utf8');
    assert.match(content, /COPY --from=/);
    assert.match(content, /USER /);
    assert.doesNotMatch(content, /COPY \. \./);
  }
});
