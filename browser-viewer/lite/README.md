# Retained Lite build infrastructure

Viewer Lite is discontinued as of 18 November 2026. Release builds produce Full
and Even More Lite. The exporter, generic PDF.js hooks and packaging helpers in
this directory remain shared infrastructure for [Even More Lite](../even-more-lite/README.md).
The last published Lite packages are in [2.2.0-beta.3](https://github.com/thinkbox-digital/eapdf/releases/tag/v2.2.0-beta.3).

# Historical Lite reference

A standalone export of the EA-PDF browser viewer. It retains the existing layout,
message and folder navigation, attachments, metadata,
CSV/XMP export, accessibility and ordinary PDF.js text search, including collection
scanning. Conversations, Portfolio presentation and embedded metadata/full-text search indexes are absent.
The full viewer remains the authoritative source for shared fixes.

Lite uses the same pinned PDF.js version with generic document-part/DPM,
associated-file, metadata, collection membership and embedded-navigation hooks. The hooks
have no EA-PDF application dependency. Upstream xref storage and parsed-object
caching are unchanged. Ordinary decoder caps remain defensive limits; they are
not large-file optimizations or a hard process-memory sandbox.

Inputs are limited to 32 MiB and 2,048 pages per PDF, metadata to 2 MiB, and decoded
attachments to 16 MiB each. A session permits 32 retained child documents and four child levels. Released
children do not consume a lifetime opening allowance; background scans can read
more than 32 messages while foreground navigation remains available.
Those child-document limits are separate from one PDF's generic asset inspection:
it stops after 1,000,000 object visits, 1,024 levels, or 16,384 file specifications
(with at most 32 streams per file), reports an incomplete inventory and avoids
fetching queued objects after the cap. Tagged-PDF accessibility trees are checked
before rendering and are limited to 128 levels, 50,000 nodes and one million
retained string characters.
Limit failures and partial inventories are reported. Use the full viewer for
larger archives. Indexed PDFs remain readable; searches scan PDF.js text instead.
No conformance validation is performed.

## Test the viewer

See INSTALL.txt and [Privacy](PRIVACY.md). Packages are unsigned beta builds.

## Build independently

Use the exact Node/npm versions in package.json:

```sh
npm ci --ignore-scripts
npm run check
npm run build
npm test
npm run package
```

Build outputs are dist/chromium, dist/firefox and dist/web. Packaging additionally
uses Python 3 from the standard library. No Java, creator, validator, backend or
parent repository is required. Normal builds use the included hash-verified hooks
and do not download PDF.js sources.

Reproduce the hook bundles from the pinned upstream source (network access needed
for the source archive and upstream npm lockfile):

```sh
npm run build:pdfjs
```

This verifies existing output hashes. Intentional hook updates use --write, review
all source/input/output hashes, then repeat without --write from a clean build.
The recipe retains shared patches 0001, 0002 and the navigation/metadata subset of 0007–0009, plus the upstream RTL ligature correction in 0010, opt-in page-only extraction in 0011, equivalent font-name quote character classes in 0012 and the tricky TrueType family-name correction in 0013, with explicit checked
adaptations for their original archive-dependent context. Portfolio schema/layout
inspection and folder-thumbnail decoding are excluded; collection membership,
folders, associated files and GoToE navigation remain available. Patch 0003 is excluded;
xref.js is checked byte-for-byte against upstream. Selected source API regressions
run separately from the viewer. This is a local API extension, not an upstream
acceptance claim, and does not run PDF.js's full rendering corpus.

## Maintain the export

Make shared fixes in the main eapdf repository, then run its build:lite command.
The exporter selects source modules through their import graph and substitutes
small optional-feature adapters. Do not hand-edit generated copies. The export
manifest records the originating commit, each shared input's content hash, the
adapters, and each exported file's hash. A dirty source tree is explicitly marked.
A changed shared hook input fails verification until its adaptation/tests and
hashes are reviewed and the Lite bundles rebuilt.

Keep repository-specific issues/CI outside the generated source paths when this
export is hosted separately. Review exported diffs before committing updates;
publication and GitHub repository creation are separate actions.
