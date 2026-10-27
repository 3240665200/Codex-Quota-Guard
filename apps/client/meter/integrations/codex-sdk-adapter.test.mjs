import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

test('the public SDK override preserves caller options, env isolation and other SDK exports', async () => {
  const directory=await mkdtemp(path.join(os.tmpdir(),'cqg-sdk-adapter-'));
  try {
    await writeFile(path.join(directory,'original.mjs'),'export class Codex {constructor(options){this.options=options}};export const Thread="fixture-thread";');
    const configuration={meterExecutable:'D:\\quota guard\\meter.exe',codexExecutable:'D:\\sdk\\codex.exe'};
    let source=await readFile(new URL('./codex-sdk-adapter.mjs.template',import.meta.url),'utf8');
    source=source.replaceAll('"__CQG_ORIGINAL_IMPORT__"','"./original.mjs"').replace('"__CQG_CONFIGURATION__"',JSON.stringify(configuration));
    const file=path.join(directory,'adapter.mjs');await writeFile(file,source);
    const {Codex,Thread}=await import(pathToFileURL(file));
    const env={CODEX_HOME:'D:/isolated',TOKEN:'fixture-not-for-journal'};
    const options={env,baseUrl:'http://127.0.0.1:18081',config:{service_tier:'fast'}};
    const sdk=new Codex(options);
    assert.equal(sdk.options.codexPathOverride,configuration.meterExecutable);
    assert.equal(sdk.options.env.CODEX_HOME,env.CODEX_HOME);
    assert.equal(sdk.options.env.TOKEN,env.TOKEN);
    assert.equal(sdk.options.baseUrl,options.baseUrl);
    assert.equal(sdk.options.config,options.config);
    assert.equal(Thread,'fixture-thread');
    assert.equal(sdk.options.env.QUOTA_GUARD_CODEX_EXECUTABLE,configuration.codexExecutable);
    assert.deepEqual(env,{CODEX_HOME:'D:/isolated',TOKEN:'fixture-not-for-journal'});
    assert.equal(new Codex({...options,codexPathOverride:'D:/custom/codex.exe'}).options.env.QUOTA_GUARD_CODEX_EXECUTABLE,'D:/custom/codex.exe');
    assert.throws(()=>new Codex({codexPathOverride:configuration.meterExecutable}),/cannot launch itself/);
  } finally {
    assert(path.resolve(directory).startsWith(path.resolve(os.tmpdir())+path.sep+'cqg-sdk-adapter-'));
    await rm(directory,{recursive:true,force:true});
  }
});
