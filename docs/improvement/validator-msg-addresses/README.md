# Native Outlook addresses and ordinary attachments

Completed 2026-10-25. Both validators updated; creator code is unchanged.

## What changed

Both validators now check known sender and recipient mailbox occurrences from
stored Outlook properties. Recipient type and the complete declared row count
must be readable before absence can be inferred. An unreadable recipient leaves
its field unavailable. SMTP counterparts are used only where actually stored;
a delegate's address is not assigned to a different represented author.

Mailbox local-part case and repeated occurrences are preserved. Domain case is
ignored. The native recipient table does not establish original header order,
so these checks do not claim to verify that order or reconstruct missing headers.

The existing native attachment reader and occurrence matcher also handle
headerless ordinary MSG files with complete by-value attachment inventories.
Filename, size, supplied checksum and stored MIME type are compared with the
actual original attachment evidence. Missing native MIME types are not guessed.
Nested messages, inline inventories and unsupported decoding remain unavailable.

Native field checks now cover **13 supplied MSG files**, up from eight, including
**five files with attachments**. This is partial evidence, not full source PASS.
The twelve stored-header MSG comparisons from the preceding build are retained.

Java no longer leaves an obsolete unavailable-inventory warning after a native
attachment was checked. It also distinguishes a malformed mailbox from a
missing address field, matching the web validator.

## Independent creator check

Read original binary MSG properties independently of the creator and compare
with document-level XMP across all **31 current outputs**. All **87 readable
mailbox occurrences across 57 fields agree**. Three files have unresolved native
recipient addresses; their full address inventories are not claimed verified.
No new creator address defect was found, so creator code, both creator JARs and
all PDF inputs remain unchanged. Previous recipient-order and sent-date repairs
are retained.

Four historical negative PDFs already had wrong native metadata. They now also
fail their provably wrong addresses; three describe attachments absent from
their native originals and now fail that comparison too. All four remain FAIL;
no overall result is relaxed. Their PDF bytes are unchanged. Fixture
expectations and builders record the stronger findings.

## Verification

| Check | Result |
| --- | --- |
| Java validator suite, including actual desktop | 149 passed; zero failures/errors/skips |
| Python suite | 1,180 run; 1,140 passed; 40 optional skips |
| Creator suite | Unchanged; preceding complete 274-pass build retained |
| Existing parent PDFs / embedded reports | 808 / 67 |
| Additional real MSG reports | 32: 31 current outputs and prior bad-date control |
| All matching rule outcomes / reports | 193,191 / 907 |
| Missed/extra failures, inspection/execution errors | Zero |

All six public samples remain PASS in both engines. All 31 current MSG outputs
pass PDF/A with zero EA-PDF failures: twelve full PASS, nineteen source-comparison
INCOMPLETE. The earlier wrong-date output still FAILs Sent in both engines.
Native tests also reject missing, changed and duplicate recipients, altered
local-part case, wrong attachment bytes/names, and unsupported recipient types.

All current EA-PDF checks were rerun across the corpus. Java upstream checks
were freshly executed from the final JAR. Web corpus upstream results were
reused only after exact PDF SHA-256 matches; current ISO report verification
still ran. The 32 real MSG reports received fresh complete upstream and EA-PDF
checks in both engines. Cached upstream reports are not called fresh runs.

The exact final JAR passes through its GUI with only the JAR in its working
folder, Python/Docker unavailable on PATH, bundled specification text and cleaned
worker files. An initial cleanup inventory check overlapped other validator
workers; it was repeated alone after those workers ended and passed.

## Specification review and beta scope

The active rulebook and validation policies remain unchanged:

- Rule 107: require namespace bindings for used custom EA-PDF semantics; the
  standard-only exemption remains a documented project interpretation.
- Rules 156/157: the creator records the EA-PDF creation event. Date syntax alone
  does not prove provenance, and equality with Sent does not prove a violation.
- Rules 16/86: retain Table 6's Size subtype. Original email bytes remain in email
  XMP SizeInBytes; the collection field type alone does not prove original size.

These specification ambiguities are not declared resolved. The old review note
allowing omission of the attachments property is corrected to the approved
current rulebook: every profile requires a Bag, which may be empty.

The ordinary-input beta checkpoint is retained. No further confirmed ordinary
creator defect was found by this review. Full missing-header comparisons,
unsupported/nested/inline MSG inventories and very large archive limits remain
explicit limitations. Distribution stays deferred; reader work and new features
remain out of scope.

## Delivery and evidence

- [Standalone validator](../../../dist/eapdf-validator.jar)
- [Build and source hashes](evidence.json)
- [Full corpus comparison](comparison.json.gz)
- [Real MSG comparison](real-comparison.json.gz)
- [Independent native address audit](address-audit.json)
- [Native field and attachment scope](native-inventory.json)
- [Changed historical outcomes](effect.json)
- [Standalone GUI proof](standalone-proof.json)
- [Desktop screenshot](window.png)
