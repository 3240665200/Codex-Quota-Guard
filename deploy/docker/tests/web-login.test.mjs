import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const read = (name) => readFileSync(new URL(`../${name}`, import.meta.url), 'utf8');

test('one non-root backend runtime retains sing-box without bundled browsers or extra sandbox permissions', () => {
  const dockerfile = read('backend.Dockerfile');
  assert.match(dockerfile, /FROM eclipse-temurin:17-jre-alpine AS runtime/);
  assert.match(dockerfile, /COPY --from=proxy-core \/usr\/local\/bin\/sing-box/);
  assert.match(dockerfile, /USER 10001:10001/);
  assert.match(dockerfile, /RUN sing-box version/);
  assert.doesNotMatch(dockerfile, /browser-runtime|chromium|WEB_LOGIN_BROWSER|--no-sandbox|--disable-web-security|--ignore-certificate-errors/i);
  assert.match(read('compose.yaml'), /target: runtime/);
  for (const name of ['compose.web-login.yaml', 'registry/compose.web-login.yaml', 'chromium-seccomp.json']) {
    assert.equal(existsSync(new URL(`../${name}`, import.meta.url)), false, `${name} must not select an obsolete runtime`);
  }
});

test('source and registry deployments expose the same runtime-only private preset contract', () => {
  const source = read('compose.yaml');
  assert.match(source, /OFFICIAL_AUTHORIZATION_PRESET_FILE: \/run\/secrets\/official-authorization\.json/);
  assert.match(source, /source: \.\/secrets\s+target: \/run\/secrets\s+read_only: true\s+bind:\s+create_host_path: false/);
  const base = JSON.parse(read('registry/compose.yaml'));
  const backend = base.services.backend;
  assert.equal(backend.image, 'minjm/codex-quota-guard-backend:0.3.22');
  assert.equal(base.services.web.image, 'minjm/codex-quota-guard-web:0.3.22');
  assert.equal(backend.platform, 'linux/amd64');
  assert.equal(base.services.web.platform, 'linux/amd64');
  assert.equal(backend.environment.OFFICIAL_AUTHORIZATION_PRESET_FILE, '/run/secrets/official-authorization.json');
  assert.deepEqual(backend.volumes, [{ type: 'bind', source: './secrets', target: '/run/secrets', read_only: true, bind: { create_host_path: false } }]);
  assert.equal(backend.environment.APP_REQUIRE_HTTPS, 'false');
  assert.match(source, /APP_REQUIRE_HTTPS: "false"/);
  assert.deepEqual(backend.cap_drop, ['ALL']);
  assert.deepEqual(backend.security_opt, ['no-new-privileges:true']);
  assert.equal(backend.read_only, true);
  assert.equal(backend.ports, undefined);
  assert.doesNotMatch(source + JSON.stringify(base), /WEB_LOGIN_BROWSER|seccomp=|privileged|SYS_ADMIN|-browser/);
});

test('private presets are ignored by Git and both build contexts', () => {
  const root = fileURLToPath(new URL('../../../', import.meta.url));
  const ignored = spawnSync('git', ['check-ignore', '--no-index', 'deploy/docker/secrets/official-authorization.json', 'deploy/docker/registry/secrets/official-authorization.json', 'deploy/docker/offline/secrets/official-authorization.json'], { cwd: root, encoding: 'utf8' });
  assert.equal(ignored.status, 0, ignored.stderr);
  assert.equal(ignored.stdout.trim().split(/\r?\n/).length, 3);
  assert.match(readFileSync(new URL('../../../.dockerignore', import.meta.url), 'utf8'), /^\*\*\/secrets$/m);
  for (const name of ['backend.Dockerfile', 'web.Dockerfile']) {
    assert.doesNotMatch(read(name), /COPY\s+(?:\.\s+\.|[^\n]*secrets)/);
  }
  assert.match(read('.env.example'), /OFFICIAL_AUTHORIZATION_PRESET_FILE/);
});

if (process.env.COMPOSE_BINARY) {
  for (const [sourceBuild, https] of [[false, false], [false, true], [true, false], [true, true]]) {
    test(`Compose parser preserves ${https ? 'HTTPS-only' : 'HTTP+HTTPS'} policy and preset for ${sourceBuild ? 'source' : 'registry'} deployment`, () => {
      const overlays = [sourceBuild ? 'compose.yaml' : 'registry/compose.yaml'];
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
      assert.equal(backend.environment.OFFICIAL_AUTHORIZATION_PRESET_FILE, '/run/secrets/official-authorization.json');
      assert.equal(backend.environment.OFFICIAL_SESSION_ENCRYPTION_KEY, 'synthetic-official');
      assert.equal(backend.environment.MANAGED_PROXY_ENCRYPTION_KEY, 'synthetic-proxy');
      assert.equal(backend.ports, undefined);
      assert.equal(config.services.postgres.ports, undefined);
      assert.equal(config.services.web.ports.length, https ? 2 : 1);
      assert.equal(config.volumes.pgdata.name, 'quota-compose-test_pgdata');
      assert.deepEqual(backend.cap_drop, ['ALL']);
      assert.equal(backend.read_only, true);
      assert.deepEqual(backend.security_opt, ['no-new-privileges:true']);
      assert.equal(backend.cap_add, undefined);
      assert.equal(backend.privileged, undefined);
      assert.equal(backend.volumes.length, 1);
      const preset = backend.volumes[0];
      assert.equal(preset.type, 'bind');
      assert.match(preset.source, /[/\\]secrets$/);
      assert.equal(preset.target, '/run/secrets');
      assert.equal(preset.read_only, true);
      assert.notEqual(preset.bind?.create_host_path, true);
      if (sourceBuild) assert.equal(backend.build.target, 'runtime');
      else {
        assert.equal(backend.build, undefined);
        assert.equal(config.services.web.build, undefined);
        assert.equal(backend.image, 'minjm/codex-quota-guard-backend:0.3.22');
        assert.equal(config.services.web.image, 'minjm/codex-quota-guard-web:0.3.22');
      }
    });
  }
}

test('legacy local preview opt-in validates its explicit browser path and does not leak backend env to web', () => {
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
