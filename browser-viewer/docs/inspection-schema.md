# Inspection contract version 1

M1 establishes data and expected results. No browser inspection API is implemented
yet. The running extension retains M0 page rendering only. Every result has
`schemaVersion: 1`, and `identification.validationStatus` and
`coverage.conformance` are always `not-evaluated`.

The canonical definition is [inspection-contract.mjs](../scripts/inspection-contract.mjs).
It generates the [Draft 2020-12 schema](../schema/inspection-v1.schema.json) and
[readonly TypeScript declarations](../src/domain/inspection.d.ts). Run
`node scripts/inspection-contract.mjs --check` to detect drift. Schema changes
that change meaning or required shape require a new schema version and fixtures;
consumers must reject unknown versions. No silent migration is defined in M1.
JSON Schema checks structure, state/value compatibility, safe nonnegative integers
and closed object properties. TypeScript checks shape and readonly access, not
runtime input. Cross-document identities, reference integrity and page bounds
need semantic checks; the reference tests perform those checks separately.

## Observations and evidence

| State | Meaning |
|---|---|
| `present` | A usable value is available; `basis` explains how it was obtained. |
| `missing` | The inspected location contains no value. |
| `invalid` | A value exists but has the wrong type or cannot satisfy the field's interpretation. |
| `ambiguous` | More than one interpretation or binding remains; candidates are retained. |
| `unsupported` | A structure or declaration is outside the supported projection. |
| `unavailable` | Inspection could not establish a value, including resource limits or an absent linking mechanism. |
| `not-evaluated` | This operation deliberately did not examine that information. |

Only `present` has a non-null value. Other states have `basis: none` and a reason.
For present values, `observed` means directly read metadata, `derived` means a
documented computation such as resolving page references or hashing bytes, and
`inferred` is reserved for explicitly labelled future heuristics. The reference
oracle uses no heuristic ownership inference. Candidate values are never selected
merely to make a record complete. A present empty array can mean "no proven links"
in the selected projection, not proof that no such relationships exist.

`raw` retains the underlying PDF type and PDF string/name bytes as hex where
available, alongside decoded text. A PDF name's raw bytes include `/`; its usable
value removes that delimiter. These are decoded object bytes, not original file
lexemes (`#xx`, literal-string escaping or compression). XMP raw text has already
undergone XML entity decoding. Structured XMP values retain their type, not a full
XML serialization. Identity properties do not coerce URI values into text.

Each evidence locator includes an owning document ID, indirect object number and
generation when available, and a deterministic path. Direct objects use a logical
path and null object/generation. Field paths extend the owning object's path.
`documentId` is `sha256:` plus the hash of the exact PDF bytes. Rewriting a PDF
changes its identity. Equal child bytes share a document ID, while each parent
membership remains a distinct file/stream relationship.

## Messages, parts and pages

Message IDs are catalog-XMP record occurrence locators, independent of Mail_GUID
and Internet Message-ID. Duplicate GUIDs therefore preserve separate messages
and ambiguous bindings. GUID matching is exact and case-sensitive, without UUID
syntax assumptions, prefix removal or Unicode normalization. DPM identity must
be a PDF string. A present unusable GUID blocks Message-ID fallback. DPart owners
use the nearest explicit valid GUID in the traversed ancestry, with no fallback
to a coincident Internet Message-ID. Attachment records may use their explicit
Message-ID only when Mail_GUID is missing. This conservative exact comparison is
an inspection choice, not the validator's whitespace-normalization policy.

Parts preserve traversed parent/child order, the separately declared parent,
raw Start/End references, DPM scalar fields and resolved ownership. References
must resolve to pages in that document. Page ranges use `indexBase: 0` and
`endInclusive: true`; an omitted End defaults to Start. Invalid/reversed ranges
remain invalid. The contract's PDF.js inspection boundary is zero-based. The
existing public PDF.js rendering call and displayed page numbers are one-based:
future consumers must call `getPage(start + 1)`. M1 changes no rendering calls.

`partIds` lists only proven bindings; an empty list does not certify that a
message has no pages. Collection XMP summaries can describe messages whose page
parts exist only in child PDFs. Outer and child message identities are not merged.

## Files and binary extraction

Metadata records distinguish `email`, `source-asset` and `attachment`. Physical
file specifications retain their own IDs, F/UF filenames, AFRelationship,
discovery origins and every EF stream alternative. Aliases pointing to the same
stream share one stream record with all EF keys. Stream records retain MIME type,
Mail_MediaTypeParameters, declared size/MD5, computed decoded length/MD5/SHA-256,
and extraction state. Declared and computed values never overwrite each other.
MD5 here is the specification's comparison field, not an authenticity guarantee.

