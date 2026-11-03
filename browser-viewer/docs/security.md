# Beta security and privacy boundaries

Local files are selected explicitly and processed in the extension tab. No account,
telemetry, native helper or automatic PDF interception is used. Runtime assets,
workers, fonts and codecs are bundled. The script interpreter is not shipped or invoked. The upstream web-component
module is packaged only for its structure-to-ARIA builder; its scripting manager
and annotation-action machinery are never instantiated.

The context menu and toolbar action use contextMenus/activeTab. Optional HTTP(S)
host access is requested only on an explicit “Open linked PDF” click. The CSP allows
HTTP(S) fetch for that action, but not remote scripts. Opening a link banner alone
does not fetch. Fetch omits cookies, rejects redirects, supports cancellation and
removes newly granted host permission afterward. Existing site grants are retained.
Local use remains offline; document contents are never uploaded by the extension.
Native permission-prompt/menu interaction still needs manual acceptance testing.

The generic PDF.js patch is the only arbitrary PDF parser. Returned records are
plain data; extraction requires a discovered file/stream pair and the current
document token. Existing source tests reject foreign locators, corrupt filters,
cycles and over-budget decoded streams, including failed-stream reuse. Application
metadata is inserted as text. XMP DTD/entity declarations are rejected before XML
parsing. HTML/SVG/email attachments are displayed as inert source text, never
iframes or HTML. Binary attachments are never executed or opened automatically.

Every active PDF uses a dedicated terminable worker. At most three document
workers remain active per session; an isolated search/export session can temporarily
add up to three more. Within each session, older documents reopen from their parent when selected. Close/cancel rejects pending
work immediately and releases the session; timeouts retain their own explanation.
Opening checks cancellation again after the asynchronous input hash, before
starting a worker. The initial file picker stays disabled until its handler is
ready. Image previews check dimensions before pixel decoding and use a raster
canvas. Embedded PDFs use the main viewer recursively. Image/text dialogs release their
resources on close and leave the parent email intact. Save names remove path/control characters; payload bytes
are never rewritten. Blob download URLs expire or are revoked on close.

Continuous scrolling retains rendered pixels and text for at most five nearby
pages, within a combined 32-million-pixel canvas budget; the remaining pages use
lightweight placeholders. This is a canvas allocation limit, not a total-process
memory guarantee.
Root input is capped at 512 MiB before hashing. Decoded embedded streams are capped
at 32 MiB each and 512 MiB of concurrent extraction reservations per browser session. Completed and failed reads release their reservations. Successful reads have
a 32 MiB LRU cache; cached reads return copies and do not consume another decode
reservation. The recovery-only Reopen PDF action explicitly closes all workers and resets
limits; it also resets navigation to the root. Child count and nesting depth have no fixed cutoff. Repeated opens of the same
child reuse it; ancestor cycles are rejected.
Concurrent opens are capped at three. Metadata inspection retains a 16 MiB
per-stream byte ceiling. XML node, depth and record counts do not stop inspection. Unavailable metadata produces a concise warning and blocks CSV export; details are logged to the browser debug console.
Collection CSV exports enumerate actual child messages, omitting parent index
records; repeated identities in distinct children are retained. Ordinary PDF.js
decoding, including page rendering, stops before allocating more than 64 MiB for a single decode buffer or
256 MiB of cumulative buffer growth per document worker. Image decode is capped
at 16 million pixels and PDF.js canvas area at 64 MiB. Explicit linked downloads
stream at most 64 MiB and time out after 30 seconds; Content-Length is not trusted
as the actual byte count. Resource exhaustion remains explicit; no complete inventory is claimed after an interrupted scan.
These fixed ceilings may reject valid, large or unusually encoded documents.
Resource ceilings and unsupported formats are listed in beta-release.md. PDF.js
rendering and browser image decoding remain complex parsers; these controls are
not a complete process-wide CPU/memory sandbox. There is no claim of antivirus
scanning or comprehensive resistance to every hostile PDF. Browser/process
isolation remains part of the environment. Inspection results do not certify
PDF/A, EA-PDF or ISO conformance.

Verified in this checkpoint: offline UI and viewing tests in both browsers;
HTML/SVG remain text; exact downloaded bytes; oversized image rejection; truncated
text disclosure; missing and corrupt attachments; zero decoded-byte allowance;
legacy child-depth/count settings being ignored; pending worker cancellation; cancellation during input
hashing; timeout recovery; rejected external XMP entities. The pinned PDF.js core
checks also verify decompression allocation limits and cycle termination. Full
upstream rendering/integration suites and independent penetration review remain
outside this local beta checkpoint.

Dependency updates: check advisories, then follow pdfjs-patch-maintenance.md using
a new exact upstream revision and lockfile. Reproduce patches from clean source,
rerun affected source tests, all 27 semantic comparisons, both UI/viewing suites
and the beta resource/interoperability checks. Regenerate the SBOM, file hashes
and ZIPs. Do not update dependency versions or frozen expectations merely to
silence failures. `npm audit` reported zero advisories on 2026-10-31; this is a
point-in-time registry result, not a permanent guarantee.

Collection search uses an isolated session with a 120-second deadline and a
10-million-character budget for retained results. Released page text does not
consume a cumulative allowance; page and match counts have no fixed cutoff. Its results identify incomplete
coverage; they never imply that unreadable children were searched. Legacy header-only
source association requires a unique exact Message-ID, complete bounded headers,
and no contradictory declared stream checksum. It is identification evidence,
not authenticity certification or full source-email validation.

File ownership also uses explicit DPart /AF, annotation/page ownership and
single-email document scope. Declared checksum/size metadata bindings can be
shown before extraction; they do not certify byte integrity. Actual extraction
checks the bytes and refreshes checksum warnings. Legacy transport headers can
end at EOF; an EML source still requires a complete header/body separator.


The demonstration has no print service, user rotation control or global keyboard
shortcut handlers. Native PDF rotation is still honored when rendering pages.
