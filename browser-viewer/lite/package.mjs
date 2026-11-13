// Deterministic ZIP packages with byte-for-byte verification and SHA-256 sidecars.
import {readFile,readdir,mkdir,writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
import {sha256} from './verify-hooks.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));process.chdir(root);
const {version}=JSON.parse(await readFile('package.json','utf8'));await mkdir('releases',{recursive:true});
// Python is used only to create portable deterministic ZIPs, never by the viewer.
execFileSync('python3',['-c',`
from pathlib import Path
import hashlib,json,zipfile
root=Path.cwd();version=json.loads((root/'package.json').read_text())['version'];release=root/'releases'
for browser in ['chromium','firefox','web']:
 build=root/'dist'/browser;info=json.loads((build/'build-info.json').read_text());assert info['project']==version
 files={p.relative_to(build).as_posix():p.read_bytes() for p in build.rglob('*') if p.is_file()}
 assert set(files)==set(info['files'])|{'build-info.json'}
 for name,digest in info['files'].items():assert hashlib.sha256(files[name]).hexdigest()==digest,name
 destination=release/f'eapdf-viewer-lite-{version}-{browser}.zip'
 with zipfile.ZipFile(destination,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
  for name,data in sorted(files.items()):
   entry=zipfile.ZipInfo(name,date_time=(2026,11,13,0,0,0));entry.create_system=3;entry.external_attr=0o100644<<16
   archive.writestr(entry,data,compress_type=zipfile.ZIP_DEFLATED,compresslevel=9)
 with zipfile.ZipFile(destination) as archive:
  assert archive.testzip() is None;assert set(archive.namelist())==set(files)
  for name,data in files.items():assert archive.read(name)==data,name
 print(destination.name)
`],{stdio:'inherit'});
const sums=[];for(const name of (await readdir('releases')).filter(name=>name.endsWith('.zip')).sort())sums.push(`${sha256(await readFile('releases/'+name))}  ${name}`);
await writeFile('releases/SHA256SUMS',sums.join('\n')+'\n');
console.log('Verified ZIP contents and wrote releases/SHA256SUMS.');
