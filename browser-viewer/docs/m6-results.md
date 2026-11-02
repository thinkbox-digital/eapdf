> Current unreleased checkpoint, 2026-10-31: security hardening now also changes
> the creator and both validators. See the [security record](../../docs/security/HARDENING.md)
> and [verification receipt](../../docs/security/verification.json). The earlier
> checkpoint below is retained as historical evidence; its resource ceilings and
> test counts are superseded. The [user manual](../../docs/EA-PDF_User_Manual.pdf)
> covers the complete project. Public store acceptance remains unverified.

# Viable local beta checkpoint — 0.1.0-beta.1

Completed 2026-10-27. The user's requested local beta now has working attachment
viewing. Start with [installation and supported formats](beta-release.md).
No known blocker remains in the tested scope. General public release, store
signing and wider-platform acceptance are still separate work.

## Delivered

**Attachments:** User-initiated View buttons open PDF, PNG/JPEG/GIF and text files
in a separate attachment dialog. PDF pages and images support zoom; PDF supports
page navigation. Esc/Back to email preserves the parent email/page. Download
original retains exact bytes. HTML/SVG/EML are inert source text. Word, Excel,
PowerPoint and other binary formats remain downloadable, not rendered. Empty
isolated placeholders, missing streams, unsupported types, corrupt PDFs and
oversized images produce clear messages instead of blank unexplained views.

**Reliability:** Worker cancellation now rejects immediately, including pending
requests; timeout errors retain their explanation. Input hashing cannot start a
new worker after cancellation. The initial file picker is disabled until the
application handlers are ready. Image viewing checks dimensions before decoding;
text viewing discloses truncation after 2 MiB.

**Interoperability:** The supplied BFO PDF's single-email properties live directly
on the document RDF subject. The viewer now preserves that explicit metadata
without requiring this converter's email Bag convention. Its supplied file lacks
a BodyRendering DPart; the viewer does not invent a body destination. Its three
pages remain manually viewable. All 27 previous frozen semantic records remain
unchanged and still agree with browser output.

**Delivery:** Both local ZIPs are under `browser-viewer/releases/`. Versioned
Chromium/Firefox manifests, a CycloneDX component inventory, per-file build
hashes, license notices and font sources are included. Builds and ZIPs were
reproduced byte-for-byte. The packages are unsigned and unpublished.

## Final checks

Run from `browser-viewer/` with the pinned Node/npm versions:

| Command | Result |
| --- | --- |
| `npm run check` | Strict TypeScript and contract generation pass. |
| `CHROMIUM_PATH=/snap/chromium/current/usr/lib/chromium-browser/chrome npm test` | Both builds; 10 Node tests; original page-rendering checks; 6 DPart comparisons; 13 UI cases; 15 attachment cases pass. |
| `M5_UI=1 GECKODRIVER_PATH=/tmp/eapdf-browser-beta/driver/geckodriver npm run test:firefox` | Actual temporary Firefox add-on: rendering, 13 UI cases and the same 15 attachment cases pass. |
| `CHROMIUM_PATH=/snap/chromium/current/usr/lib/chromium-browser/chrome npm run test:semantics` | 27 independent records agree, including recursive children; all also satisfy the unchanged JSON schema. |
| `CHROMIUM_PATH=/snap/chromium/current/usr/lib/chromium-browser/chrome npm run test:beta` | 7 resource/cancellation/BFO/XMP checks pass. |
| `npm run build:pdfjs` | Clean hash-verified upstream source, both patches applied, 13 focused tests and 73 selected upstream tests pass; generated bundles are identical. |
| `npm audit --json` | Zero reported vulnerabilities on 2026-10-27, including development dependencies. |
| `npm run package:beta` | Both local archives built and reproduced exactly. |

Each browser exercises 21 message selections and 17 downloads in the UI suite,
plus 15 attachment viewing/error/download cases. These include real MSG PDF,
PST JPEG and EML text attachments. Independent pikepdf/Pillow checks supply byte
hashes, image dimensions/pixels and PDF page counts/widths. Original samples are
referenced in place and hash checked. Synthetic controls isolate HTML/SVG active
content, malformed PDF, empty/unsupported files, oversized images, text limits,
and known PDF/image pixels. Attachment downloads remain byte-exact after viewing.

Chromium 156.0.8078.47 recorded no attempted HTTP(S) requests or uncaught page
errors. Firefox 158.0.1/geckodriver 0.37.1 ran offline and recorded no HTTP(S)
page resources. Firefox still prints its known host sandbox/GLib warnings and
PrivateBrowsingUtils shutdown error; the test exits successfully. This does not
claim all browser-internal logs are clean.

The exact source, package hashes and numerical evidence are in
[m6-verification.json](m6-verification.json). Full logs/screenshots are retained
in ignored `test-results/`; original emails and PDFs were not changed. Temporary
upstream build trees, browser profiles, saved test attachments and driver copies
are removed after use. The temporary driver path in the command above must be
restored from Mozilla's pinned 0.37.1 release to repeat that exact command.

## Scope and remaining limits

This checkpoint meets the practical **local beta** goal. It is not full M6 release
acceptance: signed installs/store review, other operating systems/browser versions,
independent security/accessibility review and the complete upstream rendering
suite remain unverified. The public-release checklist and supported-format table
are in beta-release.md; security boundaries are in security.md. No conformance
certification is inferred from rendering or metadata.

Per-file/decoded-byte/child/timeout limits remain as documented. There is no
Office rendering, OCR, global child search, active attachment execution, password
entry, printing or full text-selection/accessibility layer. The supplied BFO
case demonstrates one other producer, not universal interoperability. The
point-in-time advisory check is not a lasting safety guarantee.

New/changed implementation: `src/attachment-viewer.ts`, `src/viewer.ts`,
`src/viewer.html`, `src/viewer.css`, `src/pdfjs-adapter.ts`,
`src/inspection-session.ts`, `src/domain/xmp.ts`, the extension manifest,
package/version/lock files, `scripts/build.mjs`, `scripts/sbom.mjs` and
`scripts/package-beta.py`. New viewing/resource tests and independent fixture
manifests are under `scripts/` and `tests/attachments/`; existing browser harnesses
now wait for application readiness. User/restart/security docs and the root
README link are updated. Creator, validator, rulebook and PDF.js core/patch code
are unchanged by this task.
