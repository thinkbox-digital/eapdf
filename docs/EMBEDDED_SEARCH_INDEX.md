# Embedded search indexes

The Java creator adds an optional metadata search index to new `1c` and `1ci`
collections. The offline browser viewer uses it to populate the existing message
list without opening the individual message PDFs. Version 2 is the compact metadata format; the viewer also accepts version 1. The separate version 3 full-text property, described below, extends the
same XMP/decode infrastructure for `1m`, `1mi`, `1c` and `1ci`.

## Embedding and conformance

The payload is a private catalog XMP property, `eapdfindex:payload`, in namespace
`https://thinkbox.digital/eapdf/search-index/`. Its value is base64-encoded gzip.
It accompanies the existing EA-PDF metadata without replacing its semantics.
The URI is an identifier; no network access is required.

An initial catalog associated-file (`/AFRelationship /Data`) design was rejected
by the creator's existing preservation checks: a non-source embedded payload in
a collection was treated as an unlisted contained EA-PDF. EA-PDF's restrictions
on collection contents make an arbitrary auxiliary member inappropriate. The
implementation therefore uses the XMP extension alternative. It creates **no**
FileSpec, associated-file relationship, collection entry, attachment ownership,
or document-part relationship. Validation rules were not relaxed. Original
email/native attachment streams and the collection membership list are unchanged.

Standalone Java EA-PDF and veraPDF PDF/A-4f validation of real two-message `1c`
and `1ci` conversions passes, including recursive children, document parts and
attachments. This is evidence for these generated files, not a blanket claim
about arbitrary PDFs or PDF/UA conformance.

## Wire schema (version 2)

New indexes use the same gzip/UTF-8/JSON envelope and integrity digest described below,
with `version: 2`. Each row has four values: `[Mail_GUID, sorted child name-tree keys,
declared child MD5, declared child byte length]`. Subject, sender, recipients and
date are read directly from authoritative catalog XMP. Folder membership comes
from the checked collection keys. Omitting duplicate headers and folder strings
lets substantially more messages fit the unchanged 8 MiB decoded payload budget.
The GUI’s **Embed searchable index** checkbox enables this filtering index and the
separate full-text index. Their byte limits and failure diagnostics are independent.

### Legacy version 1 (still readable)

Decompressed bytes are strict UTF-8, newline-delimited JSON, with a final LF.
The first line is an object:

```json
{"format":"eapdf-metadata-index","version":1,"profile":"1ci","messages":2,"sha256":"64 lowercase hex characters"}
```

`sha256` covers every subsequent byte, including row-ending LFs. Each subsequent
line is a 13-element tuple:

| Position | Value |
| --- | --- |
| 0 | Nonempty unique Mail_GUID; the stable message identity |
| 1 | Internet Message-ID, possibly empty or duplicated |
| 2 | Subject |
| 3–6 | Raw From, To, Cc, Bcc headers, respectively |
| 7 | Sent date, in the existing XMP representation |
| 8 | Sorted complete array of collection name-tree keys identifying one child FileSpec |
| 9 | Lowercase 32-character declared child MD5 |
| 10 | Positive integer declared child byte length |
| 11 | Sorted unique folder paths for that child |
| 12 | One-based initial child page, currently always `1` |

Metadata strings use the creator's existing XML-safe representation; CRLF and CR
normalize to LF to match XML parsing. Unicode is preserved, without additional
normalization. Missing optional values are empty strings. Duplicate subjects and
Message-IDs are valid; duplicate or missing Mail_GUIDs make the index unavailable.
A folder path includes the collection root name; `\\` escapes a backslash and
`\/` escapes a slash in a folder name. No folder tree is represented by `""`.
All aliases and multiple folder memberships are retained. Filenames and row order
alone never identify a result: the binding uses complete collection keys, child
checksum/size and message GUID.

Rows follow sorted name-tree keys, emitting each FileSpec once. JSON field and
tuple ordering, UTF-8, LF and gzip are deterministic for identical input metadata
and child bytes. Whole PDFs may contain existing creation timestamps; an index
that includes child checksums is not expected to be byte-identical when those
child bytes differ. There is no wall-clock field in the index.

## Creator behavior

`searchIndex.enabled` defaults to `true`; set it to `false` in `EAPDF_CONFIG` to
omit the optimization. No command-line argument was added. The collection writer
uses EmailRecords, folder aliases and already-established embedded-stream
checksums. It does not reopen children to construct the index. Generation occurs
inside the existing staged creation/publication transaction.

Versions 1 and 2 cover collections with single-message `1s`/`1si` children. Unsupported
multi-message/nested child shapes, ambiguous identities and resource limits omit
the index with `SEARCH_INDEX_UNAVAILABLE`, leaving legacy search available.
Cancellation still stops conversion. Other index-generation exceptions produce
that explicit diagnostic and continue without the index; fatal VM errors are not
swallowed. The payload is prepared before replacing catalog metadata.

For metadata indexing, `1s`, `1si`, `1m` and `1mi` retain their existing behavior. Flat archives already
expose their message metadata in one document. The viewer now builds and caches
filtering keys directly from that loaded metadata, without reopening the PDF on
the first filter. The viewer can use an indexed child collection
inside an older nested collection, opening only the outer path to reach it.
Empty-index decoding is supported; existing creator restrictions on empty
conversions are unchanged.

