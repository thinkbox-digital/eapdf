# M2 vertical-slice results — 2026-10-27

The requested M2 slice is complete. `PDFDocumentProxy.getDocumentParts()` exposes
generic read-only DPart/DPM observations through the normal display/worker
boundary. Browser results agree with the independent M1 expectations for real
1s and 1m PDFs. Malformed cases terminate with explicit diagnostics. The source
patch applies to the exact clean upstream Git revision and produces reproducible
display/worker bundles. No invasive PDF.js redesign or private API leak was needed.

M3 and EA-PDF semantic interpretation/UI work have not started. The existing
viewer still opens PDFs, renders pages, navigates and zooms. The public API can
be tested now using the [browser/DevTools guide](document-parts-api.md).
Unpacked extensions do not update automatically: build, reload the extension,
then close/reopen the viewer tab and worker.

## Implementation and scope

Upstream: PDF.js 6.4.299, commit
`d0991a0d53f3bb3a7307e3b83f279c1d117aafb3`.
The patch adds 350 lines across four source files: 9 in Catalog, 4 in the worker,
17 in the display API, and a new 320-line generic reader. No existing source line
or print statement is removed. There is no EA-PDF dependency or application key
interpretation inside generic PDF.js.

The API preserves object numbers/generations, document scope, direct-object paths,
parent/child order, declared parents, typed scalar DPM values and raw bytes,
Start/End references and zero-based inclusive ranges. It returns detached plain
data; returned-object mutation cannot change parser state. Complex DPM values and
resource limits produce explicit partial results. Page resolution verifies actual
page-tree membership, not just a computed page index.

Only `browser-viewer/` changed. Converter, validators, original samples, frozen
M1 expectations and existing application UI/adapter are unchanged. The root npm
lockfile and upstream asset inventory are unchanged. The [verification receipt](m2-verification.json)
contains the complete changed/new file list and baseline hash comparison.

Main changed files are `package.json`, `scripts/build.mjs`, `tests/package.test.mjs`,
README, notices, compatibility documentation and decisions. New files provide the
source patch/provenance/build task/core tests, two generated bundles, public
TypeScript declaration, reproduction/verification/browser scripts, two Node tests
and M2 documentation/evidence. No new runtime package or permission is introduced.

## Commands and exact final results

Commands ran from `browser-viewer/` unless stated otherwise. All final commands
below exited 0; tests had zero failures and zero skips.

| Command/check | Result |
|---|---|
| `npm run check` | Strict TypeScript and unchanged M1 schema/type generation checks passed. |
| `npm run verify:pdfjs` | Source/build-input hashes and both generated-bundle hashes verified. |
| `npm run build:pdfjs -- --archive /tmp/eapdf-m2-upstream.tar.gz --write` | Deliberate artifact generation from clean source; 9 core tests and 73 upstream tests passed. |
| `npm run build:pdfjs -- --archive /tmp/eapdf-m2-upstream.tar.gz` | Another fresh source tree applied the patch cleanly; 9 core and 73 upstream tests passed; both rebuilt bundles byte-identical. |
| `git apply --check .../0001-document-parts.patch` in an actual clean detached checkout | Passed at exact commit d0991a0d53f3bb3a7307e3b83f279c1d117aafb3. |
| `CHROMIUM_PATH=/snap/chromium/current/usr/lib/chromium-browser/chrome npm run test:m2` | Both packages built; six browser parity cases passed. The dedicated two-test Node typing/hash gate was subsequently added and passed in the final aggregate below. |
| `CHROMIUM_PATH=/snap/chromium/current/usr/lib/chromium-browser/chrome npm test` | Both packages built; 8 Node tests passed; existing rendering smoke passed; all 6 worker parity cases passed. |
| `npm run test:m1` | 2 contract Node tests and all 15 independent Python tests passed; 27 frozen records/provenance unchanged. |

