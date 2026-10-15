import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
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

test('browser seccomp remains default-deny and permits namespace sandbox creation', () => {
  const profile = JSON.parse(read('chromium-seccomp.json'));
  assert.equal(profile.defaultAction, 'SCMP_ACT_ERRNO');
  for (const name of ['clone', 'setns', 'unshare']) {
    assert.ok(profile.syscalls.some((rule) => rule.action === 'SCMP_ACT_ALLOW'
      && rule.names.includes(name) && !(rule.args?.length) && !(rule.includes?.caps?.length)));
  }
});

test('local preview opt-in requires an explicit validated browser path and does not leak backend env to web', () => {
  const preview = readFileSync(new URL('../../../scripts/windows/start-admin-preview.ps1', import.meta.url), 'utf8');
  assert.match(preview, /\[switch\]\$EnableWebLogin/);
  assert.match(preview, /\[string\]\$BrowserExecutable/);
  assert.match(preview, /WEB_LOGIN_BROWSER_ENABLED/);
  assert.match(preview, /WEB_LOGIN_BROWSER_EXECUTABLE/);
  assert.match(preview, /\$runtimeKeys \+ \$featureKeys \+ \$browserKeys/);
  assert.match(preview, /WEB_LOGIN_BROWSER_HEADLESS = 'true'/);
});
