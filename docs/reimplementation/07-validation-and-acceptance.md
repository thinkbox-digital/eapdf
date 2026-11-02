# Validation and acceptance specification

## Existing validation layers

1. `Common.requireCreationDate` and reader/limit checks reject selected invalid inputs.
2. `PdfCreationValidator` checks selected creator invariants before replacing the destination. It verifies readable XMP/profile/cardinality/Sent/GUID, pages/outlines/DPart presence, TS 32005 declaration presence when tagged, selected filename/checksum constraints and matching native attachment annotations. It does not validate all PDF/A, collection or EA-PDF requirements, nor rendered fidelity or PREMIS schema.
3. `validate_eapdf.validate` inspects the PDF/XMP using pikepdf and pypdf, with source comparisons when embedded RFC data is inspectable.
4. `validate_one` additionally runs veraPDF, recursively validates identifiable collection children, combines statuses and writes HTML.
5. Source/visual/accessibility/reader acceptance requires independent and human checks outside those layers.

Never replace layers 4–5 with a generator metadata claim. A different language implementation should pass the same normative/semantic assertions through a validator developed or reviewed independently from its writer.

## Python validator flow

Open the PDF; inspect allowed versions, page count, output intents and outlines; parse catalog XMP; check PDF/A and EA-PDF identification/keywords/Info crosswalk; collect name-tree/catalog AF/annotation files; check catalog/profile/collection policy; traverse DParts and page backreferences; inspect logical structure and bidirectional ParentTree correspondence; compare source assets; validate required message metadata; compare inspectable source headers/attachments/body media types; inspect additional annotation/text/mapping rules; check fonts; recursively inspect child PDFs; fill remaining registry codes with applicability explanations.

The rule registry has 216 codes at review time, sourced from the display order, JSON descriptions and reference mappings. A code count is not a coverage percentage. HTML/web collapse repeated findings by worst severity while JSON retains detailed items. PDF/A-covered local diagnostics are visually separated from the independent pass. Unknown/unexecuted code completion currently uses N/A and can conceal a missing implementation; the replacement needs a distinct UNVERIFIED state.

Tree walkers bound depth/items (256/100,000) and detect cycles/repeated entries. Source MIME helpers retain original attachment bytes where possible. Child validation has shared depth/count/decoded-byte budgets, active hash recursion detection and SHA-256 cache. A child is identified by Collection Item or recognizable EA-PDF PDF content; finding-aid PDFs without EA-PDF identity are not automatically message children. Missing/failed independent child checks must propagate to the container acceptance result.

## Known oracle limitations

The new probes demonstrate false negatives and false positives (R1–R7). Additional limitations found statically:

- Nonblank-content inspection detects painting operators/annotations, not full graphics state, clipping, opacity and CropBox visibility. The function is explicitly named `page_has_visibleish_content`.
- Field evidence can establish text objects for tagged fields without proving every required field's presence/value. English-label fallback is bounded; unfamiliar layouts can remain INCOMPLETE.
- Source comparisons parse RFC822/EML only. Embedded MSG/PST sources are not fully understood by the Python validator. No inspectable source does not imply source equality was verified.
- MIME/base media-type and optional-field checks are not full RFC conformance checks. Dates need both syntactic validity and a truthful normalization policy.
- Structure correspondence is useful but does not implement all ISO TS 32005 inclusion rules, semantic reading order, or PDF/UA acceptance.
- Membership/count matching sometimes relies on sets or heuristics, rather than a one-to-one provenance relationship. Mutations must test cross-message substitution, duplicate filenames and duplicate Message-IDs.
- PDF/A success is independent of rendered-email completeness and optional PREMIS validity.

For replacement acceptance, model requirement strength, applicability, evidence and outcome as separate values. A failed SHALL is FAIL; a mandatory rule that could not run is UNVERIFIED/INCOMPLETE; a truly inapplicable rule is N/A; a recommended deviation is WARN and subject to explicit policy. All outcomes should include observed and expected values plus evidence location.

## Portable acceptance corpus

Test semantic invariants, not byte equality between PDFs. Random GUIDs, timestamps, font subsets, compression, object ordering and page layout can differ while remaining equivalent. Keep bit equality for original/native payloads. All fixtures should be synthetic or have approved redistribution rights.