The source build uses Node 24.19.0, npm 11.17.0 and the exact upstream package-lock
(931 development packages). The application still uses TypeScript 5.9.3 and its
unchanged 16-package locked inventory; all 203 upstream rendering assets/notices
are hash-verified. Source generation does not add these build tools to the extension.

The nine focused source tests cover scalar type/byte fidelity and nonzero object
generations, detached return values, missing/invalid roots, missing parents,
repeated DParts, cyclic indirect arrays, broken references with sibling recovery,
detached/non-page/reversed/null ranges, unsupported cyclic DPM dictionaries,
visit/depth/entry/string limits and MissingDataException propagation.
The 73 unchanged upstream tests cover Catalog, primitives and message handling.
This is not a claim that the full PDF.js regression suite ran.

## Browser parity

Chromium **156.0.8078.47** ran the actual packaged extension and real module worker,
offline, with **zero HTTP(S) requests and zero uncaught page errors**. Each input
and independent expected record was hash-checked before comparison. The
[machine-readable parity report](m2-browser-parity.json) records those hashes.

| Fixture | Parts / pages | Result |
|---|---|---|
| Existing 1s | 3 / 2 | Exact part identities, links, DPM scalars/bytes and body range 0–1; no diagnostics. |
| Existing 1m | 6 / 5 | Exact part identities, links, DPM scalars/bytes; body ranges 1–2 and 3–4; no diagnostics. |
| Invalid page reference | 3 / 1 | Invalid range retained; INVALID_PAGE_RANGE. |
| Reversed range | 5 / 2 | Invalid range retained; INVALID_PAGE_RANGE. |
| DPart cycle | 3 / 1 | Repeated node stopped; REPEATED_DPART. |
| Ordinary PDF | 0 / 2 | DPartRoot missing; no invented parts. |

The comparison deliberately projects only generic DPart data from M1. It does
not manufacture message ownership or full semantic InspectionResult records.
Generic REPEATED_DPART corresponds to M1's traversal-stop case; neither is a
conformance verdict. Every valid range is also checked against public getPage
references. Repeated calls after mutating returned data confirm worker state is
unchanged. Existing rendering, navigation, zoom and error-recovery checks pass.

## Reproducibility findings

An initial clean rebuild differed only in PDF.js's randomized INTERNAL_EVT build
constant. The build task now supplies the exact original npm release value
through upstream's existing define mechanism. No compiled-code replacement or
parser workaround was introduced. A second clean build then matched exactly.

Final generated bundle SHA-256:

- `pdf.mjs`: `c72447af377e539fe426235a5057815fc5090271810f625efeacacadb64d439e`
- `pdf.worker.mjs`: `cb01b72ee9937688643ef8e7bceffe13a153bc52f76233496cb62eff9512e8c3`

An initial source-test run exposed that the pinned Dict.getKeys returns an
iterator; the reader now iterates it directly, also avoiding an unbounded key
array allocation. The upstream raw-source tests on Node 24 require their locked
core-js Map support; with that development setup all 73 pass. These corrections
did not alter independent fixture expectations.

See [patch maintenance](pdfjs-patch-maintenance.md) for reproduction, intentional
refresh and reload instructions. Temporary source/build trees are cleaned after use.

## Remaining limitations

- Complex DPM graphs, object metadata streams, attachments, collections and email
  semantics are outside this slice. The API is not a conformance validator.
- The new API is tested in Chromium. Firefox is built, but new-API execution there
  is not yet verified. M0's Firefox history does not substitute for an M2 test.
- Local file input is tested. Range-loaded network input has a retry-boundary
  unit test, not a full streaming integration test.
- Traversal/output limits do not replace PDF.js's underlying parser/page-tree
  safeguards or provide a hard CPU/memory sandbox. Broader hostile-input testing
  and third-party-producer interoperability remain future work.
- Upstream's pinned development dependencies emit npm deprecation notices.
  They are not shipped; dependency modernization and full security review are
  outside this narrowly scoped milestone.

No unresolved issue blocks the requested M2 vertical slice. Further API coverage,
Firefox verification or M3 features require a separately scoped next task.
