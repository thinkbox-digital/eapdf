# Batch 09 source-header occurrences and bounded associations

Reviewed 2026-10-18 against local EA-PDF 1.0 sections 7.3 and 9.7, especially the conditional header-tagging recommendation on printed p.76. Reference PDF hashes remain in requirements.json. This batch extends application evidence; it does not complete rule 107 or add a mandatory occurrence-equality rule.

## Source inventory

[RFC 5322 section 2.2](https://www.rfc-editor.org/rfc/rfc5322.html#section-2.2) defines named fields and folding. [Section 3.6](https://www.rfc-editor.org/rfc/rfc5322.html#section-3.6) gives occurrence constraints and addresses ordering, including trace and resent fields. The inventory preserves parsed top-level order, name case, each parsed raw value, a global index and a per-name ordinal. It does not group away repeated fields or sort their values. Body text and nested-message headers are excluded from this top-level inventory.

The source is selected through the enclosing message's effective attributes and unique XMP owner, using the existing source matcher. Rendered field values cannot select a source. Every custom Mail_Message with resolvable evidence gets a source inventory, including owners without custom fields. Optional original-source absence stays unavailable and does not make an isolated profile invalid.

Raw means the email parser's raw field value, including retained folds/encoding, not a byte-for-byte header reconstruction with original colon/separator spelling. The source hash identifies retained bytes. Decoder failures are recorded per occurrence. A source parser recovery defect leaves the inventory partial and prevents occurrence comparison from claiming a match. Available means parsed evidence is available, not that all RFC syntax/cardinality or malformed input recovery has been certified.

Header recovery defects are separated from MIME body defects using the installed Python 3.12 parser and the official [email.errors documentation](https://docs.python.org/3.12/library/email.errors.html). A missing MIME boundary does not hide intact top-level headers; other message defects remain visible separately without certifying body integrity. The provisional acceptance run was stopped after this boundary was identified. Its log, the before-fix regression and the first input freeze remain preserved; final acceptance uses the refrozen source.

Undecoded raw octets use explicit raw_bytes_base64 with raw=null; decoded comparison remains unavailable. This preserves the parsed bytes without introducing surrogate characters into UTF-8 reports. A final regression reproduced the encoding failure and now verifies both native HTML and web detail rendering. The passing complete run before this guard remains historical; final packaged acceptance uses the corrected source and a new input freeze.

## Observed associations

Observed custom field text and page references are collected before source availability is checked. Thus isolated files retain their actual field observations, with source comparison unavailable. B08 page/Form MCID scoping and message-page ownership diagnostics remain in use.

[RFC 2047 section 6.2](https://www.rfc-editor.org/rfc/rfc2047.html#section-6.2) supports decoded display text. B08's bounded whitespace/date/mailbox normalization is reused. One supported equal occurrence can identify a distinct repeated value after the owner/source is fixed. Multiple equivalent candidates, zero matching repeated values, or any candidate whose decoding/formatting cannot be assessed remain unavailable. No ordinal is guessed from source order or field traversal order. A combined rendering of repeated values is not automatically classified as a modified occurrence.

Candidate associations and supported matches remain separate. They do not establish that each source occurrence was rendered exactly once, that rendering order preserves semantics, or that every rendered header has custom tags. An occurrence with no observed association is not labelled missing, unrendered or an omitted tag. Multiple views may legitimately display the same source value. Rule 107 stays partial WARN. B04 UserProperties/raw-value fidelity checks retain their earlier behavior.

## Fixtures and failed assumptions retained

The six-case corpus uses two messages with duplicate Message-ID but different dates. Their authored source order/raw values are checked by a separate simple CRLF splitter, independently of validator findings. Extra mixed-case X-Project fields are retained but not displayed by this creator. Distinct repeated Subjects map to occurrence 1; identical Subjects retain both candidates; class attributes preserve ownership; swapped content has no supported occurrence match and has pages outside its owner's ranges. Isolated files retain ten observed names/values without retained sources.

Duplicate Subject is malformed under RFC 5322's singleton constraint. These probes are not legal repeated-Subject examples. The existing source/XMP checker compares joined source values with the creator's first Subject and reports exactly MESSAGE_SUBJECT_XMP. The accepted B08 validator independently reproduces that failure in batch-09-baseline-subject-probe.json. This batch neither waives that result nor establishes a new normative rule for malformed aggregate representations. Four corpus cases preserve this exact failure; two pass mandatory EA-PDF. All six must pass independent PDF/A.

The initial corpus accidentally gave both sources equal dates and sizes as well as duplicate IDs. Repeated Subjects then removed the source matcher's discriminator, correctly leaving source selection unresolved. The report, PDF and log are retained under batch-09-initial-ambiguous and batch-09-corpus-initial-ambiguous.log. Distinct authored dates corrected the fixture oracle without weakening source matching or using field values to select a source.

Initial missing-feature errors, a separate parser-recovery failure, focused assertion corrections and the prior all-repeats-unavailable expectation are preserved. The old repeated-header guard now uses indistinguishable values; dedicated tests assert the newly supported distinct-value behavior. An initial test-module import also included helper tests in discovery; the corrected focused baseline isolates the new tests and retains the first log.

## Verification boundaries

Focused tests cover order/case/folds/body boundaries, identical and normalized candidates, undecodable candidates, empty associations, absent sources and parser recovery. The full packaged suite uses frozen hashes and archives exact corpus/profile reports. The unchanged B08 creator JAR and saved examples match their accepted hashes; its 138 Java passes are historical. No fresh Java build, creator/CLI change or saved-sample regeneration is claimed.

Complete rendered-header enumeration across standard/untagged content, arbitrary repeated-field aggregation and occurrence semantics, wider source/asset ownership, remaining N/A predicates and complete standards/ISO/TS 32005 inclusion-rule review remain open.
