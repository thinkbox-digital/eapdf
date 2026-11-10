# M0 decisions

- Read all 352 lines of the implementation plan before implementation. Repository
  HEAD and plan/sample hashes are in baseline.json; the pre-existing dirty Git
  state is preserved in baseline-git-status.txt. Old sample paths in the plan
  were resolved through the current README. The existing normative PDF exists.
- The explicit viewer request overrides the older root AGENTS.md reader exclusion
  for this isolated project. No root instructions, converter or validator files
  are edited. No M1 inspection schema or interpreter is introduced.
- Use TypeScript plus browser ES modules and a short Node copy/build script.
  No framework, bundler, Java helper, native host or application server is needed.
  Pin PDF.js 6.4.299 and its upstream commit; ship its unmodified display/worker
  files and rendering assets. Check all 203 copied upstream files against hashes.
- Use a full extension tab, with an action handler that only opens that tab.
  Read user-selected files into memory. Render one page at a time. Disable
  controls during work so renders and document replacement do not overlap.
- Use a real same-origin worker through the public workerPort option. Do not
  loosen worker CSP to allow generated blob scripts or patch PDF.js internals.
- Keep page numbers one-based at the existing public PDF.js getPage boundary and
  in this simple UI. M1's separate inspection indexing contract is not implemented.
- Include the exact Liberation 1.07.4 source archive and its GPL/font-exception
  license. Do not relabel that asset Apache or SIL OFL. All runtime dependency
  notices remain adjacent to their assets; development tools are excluded.
- The checked-in ordinary PDF is synthetic and redistributable. Use the real
  existing EA-PDF locally for the rendering gate without copying its email content
  into a new public fixture. SHA-256 identifies the sample tested.
- Generate a Firefox manifest for the same M0 functionality. Defer permanent
  signing, store distribution and broader compatibility to later milestones.
- Keep the package's browser minimums at the tested major versions, Chromium 156
  and Firefox 158. No unsupported claim about older browsers is needed for M0.

## M1 decisions (2026-10-27)

- Implement the inspection contract and development oracle only. Keep the M0 UI,
  PDF.js distribution, extension manifests and build script unchanged. M2 has not
  started. The new declaration file emits no runtime JavaScript.
- Generate strict JSON Schema and readonly TypeScript from one small canonical
  contract. Commit both outputs and reject generation drift in tests. Inspection
  version 1 never contains a conformance verdict or embedded binary payload.
- Use an independent read-only pikepdf/qpdf extractor, not validator imports or
  PASS results. Existing validator routines and the specification inform explicit
  interpretations; direct object, XMP and byte checks corroborate the snapshots.
- Reference six repository examples in place. Add original structural controls
  for all six profiles and mixed nested collections. Synthetic declarations are
  not claims that these minimal PDFs conform to EA-PDF or PDF/A.
- Identify documents by exact file SHA-256 and messages by XMP record occurrence.
  Preserve Mail_GUID separately from Internet Message-ID; no UUID rewriting,
  case folding, URI conversion or sole-message ownership guess.
- Keep source-asset ownership unavailable where Table 16 supplies no explicit
  link. Use checksum/byte-length matching for candidate physical records, retain
  duplicate matches as ambiguous, and keep filename/size-only matches unresolved.
- Keep page indices zero-based and inclusive in the inspection contract. A future
  adapter must translate to the existing PDF.js public getPage(index + 1) API.
- Limit metadata expectations to identity and asset facts. Record omitted field
  names and coverage explicitly; do not copy email bodies or full headers into
  the fixture set. Logical structure and content-stream AF parsing remain later work.
- Preserve actual DPart traversal order in the existing reversed-order negative.
  Its individual page ranges are readable; an extraction result without warnings
  is not a validator PASS. Do not add conformance checks to make verdicts agree.
- Normalize qpdf's process-specific in-memory object address in child parse errors
  so frozen expectations are deterministic. Keep the substantive error text.
