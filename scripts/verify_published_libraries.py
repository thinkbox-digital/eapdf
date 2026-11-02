"""Verify the published Maven artifacts against the project lock without exposing credentials."""
import base64
import hashlib
import json
import os
from pathlib import Path
from urllib.request import Request, build_opener, HTTPRedirectHandler
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
lock = json.loads((ROOT / 'third_party/openhtmltopdf/lock.json').read_text())
repository = os.environ['GITHUB_REPOSITORY']
if repository != 'thinkbox-digital/eapdf':
    raise SystemExit('Refusing an unexpected package repository')
class SafeRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urlparse(newurl).scheme != 'https':
            raise SystemExit('Refusing a non-HTTPS package redirect')
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None:
            # GitHub uses signed storage URLs; never forward the registry token.
            redirected.remove_header('Authorization')
        return redirected

opener = build_opener(SafeRedirect)
credential = base64.b64encode((os.environ['GITHUB_ACTOR'] + ':' + os.environ['GITHUB_TOKEN']).encode()).decode()
for artifact in lock['artifacts']:
    relative = Path(artifact['path']).relative_to('third_party/maven').as_posix()
    request = Request('https://maven.pkg.github.com/' + repository + '/' + relative,
                      headers={'Authorization': 'Basic ' + credential})
    with opener.open(request, timeout=60) as response:
        digest = hashlib.file_digest(response, 'sha256').hexdigest()
    if digest != artifact['sha256']:
        raise SystemExit('Published bytes differ: ' + relative)
    print('Verified ' + relative)
