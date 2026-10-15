import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const read = (name) => readFileSync(new URL(`../${name}`, import.meta.url), 'utf8');

test('optional browser image keeps the existing non-root runtime and does not disable the sandbox', () => {
  const dockerfile = read('backend.Dockerfile');
  assert.match(dockerfile, /FROM runtime AS browser-runtime/);
  assert.match(dockerfile, /apk add --no-cache chromium font-noto-cjk/);
  assert.match(dockerfile, /WEB_LOGIN_BROWSER_EXECUTABLE=\/usr\/lib\/chromium\/chrome/);
  assert.match(dockerfile, /USER 10001:10001/);
  assert.doesNotMatch(dockerfile, /--no-sandbox|--disable-web-security|--ignore-certificate-errors/);
  // The base image remains the default build target for deployments not opting in.
  assert.match(read('compose.yaml'), /target: runtime/);
});

test('browser overlay preserves privilege boundaries and publishes no browser control port', () => {
  const compose = read('compose.web-login.yaml');
  assert.match(compose, /target: browser-runtime/);
  assert.match(compose, /WEB_LOGIN_BROWSER_ENABLED: "true"/);
  assert.match(compose, /WEB_LOGIN_BROWSER_HEADLESS: "true"/);
  assert.match(compose, /no-new-privileges:true/);
  assert.match(compose, /seccomp=\.\/chromium-seccomp.json/);
  assert.match(compose, /shm_size: 256mb/);
  assert.match(compose, /\/tmp:rw,noexec,nosuid,size=512m/);
  assert.doesNotMatch(compose, /ports:|privileged:|SYS_ADMIN|unconfined|ipc: host|network_mode: host/);
});

test('registry browser deployment selects the opt-in image without requiring server-side builds', () => {
  const base = JSON.parse(read('registry/compose.yaml'));
  const overlay = read('registry/compose.web-login.yaml');
  assert.equal(base.services.backend.image, 'minjm/codex-quota-guard-backend:0.3.19');
  assert.equal(base.services.web.image, 'minjm/codex-quota-guard-web:0.3.19');
  assert.equal(base.services.backend.platform, 'linux/amd64');
  assert.equal(base.services.web.platform, 'linux/amd64');
  assert.deepEqual(base.services.backend.cap_drop, ['ALL']);
  assert.equal(base.services.backend.read_only, true);
  assert.equal(base.services.backend.ports, undefined);
  assert.match(overlay, /image: minjm\/codex-quota-guard-backend:0\.3\.19-browser/);
  assert.match(overlay, /WEB_LOGIN_BROWSER_ENABLED: "true"/);
  assert.match(overlay, /WEB_LOGIN_BROWSER_EXECUTABLE: \/usr\/lib\/chromium\/chrome/);
  assert.match(overlay, /WEB_LOGIN_BROWSER_HEADLESS: "true"/);
  assert.match(overlay, /-XX:MaxRAMPercentage=40/);
  assert.match(overlay, /mem_limit: 2g/);
  assert.match(overlay, /shm_size: 256mb/);
  assert.match(overlay, /tmpfs: !override\s+- \/tmp:rw,noexec,nosuid,size=512m/);
  assert.match(overlay, /security_opt: !override\s+- no-new-privileges:true\s+- seccomp=\.\/chromium-seccomp.json/);
  assert.doesNotMatch(overlay, /build:|ports:|privileged:|SYS_ADMIN|unconfined|ipc: host|network_mode: host|--no-sandbox/);
});