- Pin the development dependency versions actually used, including qpdf provenance.
  The reference extractor caps returned bytes but qpdf allocates decoded buffers
  first; it is a trusted-fixture tool, not the future browser security boundary.

## M2 vertical-slice decisions (2026-10-27)

- Keep the source patch to three existing files (catalog, worker, display API)
  and one new generic core reader. No EA-PDF namespace, identity policy or
  application dependency enters PDF.js. The viewer application and its print
  statements remain unchanged; no semantic UI or M3 work is introduced.
- Add `PDFDocumentProxy.getDocumentParts()` through the ordinary transport's
  promise request and `PDFManager.ensureCatalog` path. Return fresh plain data,
  not parser objects or generic arbitrary-object access. Preserve asynchronous
  MissingDataException retry behavior rather than concealing loading failures.
- Use a loaded-document token plus object/generation and path locators. The M1
  content hash remains the independent fixture identity outside generic PDF.js.
  Page indices are zero-based/inclusive; verify membership using getPageDict as
  well as getPageIndex, rejecting detached page dictionaries.
- Preserve scalar DPM types and string/name bytes. Report complex values as
  unsupported, without recursive traversal. Bound node/array visits, depth,
  entries and emitted string bytes. These are inspection limits, not a complete
  hostile-PDF memory/CPU sandbox.
- Keep exact source patches and generated display/worker bundles in the project.
  Normal Node builds remain offline and do not need the upstream build toolchain.
  Reproduction uses a fresh source tree, upstream package-lock, and upstream
  bundle functions. No node_modules source or distribution file is patched in place.
- Pin upstream's normally randomized INTERNAL_EVT build define to the original
  npm 6.4.299 value. A byte comparison found that token was the sole display-bundle
  difference between clean builds. Do not normalize or rewrite generated JavaScript
  afterward. The source patch applies unchanged to an actual clean Git checkout.
- Compare only the generic slice of the unchanged M1 oracle. Do not manufacture
  EA-PDF message ownership or a full InspectionResult at this milestone.

## M5 decisions (2026-10-27)

- Resume from the on-disk M3/M4 reports and source after the interrupted session.
  The README still described M2; implementation and independent parity records
  established that M4 semantics were present. Continue only through M5 this task.
- Connect the existing InspectionSession to the viewer. Do not change PDF.js,
  the interpreter, the frozen oracle records, or converter/validator code.
- Navigate a message only through a BodyRendering DPart with one proven owner.
  A selectable metadata row does not imply a usable page destination.
- Keep unknown-owner files on the document view; show only explicitly associated
  files for a message. Each EF stream has its own download control. Blob downloads
  retain exact payload bytes, sanitize only save names and never auto-open files.
- Keep folder placement unresolved when membership is ambiguous. Cyclic/broken
  folder ancestry is displayed at document root and retains inspection notes.
- Use ordinary nested lists/buttons rather than an incomplete ARIA tree widget.
  Keep native keyboard focus and mark the current selection. Metadata uses text
  nodes only. Do not add permissions, URL interception or remote resources.
- Use PDF.js getTextContent for page-level find-next in the selected document.
  Bound traversal and report incomplete search; no global child index, highlighting
  or cached text layer is introduced. Close/cancel terminates session workers.
- Inherit the existing M4 32 MiB input limit (the old M0 renderer used 100 MiB).
  This is now documented instead of promising the earlier rendering-only limit.
- Test actual saves in both browsers against independent stream SHA-256 values.
  Blank synthetic structural controls need not contain visible ink; keep pixel
  checks for the ordinary and real EA-PDF rendering samples.

## Local beta decisions (2026-10-27)

- The user explicitly requested continued work through a viable beta and working
  attachment viewing. Add PDF, PNG/JPEG/GIF and plain-text viewing, with exact-byte
  downloads for all extracted payloads. Office document rendering is not claimed.
- Use a separate modal attachment view and dedicated PDF worker. Keep the parent
  email/page intact. HTML/SVG/EML display as source text; no iframe or active markup.