## Viewer verification and fallback

The existing PDF.js inspection/XMP path discovers the property. No PDF.js patch
or associated-file API modification is required. The viewer decodes once per open
document, caches the result, and feeds the existing metadata list and filtering
logic. Subject, sender, all recipients and date use the same searchable values as
legacy indexing. Folder presentation and normal result navigation are retained.

Before accepting an index the viewer checks its version/profile, gzip/UTF-8/JSON,
digest, row count and uniqueness, complete root message inventory, authoritative catalog XMP identities, all collection keys and folder membership,
legacy version-1 duplicate metadata fields and paths, and declared child checksums/sizes. Every PDF member must be covered.
Result selection opens the bound child and verifies its single message GUID and
searchable values before displaying it. A disagreement rebuilds the metadata
list by scanning children; it does not navigate to a guessed message.

Absent indexes use legacy behavior. Unsupported, corrupt, oversized, ambiguous
or inconsistent indexes produce an explanatory status and fall back to the
existing traversal; they cannot prevent opening the PDF. Existing incomplete
coverage reporting remains active if that traversal cannot inspect all children.
Cancellation releases decompression resources; document release/close clears the
cache. Searches reuse the decoded result rather than decompressing it again.

The SHA-256 detects accidental modification, **not authenticity**. Child MD5 and
size bind to existing PDF declarations without reading every child's bytes. A
coordinated malicious edit to the index and authoritative PDF metadata cannot be
detected by a self-contained digest. Actual child metadata is checked on
selection; establishing every child binding up front would require opening the
children and defeat this optimization. Digital-signature trust is separate.

## Resource limits

| Limit | Current reader / version 2 writer |
| --- | --- |
| Gzip bytes | 4 MiB |
| Decompressed bytes | 8 MiB |
| Header | 1,024 bytes |
| Row | 128 KiB |
| Messages | 100,000, subject to the byte limits |
| Name-tree aliases per child | 256 |
| Folder depth / path | 256 / 16,384 characters |
| Retained searchable strings | 32,000,000 JavaScript UTF-16 code units for embedded metadata |

Decompression is read incrementally and stopped at the decoded limit; parsing is
row-by-row with cancellation/yield checkpoints. Bounded byte/string copies still
exist. These are index budgets, not a total browser-memory guarantee. Root PDF
loading, hashing, XMP inspection, PDF.js workers and existing collection traversal
retain their own costs and limits. Base64 increases compressed-payload size by
approximately one third before PDF stream compression.

## Regression and performance verification

Java tests cover both collection profiles, existing member/attachment semantics,
folder aliases, Unicode, deterministic output, disable/failure behavior and limits.
Viewer unit and real browser tests cover exact metadata/destination equality,
recipient/date filtering, navigation, corrupt/unsupported/incomplete indexes,
ambiguous identity, nested collections, legacy fallback, cache reuse, cancellation
and decompression limits. Public real-message fixtures and source hashes are in
`browser-viewer/tests/metadata-index/`.

The repeatable scaling harness is `scripts/java/eapdf/MetadataIndexBenchmark.java`
and `browser-viewer/scripts/metadata-index-benchmark.mjs`. It repeats public
`in/sample.eml` with unique Mail_GUIDs while preserving duplicate Message-IDs,
using one rendered message template and the production collection front matter. It produces indexed and stripped-index pairs
of the same content. This isolates search scaling; it does not measure converter
throughput, corpus diversity or validation throughput.

Example, from the repository root (use a fresh temporary directory):

```sh
TASK_DIR=$(mktemp -d /tmp/eapdf-index-benchmark-XXXXXX)
mvn -Deapdf.build.directory="$TASK_DIR/creator" package
javac -cp "$TASK_DIR/creator/emailtopdfa-1.6.0-beta.20.jar" -d "$TASK_DIR/classes" scripts/java/eapdf/MetadataIndexBenchmark.java
EAPDF_CONFIG="$PWD/config/eapdf_config.json" java -Xmx2g -cp "$TASK_DIR/classes:$TASK_DIR/creator/emailtopdfa-1.6.0-beta.20.jar" eapdf.MetadataIndexBenchmark in/sample.eml "$TASK_DIR/fixtures" 100 1000 10000
# Run in a temporary viewer working copy with dependencies installed and bundles built:
EAPDF_INDEX_BENCHMARK="$TASK_DIR/fixtures" npm run benchmark:metadata-index
```

The browser harness uses fresh offline Chromium instances for each mode, verifies
fingerprints of all searchable values and normalized destinations, and records
root-load time, index-build time, first result, subsequent-query latency, child
opens, PDF size and sampled main-thread heap/aggregate browser process PSS.
Linux PSS and CDP heap samples are collected approximately every 100 ms; reported
maxima are sampled values, not guaranteed instantaneous peaks. Use
`EAPDF_INDEX_TRIALS` to repeat and alternate mode order; `EAPDF_INDEX_COUNTS` selects
sizes. Results are written to `test-results/metadata-index-benchmark.json` in the
working copy. Timing includes no network requests.

