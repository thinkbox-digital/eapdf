# Creator header repair diagnosis

Current status: the user authorized creator work on 2026-10-22. The proposed header repair is applied and accepted together with archive FrontMatter ownership in [C01](../creator-01/README.md). All twelve freshly generated normal/accessibility profiles pass the unchanged V85 validator, PDF/A and ISO checks. The paragraphs below retain the earlier diagnosis and experimental-build boundary.

IntelliJ logpoints observed TD becoming Mail_FieldName at MCID 0 and Mail_FieldValue at MCID 1, through PdfMailWriter.addLogicalStructure and buildSingleMessagePdf. The independent retained PDF oracle establishes that actual marked-content operands remain Span. See tracepoints.json; no breakpoint evaluation errors occurred. A direct conversion probe was needed because the CLI supervisor runs conversion in a child JVM. A completion breakpoint preserved event evidence. The temporary remote configuration and both agent breakpoints were removed; the two disabled user exception breakpoints were preserved.

proposed-repair.patch and proposed-HeaderOperandTests.java retain an exploratory repair: stream-local ParentTree ownership selects header operands, named property dictionaries and Form scopes are supported, existing stream object identities remain. The isolated Maven package run passed 164 Java tests (161 existing and three proposed tests), without writing target. That build is experimental; the patch has not passed the real PDF/A/ISO/profile acceptance gate and is not applied to source.

The noncollection archive FrontMatter ancestry failure was also repaired in C01. The earlier validator-first prerequisite was superseded by the user's instruction; remaining validator review gaps stay explicit.