- Confirm file signatures for PDF/raster rendering, bound decoded image pixels,
  cap displayed text, and preserve original payloads for download. View on an
  empty isolated placeholder explains the missing content instead of faking it.
- Fix immediate worker cancellation, distinguish timeout errors, and prevent a
  worker starting after cancelled asynchronous input hashing. Delay the initial
  file picker until the application handlers are ready.
- The supplied external-sample file stores single-email fields directly on its document RDF
  subject. Read those explicit fields only for s/si with no email container and
  an explicit identity. Do not manufacture body-page ownership; this file has no
  BodyRendering DPart. No existing frozen semantic expectation changes.
- Package unsigned local Chromium/Firefox beta ZIPs with deterministic contents,
  a component inventory and retained licenses. Do not publish, sign, push, or
  claim store approval. Keep creator/validator behavior unchanged.

## Navigation and larger files (2026-10-27, beta 0.2.0-beta.1)

- Implement the user's explicit viewer follow-up. Keep creator/validators and all
  frozen semantic expectations unchanged. Office opening belongs to the browser
  or installed applications; do not add an Office renderer.
- Replace eager interactive inspection with BrowserSession: chunked local Blob
  reads and SHA-256, explicit file extraction, lazy children, and three retained
  workers. Preserve the eager session for the independent semantic regression.
- Remove the interactive 32 MiB input cap and 32-child browsing limit. Use 64 nesting
  levels and an explicit 128–512 MiB extraction allowance, not a false claim to know
  free RAM. Raise only the generic extraction API's request cap to 512 MiB;
  leave generic inspection defaults unchanged. Reproduce the source patch twice.
- Navigate all discovered embedded PDFs in the main pane, including annotation-only
  files, with Parent/breadcrumb return and on-demand reopening after worker eviction.
  Root collection email metadata is not itself a usable child body destination.
- Keep document pages visible with an explanation when email page ownership is
  unavailable. Never invent a page or file owner.
- Use the public PDF.js TextLayer for selectable text and safe text-node search
  highlighting. Match its rotation and scale contract.
- Add an explicit context-menu/toolbar opening path with optional HTTP(S) site
  access. Never fetch on tab load or forward cookies; remove newly granted access
  after opening. Local-file URLs still use picker/drop. No automatic interception.
- Package unsigned local builds only. Preserve notices, inventory, reproducible
  hashes and the manual native-menu/permission acceptance limitation.

## Continuous scrolling (2026-10-27, beta 0.2.1-beta.1)

- Implement the user's explicit continuous-scrolling request while retaining the
  page counter, arrows and page entry. The page with the largest visible area is
  current. Render at most five nearby pages and release distant pixels/text.
- Keep metadata/message selection separate from scroll position: scrolling does
  not invent a different email owner. Existing message/search/Parent navigation
  scrolls to the destination and retains the underlying PDF bytes.
- Correct PDF-point to CSS-pixel conversion (96/72). A Letter page at 100% is
  816 CSS pixels wide. This is not physical monitor calibration.
- Add a viewer-local Ctrl+O/Cmd+O handler; do not introduce a global browser shortcut
  or new permissions. Chromium's actual picker is verified; Firefox needs manual
  native-shortcut acceptance.
- Investigate TRIP INFO_000046_1s.pdf independently with pikepdf. Its Word and Excel
  bytes are present in FileAttachment annotations on physical page 2. The current
  viewer discovers and lists both immediately on child opening. Verify actual
  downloads against independent byte lengths and SHA-256 values in both browsers;
  do not modify the creator or add an Office renderer for this report.

- Follow the user's request to condense single-email PDFs to one subject entry.
  The combined entry exposes all document files and the email's metadata; it does
  not infer ownership for previously unassigned files. Filename access remains in
  the breadcrumb, document details and tooltip. Multi-email lists retain their
  separate rows. Updated UI assertions verify both behaviors.
- Firefox WebDriver Ctrl+O events reach the extension's file input. The headless
  test cancels the native dialog's default action; native OS selection is still
  not claimed. PDF.js TrueType hint warnings remain visible and unchanged.