| ID | Fixture/trigger | Required replacement result |
| --- | --- | --- |
| A01 | Plain EML with valid Date, From/To/Subject | One message, source bytes exact in non-isolated, correct metadata/DPM/text/outline |
| A02 | Same input isolated | No original Source/native bytes; explicit omission evidence; rendered content retained |
| A03 | Missing Date | Reject/quarantine without replacing an existing accepted PDF; no invented Sent |
| A04 | Recoverably invalid Date; impossible calendar date; GMT; wrong weekday | Document normalization policy; required Raw-Sent/warnings, truthful instant or rejection |
| A05 | Empty optional headers, duplicate/missing Message-IDs | Distinct GUIDs; no merged messages or guessed addresses |
| A06 | Encoded/folded/repeated headers; international/group addresses | Retain raw source, structured agents/order and display fidelity; report ambiguity |
| A07 | multipart/alternative, mixed, related with explicit start | Correct selected part/order; attachment bytes not mistaken for body |
| A08 | Selected body has charset/format/name parameters | DPM retains required full selected type; validator accepts legal name encoding |
| A09 | Encrypted/S/MIME/unsupported type | Visible unsupported notice and warning; no implied decryption or content success |
| A10 | Attached message containing native attachments | One parent attachment containing complete child bytes; recursive extraction opens correctly |
| A11 | Identical native bytes with duplicate/case/path/Unicode filenames | Preserve original identities/names; deterministic unique safe PDF names |
| A12 | Zero-byte native file | Preserve legitimate empty file distinctly from policy omission |
| A13 | Images-only HTML with multiple data/CID images | All intended visible images retained in order/position; no silent body replacement (R1) |
| A14 | Missing/decorative/meaningful image alt | Distinguish decoration from missing description; review warning when needed (R7) |
| A15 | Hidden CSS, HTML tables, nested blocks, links | Preserve visibility/semantics; unsupported features issue scoped diagnostics |
| A16 | External HTTP/private-network/file/CSS resources | No unauthorized reads/network; explicit reduced-rendering diagnostics |
| A17 | RTL, Arabic/Indic shaping, CJK, combining marks, emoji | Font/reading-order acceptance and explicit unsupported cases; searchable core fields |
| A18 | Long message/header/attachment lists | Multi-page coverage, no clipped required annotations, usable context/navigation |
| A19 | Real MSG + embedded MSG with code pages/RTF | Original outer bytes exact; complete independently readable derived child |
| A20 | PST folders, RTF, attached messages, unsupported methods | Explicit preservation boundary, correctly mapped records, fail unsupported methods |
| A21 | mboxo/rd/cl/cl2, CR/LF/CRLF, From-like body lines | Correct split/unquote/length behavior; byte-preserving exported message provenance |
| A22 | One/two/many messages across six profiles | Correct cardinality, per-message DPM, child profile and recursive validation |
| A23 | Colon/path/casefold/collision/long collection source names | Every embedded/folder name valid and unique after normalization (R2) |
| A24 | Finding aid disabled/enabled; XLSX embedded/sidecar combinations | Required collection front matter remains; links/row association accurate |
| A25 | PREMIS enabled, zero source attachments | Valid PREMIS 3; correct navigation without false email-attachment inference (R5/R6) |
| A26 | Normalized/body-staged files, standalone packaged run | Same semantics outside checkout, correct version/assets/effective config |
| A27 | Gmail retry/resume/checksum changes/account/options changes | Frozen snapshot semantics, no incorrect completed reuse, private retention/locking |
| A28 | Multi-label Gmail, commas, unknown/ambiguous labels | Correct label resolution/membership, no unintended participant filtering |
| A29 | Crash/cancel/disk full/sidecar failure/output already exists | Previous accepted file preserved where promised; explicit partial result/recovery |
| A30 | MIME depth/parts/decoded bytes/image pixels/temp bytes/runtime limits | Bound work before allocation; terminate and clean only owned resources |
| A31 | Traversal/symlink/duplicate uploads, expired/foreign download tokens | No path escape or cross-tenant read; specified 400/404/413/429 behavior |
| A32 | Restart/multiworker capacity/proxy prefix/cleanup | Shared capacity/registries work; private result lifetime honored |

Test each profile with accessibility and relevant feature combinations, not just defaults. Independently extract native/source bytes and compare their hashes; verify child files through separate parsers/viewers. Capture visual baselines with a fixed renderer, DPI, fonts and ICC; compare body region and human review of complex cases. For accessibility, combine an appropriate independent validator, full TS 32005 checks, reading-order review and assistive-technology use.

## Mandatory negative mutations

For every applicable SHALL, start from an accepted PDF and change exactly one property. Examples: NFSM wrong (R3), required nonempty metadata removed, wrong namespace/container/type, wrong GUID owner, missing/out-of-range DPart, incorrect first-page outline, collection field E=true, source name invalid, altered source/native bytes, false size/MD5, checksum stored as hex text, annotation hidden/outside CropBox, orphan ParentTree, wrong namespace role mapping, missing TS declaration, swapped child/profile, malformed/cyclic PDF trees. Assert failure **for the intended requirement**, while keeping unrelated constraints valid. Preserve namespace prefixes when mutating XMP, or the test may fail for a different reason.

Also include valid alternative encodings: RDF attribute/element forms, permitted arrays/dates, namespace bindings, encoded PDF names and parameter ordering/case semantics. Prevent false rejection of permitted files. Verify PDF/A-3 support separately if a port implements it; current generator tests only its PDF/A-4f outputs.

## Evidence bundle and sign-off

For each release retain source snapshot and build hashes, effective config/limits, dependency and asset inventories, standards editions/errata, fixture provenance, command/environment/tool versions, generator logs, original/stored hashes, PDFs, extracted child inventory, independent reports, mutation outcomes and fidelity/accessibility reviewer decisions. Include unavailable checks and unsupported inputs explicitly. Temporary `/tmp` paths are useful during development but cannot be the sole durable proof.

Release approval requires the [readiness gates](01-readiness-review.md), a completed clause register and written policy for residual recommended deviations. Passing this repository's current suite alone does not satisfy that acceptance contract.