Final acceptance on November 7, 2026: creator 422 tests (four skipped), Java
validator 264 tests (nine skipped), viewer 91 unit tests plus the complete
Chromium integration command, Firefox 158.0.1 fresh temporary installation,
and 18 public Python regressions passed. TypeScript/inspection-contract checks,
PDF.js asset integrity, documentation build and optimized-Python package checks
passed. Both local viewer packages and creator JARs were rebuilt without a
version bump or publication.

Real public-source conversions of all six profiles pass standalone Java EA-PDF
and veraPDF checks. The indexed two-message collection fixtures and corrected
100- and 1,000-message scaling fixtures have zero failures and nine existing warnings each.
Those checks include document-part and attachment rules. An early scaling
harness with blank front matter failed five checks; it was corrected to use the
production front matter, with no validator changes. Only corrected-fixture
measurements are reported here.

The 1,000-message recursive report exceeds the default 64 MiB work-file limit;
the initial run correctly reported `INCOMPLETE`. Rerunning with
`VALIDATOR_MAX_WORK_FILE_BYTES=268435456` (256 MiB) completed successfully. No
rule was suppressed. The 10,000-message container independently passes veraPDF
PDF/A-4f; full recursive EA-PDF acceptance was not run at that size. Do not infer
that larger collections fit default validation report budgets.

Measured November 7, 2026 on Linux x86-64, Ryzen 5 2600 (six cores/12 threads),
32 GiB RAM, Chromium 156.0.8078.79. One fresh-browser trial per mode on a shared
host; other acceptance work overlapped part of the run. Times include memory
sampling overhead. These are measured observations, not statistical guarantees.
All three pairs had identical fingerprints of searchable values and destinations.

| Messages | Metadata build, indexed / scan | Complete list including root load, indexed / scan | First result, indexed / scan | Added bytes |
| --- | --- | --- | --- | --- |
| 100 | 0.023 / 5.808 s | 0.653 / 6.453 s | 0.653 / 0.996 s | 6,252 |
| 1,000 | 0.074 / 47.394 s | 2.248 / 49.607 s | 2.248 / 2.507 s | 50,169 |
| 10,000 | 0.887 / 711.620 s | 20.438 / 730.962 s | 20.438 / 19.672 s | 492,781 |

Indexed construction opened zero children at every size; traversal opened 100,
1,000 and 10,000 respectively. Subsequent filtering reused the cached list.

| Messages | Median subsequent query, indexed / scan | Sampled main JS heap, indexed / scan | Sampled aggregate browser PSS, indexed / scan |
| --- | --- | --- | --- |
| 100 | <0.1 / <0.1 ms | 5.2 / 13.5 MiB | 278.4 / 379.6 MiB |
| 1,000 | 0.1 / 0.1 ms | 28.3 / 40.3 MiB | 376.8 / 448.6 MiB |
| 10,000 | 0.6 / 0.9 ms | 153.6 / 249.6 MiB | 1197.8 / 1244.1 MiB |

The indexed 10,000-message PDF is 447,510,738 bytes. Root loading alone took
19.55 seconds in its indexed trial. Legacy scanning can emit a first result
before its complete list is ready; the index verifies the complete payload
before emitting results. First-result speed and whole-browser memory therefore
must be assessed separately from complete metadata construction. The 100- and
1,000-message subsequent queries are close to timer resolution.


## Implementation map

- `MetadataSearchIndex.java`: bounded deterministic encoder and XMP embedding.
  `PdfCollectionSupport.java` and `PdfMailWriter.java` establish metadata before
  embedding; `EapdfConfig.java`, `ConversionDiagnostic.java` and the configuration
  reference expose disable/failure behavior.
- `domain/xmp.ts`, `domain/interpret.ts` and `inspection-session.ts`: expose the
  private payload without changing the canonical inspection contract.
- `embedded-index.ts`: bounded decode, integrity and authoritative-metadata checks,
  cache and shared search projection. `message-index.ts`, `browser-session.ts`
  and `viewer.ts`: fast path, lifetime handling, fallback and navigation.
- `MetadataSearchIndexTests.java`, `embedded-index.test.mjs`, the public
  `tests/metadata-index` fixtures, `metadata-index-smoke.mjs` and Firefox acceptance:
  generation, decoding, equality, limits, cancellation and real navigation tests.
- `MetadataIndexBenchmark.java` and `metadata-index-benchmark.mjs`: repeatable
  scaling fixtures and measured browser comparison. `package.json` includes the
  new acceptance test in the normal suite and exposes the benchmark command.

## Full-text index, version 3

### Architecture and extraction

`FullTextSearchIndex.java` runs once at the outermost atomic output-publication
boundary, after the visible PDF content is finalized. It covers the root pages
and recursively follows declared collection members, extracting each PDF once.
Rendered headers, bodies, overview and attachment-list pages are included.
Original MIME/source bytes and unrendered embedded attachment contents are not
indexed. There is no OCR. All four archive profiles (`1m`, `1mi`, `1c`, `1ci`)
are supported; standalone `1s`/`1si` keep ordinary PDF.js search.