if (process.env.COMPOSE_BINARY) {
  for (const [sourceBuild, browser, https] of [
    [false, false, false], [false, true, false],
    [false, false, true], [false, true, true],
    [true, true, false], [true, true, true],
  ]) {
    test(`Compose parser preserves ${https ? 'HTTPS-only' : 'HTTP+HTTPS'} policy for ${sourceBuild ? 'source' : 'registry'} ${browser ? 'browser' : 'base'} deployment`, () => {
      const base = sourceBuild ? 'compose.yaml' : 'registry/compose.yaml';
      const overlays = [base];
      if (browser) overlays.push(sourceBuild ? 'compose.web-login.yaml' : 'registry/compose.web-login.yaml');
      if (https) overlays.push('compose.https.yaml');
      const origin = `${https ? 'https' : 'http'}://192.0.2.10${https ? '' : ':8080'}`;
      const parsed = spawnSync(process.env.COMPOSE_BINARY, [
        '--project-name', 'quota-compose-test',
        '--env-file', fileURLToPath(new URL('../.env.example', import.meta.url)),
        ...overlays.flatMap((name) => ['-f', fileURLToPath(new URL(`../${name}`, import.meta.url))]),
        'config', '--format', 'json',
      ], {
        encoding: 'utf8', timeout: 15000,
        env: { ...process.env, PUBLIC_ORIGIN: origin, HTTP_BIND: '0.0.0.0', HTTP_PORT: '8080',
          HTTPS_BIND: '0.0.0.0', TLS_CERT_DIRECTORY: '/tmp/quota-synthetic-tls',
          POSTGRES_PASSWORD: 'synthetic-postgres', DATABASE_PASSWORD: 'synthetic-database',
          JWT_SECRET: 'synthetic-jwt', JWT_ISSUER: 'quota-compose-test',
          MAIL_ASSISTANCE_ENCRYPTION_KEY: 'synthetic-mail', OFFICIAL_SESSION_ENCRYPTION_KEY: 'synthetic-official',
          MANAGED_PROXY_ENCRYPTION_KEY: 'synthetic-proxy', ADMIN_BOOTSTRAP_EMAIL: '', ADMIN_BOOTSTRAP_PASSWORD: '' },
      });
      assert.equal(parsed.status, 0, parsed.stderr);
      const config = JSON.parse(parsed.stdout);
      const backend = config.services.backend;
      assert.equal(backend.environment.APP_REQUIRE_HTTPS, String(https));
      assert.equal(backend.environment.CORS_ALLOWED_ORIGINS, origin);
      assert.equal(backend.ports, undefined);
      assert.equal(config.services.postgres.ports, undefined);
      assert.equal(config.services.web.ports.length, https ? 2 : 1);
      assert.deepEqual(backend.cap_drop, ['ALL']);
      assert.equal(backend.read_only, true);
      assert.ok(backend.security_opt.includes('no-new-privileges:true'));
      assert.equal(backend.cap_add, undefined);
      assert.equal(backend.privileged, undefined);
      if (sourceBuild) assert.equal(backend.build.target, 'browser-runtime');
      else {
        assert.equal(backend.build, undefined);
        assert.equal(config.services.web.build, undefined);
        assert.equal(backend.image, `minjm/codex-quota-guard-backend:0.3.19${browser ? '-browser' : ''}`);
        assert.equal(config.services.web.image, 'minjm/codex-quota-guard-web:0.3.19');
      }
      if (browser) {
        assert.equal(backend.environment.WEB_LOGIN_BROWSER_ENABLED, 'true');
        assert.equal(backend.environment.WEB_LOGIN_BROWSER_HEADLESS, 'true');
        assert.equal(backend.environment.WEB_LOGIN_BROWSER_EXECUTABLE, '/usr/lib/chromium/chrome');
        assert.ok(backend.security_opt.includes('seccomp=./chromium-seccomp.json'));
        assert.deepEqual(backend.tmpfs, ['/tmp:rw,noexec,nosuid,size=512m']);
        assert.equal(Number(backend.mem_limit), 2 * 1024 ** 3);
        assert.equal(Number(backend.shm_size), 256 * 1024 ** 2);
      }
    });
  }
}

test('browser seccomp remains default-deny and permits namespace sandbox creation', () => {
  const profile = JSON.parse(read('chromium-seccomp.json'));
  assert.equal(profile.defaultAction, 'SCMP_ACT_ERRNO');
  for (const name of ['clone', 'setns', 'unshare']) {
    assert.ok(profile.syscalls.some((rule) => rule.action === 'SCMP_ACT_ALLOW'
      && rule.names.includes(name) && !(rule.args?.length) && !(rule.includes?.caps?.length)));
  }
});

test('browser sandbox chroot survives capability filtering without granting container capabilities', () => {
  const profile = JSON.parse(read('chromium-seccomp.json'));
  const rule = profile.syscalls.find((entry) => entry.names.includes('chroot')
    && !entry.includes?.caps?.length && !entry.excludes?.caps?.length);
  assert.ok(rule, 'Chromium user-namespace chroot must be available after cap_drop: ALL');
  assert.deepEqual(rule.names, ['chroot']);
  assert.equal(rule.action, 'SCMP_ACT_ALLOW');
  assert.deepEqual(rule.args, []);
  assert.equal(profile.defaultAction, 'SCMP_ACT_ERRNO');
  assert.deepEqual(JSON.parse(read('registry/compose.yaml')).services.backend.cap_drop, ['ALL']);
  assert.match(read('compose.yaml'), /cap_drop: \[ALL\]/);
  for (const name of ['compose.web-login.yaml', 'registry/compose.web-login.yaml']) {
    const overlay = read(name);
    assert.match(overlay, /no-new-privileges:true/);
    assert.doesNotMatch(overlay, /cap_add:|privileged:|SYS_CHROOT|SYS_ADMIN|unconfined|--no-sandbox/);
  }
});

test('local preview opt-in requires an explicit validated browser path and does not leak backend env to web', () => {
  const preview = readFileSync(new URL('../../../scripts/windows/start-admin-preview.ps1', import.meta.url), 'utf8');
  const worker = readFileSync(new URL('../../../scripts/windows/preview-supervisor.ps1', import.meta.url), 'utf8');
  assert.match(preview, /\[switch\]\$EnableWebLogin/);
  assert.match(preview, /\[string\]\$BrowserExecutable/);
  assert.match(preview, /Assert-PreviewPath \$BrowserExecutable/);
  assert.match(worker, /WEB_LOGIN_BROWSER_ENABLED/);
  assert.match(worker, /WEB_LOGIN_BROWSER_EXECUTABLE/);
  assert.match(worker, /\$uiEnvironment=Get-PreviewBaseEnvironment/);
  assert.match(worker, /\$apiEnvironment\['WEB_LOGIN_BROWSER_HEADLESS'\]='true'/);
  assert.doesNotMatch(worker, /\$env:|\$previous\['NODE_OPTIONS'\]/);
});
