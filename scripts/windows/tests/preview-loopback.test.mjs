import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
const hook = fileURLToPath(new URL('../preview-loopback.cjs', import.meta.url));
for (const args of ['0', '0,"0.0.0.0"', '{port:0,host:"0.0.0.0"}']) {
  test(`preview forces literal loopback for listen(${args})`, () => {
    const code = `const s=require('node:net').createServer();s.listen(${args},()=>{console.log(s.address().address);s.close()})`;
    const result = spawnSync(process.execPath, ['--require', hook, '-e', code], {encoding:'utf8',timeout:5000});
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout.trim(), '127.0.0.1');
  });
}