Full-text generation uses PDFBox in the Java process; Node.js is not required.
The creator extracts each rendered page with PDFTextStripper in content-stream
order, preserves PDFTextStripper's emitted text and inserts a separator only at
proven disjoint backward horizontal LTR run boundaries, then normalizes the
result and builds the existing positional index. The viewer applies the same
narrow boundary rule described below. If an affected PDFBox word cannot be
safely aligned with its character positions, index creation reports
`SEARCH_INDEX_UNAVAILABLE` and retains ordinary PDF.js search. It reuses
loaded documents and spools text rows to disk. No JavaScript runtime or helper is
packaged in the creator. The viewer continues using PDF.js for rendering.

PDFBox and PDF.js can differ in text grouping and reading order. Shared
normalization fixes common spacing differences (including the observed
`Power VaR` phrase discrepancy), but cannot guarantee identical extraction for
all PDFs. Indexed counts describe PDFBox-extracted text. The viewer displays
that limitation and verifies selected matches with PDF.js before highlighting.

`searchIndex.enabled` controls both indexes; `searchIndex.fullTextEnabled`
defaults to `true` and can independently disable full text. Extraction failure, ambiguity or resource limits
emit `SEARCH_INDEX_UNAVAILABLE` and publish the valid PDF without full text.
Cancellation still aborts conversion. The staged original is replaced only
after the complete replacement index and binding succeed. Signed outputs retain full text using the signature-compatible binding described below.
Existing metadata indexing is unaffected. There are no new CLI arguments.

### Search semantics

Creator and viewer apply the same scalar normalization: Unicode NFKD,
locale-independent lowercase, Greek final sigma folded to sigma, removal of soft
hyphens, and collapse/trim of Unicode whitespace to U+0020. Accents are retained
as combining marks; compatibility ligatures expand. Visible hyphens and punctuation remain except broken words at an explicit
line ending: lowercase-hyphen-LF-lowercase and uppercase-hyphen-LF-letter
are joined, following the pinned PDF.js broken-word rule. PDFBox line endings
are explicitly LF on every platform. There is no OCR or inferred reading-order
correction. Matching remains non-overlapping substring matching;
multiple words form a phrase, not Boolean AND. Quotes are literal characters.
As with other substring searches, `cafe` can match the prefix of decomposed
`café`; accents are not explicitly removed.

The viewer concatenates contiguous PDF.js `str` fragments and adds LF for
`hasEOL`, following pinned PDFFindController extraction. One narrow spatial
boundary prevents false words when a deferred text run jumps backward on the
same baseline: a horizontal LTR run entirely left of the preceding run, with a
gap exceeding one tenth of the larger font size, gets a separating space.
For example, a separate `mailto:` link must not join a preceding surname ending
in `e` to manufacture an `email` match. Actual whitespace, contiguous/overlapping
fragments, combining marks, RTL, vertical and rotated runs retain their existing
boundaries; text is never reordered. This supplements PDF.js string assembly
without modifying PDF.js or the creator's index format.

The text layer records the same separators and EOL flags while streaming,
including across chunk boundaries, so searching and highlighting share offsets
even when words cross font runs. Empty items add no space. This is not a wholesale
copy of upstream normalization: punctuation and CJK newline handling retain this
application's documented rules.

The viewer maps normalized match endpoints back to original UTF-16 text-layer
positions, including expanded ligatures and collapsed whitespace. Index version
3 / engine `pdfbox-nfkd-space-fold-v3` identifies the corrected extraction
boundaries. The wire schema and scalar normalization are unchanged. Earlier
`pdfbox-nfkd-space-fold-v1`/`v2` engines and version-2 indexes safely fall back to
PDF.js scanning: their embedded text can contain false words across separately
positioned runs. Existing PDFs still open; regeneration with the current creator
provides the new fast index. Unicode-table
differences between Java and JavaScript can also cause safe index rejection.
Coverage means all PDFBox-extracted pages, not guaranteed equality with PDF.js.

### Full-text wire schema

The private XMP property `eapdfindex:fulltext` holds base64(gzip(UTF-8 NDJSON)).
It coexists with the version 1 or compact version 2 `payload`; it creates no FileSpec,
collection member, attachment or DPart. Catalog XMP is unfiltered to support the
fixed-width file binding below. The URI identifies an implementation-specific
extension, not an EA-PDF 1.0 requirement.

The first line has these fields:

```json
{"format":"eapdf-search-index","version":3,"profile":"1ci","engine":"pdfbox-nfkd-space-fold-v3","complete":true,"documents":3,"pages":3,"texts":3,"terms":120,"postings":200,"sha256":"payload SHA-256"}
```

Counts above are illustrative. SHA-256 covers all subsequent bytes, including
LFs. Subsequent rows have exactly two elements, `[section, value]`, in this order:

| Section | Value | Identity / interpretation |
| --- | --- | --- |
| D | `[route, childMd5, childBytes, pageCount]` | Implicit zero-based document ID; first row is root (`[]`, empty MD5, zero bytes) |
| P | `[documentId, page, textId, Mail_GUID]` | Every page exactly once; page is one-based; overview GUID can be empty |
| T | `normalizedPageText` | Implicit zero-based text ID; identical page strings share one row |
| W | `[term, [textDelta, positionDelta, ...]]` | Inverted positional postings for each distinct lowercase token |