An existing zero-byte stream is present and extractable. A missing EF dictionary
is missing; wrong EF types are invalid. Neither is substituted with the other.
An extraction budget failure is unavailable, not missing. File discovery coverage
describes traversal completion, not whether every payload could be decoded.

Table 16 does not provide a mandatory source-asset-to-message link. The oracle
does not parse original email or guess by filename/order/sole-message count.
Source ownership stays unavailable and message `sourceAssetIds` stays not-evaluated.
Attachment metadata owners use explicit identity. A unique decoded MD5 plus
length match is a derived physical binding; duplicate matches remain ambiguous.
Filename/length without checksum remains a candidate, including isolated examples.
`metadataRecordIds` inventories possible matches; consumers must inspect each
record's `files` state before treating a match as established. Roles are derived
only from unique byte bindings, not a filename extension or AFRelationship alone.

File annotation owners require every annotation page context to resolve uniquely
through the inspected parts. A file shared across uniquely resolved contexts can
have multiple owners. Otherwise ownership stays ambiguous/unavailable. Unannotated
files can use unique physical metadata bindings with explicit message identities.
Object-level AF, catalog AF and name-tree discovery do not alone prove an owner.

Binary bytes are excluded from inspection JSON. `BoundedExtraction.read` accepts
document ID, file ID, stream locator and maximum returned bytes, yielding a
`Uint8Array`. This is a future browser interface, not a shipped implementation.
Development-only `read_embedded_bytes` verifies the exact document hash, indirect
file specification, EF membership and byte allowance, and returns bytes in memory.
It rejects foreign locators and unsupported direct file-spec paths; it never
uses a stored filename as a destination path.

## Collections and limits

Collections retain folder identities, numbers, names, tree links, collection
schema key names, view, file membership and recursive child inspections. Folder
membership follows the name-tree `<ID>` prefix or root convention; duplicate
folder numbers remain ambiguous. Original name-tree keys remain evidence. Child
PDFs declared as application/pdf are examined regardless of their EA-PDF profile,
including mixed profiles and nested collections. Corrupt children are invalid;
budget-limited children are unavailable. Non-PDF members remain ordinary files.
M1 does not guess PDF membership by filenames or repair mislabeled MIME types.

Default limits are 32 MiB input per PDF, 16 MiB per embedded stream, 2 MiB catalog
metadata, 64 MiB total decoded bytes across children, 100,000 reachable objects,
10,000 DParts, tree depth 64, embedded-child depth 4 and 32 child inspections.
Repeated tree nodes stop traversal; child ancestor hashes detect recursive
document references. Limits are recorded in each result. The reference tool
uses qpdf's full-buffer decoding before checking decoded sizes, so these are
returned-data/traversal limits, not a decompression-memory sandbox. Only trusted,
hash-pinned development fixtures are in scope. Browser worker hardening is later work.

## Deliberate projection limits

The oracle reads catalog XMP, text/name DPM values, page references, reachable
object AF arrays, embedded-file name trees, file annotations and collection trees.
It supports the RDF Bag forms observed in these fixtures, including record
attributes and nested RDF Description; it is not a complete RDF processor.
Malformed catalog XMP makes metadata coverage invalid and declarations unavailable.
Unknown profiles remain unsupported, with raw declarations preserved.

Expected XMP values are limited to identity, counts and asset descriptors; omitted
property names are listed in `uninspectedFields`. Full headers, email bodies,
logical structure, original email parsing, content-stream marked AF references,
encryption/password handling and full collection schema semantics are not covered.
No source parser, validation engine or Python/Java runtime is shipped. A result
with no diagnostics still makes no conformance claim.

## Reproducing the development oracle

Use Python 3.12 with the exact packages in `reference/requirements.txt`. The
provenance manifest records the Python minor version, pinned dependency versions,
source hashes and authoritative tracked specification/schema evidence; patch
releases of Python 3.12 may differ between developer machines and CI. Runtime
patch versions remain visible in the execution logs.

The 27 frozen inspection cases are self-contained in the public repository.
`existing-reversed-order` and `existing-uri-checksum` retain their historical case
identifiers but now use original synthetic controls generated by
`reference/make_controls.py`: descending body ranges `[4,4]`, `[3,3]`, `[1,2]`,
and an attachment checksum expressed as an RDF URI. Direct page/object, byte
hash and metadata assertions remain independent of the frozen JSON snapshots.
No private fixture directory or local historical audit text is required.
