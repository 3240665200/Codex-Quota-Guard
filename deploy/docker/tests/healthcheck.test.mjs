import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const bash = process.env.TEST_BASH || (process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash');
const read = (name) => readFileSync(new URL(`../${name}`, import.meta.url), 'utf8');
const registry = JSON.parse(read('registry/compose.yaml'));
const probe = registry.services.backend.healthcheck.test;

test('one local authentication health probe accepts HTTP 401 and strict HTTPS redirect 302 only', () => {
  assert.equal(probe[0], 'CMD-SHELL');
  assert.match(probe[1], /http:\/\/127\.0\.0\.1:8080\/api\/v1\/users\/me/);
  assert.doesNotMatch(probe[1], /X-Forwarded|--insecure|--location|\s-k(?:\s|$)/);
  // Only curl is replaced. Execute the deployed shell expression with synthetic
  // status/exit results; no Docker daemon, network or real credentials are used.
  const expression = probe[1].replaceAll('$$', '$');
  for (const [status, curlExit, healthy] of [
    ['401', '0', true], ['302', '0', true],
    ['200', '0', false], ['403', '0', false], ['404', '0', false],
    ['500', '0', false], ['000', '7', false], ['401', '7', false],
  ]) {
    const result = spawnSync(bash, ['-c', 'curl() { printf "%s" "$TEST_HTTP_CODE"; return "$TEST_CURL_EXIT"; }; ' + expression], {
      encoding: 'utf8', timeout: 15000,
      env: { ...process.env, TEST_HTTP_CODE: status, TEST_CURL_EXIT: curlExit },
    });
    assert.equal(result.status === 0, healthy, `status=${status}, curlExit=${curlExit}: ${result.stderr}`);
  }
});

test('source and registry use identical health commands without an HTTPS-specific override', () => {
  const sourceLine = read('compose.yaml').split(/\r?\n/).find((line) => line.includes('test: [CMD-SHELL') && line.includes('curl'));
  assert.ok(sourceLine);
  const sourceCommand = JSON.parse(sourceLine.slice(sourceLine.indexOf(',') + 1, sourceLine.lastIndexOf(']')).trim());
  assert.equal(sourceCommand, probe[1]);
  assert.doesNotMatch(read('compose.https.yaml'), /healthcheck:/);
});
