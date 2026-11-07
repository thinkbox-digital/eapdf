import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,copyFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
test('optimized Python still rejects stale versions, changed bytes and unexpected package files',async()=>{
 const root=await mkdtemp(join(tmpdir(),'eapdf-package-test-'));
 try{
  await mkdir(join(root,'scripts'));await copyFile('scripts/package-beta.py',join(root,'scripts/package-beta.py'));
  await writeFile(join(root,'package.json'),JSON.stringify({version:'1.0.0-beta.1'}));
  for(const browser of ['chromium','firefox']){
   const dir=join(root,'dist',browser);await mkdir(dir,{recursive:true});
   const manifest=JSON.stringify({version:'1.0.0.1'});await writeFile(join(dir,'manifest.json'),manifest);
   await writeFile(join(dir,'build-info.json'),JSON.stringify({project:'1.0.0-beta.1',files:{'manifest.json':createHash('sha256').update(manifest).digest('hex')}}));
  }
  const run=()=>spawnSync('python3',['-O',join(root,'scripts/package-beta.py')],{encoding:'utf8'});
  assert.equal(run().status,0);
  const dir=join(root,'dist/chromium');
  await writeFile(join(dir,'unexpected.txt'),'extra');assert.match(run().stderr,/Unexpected or missing/);await rm(join(dir,'unexpected.txt'));
  await writeFile(join(dir,'manifest.json'),'{"version":"1.0.0.1", "extra":true}');assert.match(run().stderr,/hash mismatch/);
  await writeFile(join(dir,'manifest.json'),'{"version":"0.0.0"}');assert.match(run().stderr,/manifest version differs/);
  await writeFile(join(root,'package.json'),'{"version":"2.0.0"}');assert.match(run().stderr,/current version/);
 }finally{await rm(root,{recursive:true,force:true});}
});
