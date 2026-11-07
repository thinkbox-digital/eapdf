"""Create deterministic local beta ZIPs; never publish or sign them."""
from pathlib import Path
import json,hashlib,zipfile
root=Path(__file__).resolve().parents[1]
version=json.loads((root/'package.json').read_text())['version']
release=root/'releases';release.mkdir(exist_ok=True)
results={}
for browser in ['chromium','firefox']:
 build=root/'dist'/browser
 info=json.loads((build/'build-info.json').read_text())
 if info['project'] != version: raise ValueError('Build the current version before packaging')
 manifest=json.loads((build/'manifest.json').read_text())
 if manifest['version'] != version.replace('-beta.','.'): raise ValueError('Extension manifest version differs from package version')
 expected=set(info['files'])|{'build-info.json'}
 actual={path.relative_to(build).as_posix() for path in build.rglob('*') if path.is_file()}
 if actual != expected: raise ValueError(f'Unexpected or missing package files: {actual ^ expected}')
 payloads={}
 for name in sorted(expected):
  path=build/name
  if path.is_symlink() or build.resolve() not in path.resolve().parents: raise ValueError(f'Unsafe package path: {name}')
  payloads[name]=path.read_bytes()
 for name,digest in info['files'].items():
  if hashlib.sha256(payloads[name]).hexdigest()!=digest: raise ValueError(f'Build hash mismatch: {name}')
 name=f'eapdf-viewer-{version}-{browser}.zip'
 with zipfile.ZipFile(release/name,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
  for filename,data in payloads.items():
   entry=zipfile.ZipInfo(filename,date_time=(2026,10,27,0,0,0))
   entry.compress_type=zipfile.ZIP_DEFLATED;entry.create_system=3;entry.external_attr=0o100644<<16
   archive.writestr(entry,data,compress_type=zipfile.ZIP_DEFLATED,compresslevel=9)
  entry=zipfile.ZipInfo('INSTALL.txt',date_time=(2026,10,27,0,0,0));entry.create_system=3;entry.external_attr=0o100644<<16
  archive.writestr(entry,'EA-PDF viewer beta. Extract this ZIP. Chromium: Load unpacked in chrome://extensions. Firefox: Load Temporary Add-on in about:debugging, select manifest.json. Firefox installation is temporary until signed. Open or drop a local PDF. Scroll between pages; Page x of x follows scrolling, and page arrows/entry still work. Open PDF beside an embedded PDF navigates into it, including its own embedded files; Parent returns. View supports PNG/JPEG/GIF and text. Office and other formats use normal browser downloads. Web links require an explicit opening action and optional site permission. No conformance validation. No document data leaves the tab.\n')
 with zipfile.ZipFile(release/name) as archive:
  if set(archive.namelist()) != expected|{'INSTALL.txt'} or len(archive.namelist()) != len(expected)+1:
   raise ValueError('Packaged file set differs from approved files')
  for filename,data in payloads.items():
   if archive.read(filename)!=data: raise ValueError(f'Packaged bytes differ: {filename}')
  if archive.testzip() is not None: raise ValueError('Package CRC verification failed')
 data=(release/name).read_bytes();results[browser]={'file':name,'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data)}
(release/'beta-builds.json').write_text(json.dumps({'version':version,'status':'local beta; unsigned; not published','builds':results},indent=2)+'\n')
print(json.dumps(results,indent=2))
