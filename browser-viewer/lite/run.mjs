// One command produces a source repository and verified Lite test packages locally.
import {cp,mkdir,mkdtemp,readFile,readdir,rm,writeFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import {tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
const root=fileURLToPath(new URL('../',import.meta.url));
const temporary=await mkdtemp(join(tmpdir(),'eapdf-lite-release-')),source=join(temporary,'source');
const run=(cmd,args,cwd=source)=>execFileSync(cmd,args,{cwd,stdio:'inherit'});
try {
 run(process.execPath,['lite/export.mjs','--output',source],root);
 run('npm',['ci','--ignore-scripts','--no-audit','--no-fund']);
 for(const script of ['check','build','test','package'])run('npm',['run',script]);
 const {version}=JSON.parse(await readFile(join(source,'package.json'),'utf8'));
 const name=`eapdf-viewer-lite-${version}-source.zip`;
 run('python3',['-c',`
from pathlib import Path
import json,zipfile
root=Path.cwd();manifest=json.loads((root/'export-manifest.json').read_text());names=sorted(set(manifest['files'])|{'export-manifest.json'})
path=root/'releases'/${JSON.stringify(name)}
with zipfile.ZipFile(path,'w') as archive:
 for name in names:
  entry=zipfile.ZipInfo(name,date_time=(2026,11,13,0,0,0));entry.create_system=3;entry.external_attr=0o100644<<16
  archive.writestr(entry,(root/name).read_bytes(),compress_type=zipfile.ZIP_DEFLATED,compresslevel=9)
with zipfile.ZipFile(path) as archive:
 assert archive.testzip() is None;assert set(archive.namelist())==set(names)
 for name in names:assert archive.read(name)==(root/name).read_bytes()
`]);
 const release=join(root,'releases');await mkdir(release,{recursive:true});const sums=[];
 for(const name of (await readdir(join(source,'releases'))).filter(name=>name.endsWith('.zip')).sort()) {
  const data=await readFile(join(source,'releases',name));await writeFile(join(release,name),data);
  sums.push(`${createHash('sha256').update(data).digest('hex')}  ${name}`);
 }
 await writeFile(join(release,`eapdf-viewer-lite-${version}-SHA256SUMS`),sums.join('\n')+'\n');
 await cp(join(source,'export-manifest.json'),join(release,`eapdf-viewer-lite-${version}-provenance.json`));
 console.log(`EA-PDF Viewer Lite packages and checksums: ${release}`);
} finally {await rm(temporary,{recursive:true,force:true});}