A route is an array of complete, sorted collection name-tree alias arrays, one
array per nesting level. It preserves folder aliases and duplicate filenames.
Child MD5/length bind to existing collection declarations; the root file binding
also covers the actual embedded bytes. Duplicate subjects and Message-IDs do
not identify destinations. Page ownership comes from DPart Mail_GUID values;
flat archives map directly to their root page numbers.

Tokens are maximal runs excluding U+0020 in the lowercase page string. W rows
are sorted by token; postings are ordered by text ID and UTF-16 start position.
Text ID deltas are relative to the previous posting, initially zero. Position
deltas are relative to the preceding position in the same text, or zero when
the text ID changes. The decoder validates each token boundary, posting order,
complete token inventory, page inventory and root ownership/member declarations.

Queries first use the term-to-text inverted map to select candidates, then
confirm exact substring/phrase occurrences against retained page strings. This
supports legacy substring matching (including within tokens) without scanning
all PDFs. Term positions establish/validate the positional index; they are
**not** PDF.js text-layer coordinates. Normalized strings are retained for counts and excerpts. JSON/gzip and integer deltas
were chosen over a custom binary codec for inspectability and no new codec
dependency. Binary compression has not been benchmarked; no superiority claim
is made. Text is deduplicated, and document paths/metadata are not repeated in
postings. Construction spools text rows to disk, retaining hashes, compact
page/document rows and bounded primitive postings. Final serialization writes a
bounded body spool while hashing, then streams the header and body through gzip.
Posting deltas are written directly without a full posting-array copy. Only the
bounded compressed payload/base64 XMP remains materialized; the uncompressed
index is not reconstructed in multiple heap buffers. Spools are removed on
success, cancellation and failure. Identical extraction produces deterministic payloads
and gzip; ordinary PDF timestamps and child byte identities retain their
existing behavior.

### Integrity, navigation and fallback

`eapdfindex:fileDigest` contains SHA-256 of the final PDF with that unique
64-character field replaced by 64 ASCII zeros. For unsigned PDFs it covers every
byte. New indexes also include `eapdfindex:signatureContents`, initially
`00000000000000000000:00000000000000000000`. This optional binding extension
leaves the version-3 search payload and its normalization unchanged.

For creator-signed output, that property holds two zero-padded, 20-digit decimal
byte offsets separated by a colon: the start (inclusive) and end (exclusive) of
the signature's hex `/Contents`, including its `<` and `>` delimiters. Only this
range is omitted from the file hash. The descriptor, signature `/ByteRange`,
all page streams, embedded files, index payload, metadata, and final incremental
revision remain covered. The reserved range is limited to 1 MiB and must be a
hex string after the binding fields. Duplicate markers, malformed offsets,
overlap, out-of-file ranges, and non-hex contents fail closed.

The creator first verifies the unsigned index binding, refusing to reseal stale
content. PDFBox late external signing then writes the final revision with empty
signature space. The creator fills the range descriptor, reseals the fixed-width
index digest over the signature byte ranges, computes CMS over those same ranges
(including the completed index digest), and fills the reserved signature space.
No PDF offsets or stream lengths change. Signature verification still runs before
atomic publication. Signing failure leaves an existing destination untouched.

The viewer verifies the binding once before using the index. Appended revisions,
including another signature, conservatively cause ordinary-search fallback.
Existing unsigned indexes without the new property remain supported. Older
viewers safely reject the signed binding and fall back. Previously indexed files
without a reserved descriptor are not upgraded during signing and likewise fall
back. Arbitrary third-party signing workflows are not supported by this binding.

This check is **not signature verification**: changed CMS bytes can still pass the
index fingerprint because they do not change searchable content. Cryptographic
signature validity, certificate trust, revocation and timestamps require separate
verification. Initial binding verification still reads the file; it does not open
every embedded child or make initial loading constant with archive size.

The payload digest detects corruption, and the file digest binds the complete
payload to the saved file. Neither proves authenticity: a malicious editor can
rewrite and reseal both. A deliberately forged, self-consistent index cannot
be exhaustively disproved without extracting all text. Signed/trusted provenance
is a separate concern. Selection verifies the actual PDF.js normalized match count and Mail_GUID before applying highlights. Disagreement invalidates
the cached index and automatically restarts legacy scanning. Unknown versions,
unsupported engines, invalid references/inventories, partial coverage and size,
time or integrity failures also fall back with a diagnostic. Invalid indexes
cannot prevent opening the PDF. Legacy incomplete-search reporting is preserved.

The viewer caches the validated index for the open root, uses its existing
results panel (the **All files** search scope), and opens only the selected child path. PDF.js renders and
extracts the selected page to confirm the match. Highlight offsets use
`TextLayer.textContentItemsStr` and `textDivs`, preserving empty text runs;
coordinates are never inferred from index postings. A layout that cannot supply
precise occurrence highlights shows the verified page with a diagnostic.
Viewport refits preserve the selected occurrence. Closing/replacing/releasing
a document clears the cache; cancellation checks bound decoding and queries.
There is one cached index per live document, with existing session eviction.

