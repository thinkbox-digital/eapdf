# Fresh creator accuracy review

Completed 2026-10-25. Creator and both validators updated.

## Reviewed sources

Freshly generated and checked **100 ordinary emails**:

| Source | Emails |
| --- | ---: |
| EML | 4 |
| MSG | 6 |
| Two PST mailboxes | 78 |
| Live Gmail, read-only | 12 |

The same twelve Gmail originals were selected before and after the repair.
Credential originals were unchanged; temporary credential copies were removed.
Private Gmail emails, subjects, screenshots and detailed reports are not included
in project evidence.

## Creator repair

One real Gmail subject contained MIME encoded words joined without separating
spaces. The Java library decoded only part of it, leaving encoding syntax in the
visible subject and XMP. A direct JVM probe found four encoded words with one
joined boundary: the partial subject had 85 characters; complete decoding had 71.

The creator now separates complete encoded-word boundaries in the **original**
header and decodes it once. Ordinary subjects retain their existing handling;
intentionally encoded literal examples are not decoded twice. Gmail filenames
use the same corrected subject. Original email bytes are unchanged.

Both creator JARs include this source-field correction. CLI arguments and debug
output are retained; EA-PDF reader work remains out of scope.

## Validator repairs exposed by the fresh files

- Java now compares joined subject encoded words consistently with the web
  validator. The shareable old subject example correctly FAILs; the corrected
  example PASSes both engines and PDF/A.
- Web no longer invents an SMTP mailbox from a whole quoted display-name token
  such as `"reader@example.org"`. Exact Raw-Cc preservation remains accepted.
  A real quoted local part followed by its domain remains a mailbox.
- Java explicitly decodes a message attachment's transfer encoding before its
  size/checksum comparison. The supplied PST's native email attachment is
  **17,730 decoded bytes**; the previous reader counted **24,262 base64 wire
  bytes** and falsely failed it. Existing byte and inspection limits remain.

No rulebook interpretations were changed. All preceding corpus/real-MSG parent
rule outcomes and overall results are retained.

## Content checks

- All **22 directly retained EML/MSG/Gmail originals** match their source bytes.
- All **78 PST exports** retain the selected native body text. PST sources are
  reconstructed RFC822 messages, not byte-identical copies of native PST items.
- **84 by-value asset occurrences** have their source payload hashes present.
  This includes repeated/empty payloads; it does not assert 84 distinct copies
  or independently prove occurrence ownership. Structural/source validation
  checks those associations separately.
- All **five referenced stored pictures** have matching decoded pixels in the
  owning email's body pages.
- One nested PST email is retained as a native annotation-level attachment. Its
  selected visible body text and its one native file payload are preserved.
- Body text is compared with the email's own rendering pages. Five apparent
  missing tokens were verified across line wraps, including two table labels;
  source/PDF visual review confirmed the labels were present. Remote pictures
  absent from the source are outside this pixel proof.
- **210 body pages** were compared before/after. The pages of **99 emails** are
  pixel-identical; only the repaired Gmail subject changes its email's pages.
  The other core metadata agrees. Finding-aid run timestamps and generated
  identities are not treated as source content.
- **440 readable address/date fields** were compared; 42 unsupported address
  fields retain matching raw text. Sixty field comparisons remain unavailable.
  One native PST timestamp loses fractional seconds during reconstructed
  RFC822 Date/XMP export; this precision limit is recorded, not claimed exact.

Representative plain, HTML, RTF, stored-picture and repaired-subject pages were
visually reviewed. These checks establish the tested scope, not complete visual
or accessibility equivalence for arbitrary email HTML.

## Validation and delivery

| Check | Result |
| --- | --- |
| Creator complete suite | 277 passed; zero failures/errors/skips |
| Java validator complete suite, actual desktop included | 154 passed; zero failures/errors/skips |
| Python suite | 1,182 run; 1,142 passed; 40 optional skips |
| Existing corpus parent/embedded reports | 808 / 67 |
| Previous real MSG reports | 32 |
| Fresh parent/embedded reports, including before/after controls | 15 / 12 |
| Matching rule outcomes / reports | 198,942 / 934 |
| Missed/extra failures; execution/inspection errors | Zero |

All fresh ordinary outputs pass PDF/A with zero EA-PDF failures in both engines.
Two MSG outputs retain their known source-comparison INCOMPLETE result. The
Gmail collection and all twelve children PASS. The old public subject control
FAILs only MESSAGE_SUBJECT_XMP while passing PDF/A; its corrected counterpart
fully PASSes. All six unchanged public sample profiles remain PASS.

All current EA-PDF checks were rerun. Final Java upstream checks are fresh.
Python corpus/previous MSG upstream results were reused only after exact PDF
SHA256 matching. Fresh-output upstream checks were performed in this review;
the same verified results were reused for identical bytes after the validator
repairs, while current EA-PDF and ISO report verification were rerun. Reused
upstream reports are not described as newly executed checks.

The exact final validator JAR passes the corrected public PDF through its GUI
from a directory containing only the JAR, with Python/Docker absent from PATH.
Bundled specification text loads and worker files are cleaned.

The IDE debugger's launch configuration writes into `target/`, prohibited by
AGENTS.md. Direct JVM probes in `/tmp` supplied the actual subject and attachment
values instead. No debugger session or breakpoint was created.

## Beta scope and remaining limits

The ordinary-input beta checkpoint is retained. This review fixes a confirmed
creator display/metadata error and three validator discrepancies.

Remaining limits include full comparisons when original MSG headers are absent,
unsupported/nested/inline MSG inventories, native PST subsecond timestamp
precision, remote resources absent from the message and very large archive
validation limits. The three recorded specification ambiguities retain their
current project policies. Distribution stays deferred. Reader development and
new features remain out of scope.

Only numerical validation summaries are published. Detailed Gmail-derived
comparison rows and diagnostics remain outside project evidence.

## Files

- [Creator CLI](../../../dist/emailtopdfa.jar)
- [Creator GUI](../../../dist/emailtopdfa-gui.jar)
- [Standalone validator](../../../dist/eapdf-validator.jar)
- [Corrected subject PDF](../../../output/creator-review/fidelity/Decoded%20subject.pdf)
- [Shareable source email](Subject%20decoding.eml)
- [Build and source hashes](evidence.json)
- [Preservation comparison](preservation.json)
- [Address/date summary](header-summary.json)
- [Native PST nested-email proof](nested-pst-proof.json)
- [Existing corpus comparison](comparison-summary.json)
- [Previous real MSG comparison](real-msg-summary.json)
- [Fresh-output comparison](fresh-summary.json)
- [Standalone GUI proof](standalone-proof.json)
