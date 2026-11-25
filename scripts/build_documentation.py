#!/usr/bin/env python3
"""Build the public site from an explicit set of existing documentation sources."""
import argparse
import json
import logging
import posixpath
import re
import shutil
import tempfile
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit, urlunsplit

from bs4 import BeautifulSoup
from markdownify import markdownify
from mkdocs.commands.build import build
from mkdocs.config import load_config

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'https://github.com/thinkbox-digital/eapdf'
SOURCES = {
    'README.md': 'index.md',
    'docs/site/privacy.md': 'privacy.md',
    'docs/PROJECT_STATUS.md': 'developers/project-status.md',
    'docs/ARCHITECTURE.md': 'developers/architecture.md',
    'docs/README.md': 'developers/documentation-map.md',
    'config/README.md': 'reference/configuration.md',
    'docs/GMAIL.md': 'reference/gmail.md',
    'docs/CONVERSION_OPERATIONS.md': 'reference/operations.md',
    'docs/VALIDATOR_COVERAGE.md': 'reference/validation.md',
    'validator-desktop/README.md': 'reference/java-validator.md',
    'docs/security/HARDENING.md': 'reference/security.md',
    'CONTRIBUTING.md': 'developers/contributing.md',
    'docs/PACKAGES.md': 'developers/packages.md',
    'docs/WEBSITE.md': 'developers/website.md',
    'SECURITY.md': 'developers/security.md',
}
HOMEPAGE_ASSETS = {
    'ea-pdf-examples.zip': 'ea-pdf-examples.zip',
    'docs/manual/user-manual.html': 'manual/user-manual.html',
    'docs/EA-PDF_User_Manual.pdf': 'EA-PDF_User_Manual.pdf',
    'docs/EA-PDF_Software_Architecture.pdf': 'EA-PDF_Software_Architecture.pdf',
    'docs/screenshots/readme-viewer-1m.png': 'screenshots/readme-viewer-1m.png',
    'docs/screenshots/creator-1280x1024.png': 'screenshots/creator-1280x1024.png',
    'docs/screenshots/validator-1280x800.png': 'screenshots/validator-1280x800.png',
    'docs/screenshots/viewer-1280x800.png': 'screenshots/viewer-1280x800.png',
    'examples/eml/eml_seating_archive_1m.pdf': 'examples/eml/eml_seating_archive_1m.pdf',
}


def rewrite_links(text, source, destination, site_paths):
    """Keep selected documents on-site; link other repository files to GitHub."""
    def replace(match):
        value = match.group(2)
        url = urlsplit(value)
        if url.scheme or url.netloc or not url.path:
            return match.group(0)
        if url.path.startswith('/'):
            raise ValueError(f'{source}: absolute local link {url.path}')
        path = posixpath.normpath(posixpath.join(posixpath.dirname(source), url.path))
        if not (ROOT / path).exists():
            raise ValueError(f'{source}: missing link target {path}')
        if path in site_paths:
            target = posixpath.relpath(site_paths[path], posixpath.dirname(destination))
        else:
            kind = 'tree' if (ROOT / path).is_dir() else 'blob'
            target = f'{REPOSITORY}/{kind}/main/{quote(path)}'
        return match.group(1) + urlunsplit(('', '', target, url.query, url.fragment)) + match.group(3)
    return re.sub(r'(!?\[[^\]\n]*\]\()([^\s)]+)(\))', replace, text)


def check_release_versions():
    current = (ROOT / 'VERSION').read_text().strip()
    viewer = json.loads((ROOT / 'browser-viewer/package.json').read_text())['version']
    release_pages = ('docs/manual/user-manual.html', 'README.md', 'docs/site/downloads.md')
    # A local viewer-only beta can precede publication. Keep released download
    # references tied to the immutable release notes, not nonexistent assets.
    release_notes = ROOT / 'docs/releases' / (current + '.md')
    published = re.search(r'viewer \*\*(\d+\.\d+\.\d+-beta\.\d+)\*\*', release_notes.read_text()) if release_notes.exists() else None
    for path in release_pages:
        text = (ROOT / path).read_text()
        for version in re.findall(r'\b\d+\.\d+\.\d+-beta\.\d+\b', text):
            expected = {current, viewer}
            if path in release_pages and published:
                expected.add(published.group(1))
            if version not in expected:
                raise ValueError(f'{path}: stale version {version}; expected {expected}')


def assemble(destination):
    check_release_versions()
    shutil.copytree(ROOT / 'docs/site', destination, dirs_exist_ok=True)
    for source, target in SOURCES.items():
        out = destination / target
        out.parent.mkdir(parents=True, exist_ok=True)
        site_paths = SOURCES | HOMEPAGE_ASSETS if source == 'README.md' else SOURCES
        out.write_text(rewrite_links((ROOT / source).read_text(), source, target, site_paths))
    for source, target in HOMEPAGE_ASSETS.items():
        out = destination / target
        out.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / source, out)
    manual = BeautifulSoup((ROOT / 'docs/manual/user-manual.html').read_text(), 'html.parser')
    chapters = manual.select('section.chapter')
    if len(chapters) != 12:
        raise ValueError('Manual chapters changed: update the website navigation')
    for chapter in chapters:
        if chapter['id'] == 'start':
            continue
        # Preserve captions and screenshot alt text while generating a real MkDocs TOC.
        for caption in chapter.select('figcaption'):
            caption.name = 'p'
        content = markdownify(str(chapter), heading_style='ATX', bullets='-')
        out = destination / 'manual' / (chapter['id'] + '.md')
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(content)
    shutil.copytree(ROOT / 'docs/manual/screenshots', destination / 'manual/screenshots')


def check_site(output, site_url):
    output = output.resolve()
    prefix = urlsplit(site_url).path
    errors = []
    for page in output.rglob('*.html'):
        soup = BeautifulSoup(page.read_text(), 'html.parser')
        for tag in soup.select('[href], [src]'):
            url = urlsplit(tag.get('href', tag.get('src', '')))
            if url.scheme or url.netloc or not url.path:
                continue
            if url.path.startswith('/'):
                if not url.path.startswith(prefix):
                    errors.append(f'{page.name}: URL outside site prefix {url.path}')
                    continue
                target = (output / unquote(url.path[len(prefix):])).resolve()
            else:
                target = (page.parent / unquote(url.path)).resolve()
            if target.is_dir():
                target = (target / 'index.html').resolve()
            if not target.is_relative_to(output):
                errors.append(f'{page.name}: URL outside generated site {url.path}')
            elif not target.exists():
                errors.append(f'{page.name}: missing {url.path}')
    if errors:
        raise ValueError('Broken website links: ' + '; '.join(errors))
    print(f'Checked {len(list(output.rglob("*.html")))} HTML pages: local links and assets exist.')


def main():
    logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path, help='Generated site directory outside the source tree')
    args = parser.parse_args()
    output = args.output.resolve()
    if output == ROOT or ROOT in output.parents or output in ROOT.parents:
        parser.error('Use a separate build directory, for example /tmp/eapdf-site')
    with tempfile.TemporaryDirectory(prefix='eapdf-docs-') as work:
        docs = Path(work) / 'docs'
        assemble(docs)
        config = load_config(config_file=str(ROOT / 'mkdocs.yml'), docs_dir=str(docs), site_dir=str(output))
        build(config)
        check_site(output, config.site_url)


if __name__ == '__main__':
    main()
