import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync, unlinkSync, rmdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const script=fileURLToPath(new URL('../src/self_update/apply-update.ps1',import.meta.url));
const installer=fileURLToPath(new URL('../../../../scripts/windows/install-meter-service.ps1',import.meta.url));
const quoted=value=>"'"+value.replaceAll("'","''")+"'";
function run(body){
  const root=mkdtempSync(path.join(tmpdir(),'cqg-update-test-'));
  assert.equal(path.dirname(root),path.resolve(tmpdir()));assert.ok(path.basename(root).startsWith('cqg-update-test-'));
  try {
    const source=`$ErrorActionPreference='Stop'
function Import-TestFunction([string]$Path,[string]$Name) {
 $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($Path,[ref]$tokens,[ref]$errors)
 if($errors.Count){throw 'PowerShell parse failed'}
 $node=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $Name},$true)
 if(!$node){throw 'Function missing'}
 return $node.Extent.Text
}
Invoke-Expression (Import-TestFunction ${quoted(script)} 'Invoke-MeterBinaryTransaction')
Invoke-Expression (Import-TestFunction ${quoted(installer)} 'Select-MeterSetupBinary')
$root=${quoted(root)}
$target=Join-Path $root 'meter.exe';$candidate=Join-Path $root 'new.exe';$backup=Join-Path $root 'old.exe'
$queue=Join-Path $root 'usage-outbox.json';$config=Join-Path $root 'meter-config.json'
[IO.File]::WriteAllText($target,'old');[IO.File]::WriteAllText($candidate,'new')
[IO.File]::WriteAllText($queue,'original queued usage');[IO.File]::WriteAllText($config,'original identity')
$events=[Collections.Generic.List[string]]::new()
$stop={$events.Add('stop')}.GetNewClosure();$start={$events.Add('start')}.GetNewClosure()
${body}
if([IO.File]::ReadAllText($queue) -ne 'original queued usage' -or [IO.File]::ReadAllText($config) -ne 'original identity'){throw 'Usage or identity was changed'}
Write-Output 'PASS'
`;
    const file=path.join(root,'run.ps1');writeFileSync(file,source);
    const powershell=path.join(process.env.SystemRoot || 'C:/Windows','System32/WindowsPowerShell/v1.0/powershell.exe');
    const result=spawnSync(powershell,['-NoLogo','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',file],{encoding:'utf8',windowsHide:true,timeout:20000});
    assert.equal(result.status,0,result.stderr);assert.match(result.stdout,/PASS/);
  } finally {
    for(const name of readdirSync(root))unlinkSync(path.join(root,name));rmdirSync(root);
  }
}
test('hidden update swaps atomically without changing queues or configuration',{skip:process.platform!=='win32'},()=>run(`
Invoke-MeterBinaryTransaction $target $candidate $backup $stop $start {$true}
if([IO.File]::ReadAllText($target) -ne 'new' -or [IO.File]::ReadAllText($backup) -ne 'old'){throw 'Swap failed'}
if(($events -join ',') -ne 'stop,start'){throw 'Unexpected service operations'}
`));
test('unhealthy replacement restores and restarts the previous binary',{skip:process.platform!=='win32'},()=>run(`
$failed=$false
try {Invoke-MeterBinaryTransaction $target $candidate $backup $stop $start {$false}}catch{$failed=$true}
if(!$failed -or [IO.File]::ReadAllText($target) -ne 'old'){throw 'Rollback failed'}
if(($events -join ',') -ne 'stop,start,stop,start'){throw 'Rollback did not restart old service'}
`));
test('failed candidate copy restarts the old service without replacing its executable',{skip:process.platform!=='win32'},()=>run(`
[IO.File]::Delete($candidate);$failed=$false
try {Invoke-MeterBinaryTransaction $target $candidate $backup $stop $start {$true}}catch{$failed=$true}
if(!$failed -or [IO.File]::ReadAllText($target) -ne 'old' -or ($events -join ',') -ne 'stop,start'){throw 'Old service not restored'}
`));
test('client installers preserve a newer independently updated meter',{skip:process.platform!=='win32'},()=>run(`
$selected=Select-MeterSetupBinary 'bundled.exe' '0.2.13' 'installed.exe' '0.2.14'
if($selected.Path -ne 'installed.exe' -or $selected.Version -ne '0.2.14'){throw 'New service would be downgraded'}
$selected=Select-MeterSetupBinary 'bundled.exe' '0.2.13' 'installed.exe' '0.2.12'
if($selected.Path -ne 'bundled.exe'){throw 'Old service would not upgrade'}
`));
