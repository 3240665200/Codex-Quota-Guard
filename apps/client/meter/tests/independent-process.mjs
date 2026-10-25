import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// This smoke test uses only a generated temporary profile and loopback HTTP.
// It never starts/stops an SCM service or reads an installed meter credential.
assert.ok(process.argv[2], 'Pass the compiled meter executable path.');
const binary = path.resolve(process.argv[2]);
const reportDirectory = process.argv[3] ? path.resolve(process.argv[3]) : fileURLToPath(new URL('../../../../dist/meter-independent-process/', import.meta.url));
await fs.mkdir(reportDirectory, { recursive: true });
const expectedVersion = (await fs.readFile(new URL('../Cargo.toml', import.meta.url), 'utf8')).match(/^version = "([^"]+)"/m)?.[1];
assert.ok(expectedVersion, 'meter manifest version must exist');
const reportPath = path.join(reportDirectory, 'production-meter-smoke-result.json');
const report = {
  startedAt: new Date().toISOString(),
  passed: false,
  binary,
  nativeGuard: true,
  desktopLeaseCreated: false,
  usesRealAccount: false,
  usesRealService: false,
  requests: [],
  checks: [],
};

let fixtureRoot;
let server;
let phase = 'offline';
const mockErrors = [];

function check(name, condition) {
  report.checks.push({ name, passed: Boolean(condition) });
  assert.ok(condition, name);
}

function runBinary(arguments_, timeoutMs = 20000) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const child = spawn(binary, arguments_, {
      cwd: fixtureRoot || os.tmpdir(),
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    let timedOut = false;
    child.stdout.on('data', chunk => { stdout += chunk.toString(); });
    child.stderr.on('data', chunk => { stderr += chunk.toString(); });
    // This handle belongs only to this test's own child process.
    const timeout = setTimeout(() => {
      timedOut = true;
      child.kill();
    }, timeoutMs);
    child.once('error', error => {
      clearTimeout(timeout);
      reject(error);
    });
    child.once('close', (exitCode, signal) => {
      clearTimeout(timeout);
      resolve({ exitCode, signal, timedOut, stdout: stdout.trim(), stderr: stderr.trim(), durationMs: Date.now() - started });
    });
  });
}

async function jsonAt(file) {
  return JSON.parse(await fs.readFile(file, 'utf8'));
}

