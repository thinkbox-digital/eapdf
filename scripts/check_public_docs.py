"""Block private contributor identifiers in first-party public documentation."""
from pathlib import Path
import base64
import re
import subprocess
import sys
import zipfile
root=Path(__file__).resolve().parents[1]
# Encoded to avoid reproducing blocked identifiers in public policy source.
blocked=[base64.b64decode(v).decode() for v in ['QkZP','QnJlbWZvcmQ=','QmlnIEZhY2VsZXNz']]
pattern=re.compile(r'(?<![A-Za-z0-9])(?:'+ '|'.join(map(re.escape,blocked))+r')(?![A-Za-z0-9])',re.I)
paths=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=root).decode().split('\0')
failures=[]
for name in sorted(set(filter(None,paths))):
    p=root/name
    if not p.is_file() or any(part in p.parts for part in ['third_party','third_party_licenses','vendor','node_modules']):
        continue
    if name in ['THIRD_PARTY_NOTICES.md','docs/THIRD_PARTY_LICENSES.md']:
        continue
    if (p.suffix.lower() in ['.md','.txt'] and ('/' not in name or name.startswith(('docs/', 'browser-viewer/docs/')) or name.endswith('/README.md'))) or (name.startswith('browser-viewer/docs/') and p.suffix=='.json') or (name.startswith('docs/') and p.suffix.lower()=='.html'):
        for number,line in enumerate(p.read_text(errors='replace').splitlines(),1):
            if pattern.search(line):failures.append(f'{name}:{number}: private contributor reference; anonymize it')
    elif p.suffix.lower()=='.docx' and ('/' not in name or name.startswith('docs/')):
        with zipfile.ZipFile(p) as archive:
            if any(pattern.search(archive.read(n).decode(errors='replace')) for n in archive.namelist() if n.endswith('.xml')):
                failures.append(f'{name}: private contributor reference in document text')
if failures:
    print('\n'.join(failures),file=sys.stderr)
    sys.exit(1)
print('Public documentation privacy check passed.')