Signed-output regression verification (November 7, 2026): all four supported
profiles passed standalone Java EA-PDF and veraPDF PDF/A-4f validation, with zero
failures and ten reported warnings each. Poppler `pdfsig` independently reported
valid signatures covering each complete file; test certificate issuers were
unknown, as expected. Java tests compare rendered text and embedded bytes before
and after signing, verify the masked digest and CMS, and reject resealing stale
indexes without replacing the original file. Existing RSA and EC signing tests
also pass.

Chromium compared 36 signed indexed/scanned queries across all four profiles,
with zero child opens for indexed queries, verified navigation/highlights, and
ordinary-search fallback after actual incremental metadata revisions. The same
36-query unsigned regression passed. Firefox verified signed `1mi` and `1ci`
search/navigation/highlighting. The signature checkpoint creator package suite ran 433 tests (four
skipped); all 120 viewer unit tests passed. No signed large-archive performance
benchmark or third-party signing compatibility claim is made.

To regenerate temporary test-only signed fixtures and exercise browser acceptance:

```sh
mvn -Deapdf.build.directory=/tmp/eapdf-signing-build -Dtest=PdfSigningTests \
  -Deapdf.signedIndexFixtures=/tmp/eapdf-signed-fixtures test
cd browser-viewer
npm run build
EAPDF_SIGNED_INDEX_FIXTURES=/tmp/eapdf-signed-fixtures npm run test:fulltext-index
EAPDF_SIGNED_INDEX_FIXTURES=/tmp/eapdf-signed-fixtures npm run test:firefox
```

The browser commands require the usual development browsers/drivers. Generated
certificates are for tests only; no private keys are exported with these fixtures.

### Full-text resource limits

| Resource | Limit |
| --- | --- |
| Compressed / decompressed payload | 16 MiB / 128 MiB |
| Documents / pages | 100,000 / 200,000 |
| Terms / positional records | 250,000 / 5,000,000 |
| Unique raw text / individual page | 32 million / 1 million UTF-16 units |
| Row bytes / route depth / aliases per step | 16 MiB / 8 / 256 |
| Accounted retained viewer index | 256 MiB |
| Index loading / parsing / query | 120 s / 20 s / 2 s |
| Creator root or individual child bytes | 512 MiB |
| Creator extracted page text | 1 million UTF-16 characters |
| Creator complete index time | 30 min (cooperative checks during extraction) |
| Query result retained strings / matches per unique text | 10 million units / 1 million |

The creator further limits its serialization budget to the smaller of 128 MiB
and one-sixteenth of JVM maximum heap (minimum 1 MiB), then derives tighter
character, term and posting budgets from that value. This can omit an index
before the absolute format ceilings. Collection child extraction is sequential
inside each output; the existing bounded conversion workers still apply.
Intermediate files are cleaned on success/failure.

These are component budgets, not a total process RSS guarantee. PDF.js input
buffers/workers, XMP/base64, decompression buffers, parser temporaries, JVM/PDFBox
state and browser rendering add memory. The previous version-2 helper read one PDF into
memory; the 512 MiB input ceiling and explicit omission policy apply even though
the viewer accepts larger PDFs. Full-text indexes on larger files require a
future range-backed extraction design. Searches exceeding the query budget use
legacy scanning, which may itself explicitly report incomplete coverage.
Its 120-second deadline is checked before opening collection members and streams
as well as before page reads, including when children are unreadable or empty.