try {
  report.binarySha256 = createHash('sha256').update(await fs.readFile(binary)).digest('hex');
  report.versionCommand = await runBinary(['--version']);
  check(`production binary version is ${expectedVersion}`, report.versionCommand.exitCode === 0 && report.versionCommand.stdout === `quota-guard-meter ${expectedVersion}`);

  fixtureRoot = await fs.mkdtemp(path.join(os.tmpdir(), 'pingche-production-meter-smoke-'));
  const codexHome = path.join(fixtureRoot, '.codex');
  const sessions = path.join(codexHome, 'sessions');
  const stateDirectory = path.join(fixtureRoot, 'state');
  const statusPath = path.join(fixtureRoot, 'meter-status.json');
  const guardStatusPath = path.join(fixtureRoot, 'ccswitch-guard-status.json');
  const leasePath = path.join(fixtureRoot, 'ccswitch-guard.json');
  const logPath = path.join(sessions, 'synthetic.jsonl');
  const configPath = path.join(fixtureRoot, 'synthetic-config.json');
  const outboxPath = path.join(stateDirectory, 'usage-outbox.json');
  const deviceId = randomUUID();
  const userId = randomUUID();
  await fs.mkdir(sessions, { recursive: true });
  await fs.mkdir(stateDirectory, { recursive: true });
  await fs.writeFile(path.join(stateDirectory, 'scan-state.json'), JSON.stringify({ files: {} }));
  await fs.writeFile(path.join(stateDirectory, 'meter-credential.json'), JSON.stringify({
    deviceId,
    deviceToken: 'A'.repeat(43),
    userId,
    ownerVerified: true,
  }));
  const row = {
    type: 'token_usage_record',
    timestamp: new Date().toISOString(),
    payload: { response_id: `synthetic-production-${randomUUID()}`, usage: { total_tokens: 42 } },
  };
  await fs.writeFile(logPath, JSON.stringify(row) + '\n');

  server = http.createServer(async (request, response) => {
    try {
      const chunks = [];
      let bytes = 0;
      for await (const chunk of request) {
        bytes += chunk.length;
        assert.ok(bytes <= 65536, 'mock request must stay bounded');
        chunks.push(chunk);
      }
      const body = Buffer.concat(chunks).toString('utf8');
      const parsed = body ? JSON.parse(body) : {};
      assert.equal(request.method, 'POST');
      assert.equal(request.headers.authorization, undefined);
      assert.equal(request.headers['x-quota-guard-meter'], 'A'.repeat(43));
      assert.ok(['/api/v1/meter/usage/events', '/api/v1/meter/heartbeat'].includes(request.url));
      const responseStatus = phase === 'offline' && request.url === '/api/v1/meter/usage/events' ? 503 : 200;
      report.requests.push({
        phase,
        method: request.method,
        path: request.url,
        responseStatus,
        authorizationPresent: false,
        meterHeaderPresent: true,
        ...(parsed.events ? { events: parsed.events } : {}),
      });
      response.writeHead(responseStatus, { 'Content-Type': 'application/json', Connection: 'close' });
      response.end(JSON.stringify(parsed.events ? { acceptedEvents: parsed.events.length } : { seenAt: new Date().toISOString() }));
    } catch (error) {
      mockErrors.push(error.message);
      response.writeHead(400, { Connection: 'close' });
      response.end('{}');
    }
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const address = server.address();
  check('mock binds only to IPv4 loopback', address.address === '127.0.0.1');
  await fs.writeFile(configPath, JSON.stringify({
    serverUrl: `http://127.0.0.1:${address.port}`,
    codexHome,
    pairingInbox: path.join(fixtureRoot, 'unused-pairing-inbox.json'),
    stateDir: stateDirectory,
    statusFile: statusPath,
    deviceId,
    ownerUserSid: null,
  }));

  // Asynchronous spawn keeps the parent HTTP mock responsive while the real
  // executable executes MeterRuntime::new and its native Windows guard.
  report.offlineRun = await runBinary(['run', '--config', configPath, '--once']);
  report.offlineStatus = await jsonAt(statusPath);
  report.offlineGuardStatus = await jsonAt(guardStatusPath);
  const offlineOutbox = await jsonAt(outboxPath);
  report.offlineOutbox = { pendingEvents: offlineOutbox.pending.length, totalTokens: offlineOutbox.pending.reduce((sum, event) => sum + event.totalTokens, 0) };
  check('503 is reported as a failed single tick', report.offlineRun.exitCode === 1 && report.offlineStatus.errorCode === 'METER_BACKEND_UNAVAILABLE');
  check('native guard is INACTIVE without a desktop lease', report.offlineStatus.ccSwitchGuard === 'INACTIVE' && report.offlineGuardStatus.ccSwitchGuard === 'INACTIVE');
  check('offline usage is durably queued as one event with 42 tokens', offlineOutbox.pending.length === 1 && offlineOutbox.pending[0].totalTokens === 42 && report.offlineStatus.pendingEvents === 1);
  const offlineEventId = offlineOutbox.pending[0].eventId;

  // Delete exactly the synthetic source row created by this test, inside its
  // own generated temporary profile, before starting a fresh meter process.
  check('synthetic source path remains inside the generated fixture', path.resolve(logPath).startsWith(path.resolve(fixtureRoot) + path.sep));
  await fs.unlink(logPath);
  report.syntheticSourceDeletedBeforeRecovery = true;
  phase = 'recovery';
  report.recoveryRun = await runBinary(['run', '--config', configPath, '--once']);
  report.recoveryStatus = await jsonAt(statusPath);
  report.recoveryGuardStatus = await jsonAt(guardStatusPath);
  const recoveryOutbox = await jsonAt(outboxPath);
  report.recoveryOutbox = { pendingEvents: recoveryOutbox.pending.length };
  check('fresh meter process successfully reports after synthetic source deletion', report.recoveryRun.exitCode === 0 && report.recoveryStatus.state === 'REPORTED' && report.recoveryStatus.reportedEvents === 1);
  check('native guard stays INACTIVE after restart', report.recoveryStatus.ccSwitchGuard === 'INACTIVE' && report.recoveryGuardStatus.ccSwitchGuard === 'INACTIVE');
  check('acknowledged usage is removed from the outbox', recoveryOutbox.pending.length === 0 && report.recoveryStatus.pendingEvents === 0);
  const uploads = report.requests.filter(request => request.path === '/api/v1/meter/usage/events');
  const successfulUploads = uploads.filter(request => request.responseStatus === 200);
  check('one failed upload and exactly one successful usage upload occur', uploads.length === 2 && successfulUploads.length === 1 && uploads[0].responseStatus === 503);
  check('retry carries the same event ID and exactly 42 tokens', uploads.every(request => request.events.length === 1 && request.events[0].eventId === offlineEventId && request.events[0].totalTokens === 42));
  check('no Authorization header or unexpected endpoint reaches the mock', mockErrors.length === 0);
  check('no desktop lease was created', await fs.stat(leasePath).then(() => false, error => error.code === 'ENOENT'));
  report.passed = true;
} catch (error) {
  report.failure = error.message;
} finally {
  if (server?.listening) {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
  if (fixtureRoot) {
    const relative = path.relative(path.resolve(os.tmpdir()), path.resolve(fixtureRoot));
    const safeFixture = relative && !relative.startsWith('..') && !path.isAbsolute(relative) && path.basename(fixtureRoot).startsWith('pingche-production-meter-smoke-');
    if (safeFixture) {
      await fs.rm(fixtureRoot, { recursive: true, force: true });
      report.temporaryFixtureRemoved = true;
    } else {
      report.temporaryFixtureRemoved = false;
      report.passed = false;
      report.failure = 'Refused to remove a fixture outside the intended temporary directory';
    }
  }
  report.mockErrors = mockErrors;
  report.finishedAt = new Date().toISOString();
  report.limitations = [
    'Foreground run --once covers the packaged production runtime and native guard; it does not exercise SCM startup/recovery or long-running scheduling.',
    'The mock proves local durable capture and one successful usage request, not central database deduplication or real billing.',
    'The fixture contains only one synthetic token_usage_record and does not cover legacy event_msg/token_count.',
  ];
  await fs.writeFile(reportPath, JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify({ passed: report.passed, binaryVersion: report.versionCommand?.stdout, checks: report.checks, reportPath, failure: report.failure }, null, 2));
  process.exitCode = report.passed ? 0 : 1;
}
