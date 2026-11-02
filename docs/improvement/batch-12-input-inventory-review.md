# Batch 12 normalized input reconciliation

## Scope and supporting requirements

Continue the user's preservation priority from thread
`01a14fec-a1ff-7361-9b28-0bec55c15499`. EA-PDF 1.0 sections 7.2, 7.5, 7.6, 9.4,
9.8.3 and 9.8.4 describe retained sources, attachments, collection children and
their metadata. The pinned local standard remains in the requirements register.
An external input inventory is application assurance; this batch does not add
mandatory PDF-only rules or treat intentional isolation omissions as failures.

The capture boundary is entry to the normalized writer, before rendering.
It does not establish that readers enumerated every original message or MIME/MAPI
asset. Reader omissions, dispatch/concatenation losses before this boundary,
reconstruction provenance and complete independent publication/sidecar acceptance
remain separate work.

## Reproduced gap

Six failing Java assertions against the old gate are retained in
`batch-12-java-before.log`. It accepted outputs after entire messages or native
attachments disappeared together with their generated metadata. It also accepted
changed payloads with updated PDF checksums, equal-named attachments swapped
between GUID owners, one annotation satisfying repeated identical metadata
occurrences, and modified disk-staged native bytes. The intact six-profile test
passed. The initial harness used the existing metadata-derived gate; its final
capture/write helpers pass the independent immutable snapshot instead.

## Implementation

`PreservationInventory.capture` finalizes internal GUIDs and freezes immutable
per-message expectations from normalized input streams. It computes SHA-256 and
length itself, without trusting the model's existing checksum fields or PDF/XMP
values. Native attachment occurrences are retained separately, including equal
names/bytes. Sources and declared file-set Supplements are fingerprinted when
retained. Reconstruction uses the existing deterministic EML synthesizer where
no original source payload is present; this does not turn an export into original
PST/MBOX bytes.

All six production writer paths capture before rendering. The existing atomic
gate receives the snapshot and reconciles the reopened final document and actual
embedded children during B11's bounded recursive validation. Returned single-
message byte arrays are now reopened and checked after serialization as well.
No CLI options or debug statements change.

The reconciliation compares root GUID/count metadata with expected inputs,
checks attachment metadata counts, hashes actual catalog Source/Supplement
streams, follows each native annotation's page DPart ancestry to its GUID owner,
and consumes one expected occurrence per actual annotation. A single annotation
cannot satisfy two occurrences. F/UF payload aliases must agree. Each expected
message must have rendered pages and occur exactly once across rendered leaf
documents. Sources are compared within each document's scope; arbitrary source-
to-message association inside a flat archive is not newly certified.

Collections also require embedded children to occur in their member name tree.
Unused PDF objects retained in the file cannot stand in for an unlisted member.
Repeated listings of a Data file specification are rejected by this bounded
creator policy. B11 still checks the other embedded objects as before. Normal
Source, declared Supplement and Related Files exclusions remain in place.

The snapshot records source/attachment dispositions. Isolated profiles accept
absent retained sources and compare native annotations with the expected zero-
byte placeholders. Original attachment fingerprints remain available when input
bytes are available. Message and attachment occurrences still have to survive.
Missing native bytes in non-isolated normalized inputs prevent capture.

Input hashing uses streaming buffers with cancellation/output-size checks. The
snapshot has a metadata budget and input-count limit. B11's shared recursive
depth/count/expanded-byte bounds remain active. Limits abort acceptance. Captured
snapshots are process-local expectations; corpus JSON exports are test evidence,
not new user-facing sidecars or CLI features.

## Corpus and independent oracle

The Java test recipe retains 25 candidate PDFs plus pre-mutation snapshots and
creator diagnostics: six intact profiles, three isolated/no-source cases and
sixteen omission/change/ownership/occurrence cases. Rejection tests require
unchanged destination bytes. The source is the authored B11 CRLF MIME fixture,
with independently specified A/B/C subject/native-payload substitutions; the
identical-occurrence case explicitly adds a second equal normalized attachment.

The Python verifier independently reconstructs the authored source and native
payload hashes, checks the captured snapshots against them, walks named children,
and counts actual bytes/owners/occurrences with pikepdf. It also runs the existing
PDF/A and PDF-only EA-PDF validator. A PDF can be internally conformant while
missing an expected input; those outcomes are recorded separately from creator
inventory rejection. Exact reviewed PDF-only expectations are declared in
`tests/fixtures/eapdf/batch12/cases.json` after initial report inspection.

The final Java package passes 152 tests. All 25 corpus candidates and 21 embedded
children PASS independent PDF/A. All nine positive cases reconcile, and all
sixteen negative cases reject with independently confirmed input differences.
Nine rejected candidates still PASS PDF-only EA-PDF; seven fail their exact
declared PDF-only rules. All six regenerated samples, both collection finding-aid
PDF sidecars and all twelve normal/accessibility profile reports PASS their
independent checks. The complete Python suite ran 249 tests in 587.656 seconds: 248 passes,
one optional PREMIS schema skip, no failures/errors. Final hashes and commands
are recorded in `batch-12-latest-evidence.json`.

## Remaining boundaries

This is a normalized writer inventory, not proof of original reader completeness.
Broader RDF/cardinality/source ownership, arbitrary Related Files layouts,
rendered inline/body asset fidelity, export provenance, full standards/accessibility
coverage and an integrated independent final-output/sidecar gate remain open.
Current attachment metadata count checks do not certify every per-occurrence XMP
property or semantic association. Shared file-set Supplements are compared by
byte content without requiring duplicate storage. The Python validator's scope
and existing recommendation warnings are unchanged.

The generic creation validator without an expected inventory remains usable for
metadata-only checks. The unused legacy `writeFolderMessageCollection` helper is
outside the three current production entry points and still uses that generic
gate. Current generation uses a uniform isolation policy; this is not an inventory
format for importing arbitrary mixed-profile collections.
