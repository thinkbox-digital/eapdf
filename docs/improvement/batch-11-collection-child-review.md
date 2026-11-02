# Batch 11 final collection child acceptance

The recovered thread `01a14fec-a1ff-7361-9b28-0bec55c15499` ends with the user's
approval to prioritize preservation assurance. Its later direction supersedes
the B10 work log's suggested rendered-header batch. The first confirmed gap is
acceptance of damaged embedded children in an otherwise accepted final collection.

## Requirement and interpretation

Reviewed the supplied EA-PDF 1.0 section 7.6, printed pages 49–50 (PDF pages
56–57). Collection members are EA-PDF files, including recursively nested
collections; extracted isolated members retain their other EA-PDF requirements.
The requirement register retains the reference PDF hash. Recursive creator
consistency checking is application assurance supporting that obligation. The
Java subset does not replace independent PDF/A and complete EA-PDF validation.

## Confirmed defect and repair

`PdfCreationValidator.validateContainedFiles` previously checked child XMP
encoding, profile/version/revision identification, and whether PDFBox could open
the stream. It did not invoke the remaining creator checks on the child. A child
without Sent therefore passed the parent gate and replaced an existing archive.
The two pre-fix failing assertions in `batch-11-java-before.log` reproduce both
direct and nested acceptance gaps.

The parent gate now streams each actual embedded child to a temporary file,
opens it with the existing disk-backed PDFBox cache, and applies the same creator
checks recursively. It retains Source, declared Supplement and Related Files
exclusions. The shared traversal context limits all descendants to the configured
message count and cumulative expanded child bytes to the existing output-byte
budget. At most eight child nesting levels are accepted. Limits abort acceptance;
they do not waive unchecked members. Cancellation is checked before validation
and while copying bytes. Temporary child files are deleted on success or failure.

`Common.writePdfAtomically` already validates the serialized parent before
replacement. Its existing gate now includes these child checks. The new tests
require unchanged existing destination bytes after each rejected candidate.
This repair does not add an external validator subprocess or change CLI options.

## Independent fixtures

An authored CRLF MIME source contains a body and one native attachment. Two
valid collection fixtures cover c/ci. Six mutations remove child Sent, remove a
page's DPart connection, or corrupt the native annotation's MD5 parameter. Each
replacement is reserialized, and the parent's embedded size/checksum describes
the exact replacement bytes. The independent Python recipe inspects the changed
objects, verifies the parent envelope checksum, and checks exact expected child
rule failures as well as PDF/A on both parent and child. Isolated placeholders
remain zero-byte payloads; their stored-byte checksums are still checked.

Java tests also accept valid grandchildren, reject damaged grandchildren, accept
eight levels but reject nine, and verify shared descendant count/expanded-byte
limits. Corpus inputs, Java mutation recipe and Python verifier are retained.

The initial normalized-record helper omitted a consistent original body MIME
type. Independent validation caught an additional DPM_BODY_MEDIA_TYPE_MATCH_SOURCE
failure even on its valid c counterpart. That provisional eight-PDF corpus and
reports are retained under `batch-11-corpus-initial`; the final corpus uses the
authored EML. A later verifier assumption that a child had only one file-attachment
annotation was corrected to identify `native.txt`; source-email annotations are
also legitimate. Its failed log is retained. The application repair was unchanged
during these fixture corrections. Final tests narrow the checksum mutation to
the intended native attachment rather than also changing the source annotation.

## Remaining preservation work

This closes a final-container consistency gap, not preservation completeness.
The creator still lacks an independent inventory proving that every original
input message/attachment reached the normalized model and final output. Existing
checksum and attachment checks consume PDF metadata, so a writer that omits both
an asset and its metadata may escape them. Broader ownership/cardinality checks,
source/export reconstruction provenance, independently required final PDF/A and
full EA-PDF gates, configured sidecar acceptance, and external release checks
remain open. Eight-level/count/expanded-byte bounds are application policy, not
new EA-PDF normative limits. Full standards and header work remain partial.

The final Java suite passes 142 tests. All eight corpus parents and their eight
children PASS independent PDF/A; the two valid cases PASS EA-PDF and six mutations
produce exactly their declared child failure plus COLLECTION_CHILD_VALIDATION.
All six regenerated saved samples and both collection finding-aid PDF sidecars
PASS independent checks. Full Python acceptance completed: 249 tests in 566.697 seconds, 248 passes,
one optional PREMIS schema skip, no failures/errors. All twelve normal and
accessibility profile reports PASS including children. Artifact hashes and exact
commands are in `batch-11-latest-evidence.json`.
