# Viewer 0.3.0-beta.1 verification

Release date: 2026-10-27. Included with creator and validator 1.6.0-beta.1.

Opening now renders the selected page first, then nearby pages. Scrolling finds
visible pages without measuring every page on every frame. Repeated scroll work
is combined, and retained page canvases share a 32-million-pixel budget. This is
a canvas budget, not a limit on total browser memory. Individual pages retain the
16-million-pixel limit, and at most five nearby pages retain pixels and text.

A local check of the same 151-page PST archive opened in 1.6 seconds, previously
6.2 seconds. At page 100 and 200% zoom, retained canvas pixels fell from 47,886,408
to 27,574,272 (about 42%). The viewport was 1600 × 1000 at device pixel ratio 2.
These are single-machine observations, not general performance guarantees.
Hashing the same 40 MiB Blob took 332–366 ms over three runs, previously
3,004–3,037 ms. File identities and the SHA-256 algorithm are unchanged.

Folder rows use less indentation. Document headings stay visible while scrolling.
Errors wrap, the page view accepts keyboard focus, and Parent PDF has a clearer
label. The footer shows the installed version. Both extensions include original
project icons, with their SVG source and a reproducible PNG generation script.

## Checks

- Clean dependency installation, TypeScript checks and the complete Chromium suite pass.
- Firefox passes the same 13 interface cases, 17 message selections and 15 attachment cases.
- Continuous scrolling, the canvas budget, Ctrl+O routing, large files, seven nested PDF levels and the 71-message PST collection pass in both browsers.
- All 27 frozen semantic expectations and eight beta hardening checks pass.
- Workspace and clean-source extension build manifests match. Packaging twice produces identical ZIP hashes.
- Creator and validator builds and fresh EML, MSG, PST and MBOX conversions pass; see the [combined release receipt](../../docs/improvement/release-1.6.0-beta.1/verification.json).

The frozen sample PDFs and semantic expectations were not regenerated. Newly
created EML and MSG samples are a separate versioned release download.

## Remaining limits

These are unsigned extensions tested on Chromium 156.0.8078.47 and Firefox
158.0.1 on Linux. Firefox requires temporary installation. Native browser menu
clicks, site-permission prompts and full native file-picker interaction still
need manual acceptance. Other operating systems, older browsers, accessibility
acceptance and printing have not been verified. The viewer displays files; it
does not certify EA-PDF or PDF/A conformance. See the [release guide](beta-release.md)
for resource limits and installation instructions.
