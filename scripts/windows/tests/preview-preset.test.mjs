import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const starter = readFileSync(new URL('../start-admin-preview.ps1', import.meta.url), 'utf8');
const worker = readFileSync(new URL('../preview-supervisor.ps1', import.meta.url), 'utf8');

test('preview forwards a validated preset file path through its launch request', () => {
  assert.match(starter, /\[string\]\$AuthorizationPresetFile\s*=\s*''/);
  assert.match(starter, /Assert-PreviewPath \$AuthorizationPresetFile/);
  assert.match(starter, /PREVIEW_AUTHORIZATION_PRESET_INVALID/);
  assert.match(starter, /AuthorizationPresetFile=\$AuthorizationPresetFile/);
});

test('supervisor supplies the preset only to the backend and validates it again', () => {
  assert.match(worker, /Assert-PreviewPath \$AuthorizationPresetFile/);
  assert.match(worker, /PREVIEW_AUTHORIZATION_PRESET_INVALID/);
  assert.match(worker, /\$apiEnvironment\['OFFICIAL_AUTHORIZATION_PRESET_FILE'\]=\$AuthorizationPresetFile/);
  assert.doesNotMatch(worker, /\$uiEnvironment\['OFFICIAL_AUTHORIZATION_PRESET_FILE'\]/);
  assert.match(worker, /\$request\.PSObject\.Properties\['AuthorizationPresetFile'\]/);
});
