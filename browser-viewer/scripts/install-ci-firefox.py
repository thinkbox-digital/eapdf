"""Install checksum-pinned Linux Firefox/WebDriver into the given temporary directory."""
import hashlib
from pathlib import Path
import sys
import tarfile
import urllib.request

root = Path(sys.argv[1]); root.mkdir(parents=True, exist_ok=True)
artifacts = [
    ('firefox.tar.xz', 'https://archive.mozilla.org/pub/firefox/releases/158.0.1/linux-x86_64/en-US/firefox-158.0.1.tar.xz', 'sha512', 'f15f8b281c4ead2ef35d0b56c59a0ead825521d7243d16f3e7c36f0c2732101ae1f564e044a7aad1e04c310f70064611d13434fb24d052a662356ce068d7d3ec'),
    ('geckodriver.tar.gz', 'https://github.com/mozilla/geckodriver/releases/download/v0.36.0/geckodriver-v0.36.0-linux64.tar.gz', 'sha256', '0bde38707eb0a686a20c6bd50f4adcc7d60d4f73c60eb83ee9e0db8f65823e04'),
]
for name, url, algorithm, expected in artifacts:
    archive = root / name
    with urllib.request.urlopen(url, timeout=120) as response, archive.open('wb') as output:
        while chunk := response.read(1024 * 1024): output.write(chunk)
    with archive.open('rb') as source:
        if hashlib.file_digest(source, algorithm).hexdigest() != expected:
            raise ValueError(f'Checksum mismatch: {name}')
    with tarfile.open(archive) as source: source.extractall(root, filter='data')
    archive.unlink()