Indexed queries count non-overlapping literal matches directly in page strings
already verified as canonical during index parsing. They do not normalize those
strings again or allocate original-glyph offset arrays; PDF.js establishes glyph
offsets when a result is selected. The two-second budget, match/result limits,
cancellation checks and index lifetime remain unchanged; no additional query
cache is retained. [Measured large-PST follow-up](REPOSITORY_AUDIT.md#search-correctness-and-query-performance-follow-up)
records the observed improvements and remaining scope limits.

### Full-text verification and benchmarks

Version 3 verification: 431 creator tests (four skips), then six focused tests
after the single-pass extraction adjustment; 117 viewer unit tests; Chromium
36 real-source query comparisons across four profiles and selected-page
highlights/fallback; Firefox indexed navigation/highlighting. Four generated
Enron-source archive profiles passed standalone Java EA-PDF and veraPDF checks
with zero failures and nine existing warnings each. No Node executable is used
by the creator. Large-scale timings below are historical version-2 evidence.

Real fixtures in `browser-viewer/tests/fulltext-index/` come from the public
`in/enron-sample.mbox`; their manifest records source/output SHA-256, accessibility
and PREMIS settings. The real PDF integration test compares full results,
including document paths, page numbers and counts, for nine queries
in each of four profiles, and verifies zero child opens on indexed queries.
It also checks cache reuse, corruption/stale-file fallback and selected-page
confirmation/highlighting. Unit tests cover phrase/substring/Unicode semantics,
empty pages, nested routes, duplicate names/aliases, malformed/unsupported or
incomplete indexes, forged postings, cancellation and decompression limits.
Java tests cover actual international rendered text across PDF text objects,
empty pages, deterministic payloads, disable behavior and fixed-width binding.

Repeatable scaling uses `scripts/java/eapdf/FullTextIndexBenchmark.java` followed
by `browser-viewer/scripts/fulltext-index-benchmark.mjs`. First generate the
Phase 1 paired real-source scaling fixtures using the instructions above. Then:

```sh
javac -cp "$TASK_DIR/creator/emailtopdfa-1.6.0-beta.20.jar" -d "$TASK_DIR/classes" scripts/java/eapdf/FullTextIndexBenchmark.java
EAPDF_CONFIG="$PWD/config/eapdf_config.json" java -Xmx2g -cp "$TASK_DIR/classes:$TASK_DIR/creator/emailtopdfa-1.6.0-beta.20.jar" eapdf.FullTextIndexBenchmark "$TASK_DIR/fixtures" "$TASK_DIR/fulltext"
# From a built temporary viewer working copy, run each kind: common, rare, phrase.
EAPDF_FULLTEXT_BENCHMARK="$TASK_DIR/fulltext" EAPDF_QUERY_KIND=common npm run benchmark:fulltext-index
```

The Java timing measures the added final indexing/save/binding stage, not a
complete fresh conversion. The baseline copy time is not conversion throughput.
The browser harness compares complete normalized result fingerprints, including
identities, destinations and counts. It uses a fresh offline browser
per mode, separates opening/index loading, and executes each query twice
(the second uses uppercase to exercise case equivalence with the cached index).
These are repeated **engine executions**; the UI additionally caches identical
queries. Time to first result equals complete query time because this interface
publishes full-text results in one batch. Legacy benchmark traversal is allowed
30 minutes rather than the normal UI's 120 seconds, so large complete comparisons
are possible without changing the shipping search budget. Main JS heap and
aggregate Linux browser PSS are sampled each second, not instantaneous peaks.

The corpus repeats one public rendered email with distinct Mail_GUIDs. It
exercises scale and duplicate content, not realistic text diversity. Repeated
text compresses unusually well; index limits may be reached earlier on diverse
archives. The three benchmark kinds are a common term (`amsterdam`), root-only
rare term (`messages`) and phrase (`BODY TEXT`). Results and limitations below
must be interpreted with that corpus and the shared-host timing conditions.

Measured November 7, 2026 on Linux x86-64, Ryzen 5 2600 (six cores/12 threads),
32 GiB RAM, Node 24.19.0 and Chromium 156.0.8078.79. One instrumented trial per
mode; the three query kinds ran concurrently and other acceptance jobs shared
the host. The final disk-spooling, backpressure-aware creator produced byte-identical PDFs to the
browser benchmark inputs. All 18 mode/size/query runs completed, with identical
indexed/legacy fingerprints for both executions at every size.

| Messages | Query | Open, indexed / scan (s) | Index load (s) | First query, indexed / scan (s) | Repeated engine query, indexed / scan (s) |
| --- | --- | --- | --- | --- | --- |
| 100 | common | 0.664 / 0.693 | 0.198 | 0.0021 / 7.859 | 0.0009 / 4.865 |
| 1,000 | common | 1.443 / 2.846 | 1.849 | 0.0265 / 50.481 | 0.0264 / 46.627 |
| 10,000 | common | 11.705 / 11.499 | 14.117 | 0.1210 / 577.594 | 0.1215 / 728.711 |
| 100 | rare | 0.738 / 0.438 | 0.383 | 0.0077 / 5.745 | 0.0005 / 5.028 |
| 1,000 | rare | 1.678 / 2.423 | 2.185 | 0.0244 / 49.773 | 0.0326 / 46.960 |
| 10,000 | rare | 11.539 / 11.091 | 14.233 | 0.1200 / 577.257 | 0.1240 / 729.469 |
| 100 | phrase | 0.730 / 0.463 | 0.407 | 0.0019 / 5.809 | 0.0008 / 5.116 |
| 1,000 | phrase | 1.579 / 2.359 | 1.953 | 0.0327 / 49.357 | 0.0257 / 46.395 |
| 10,000 | phrase | 11.934 / 11.205 | 14.183 | 0.1786 / 577.832 | 0.1245 / 730.027 |

At 10,000 messages, the common-term first complete result took **25.943 seconds
including opening and index validation**, versus **589.093 seconds** for scanning.
The common query had 40,000 occurrences on 10,000 pages; the phrase had 10,000
occurrences/pages; the rare query had one root-page occurrence. Indexed queries
opened zero child PDFs at all sizes. Each complete scan opened 100/1,000/10,000
children per execution. These results demonstrate the optimization for this
corpus; they do not imply that large files open instantly.

| Messages | Baseline PDF bytes | Indexed PDF bytes | Added bytes | Added creator stage (s) |
| --- | --- | --- | --- | --- |
| 100 | 4,490,360 | 4,658,950 | 168,590 | 2.734 |
| 1,000 | 44,763,574 | 46,420,269 | 1,656,695 | 13.420 |
| 10,000 | 447,510,738 | 464,054,943 | 16,544,205 | 111.157 |

These measurements describe the former version-2 PDF.js helper, not the current
PDFBox implementation. They must not be used as version-3 performance claims.

The table uses the final helper with pipe backpressure. An earlier shared-host
run of the 10,000-message indexing stage took 159.666 seconds; the final run took
111.157 seconds. Neither is a throughput guarantee.

Overhead is approximately 3.7% in these files. The catalog XMP is intentionally
unfiltered for fixed-width binding, so the overhead also includes replacing the
previous compressed XMP representation. A binary posting codec alone would not
remove that cost. The added creator stage is substantial and is justified mainly
when the archive will be searched repeatedly; full-text generation can be disabled.

| Messages | Common query: sampled JS heap, indexed / scan (MiB) | Sampled aggregate browser PSS, indexed / scan (MiB) |
| --- | --- | --- |
| 100 | 8.6 / 13.3 | 283.4 / 367.0 |
| 1,000 | 26.2 / 45.8 | 408.5 / 516.6 |
| 10,000 | 136.0 / 213.4 | 1677.0 / 1372.2 |

At 10,000 messages, rare/phrase sampled aggregate PSS was respectively
1733.2/1632.7 MiB indexed versus 1395.3/1557.7 MiB scanned. Indexing reduced query
latency and main-thread heap in these large trials, **not total browser memory**.
Root loading, workers, byte binding and retained raw text remain material costs.
No creator peak-RSS measurement or diverse-corpus throughput comparison was
made. The repeatable scripts retain full measurements for each query kind.

### Acceptance evidence and remaining limits

All six real-source profiles passed standalone Java EA-PDF and independent
veraPDF PDF/A-4f checks, with zero failures and nine existing warnings each.
The four indexed archive profiles passed their document-part/attachment checks.
The 100- and 1,000-message indexed scaling collections passed recursive validation;
the latter used the explicit 256 MiB report/work-file allowance described above.
The 10,000-message root passed veraPDF; full recursive EA-PDF validation at that
size was not run. No validation rule was changed. Before/after comparison at all
three benchmark sizes found byte-identical embedded streams and unchanged
collection dictionaries/aliases. Original source hashes were retained.
A missing-Node conversion emitted the expected omission warning and still passed
both validators. The index remains optional.

Creator: the full 426-test suite passed (four skips), followed by all five
full-text tests after adding the international-text/empty-page case. Java
validator: 264 tests passed (nine skips). Viewer: 114 unit tests, Chromium
integration checks including 36 real indexed/scan query comparisons, automatic
selected-page disagreement fallback, and Firefox 158.0.1 temporary-install/UI
acceptance passed. The new highlighting tests exposed and fixed loss of an active
occurrence during viewport refits. Public Python regressions: 18 passed.
TypeScript/inspection-contract and documentation checks passed. Validation is
automated evidence for the tested files, not independent EA-PDF certification.

Historical version-2 acceptance boundaries (Node is no longer required):
files over the creator's 512 MiB indexing ceiling omit it;
diverse 10,000-message corpora, multi-GiB generation, full recursive 10,000-message
validation, creator peak RSS, older browsers and signed/store-installed Firefox
were not established. Nested routes and duplicate names are covered by decoder
regressions; real large nested-collection generation is not benchmarked. The
index is an optimization with explicit fallback, not an authenticity mechanism.

Full-text implementation map: `FullTextSearchIndex.java` and `Common.java`
handle generation/publication. `fulltext-index.ts` reuses `embedded-index.ts`
decoding; XMP inspection and `browser-session.ts` handle discovery/lifetime.
`collection-search.ts`, `viewer.ts`, `page-text.ts` and `scrolling-pages.ts`
handle query selection, verified navigation and highlights. Tests and both
benchmark harnesses are named above. Node remains a viewer development/build tool,
not a Java conversion or end-user dependency. No PDF.js patch, source asset,
CLI argument, release version or validation rule was changed by Phase 2.


### Follow-up performance checks

The November 7 follow-up streams index serialization without changing version 3
or its normalization/binding semantics. An isolated 1,000-real-message benchmark
produced identical gzip bytes before and after (754,713 bytes). Over three measured
rounds after warm-up, median serialization/gzip time was 197.728 → 152.230 ms,
and current-thread allocation was 63,395,456 → 18,869,688 bytes. These figures
measure that stage, not complete conversion or process peak memory.

The reproducible harness is `scripts/benchmarks/CreatorMemoryBenchmark.java`
(instructions in its JavaDoc). `scripts/benchmark_diverse_index.py` builds 100/
1,000-message subsets from an explicit existing mailbox, preserves its original,
and records source/output hashes, elapsed conversion time and resource statistics.
`browser-viewer/scripts/diverse-index-benchmark.mjs` compares indexed and scanned
common/rare/phrase results on an explicit PDF, including exact per-page destinations
and counts, child opens, opening/loading times and sampled browser PSS. Reports
must use explicit temporary destinations; no private corpus is bundled.

Signed browser acceptance now runs in CI using fixtures produced by the Java
signing tests and checked by `scripts/check_signed_index.py`. Both browser jobs
consume the same fixture artifact, preserving separate signed/unsigned reports.
