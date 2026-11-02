# D03 — Java validator repairs

The Java app is closer to the web validator. It remains incomplete, and overall
PASS remains disabled. [Results and remaining differences](RESULTS.md).

## Repairs

- Read PDF text strings using their UTF-8/UTF-16 markers. Keep unreadable text
  separate from a decoded empty value.
- Check FOAF mailbox containers and values. Decode mailto escapes once, preserve
  mailbox local-part case and quoted data, and compare ordered source addresses.
- Check Sent dateTime syntax, reply Bags, canonical Message-ID and its older
  alias, required asset records, supplied attachment identities and owned counts.
- Match an email to source evidence rather than assuming that the only embedded
  source belongs to the only metadata record. Ambiguous matches remain unavailable.
- Compare Subject text without silently repairing damaged encoded words. Decode
  normal UTF-8 source headers. Unknown or invalid source encoding stays unavailable.
- Compare Sent against the original date as an instant, including dates in child
  PDFs. Different timezone spellings of the same instant are permitted. Dates
  without a known comparable instant remain unavailable.
- Match original MIME attachments as separate occurrences. An optional checksum
  cannot take the occurrence needed by a more specific record. Preserve permitted
  generated filenames and zero-byte placeholder comparisons.
- Compare attachment metadata against native bytes stored in the PDF, even when
  the original email cannot be inspected. Ambiguous associations can fail a field
  only when every possible payload contradicts it. These comparisons do not prove
  occurrence identity or email ownership.
- Compare media parameters with case-insensitive charset names while preserving
  case and whitespace in other parameter values.
- Check DPart object/reference shapes, labels, record levels, parent/leaf entries,
  metadata graphs and GUID values. Report unreadable identity evidence separately.
- Resolve active UserProperties arrays in the same order as the web engine;
  later arrays replace earlier arrays. Non-text GUID values do not become empty
  strings for ownership comparisons.
- Discover files associated by marked content through its MCAF entry, including
  named property resources and nested forms.
- Keep malformed MIME parameter errors local to the affected comparison, so the
  rest of the PDF is still checked.

## Comparison

The baseline is the delivered D02 Java JAR. Both Java revisions are compared with
the same frozen Python V85 reports: 808 parent PDFs, 67 matched embedded-file
reports and all 225 rule codes. Input bytes and frozen engine files are checked
by SHA-256. The web app still calls that unchanged Python engine.

The repaired Java JAR runs the entire corpus again with real bundled PDF/A and
selected ISO checks. Cached Python reports are reused only for the identical
input manifest and frozen engine hashes. No Python behavior or inspection budget
is changed to improve agreement. See [D02's method](../validator-desktop-02/README.md)
for the full comparison policy and corpus limits.

The evidence retains every severity transition, item counts, upstream rule IDs,
object locations, error text, child inventories and execution errors. A smaller
disagreement count is progress against V85, not independent standards certification.
Overall PASS is not enabled by a matching maximum severity.

## Runtime trace

The MCAF regression was also checked with an isolated Java parser probe. The IDE
had no imported standalone-validator module or launchable entry point, so the
debugger could not launch it without changing the project configuration. The
fallback probe used the same PDFBox classes and the actual saved regression PDF:

```text
operator=BDC tag=COSName{AF} property keys=[COSName{MCAF}]
entry class=COSObject value=COSObject{298 0 R}
```

The cause was a lookup for AF where the marked-content dictionary uses MCAF.

- The actual `upstream-issues/mcaf-unlisted.pdf` page uses an AF BDC operator.
- Its property dictionary contains MCAF, whose value is indirect object 298 0 R.
- The old AF lookup missed that file specification. Reading MCAF adds it to the
  associated-file inventory and exposes its missing EmbeddedFiles name-tree entry.

The repair is in [MarkedFileAssociations.java](../../../validator-desktop/src/main/java/eapdf/MarkedFileAssociations.java).
The regression verifies AF_IN_NAME_TREE FAIL for the unlisted file. No debugger
call stack or suspended-frame state was obtained; the runtime evidence is the
parser probe and the real-file regression. No debugger sessions, breakpoints or
IDE configuration were added. Final cleanup found no agent breakpoints or active
sessions; the two existing disabled exception breakpoints were left alone.

## Delivery and limits

`dist/eapdf-validator.jar` remains a single Java 21 JAR with its libraries, rules,
profile and specification references included. Verification includes the real
desktop, cancellation/cleanup and a copied JAR running in an empty working folder
with Python and Docker unavailable on PATH. [Build and hash evidence](evidence.json).

The remaining differences include source inventories and binding, metadata
qualifiers, attachment annotation ownership, content-set ownership and collection
rules. Follow the measured priorities in [RESULTS.md](RESULTS.md). The creator,
frozen validator and protected Agenda/BFO PDFs are unchanged. No release or push
is performed.
