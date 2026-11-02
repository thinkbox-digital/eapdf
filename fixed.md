# Fixed bugs, improvements, and functionality gaps

**C134 — 2026-10-23:** Native Outlook picture bodies are no longer wrongly discarded as unavailable when HTML uses the picture's stored Content-Location. Existing text fallback remains for unrelated pictures. Both apps updated; 12 focused checks pass, exact source/image/attachment bytes and public CLI output verified. [Evidence](docs/improvement/creator-poc-day5/c134/result.json).

**C132–C133 — 2026-10-23:** Archive footers now share their image/font resources: the real 1,433-email file is 42.67% smaller, with exact originals and identical page appearances except the expected finding-aid time. A supplied MSG picture referenced by filename now renders from its exact stored payload; original MSG and both attachments remain exact. Both local apps updated. Full C132 Java suite: 262 passes; focused C133: 12. [Archive evidence](docs/improvement/creator-poc-day5/c132/result.json), [Outlook evidence](docs/improvement/creator-poc-day5/c133/result.json).

**C09 accepted — 2026-10-22:** Originals with direct valid UTF-8 top-level headers retain exact bytes under `message/global`; ASCII encoded words and body-only UTF-8 keep their prior label. **204 Java tests and eighteen ordinary/international profiles pass** unchanged V85 EA-PDF, PDF/A and verified ISO checks. Nested-only/reconstructed source classification remains open. [Evidence](docs/improvement/creator-09/README.md). C10 repairs escaped CID image lookup and literal Content-ID export; continue creator work.

**C08 accepted — 2026-10-22:** Malformed MIME text recovery emits a diagnostic while preserving decoded recovery, valid charset behavior and original bytes. **199 Java tests and eighteen ordinary/decoding profiles pass** unchanged V85 EA-PDF, PDF/A and verified ISO checks. [Evidence](docs/improvement/creator-08/README.md). C09 is reviewing media labels for originals with direct UTF-8 headers; continuous creator work remains active and validator development stays stopped.

**C07 accepted — 2026-10-22:** Flowed plain-text display interprets soft breaks, DelSp, quotes and stuffing while retaining exact original bytes and MIME parameters. **194 Java tests and eighteen ordinary/flowed profile checks pass** unchanged V85 EA-PDF, PDF/A and verified ISO checks. Receiver reflow is optional under RFC 3676. [Evidence](docs/improvement/creator-07/README.md). Continue creator work with malformed-text decoding diagnostics; validator development stays stopped at V85.

**C06 accepted — 2026-10-22:** Fixed MSG raw-address reconstruction that lost supplied transport text and recipient labels that hid known mailbox addresses. 185 Java tests, twelve ordinary profiles and six native MSG raw/rendered/source inspections pass their documented gates. [Evidence](docs/improvement/creator-06/README.md).

**C05 accepted — 2026-10-22:** MSG creation no longer always paints the converted RTF alternative. Native body declarations and a bounded read of the synchronization flag select the actual body and its subtype. 181 Java tests, twelve ordinary profiles and six native MSG body inspections pass their checks. Unsupported-source availability remains explicit. [Evidence](docs/improvement/creator-05/README.md).

**C04 accepted — 2026-10-22:** Fixed dropped unnamed message/global attachments, incorrect retention of encoded rather than native message payloads, and false unsupported-body text in parents. 176 Java tests and eighteen profile checks pass with native byte and media-type evidence. [Evidence](docs/improvement/creator-04/README.md).

**C03 accepted — 2026-10-22:** Valid direct UTF-8 email headers no longer become garbled rendered text or XMP. EML/MBOX, Gmail naming and PST transport parsing share the bounded decoder; invalid legacy headers retain their previous byte mapping. 172 Java tests, twelve ordinary and six international profile checks pass. [Evidence](docs/improvement/creator-03/README.md).

**C02 accepted — 2026-10-22:** Fixed PST display fallbacks that copied MAPI storage NULs into exported To/Cc/Bcc headers. The actual PST loses 59 fictitious Cc and 71 fictitious Bcc headers while its other compared headers and decoded bodies/attachments are preserved. 167 Java tests, all twelve normal/accessibility profiles, ten collection children and four collection PDF/XLSX sidecar pairs pass. Both affected saved PST PDFs are refreshed; their remaining INCOMPLETE limits are documented. [Evidence](docs/improvement/creator-02/README.md).

**C01 samples refreshed — 2026-10-22:** Recovered and completed the interrupted sample-regeneration request after the header operand and archive ownership repairs. All six saved PDFs, collection PDF/XLSX sidecars and the existing 1s alias are refreshed. Fresh rebuild: 165 Java tests pass. All six pass PDF/A and the verified ISO profile with zero mandatory failures; four profiles and both collection children PASS EA-PDF. The two PST archive INCOMPLETE results remain documented in [sample evidence](docs/improvement/creator-samples-c01/README.md). Validator development remains stopped at V85.

**V84 accepted — 2026-10-22:** Subject comparisons use reliable original decoding or exact literal preservation. Unknown source normalization remains unavailable; supplied malformed words cannot borrow decoder repair into known text. **1104 unit passes / 40 skips; 766 freshly checked custom cases; ten Subject and four positive collection PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 756 prior hashes/statuses/failure sets remain. Two silent passes and two unsupported failures become INCOMPLETE; one missed mismatch fails. Creator repair remains deferred. [Evidence](docs/improvement/validator-84/evidence.json).

**V83 accepted — 2026-10-22:** Child staging/execution exceptions produce unavailable comparisons and INCOMPLETE; returned child findings survive cleanup errors, and actual invalid children retain FAIL. **1096 unit passes / 40 skips; 756 freshly checked custom cases; four separate operational custom controls; fourteen collection PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All prior custom hashes/statuses/failure sets remain. Four known-valid PDFs no longer receive operationally invented child failures. Creator repair remains deferred. [Evidence](docs/improvement/validator-83/evidence.json).

**V82 accepted — 2026-10-22:** Downstream source-fidelity recommendations, ordered header inventories and source-derived attachment aliases reuse accepted email ownership, including unassigned originals. **1086 unit passes / 40 skips; 756 freshly checked custom cases; six source-binding and four positive collection PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 750 prior hashes/statuses/failure sets remain. False source attributions become unavailable without changing the six new overall statuses. Creator repair remains deferred. [Evidence](docs/improvement/validator-82/evidence.json).

**V81 accepted — 2026-10-22:** Known nested mail candidates and mailbox envelopes prevent universal source contradictions based only on outer headers. Body-media checks reuse the accepted original binding. **1077 unit passes / 40 skips; 750 freshly checked custom cases; eight nested-source and four positive collection PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 742 prior hashes/statuses/failure sets remain. Six unsupported failures become INCOMPLETE. Creator repair remains deferred. [Evidence](docs/improvement/validator-81/evidence.json).

**V80 accepted — 2026-10-22:** Viewer attachment conditions and final applicability use the represented email’s matched original. Unrelated retained originals do not activate the attachment branch; unknown counts stay unavailable, while modes forbidden by every possible count still fail. **1067 unit passes / 40 skips; 742 freshly checked custom cases; thirteen viewer-scope and four positive collection PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 729 prior hashes/statuses/failure sets remain. Three false failures pass; one unsupported failure becomes INCOMPLETE. Creator repair remains deferred. [Evidence](docs/improvement/validator-80/evidence.json).

**V79 accepted — 2026-10-22:** Source-derived coverage and final date applicability follow the matched email original, including a second catalog Source. Unassigned ownership remains unavailable; optional raw-address omission and supplied empty text stay distinct. **1056 unit passes / 40 skips; 729 freshly checked custom cases; fourteen source-scope and four positive collection PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 715 prior hashes/statuses/failure sets remain. Five unsupported failures become INCOMPLETE; one false failure and three unperformed comparisons pass. Creator repair remains deferred. [Evidence](docs/improvement/validator-79/evidence.json).

**V78 accepted — 2026-10-22:** Non-SMTP originals produce unavailable SMTP presence/normalization assessments, including coverage fallback. Exact literal raw preservation and understood SMTP subsets pass; known SMTP absence, changed supplied raw text and malformed FOAF still fail independently. **1046 unit passes / 40 skips; 715 freshly checked custom cases; eight non-SMTP and four positive collection PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 707 prior hashes/statuses/failure sets remain. Two unsupported failures become INCOMPLETE. Creator repair remains deferred. [Evidence](docs/improvement/validator-78/evidence.json).

**V77 accepted — 2026-10-21:** Ambiguous source ownership no longer hides universally contradicted Core Fields. Every original is considered; unsupported/recovered candidates retain unavailable proofs. Incompatible field combinations receive a Source Match failure without guessing which field owner was wrong. **1033 unit passes / 40 skips; 707 freshly checked custom cases; twelve PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 695 prior hashes/statuses/failure sets remain. Four missed contradictions now fail; two unsupported owner failures become INCOMPLETE. Creator repair remains deferred. [Evidence](docs/improvement/validator-77/evidence.json).

**V76 accepted — 2026-10-21:** Independently constructed c/ci collections pass complete parent and child validation in normal and accessibility fixture modes. Unavailable, invalid and corrupt child results propagate correctly; CLI preserves INCOMPLETE and worker returns PASS for the positive control. **Ten fresh custom controls and ten fresh PDF/A/ISO collection pipelines; 685 prior custom observations, 1020 unit passes / 40 skips, 16 browser checks and twelve known creator failures are inherited by unchanged input hashes.** Parent pages/metadata and corrected-child source assets/non-tag operands are preserved. Creator code remains deferred. [Evidence](docs/improvement/validator-76/evidence.json).

**V75 accepted — 2026-10-21:** Source date parser limits no longer prove Raw-Sent violations. RFC negative-zero UTC and supported wide offsets preserve instant semantics; extended-year/UTC-overflow assessments and unperformed instant comparisons remain INCOMPLETE. Known wrong instants, missing Sent and invalid calendars still fail. **1020 unit passes / 40 skips; 685 freshly checked custom cases; ten PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 675 prior hashes/statuses/failure sets remain. One false failure passes; one unsupported failure and three silent passes become INCOMPLETE. Creator repair remains deferred. [Evidence](docs/improvement/validator-75/evidence.json).

**V74 accepted — 2026-10-21:** Attached unsupported originals leave source-header comparisons INCOMPLETE. Isolated Source presence alone produces a provenance warning rather than an unsupported profile failure; isolated omission remains permitted. A matched EML remains verifiable in the presence of an extra unrendered original. **1008 unit passes / 40 skips; 675 freshly checked custom cases; eight PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 667 prior hashes/statuses/failure sets remain. One silent pass and two unsupported isolated-source failures become INCOMPLETE. Creator repair remains deferred. [Evidence](docs/improvement/validator-74/evidence.json).

**V73 accepted — 2026-10-21:** Undecodable original mailbox and header values produce unavailable comparisons instead of invented text mismatches. Report copies escape unknown octets without changing retained bytes or internal evidence. Independent supplied-schema, known-presence and readable-field failures remain. **998 unit passes / 40 skips; 667 freshly checked custom cases; ten PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 657 prior hashes/statuses/failure sets remain. Seven unsupported FAIL results become INCOMPLETE. Creator repair remains deferred. [Evidence](docs/improvement/validator-73/evidence.json).

**V72 accepted — 2026-10-21:** Valid direct UTF-8 source headers are restored losslessly before parsing and comparison. `message/global` sources and nested messages are recognized by media type, including non-`.eml` filenames. **985 unit passes / 40 skips; 657 freshly checked custom cases; ten PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 647 prior hashes/statuses/failure sets remain. Seven false failures now pass and one missed mismatch fails. Creator repair remains deferred. [Evidence](docs/improvement/validator-72/evidence.json).

**V71 accepted — 2026-10-21:** Original Date presence is independent of text truthiness. Explicit blank/whitespace Date values require Raw-Sent, and present empty normal text preserves the blank; an absent Date does not invent an error value. **972 unit passes / 40 skips; 647 freshly checked custom cases; eight PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 639 prior hashes/statuses/failure sets remain. Two new false passes fail; empty element/attribute/qualified alternatives pass. Creator repair remains deferred. [Evidence](docs/improvement/validator-71/evidence.json).

**V70 accepted — 2026-10-21:** Supplied empty raw text is distinct from omission and must reflect a known source value. Matching empty originals remain valid; unreadable text remains unavailable. Raw-Sent presence counts empty text, and ordinary optional Raw-To/From/Cc/Bcc absence is N/A without unsupported warnings. **966 unit passes / 40 skips; 639 freshly checked custom cases; ten PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 629 prior hashes/statuses/failure sets remain; three warning rows become N/A. Seven new false passes fail. Creator repair remains deferred. [Evidence](docs/improvement/validator-70/evidence.json).

**V69 accepted — 2026-10-21:** Supplied Raw-To/From/Cc/Bcc values cannot hide changed quoted mailbox data behind whitespace normalization. Known ASCII addresses are compared with original header occurrences; optional absence and the existing outside-whitespace policy remain. **956 unit passes / 40 skips; 629 freshly checked custom cases; eight PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 621 prior hashes/statuses/failure sets remain. Four new false passes fail; matching text forms and the separate FOAF failure retain their results. Creator repair remains deferred. [Evidence](docs/improvement/validator-69/evidence.json).

**V68 accepted — 2026-10-21:** Source address structure is parsed before display-name decoding, preserving quoted mailbox spaces and literal address tokens. A narrow SMTP atom fallback handles a header-parser false rejection; malformed mailbox parser errors become schema failures. **949 unit passes / 40 skips; 621 freshly checked custom cases; ten PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 611 prior hashes/statuses/failure sets remain. Six new false comparison failures pass, two false matches fail, and two parser aborts become specific mailbox failures. Creator repair remains deferred. [Evidence](docs/improvement/validator-68/evidence.json).

**V67 reference review — 2026-10-21:** The exact June 2009 Adobe supplement cited for PDF 1.7 collections is identified and reviewed. The authoritative dependency record and generated views are updated; all validation scopes, requirement entries and runtime logic are unchanged. Sixteen fresh browser checks pass; V66 remains the latest full runtime acceptance. The [current exit review](docs/improvement/coverage-exit-review/current-status.md) separates historical counts from remaining obligation work. [Evidence](docs/improvement/validator-67/evidence.json).

**V66 accepted — 2026-10-21:** Unreadable RF companion filenames retain unavailable comparison while their streams remain available for independent asset/byte checks. Known malformed pairs and provably missing inventory members still fail. **941 unit passes / 40 skips; 611 freshly checked custom cases; eight PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 603 prior hashes/statuses/failure sets remain. Two decoder aborts become INCOMPLETE; two preserve their actual independent failures. Twelve retained creator outputs keep their known failures. [Evidence](docs/improvement/validator-66/evidence.json).

**V65 coverage accepted — 2026-10-21:** Independent raw Cc/Bcc, invalid-date Raw-Sent, related-file, URL and PDF 1.7 collection-extension controls exercise all six checks missing from the V64 observation cohort. **934 unit passes / 40 skips; 12 new custom controls and real PDF/A checks match; ten applicable ISO checks pass, two PDF 1.7 checks are N/A.** The 591 prior receipts are inherited with identical runtime/rule/PDF hashes (603 total), not claimed as fresh executions. All 202 local assertion codes now have non-N/A observations; this is not obligation coverage certification. CLI/worker agree and twelve retained creator outputs retain their known failures. Creator repair remains deferred. [Evidence](docs/improvement/validator-65/evidence.json).

**V64 accepted — 2026-10-21:** Explicitly empty supplied attachment GUIDs are checked against the email inventory even when Message-ID is present. Omission remains distinct; invalid parent identities cannot establish absence. **933 unit passes / 40 skips; 591 custom cases, eight PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 583 prior hashes/statuses/failure sets remain. Four new false passes fail; an existing annotation failure gains its independent identity failure. Twelve retained creator outputs keep their known failures. [Evidence](docs/improvement/validator-64/evidence.json).

**V63 accepted — 2026-10-21:** FOAF mailto URIs decode percent escapes once, preserve literal plus/source percent data, and compare quoted mailbox local parts with exact case while ignoring domain case. Invalid URI escapes/UTF-8/control characters fail; unverified differing IDNA normalization stays unavailable without hiding definite mismatches. **930 unit passes / 40 skips; 583 custom cases, nine PDF/A/ISO controls and CLI/worker checks match.** All 574 prior hashes/statuses/failure sets remain. Four new false comparison failures pass and one false local-case pass fails. Twelve retained creator outputs keep their known failures. [Evidence](docs/improvement/validator-63/evidence.json).

**V62 accepted — 2026-10-21:** Supplied attachment GUIDs identify an email in a complete typed XMP GUID inventory, independently of body rendering. Unknown owner metadata no longer manufactures a missing-annotation failure; proved annotation geometry failures remain. **921 unit passes / 40 skips; 574 custom cases, eight PDF/A/ISO controls and CLI/worker checks match.** All 566 prior hashes/statuses/failure sets remain. One new header-only false pass fails and four new annotation failures receive the correct identity failure. Twelve retained creator outputs keep their known failures. The eleven local decisions and their external limits remain as recorded in V61. [Evidence](docs/improvement/validator-62/evidence.json).

**V61 accepted — 2026-10-21:** All eleven questions have explicit user-authorized project interpretations, with cited PDF/A draft-edition equivalence still unverified. Leaf DPM identities, conflicting Message-ID aliases, unordered reply Bags and attachment Contents are checked; collection ancestry follows the documented local exception. **915 unit passes / 40 skips; 566 custom cases, 18 PDF/A/ISO controls, CLI/worker and 16 offline browser checks match.** All 548 prior hashes/statuses/failure sets remain. Ten new false passes fail; one becomes INCOMPLETE and one operational exception becomes INCOMPLETE. Twelve retained creator outputs keep their known failures. Full obligation coverage and creator repairs remain open. [Evidence](docs/improvement/validator-61/evidence.json).

**V60 accepted — 2026-10-21:** Typed unique parent MessageID binding verifies attachment annotations while retaining required GUID failures. **905 unit passes / 40 skips; 548 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 540 prior hashes/statuses remain; 537 prior failure sets are unchanged and three unsupported annotation codes are removed. Four analogous new controls are corrected. URI/missing/duplicate identities do not activate a new match; GUID precedence, unreadable identity and actual geometry failures remain distinct. Twelve fresh creator runs retain known FAIL outcomes. Eleven questions and broader review remain open. [Evidence](docs/improvement/validator-60/evidence.json).

**V59 accepted — 2026-10-21:** DPart identity selection uses canonical Table7 keys and retains unrelated extension data. **896 unit passes / 40 skips; 540 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 532 prior hashes/statuses/failure sets remain. Five extension-induced false annotation failures now PASS with verified source/body matching. Shared XMP/source aliases, decoder guards and canonical contradictions retain their behavior. Twelve fresh creator runs retain known FAIL outcomes. Eleven questions and broader review remain open. [Evidence](docs/improvement/validator-59/evidence.json).

**V58 accepted — 2026-10-21:** Required DPM GUID decoding preserves unavailable identity across mandatory checks, source selection, actual owners and global inventory. **886 unit passes / 40 skips; 532 custom cases, ten exact-byte PDF/A/ISO controls and two entry points match.** All 522 prior hashes/statuses/failure sets remain. Four operational failures become two INCOMPLETE results, one retained revision FAIL and one definite other-owner FAIL. Unknown body owners leave annotation applicability unavailable while known missing annotations still fail. Twelve fresh creator runs retain known FAIL outcomes. Eleven questions and broader review remain open. [Evidence](docs/improvement/validator-58/evidence.json).

**V57 accepted — 2026-10-21:** Optional DPM MessageID decoding preserves unavailable comparison and readable GUID precedence in source selection. **868 unit passes / 40 skips; 522 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 514 prior hashes/statuses/failure sets remain. Three operational failures become two INCOMPLETE results and one retained revision FAIL. Optional absence, readable differences, wrong classes and later known mismatches remain distinct. The first pipeline failure exposed a second consumer; the final runtime passed fresh complete gates. Twelve fresh creator runs retain known FAIL outcomes. Eleven questions and broader review remain open. [Evidence](docs/improvement/validator-57/evidence.json).

**V56 accepted — 2026-10-21:** Unreadable DPM descriptions retain explicit unavailable assessment and continue validation. **853 unit passes / 40 skips; 514 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 506 prior hashes/statuses/failure sets remain. Three decoder exceptions become two INCOMPLETE results and one independently retained revision FAIL. Valid UTF8/UTF16 text, empty descriptions and wrong object classes keep their distinctions. Twelve fresh creator runs retain known FAIL outcomes. Eleven questions and broader review remain open. [Evidence](docs/improvement/validator-56/evidence.json).

**V55 accepted — 2026-10-21:** Art checks use actual Content Set counterparts, including implicit Art through Mail_ContentSet. **843 unit passes / 40 skips; 506 custom cases, six exact-byte PDF/A/ISO controls and two entry points match.** All 500 prior PDF hashes and statuses remain; four unsupported Art failure codes are removed while independent coverage/structure failures remain. Seven historical Art levels are reviewed. A new cross-set Article fails; an extra-Article count failure becomes INCOMPLETE for the separate role-map scope boundary. Twelve fresh creator runs retain known FAIL outcomes. Eleven questions and broader review remain open. [Evidence](docs/improvement/validator-55/evidence.json).

**V54 accepted — 2026-10-21:** Actual content references replace tag-count equality for conditional Content Set coverage. **822 unit passes / 40 skips; 500 custom cases, six exact-byte PDF/A/ISO controls and two entry points match.** All 494 prior hashes/statuses/failure sets remain. One balanced-count case gains its missing-coverage failure; two empty-tag coverage passes become explicitly unavailable while separate structure failures remain. Twelve fresh creator runs retain known FAIL outcomes. Art-only semantics, eleven questions and broader review remain open. [Evidence](docs/improvement/validator-54/evidence.json).

**V53 accepted — 2026-10-21:** Actual Content Set ancestry replaces the unsupported scalar FrontMatter exemption; the collection-parent conflict stays explicit. **805 unit passes / 40 skips; 494 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 486 historical PDF hashes remain. 119 proved noncollection parent failures correct 32 prior PASS and eight INCOMPLETE results; 79 already failed files gain the violation. Thirty collection predicates become unavailable while their existing custom statuses/failure sets remain; other 337 prior outcomes are unchanged. Twelve fresh creator runs retain FAIL, with four m/mi outputs gaining parent failures and four c/ci parents remaining unresolved. Eleven listed questions and broader review remain open. [Evidence](docs/improvement/validator-53/evidence.json).

**V52 accepted — 2026-10-21:** Unreadable source header values retain unavailable SHOULD comparisons instead of aborting validation. **793 unit passes / 40 skips; 486 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 478 prior hashes/statuses/failure sets remain. Four operational failures become PASS with explicit recommendation WARN/unavailable; no mandatory incompleteness is added. Valid encoded comparisons, readable differences and separate date/type checks retain their outcomes. Twelve fresh creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](docs/improvement/validator-52/evidence.json).

**V51 accepted — 2026-10-21:** Guarded group-type and subtype correspondence preserves unavailable evidence and readable contradictions. **781 unit passes / 40 skips; 478 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 470 prior hashes/statuses/failure sets remain. Two operational failures become INCOMPLETE; two retain FAIL with definite mismatches. Presence recommendations, exact group names, MIME signatures and attribute-array replacement retain their behavior. Twelve fresh creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](docs/improvement/validator-51/evidence.json).

**V50 accepted — 2026-10-21:** Guarded required GUID presence and every active matching occurrence retain explicit unavailable evidence and definite contradictions. **771 unit passes / 40 skips; 470 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 462 prior hashes/statuses/failure sets remain. Three operational failures become INCOMPLETE; one retains FAIL with its readable mismatch. Valid encoded GUIDs, empty values, object types and inactive-array replacement retain expected outcomes. Twelve fresh creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](docs/improvement/validator-50/evidence.json).

**V49 accepted — 2026-10-21:** Unreadable PDF-date text fails the existing date predicate instead of aborting validation. **759 unit passes / 40 skips; 462 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 454 prior hashes/statuses/failure sets remain. Two operational failures become USERPROPS_TYPE findings with FAIL status retained. Valid UTF8/UTF16 dates and source recommendation strength remain unchanged. Twelve fresh creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](docs/improvement/validator-49/evidence.json).

**V48 accepted — 2026-10-21:** Unrelated generic UserProperties retain ISO V(any); EA field types and Mail_* header constraints keep their scope. **747 unit passes / 40 skips; 454 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 446 prior hashes/statuses/failure sets remain. Four false generic-property type failures now pass; required V, raw N ASCII and EA tag scope remain enforced. Eight controls represent seven distinct hashes because null canonicalizes to missing; direct parsed-null tests retain separate evidence. Twelve fresh creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](docs/improvement/validator-48/evidence.json).

**V47 accepted — 2026-10-21:** Guarded wrong-value diagnostics and actual-string owner identity handling preserve prescribed type failures. **730 unit passes / 40 skips; 446 custom cases, eleven exact-byte PDF/A/ISO controls and two entry points match.** All 435 prior hashes/statuses/failure sets remain. Seven operational exceptions become definite USERPROPS_TYPE findings while their FAIL statuses remain. Existing generic Mail_* rejections and Unicode strings are preserved. Twelve fresh creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](docs/improvement/validator-47/evidence.json).

**V46 accepted — 2026-10-21:** Guarded Text-string property-name decoding preserves raw ASCII validation and rejects object coercion. **716 unit passes / 40 skips; 435 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 427 prior hashes/statuses/failure sets remain. Four malformed-name cases retain FAIL status and now report prescribed data violations instead of operational exceptions. Known encoded-name presence, legal empty ASCII extensions and Unicode V values remain distinct. Twelve fresh creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](docs/improvement/validator-46/evidence.json).

**V45 accepted — 2026-10-21:** The subtype SHOULD recommendation applies to every Mail_ContentSet, independently of optional self type metadata. **704 unit passes / 40 skips; 427 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 419 prior hashes/statuses/failure sets remain. 119 prior recommendation passes and four new controls now WARN without changing conformance strength. Supplied values and unresolved attributes retain separate assessments. Twelve fresh creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](docs/improvement/validator-45/evidence.json).

**V44 accepted — 2026-10-21:** DPM entry presence, empty Name validity and media syntax are assessed separately; unreadable UTF8 remains unavailable for applicability. **694 unit passes / 40 skips; 419 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 411 prior hashes/statuses remain; 410 prior failure sets are unchanged and one proved false presence code is removed. One false pass fails, one false failure passes, and an empty required subtype receives the correct syntax failure. Twelve fresh final creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](docs/improvement/validator-44/evidence.json).

**V43 accepted — 2026-10-21:** Connected subtype comparisons use reviewed MIME semantics without changing quoted parameter data. **681 unit passes / 40 skips; 411 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 403 prior hashes/outcomes remain. One false pass now fails; five false comparison failures pass. Equivalent shared candidates match without choosing an owner; distinct compatible candidates remain unverified. Twelve fresh creator runs preserve known failures. Eleven listed questions and broader review remain open. [Evidence](docs/improvement/validator-43/evidence.json).

**V42 accepted — 2026-10-21:** Every applicable active UserProperties occurrence is checked; actual name owners and connected subtype data determine matching. **667 unit passes / 40 skips; 403 custom cases, sixteen exact-byte PDF/A/ISO controls and two entry points match.** All 387 prior hashes/outcomes remain. Six false passes now fail; one unavailable required-owner case fails; two unsupported comparison failures become INCOMPLETE. Legal duplicates and whole-array replacement remain valid. Twelve fresh creator runs preserve known failures. The audit generator now accepts ordering-only mapping differences. Eleven listed questions and broader review remain open. [Evidence](docs/improvement/validator-42/evidence.json).

**V41 accepted — 2026-10-20:** Supplied group types match independently resolved connected DPart data, with exact characters and explicit unavailable XMP binding. **643 unit passes / 40 skips; 387 custom cases, ten exact-byte PDF/A/ISO controls and two entry points match.** All 377 prior hashes/outcomes remain. Three false passes now fail; two unsupported passes become INCOMPLETE; two false comparison failures are corrected. Optional absence remains a recommendation. Twelve fresh creator runs preserve known failures. Eleven listed questions and broader review remain open. [Evidence](docs/improvement/validator-41/evidence.json).

**V40 accepted — 2026-10-20:** UserProperties N names retain exact characters and raw PDF ASCII-string bytes; Unicode V text remains permitted. **626 unit passes / 40 skips; 377 custom cases, ten exact-byte PDF/A/ISO controls and two entry points match.** All 367 prior hashes/outcomes remain. Five false passes now fail. Twelve current creator checks and independent raw-name guards, including embedded children, preserve known failures and permit exact fresh V39 upstream reuse. Eleven listed questions and broader review remain open. [Evidence](docs/improvement/validator-40/evidence.json).

**V39 accepted — 2026-10-20:** Mail_Message GUIDs are compared with independently resolved content/DPart owners; ambiguous or absent connections remain unverified. **618 unit passes / 40 skips; 367 custom cases, nine exact-byte PDF/A/ISO controls and two entry points match.** All 358 prior hashes/outcomes remain. Three false global-membership passes now fail; one already rejected DPM swap gains its proved owner mismatch. Twelve fresh creator runs keep their known failures. The combined-clause owner interpretation is explicit; eleven listed questions and broader review remain open. [Evidence](docs/improvement/validator-39/evidence.json).

**V38 accepted — 2026-10-20:** DPartRoot/nodes require dictionary objects; leaf ranges uniquely cover pages in depth-first page order. **601 unit passes / 40 skips; 358 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 350 prior hashes remain; 345 prior outcomes are unchanged and five gain independently proved overlap failures. Four further false passes now fail. Five retained negative controls pass PDF/A with ISO correctly N/A. Twelve current creator checks retain known failures and reuse exact fresh V37 upstream reports. Eleven listed questions and wider coverage remain open. [Evidence](docs/improvement/validator-38/evidence.json).

**V37 accepted — 2026-10-20:** RecordLevel bounds, prohibited DPart Metadata, recursive DPM value classes/key spelling and all name-value UTF-8 are checked against published corrections. **593 unit passes / 40 skips; 350 custom cases and sixteen exact-byte PDF/A/ISO controls match, with one CLI and one worker check.** All 334 prior hashes/outcomes remain. Ten false passes now fail; two unresolved key cases become INCOMPLETE. Twelve fresh creator runs retain their known conformance failures. Eleven listed questions and broader review remain open. [Evidence](docs/improvement/validator-37/evidence.json).

**V36 accepted — 2026-10-20:** Supplied optional DPart entries now retain their prescribed types and hierarchy counts; unresolved XML-name evidence stays INCOMPLETE. **581 unit passes / 40 skips; 334 custom cases and fourteen exact-byte PDF/A/ISO controls match, with one CLI and one worker check.** All 320 prior hashes/outcomes remain. Eight missed violations now fail; two unsupported complete passes become INCOMPLETE. Twelve fresh retained creator runs keep their known operand/child failures. Eleven listed questions and broader review remain open. [Evidence](docs/improvement/validator-36/evidence.json).

**V35 accepted — 2026-10-20:** All eight Info/XMP entries retain actual PDF string types before comparison; optional nulls are absent. **571 unit passes / 40 skips; 320 custom cases and twelve exact-byte PDF/A-3U controls match, with one CLI and one worker check.** All 308 prior hashes/outcomes remain. Eight raw-type false pipeline passes now fail; normal numeric/name-like/empty strings remain valid. Date/text comparison policy is unchanged. Eleven decisions and broader coverage remain open. [Evidence](docs/improvement/validator-35/evidence.json).

**V34 accepted — 2026-10-20:** PDF/A-3 Info Title/Subject use x-default, Keywords synchronize with XMP, and established absent optional fields receive N/A. **563 unit passes / 40 skips; 308 custom cases and twelve exact-byte PDF/A-3A/3U controls match, with one CLI and one worker check.** All 296 prior hashes/outcomes remain. Three false passes, three false comparison failures and two false INCOMPLETE results are corrected. ISO32005 is correctly inapplicable to these PDF1.7 inputs. Eleven decisions and broader coverage remain open. [Evidence](docs/improvement/validator-34/evidence.json).

**V33 accepted — 2026-10-20:** Required creator/producer/creation-date metadata now retains standard XMP types and valid Date values. **555 unit passes / 40 skips; 296 custom cases and twelve exact-byte PDF/A/ISO controls match, with one CLI and one worker check.** All 284 prior hashes/outcomes are unchanged; seven false pipeline passes now fail. Qualified/attribute forms, reduced precision and unknown timezones remain valid. Twelve creator diagnoses are reused after native checks; eleven decisions and broader coverage remain open. [Evidence](docs/improvement/validator-33/evidence.json).

**V32 accepted — 2026-10-20:** The title recommendation reads the explicit x-default Text item, including permitted empty Text. **545 unit passes / 40 skips; 284 custom cases and eight exact-byte PDF/A/ISO controls match, with one CLI and one worker check.** All 276 prior bytes/status/failure outcomes remain; three incorrect recommendation PASS results now WARN. Four exact V31 before-runs and twelve creator diagnoses are reused with explicit checks. Mandatory crosswalk, eleven decisions and broader coverage remain open. [Evidence](docs/improvement/validator-32/evidence.json).

**V31 accepted — 2026-10-20:** Catalog Version and required Collection Subtype entries now require PDF names. Header/effective-version restrictions follow EA-PDF and ISO precedence. **537 unit passes / 40 skips; 276 custom cases and eleven exact-byte engine probes match, with one CLI and two worker checks.** All 265 prior hashes/outcomes are unchanged; five false pipeline passes now fail. Three independent PDF/A failures remain explicit. Twelve creator diagnoses are reused after native-value checks; eleven decisions and broader coverage remain open. [Evidence](docs/improvement/validator-31/evidence.json).

**V30 accepted — 2026-10-20:** Profile navigation modes now require actual PDF names. **528 unit passes / 40 skips; 265 custom cases and six exact-byte pipeline probes match, with one CLI and one worker check.** All 259 prior hashes/outcomes are unchanged; three false PASS results now fail. Twelve exact V29 creator runs are reused after independent native-name/hash checks, with their known conformance failures retained. The observation queue is current for the bounded V30 cohort. Eleven decisions and broader coverage remain open. [Evidence](docs/improvement/validator-30/evidence.json).

**V29 accepted — 2026-10-20:** Collection checks preserve exact decoded names, inspect every name-tree alias and compare case uniqueness in the actual folder scope. **524 unit passes / 40 skips; 259 custom cases, fourteen exact-byte PDF probes and six CLI/worker checks match.** All 245 prior hashes/outcomes are unchanged. Eight missed violations now fail; three false failures now pass. Twelve retained creator gates keep their known failures. The current observation queue is refreshed; eleven decisions and broader coverage remain open. [Evidence](docs/improvement/validator-29/evidence.json).

**V28 accepted — 2026-10-20:** Catalog DisplayDocTitle and Collection field E now require exact PDF Boolean values. **511 unit passes / 40 skips; 245 custom cases, thirteen exact-byte PDF probes and six CLI/worker checks match.** All 232 prior PDF hashes/outcomes are unchanged. Seven former complete-pipeline false PASS results now FAIL; exercised PDF/A/ISO checks still PASS. All twelve retained creator gates keep the known operand/child failures. Collection filename counterexamples are saved for the next repair; eleven decisions and broader coverage remain open. [Evidence](docs/improvement/validator-28/evidence.json).

**V27 accepted — 2026-10-20:** Source associations preserve exact decoded filename whitespace and raw PDF UF/F text. **505 unit passes / 40 skips; 232 custom cases, eight PDF probes and eight CLI/worker checks match.** All 224 prior PDF hashes/outcomes are unchanged. Four permitted filename forms now pass; an invented alias stays unavailable; real contradictions still fail. All twelve retained creator conformance gates remain failed on the known operand/child defect, with exercised PDF/A/ISO PASS. Eleven decisions and broader coverage gates remain open. [Evidence](docs/improvement/validator-27/evidence.json).

**V26 accepted — 2026-10-20:** Unresolved declared source parameters now remain WARN/INCOMPLETE; verified checksum/name associations retain their comparisons. **495 unit passes / 40 skips; 224 custom cases, eleven PDF probes and eight CLI/worker checks match.** All 215 prior PDF hashes and overall status/failure outcomes are unchanged. Ten media-reporting rows improve. Twelve retained creator outputs retain their known failed conformance gates with PDF/A/ISO PASS. Exact source filename handling is the next confirmed gap; eleven decisions remain open. [Evidence](docs/improvement/validator-26/evidence.json).

**V25 accepted — 2026-10-20:** Ambiguous source associations now report size/media values contradicted by every candidate. **485 unit passes / 40 skips; 215 custom cases, eight PDF probes and eight CLI/worker checks match.** All 207 prior PDF hashes/outcomes are unchanged. All twelve retained creator outputs still pass the exercised PDF/A/ISO checks and fail the same operand/child conformance gates. Source assignment and eleven decisions remain open. [Evidence](docs/improvement/validator-25/evidence.json).

**V24 validator accepted — 2026-10-20:** Custom marked-content operands now match their actual page/Form structure owners. **473 unit passes / 40 skips; 207 custom comparisons; six PDF probes and six CLI/worker checks match.** All nine previously unexercised checks now have focused evidence. All 202 historical PDFs remain unchanged, but independent inspection proves operand mismatches in 194 and affected children in eight. **All twelve original creator conformance gates fail on this newly detected defect** while the exercised PDF/A and ISO checks pass. Creator unchanged; repair is pending. [Evidence](docs/improvement/validator-24/evidence.json), [review](docs/improvement/validator-24/README.md).

**Coverage exit review — 2026-10-20:** Recovered thread `01a159dd-2541-73c3-92d8-a63e0f535f7a` and completed its whole-validator checklist. All **224 checks / 301 obligations** now have evidence views and six explicit exit gates. The V23 census confirms **447 passes / 40 skips and 202 custom fixtures**; nine custom checks lack non-N/A production observations, and all 29 current full-pipeline reports use PDF/A-4F. Eleven questions remain open. [Exit checklist](docs/improvement/coverage-exit-review/README.md), [review queues](docs/improvement/coverage-exit-review/review-queues.md). Runtime and requirements are unchanged.

**V23 accepted — 2026-10-20:** Declared attachment parameters without a native association remain WARN/unavailable, including mixed verified/unmatched records. **447 unit passes / 40 skips; nine PDF cases, twelve generated profiles, 202 custom comparisons and eight CLI/web-worker checks match.** Ten parameter-rule results improve: nine N/A-to-WARN and one PASS-to-WARN. All 194 prior PDF hashes and status/failure outcomes are unchanged. No native-copy requirement is added; creator unchanged; eleven questions remain open. [Evidence](docs/improvement/validator-23/evidence.json).

**V22 accepted — 2026-10-20:** Ambiguous native associations no longer hide universally contradicted media types or parameters. **440 unit passes / 39 skips; twelve PDF cases, twelve generated profiles, 194 custom comparisons and eight CLI/web-worker checks match.** Six new probes and two retained cases gain proved media/parameter failures; two compatible-parameter controls now report WARN instead of N/A. All 184 prior PDF hashes and 182 other prior status/failure outcomes are unchanged. Occurrence assignment remains open; creator unchanged; eleven questions remain. [Evidence](docs/improvement/validator-22/evidence.json).

**V21 accepted — 2026-10-20:** Ambiguous native associations no longer hide a size/checksum contradicted by every possible payload. **430 unit passes / 38 skips; twelve PDF cases, twelve generated profiles, 184 custom comparisons and eight CLI/web-worker checks match.** Five new negative probes and two retained cases now fail; all 174 prior PDF hashes and 172 other prior custom outcomes are unchanged. Association remains unavailable; no owner or occurrence is selected. Creator unchanged; eleven questions remain open. [Evidence](docs/improvement/validator-21/evidence.json).

**V20 accepted — 2026-10-20:** Media comparisons preserve malformed parameter text and quoted spaces. **421 unit passes / 37 skips; ten PDF cases, twelve generated profiles, 174 custom comparisons and eight CLI/web-worker checks match.** Five altered values now fail; two preserved-source cases now pass. All 164 prior fixture bytes and custom outcomes are unchanged. Creator unchanged; eleven review questions remain open. [Evidence](docs/improvement/validator-20/evidence.json).

**V19 accepted — 2026-10-20:** Equivalent attachment metadata now permits stored-byte/media comparisons without assigning an owner. **412 unit passes / 36 skips; eight PDF cases, twelve generated profiles, 164 custom comparisons and eight CLI/web-worker checks match.** All 156 prior fixture bytes and custom outcomes are unchanged. Creator unchanged. Eleven review questions and unique occurrence assignment remain open. [Evidence](docs/improvement/validator-19/evidence.json).

**V18 accepted — 2026-10-20:** Attachment source comparisons no longer depend on XMP record order. **404 unit passes / 35 skips; six PDF cases, twelve generated profiles, 156 custom comparisons and eight CLI/web-worker checks match.** All 150 prior fixture bytes and custom outcomes are unchanged. Creator unchanged. Native occurrence assignment and eleven review questions remain open. [Evidence](docs/improvement/validator-18/evidence.json).

**V17 accepted — 2026-10-20:** Fixed Message-ID attachment ownership and missing/zero attachment counts. **396 unit passes / 34 skips; nine PDF cases, twelve generated profiles, 150 custom comparisons and sixteen CLI/web-worker checks match.** All 141 older PDF files and custom results are unchanged. Java creator unchanged. Eleven expert/dependency questions remain. [Evidence](docs/improvement/validator-17/evidence.json).

**V16 accepted — 2026-10-20:** Attachment association preserves exact PDF/MIME filename text and keeps Alternative representations out of native annotation matching. **389 current unit passes / 33 skips; eight exact-byte PDF probes, twelve generated profiles, all 141 custom-only comparisons and twelve CLI/isolated-worker checks match.** Only six reviewed outcomes change; all 133 prior fixture bytes/custom outcomes are unchanged. Creator/JAR is unchanged. [Evidence](docs/improvement/validator-16/evidence.json).

**V15 accepted — 2026-10-20:** A supplied attachment `Mail_GUID` must retain its normal text type when `Message-ID` permits omission. **381 current unit passes / 32 skips; eight exact-byte before/after PDF/A/custom/ISO probes, all 133 custom-only fixture comparisons and sixteen CLI/isolated-worker checks match.** V14's separately bounded 125 complete-pipeline cases and twelve generated profiles are unchanged prior evidence. Creator source/JAR is unchanged; its 161 Java passes remain historical. Eleven expert/dependency questions and broader ownership/occurrence review remain open; full coverage is not claimed. [Evidence](docs/improvement/validator-15/evidence.json).

**V14 accepted — 2026-10-20:** Native attachment size/checksum/media comparisons now execute without BodyRendering. Collection member PDFs and Alternative representations retain their distinct roles; collection completion explicitly defers payload comparisons to owning children. **379 verified unit passes / 31 skips; all 125 retained complete-pipeline PDF outcomes, twelve generated profiles and ten CLI/isolated-worker checks match.** Fifteen exact-byte before/after probes and the recovered thread's failed/interrupted gates are preserved separately. Creator code/JAR is unchanged. [Evidence](docs/improvement/validator-14/evidence.json).

**V07–V13 / creator structure — 2026-10-19:** Declaration/schema, verified upstream ISO execution, metadata and source-asset repairs are accepted. V13 fixes false attachment-annotation failures when the permitted native PDF filename differs from the original MIME filename, and closes a large-integer stored-size bypass; unavailable association stays INCOMPLETE. **365 current unit passes / 29 integration/schema skips; all 110 retained PDF outcomes, 12 generated normal/accessibility profiles and ten actual CLI/worker checks match.** The unchanged creator JAR has 161 Java passes; the historical V11 full packaged suite has 375 passes / one optional PREMIS skip. These are separately bounded gates. The audit has **301 records / 224 codes / 11 open expert/dependency questions**; full implementation coverage is not claimed. [Current evidence](docs/improvement/validator-13/evidence.json), [creator diagnosis](docs/improvement/creator-iso-structure/README.md).

**V06 XMP record schema — 2026-10-19:** Canonical Table 16/19 fields, normal-string shapes, nonnegative integer forms and supplied optional CheckSum validity are enforced. Malformed source Content-Type text remains permitted; parser defaults cannot fabricate equality. **339 Python passes / one optional schema skip (340 run); all twelve profile reports PASS; ten PDF/A-valid probes and six CLI/web-worker checks match exact expectations.** The recovered work continues with the confirmed declaration namespace/parser defect and separately reviewed upstream ISO-only execution. Eleven expert/dependency questions remain. [Evidence](docs/improvement/validator-06/evidence.json).

**V05 DPM schema — 2026-10-19:** Enforced Table 7/8 text-string and dictionary types, allowed unrelated extension keys, validated decoded UTF-8 name values and made invalid-name diagnostics reportable. Body-dependent annotation checks distinguish N/A from unavailable evidence. **308 unit passes / 22 integration skips; eight focused passes; all twelve final-source profile reports PASS; six PDF/A-valid probes and six actual CLI/web-worker checks meet expectations.** Canonical XMP field schema, broader ownership/mixed-rule coverage and 11 expert/dependency questions remain open. [Evidence](docs/improvement/validator-05/evidence.json).

**V04 file requirements — 2026-10-19:** All six previously unmapped mandatory/conditional file records now map to three executed checks: container Content Set prohibitions, associated-stream ModDate and media-type reconstruction. **321 Python passes, one optional PREMIS skip; all 12 profile reports PASS; all 12 PDF/A-valid probes match exact EA-PDF expectations; six actual CLI/web-worker checks agree.** The inventory has **301 records / 223 codes, zero unmapped mandatory/conditional file records and 11 open expert/dependency questions**. Mapping coverage is not implementation completeness. DPM field types/extensions, broader schema/ownership and mixed-rule routing remain open. [Evidence](docs/improvement/validator-04/evidence.json).

**Current checkpoint — 2026-10-19:** The [standalone rule browser](docs/improvement/specification-audit/rule-browser.html) embeds all 301 inventory records and 97 original specification pages with synchronized source highlights. [Upstream review](docs/improvement/upstream-issues/README.md) covers all 26 issues/47 comments and verifies MCAF, ADBE type and CreateDate-evidence repairs: 301 Python passes, one optional skip, 12 passing profiles and matching CLI/web probes. Eleven expert/dependency questions and six unmapped mandatory/conditional file records remain open; prior checkpoints below retain historical counts.

**V03 XMP/GUID repair — 2026-10-19:** The validator now preserves exact Mail_GUID identity across XMP, DPM, structure and attachment ownership; reads tested equivalent XMP forms; rejects URI/array/foreign-name GUID substitutions; and checks record/address array kinds and actual email cardinality. Verification: **290 broad-suite passes and one optional PREMIS skip; 272 final-source unit passes with 21 skips; all 18 focused regressions pass; all 12 normal/accessibility profile reports PASS; all 11 PDF probes and four actual CLI/web-worker checks meet expectations.** The two final scalar/cardinality guards were verified after the broad run; see the recorded boundary. Eight questions and six unmapped requirements remain open. [Evidence](docs/improvement/validator-03-evidence.json), [implementation](docs/improvement/validator-03/README.md).

**V02 ownership routing — 2026-10-19:** The normal validator pipeline now delegates all 11 fully upstream-owned codes from `validator_rule_text.json`, recording N/A delegation evidence instead of duplicate local conformance results. The web view keeps EA-PDF-specific checks visible, hides delegated rows only after a matching reviewed profile/version passes, and reloads engine rule data after JSON-only edits. Verification: **274 Python passes, one optional PREMIS schema skip; all 12 normal/accessibility profiles PASS; both real CLI and web-worker fixture pairs meet their exact expectations.** Mixed-rule fragments, metadata/schema/identity/applicability repairs, eight questions and six unmapped file requirements remain open. See [evidence](docs/improvement/validator-02-evidence.json) and [implementation review](docs/improvement/validator-02/README.md).

## Validator repair — Table 11 and authoritative reporting — 2026-10-19

The user resolved printed `Mail_ContentField` as **`Mail_Field`** and explicitly authorized validator improvements. That interpretation is recorded in authoritative `validator_rule_text.json` as user-approved, not as published errata. Eight unrelated interpretation/dependency questions remain; they do not block this authorized scoped repair. Broader preservation work remains deferred, and creator improvements follow validator work.

The validator now enforces Table 11 immediate children: required children must be immediate, and prohibited defined custom children fail even when required children also exist. New rule **220 `MAIL_CONTENTSET_CHILDREN`** permits zero or more immediate `Mail_Field` children and rejects the other defined custom types. Standard tags and unrelated extensions remain allowed. `Mail_FieldValue` requires an immediate `Mail_Field` parent with a `Mail_FieldName` child. Reading actual parent/child objects avoids conflating direct dictionaries with the same absent object identity.

Custom vocabulary remains optional for standard-only logical structure. Explicit `Document` elements can satisfy the existing multi-email Document count. Corrected rule descriptions now cite `validator_rule_text.json` in both text and attribution instead of retaining a historical DOCX source label.

Verification: **262 Python tests run: 261 pass, one optional PREMIS-schema skip.** All twelve normal/accessibility profile reports PASS PDF/A and EA-PDF, including recursive collection checks. The final focused 13 tests pass, including the last standard-only c/ci count-result guard added while the broad suite was running; that branch does not change the tested native custom-tag profile paths. Five frozen corpus candidates PASS PDF/A: one valid control PASSes EA-PDF, and four malformed candidates fail exactly their declared custom rules. The before-state validator accepted all five as EA-PDF PASS. Source and report hashes preserve the verification boundary; no Maven build, Java-source change or saved-sample regeneration is claimed.

The authoritative inventory remains **301 records**, now mapped to **220 rule codes**, with **eight open questions and six unmapped mandatory/conditional file records**. The repaired Table 11 row is mapped and verified. Scope ownership is now 138 custom, 11 delegated, 11 operational and 60 mixed. Runtime veraPDF routing, remaining schema/identity/applicability repairs and the other unmapped records remain unfinished.

[Evidence](docs/improvement/validator-01-evidence.json), [corpus and reproduction](tests/fixtures/eapdf/validator-table11/README.md), [authored regressions](tests/test_validator_requirements_improvements.py), [current expert view](docs/improvement/specification-audit/expert-review.html).

## Requirements documentation and ownership review — 2026-10-18

Corrected full Table 9 role-map wording, creator/producer/creation-date interpretations and exact GUID meaning in authoritative `validator_rule_text.json`. Added four declaration dependency records and explicit XMP/FOAF/date/byte representation contracts. Dispositioned the 182 remaining ownership candidates against all five pinned veraPDF profiles and generated a self-contained expert review. **301 records, 219 code dispositions, nine open questions and seven unmapped mandatory/conditional file records** remain visible. These are documentation/scope repairs, not runtime fixes.

Verified 30 focused Python passes, pinned XML/rule-reference integrity, traceability and document links. Validator logic and Java source hashes match the continuation before-state. [Current work log](docs/EA_PDF_WORK_LOG.md), [expert review](docs/improvement/specification-audit/expert-review.html).

## B12 — reconcile normalized inputs with final output

**B12 — normalized input reconciliation verified (2026-10-18):** Current single-message, archive and collection writers freeze an immutable inventory before rendering and reconcile reopened final bytes. SHA-256/length come from input streams. GUID, attachment-owner and occurrence checks reject missing messages/assets, updated wrong payload checksums, swaps, reused annotations and missing named children. Isolated source omissions remain valid and native placeholders retain their occurrence checks.

Verification: **152 Java tests pass; 249 Python tests run, 248 pass and one optional PREMIS schema skip**, no failures/errors, in 587.656 seconds. Twelve normal/accessibility profile reports PASS including children. All 25 new corpus candidates and 21 child checks PASS PDF/A: nine inventory accepts and sixteen expected rejects. Nine rejected candidates still PASS PDF-only EA-PDF; seven fail their exact declared PDF-only rules. Six saved samples and collection sidecars were regenerated and verified.

Next: original reader/input enumeration and reconstruction provenance, followed by complete independent staged-output/sidecar acceptance. The captured boundary begins at writer entry; it cannot discover reader or dispatch losses before that point. Broader source/per-occurrence XMP associations, legacy helper integration, arbitrary mixed-profile imports and full standards coverage remain open. Thirty-one scoped register records and the unchanged 72-site N/A inventory remain partial. Earlier checkpoints are historical.

[Evidence](docs/improvement/batch-12-latest-evidence.json).

## B11 — reject damaged embedded children before collection replacement

**B11 — final collection child acceptance verified (2026-10-18):** The creator now reopens each child embedded in a final c/ci output and recursively applies its existing consistency checks before atomic replacement. Missing child dates/page connections and corrupt native-attachment checksums reject the parent; existing destination bytes survive. Eight-level nesting and shared count/expanded-byte bounds reject unchecked excess.

Verification: **142 Java tests pass; 249 Python tests run, 248 pass and one optional PREMIS schema skip**, no failures/errors, in 566.697 seconds. All twelve normal/accessibility profile reports PASS independent PDF/A and mandatory EA-PDF, including children. All eight new corpus parents and their children PASS PDF/A: two valid collections PASS EA-PDF; six mutations fail exactly their declared child rule. Six saved samples and collection sidecars were regenerated and verified.

The recovered thread's user-approved priority is preservation assurance. Next: independently inventory expected message/attachment occurrences before rendering and compare their owners/bytes with final outputs. B11 checks a bounded creator subset; original-input completeness, broader ownership, integrated independent acceptance/sidecar gates and complete standards coverage remain open. Thirty scoped register records and the unchanged 72-site N/A inventory remain partial. Earlier checkpoints below are historical.

[Evidence](docs/improvement/batch-11-latest-evidence.json).

## B10 — contain malformed encoded-header failures

**B10 — encoded-header failure handling verified (2026-10-18):** Malformed optional source headers no longer abort unrelated validation through HeaderParseError. Decoded comparisons reject supported complete B/Q payloads that require repair and record unavailable evidence; valid words still decode. UserProperties and observed occurrences share this boundary, while raw comparisons remain literal and unexpected engine errors remain visible. This adds no mandatory source-format rule; rule 107 and overall rendered-header coverage remain partial.

Verification: **249 Python tests run, 248 pass, one optional PREMIS schema skip**, no failures/errors, in 497.391 seconds. All six profiles PASS independent PDF/A and mandatory EA-PDF in normal and accessibility integrations, including children. Six new PDFs all PASS PDF/A and mandatory EA-PDF with exact expected attribute PASS/WARN/N/A and per-occurrence decoder outcomes. The B09 baseline reproduces the source-only VALIDATION_ERROR. Creator/JAR and saved samples match accepted hashes; B08’s 138 Java passes are historical, with no fresh Java build or sample regeneration.

Resume from [the work log](docs/EA_PDF_WORK_LOG.md) and [B10 evidence](docs/improvement/batch-10-latest-evidence.json). The register has 29 scoped records; 72 explicit N/A sites remain a partial inventory. Next: rendered-header enumeration across standard/untagged content, occurrence/aggregation semantics, broader source/asset ownership and remaining applicability predicates. Full contextual RFC 2047 semantics, custom-set interpretation/external review, ISO/TS 32005 access and complete standards/inclusion-rule coverage remain open. Earlier checkpoints below are historical.

## B09 — record source-header occurrences and bounded associations

**B09 — source-header occurrences verified (2026-10-18):** Header evidence now retains parsed source order, raw values and occurrence ordinals. Observed custom fields map to a repeated source occurrence only when one supported display value matches within the fixed message/source owner. Identical, unsupported and unmatched repeated values remain unavailable. Observed text survives optional source absence. Header recovery stays partial; unrelated MIME body defects remain separate. Rule 107 remains partial WARN because complete rendered-header enumeration is still open.

Verification: **240 Python tests run, 239 pass, one optional PREMIS schema skip**, no failures/errors, in 466.932 seconds. All six profiles PASS independent PDF/A and mandatory EA-PDF in normal and accessibility integrations, including children. Six new PDFs all PASS PDF/A: two expected EA-PDF PASS and four exact MESSAGE_SUBJECT_XMP failures for malformed duplicate-Subject probes, independently reproduced with B08. Creator/JAR and saved samples match B08 hashes; its 138 Java passes are historical, with no fresh Java build or sample regeneration.

Resume from [the work log](docs/EA_PDF_WORK_LOG.md) and [B09 evidence](docs/improvement/batch-09-latest-evidence.json). The register has 28 scoped records; 72 explicit N/A sites remain a partial inventory. Next: complete rendered-header enumeration across standard/untagged content, arbitrary occurrence/aggregation semantics, broader source/asset ownership and remaining source-dependent predicates. Custom-set semantics/external review, full ISO/TS 32005 access and complete standards/inclusion-rule coverage remain open. Earlier checkpoints below are historical.

## B08 — inspect observed field text and source ownership

**B08 — observed custom header inventory verified (2026-10-18):** Header diagnostics now decode text from each field’s actual page/Form MCID scope and compare it with the retained source selected through its enclosing message identity. Duplicate IDs, ambiguous owners, repeated headers and unsupported decoding stay unavailable. Known field pages outside the message’s DPart ranges are diagnostic differences. Rule 107 remains partial WARN because standard-tagged, untagged and unrecognized rendered headers are not enumerated.

Verification: **227 Python tests run, 226 pass, one optional PREMIS schema skip**, no failures/errors, in 459.097 seconds. Fresh offline Maven package: **138 Java tests pass**. All six profiles PASS independent PDF/A and mandatory EA-PDF in normal and accessibility integrations, including children. Six new PDFs all PASS PDF/A: five expected mandatory EA-PDF PASS and one exact missing-GUID FAIL. Saved samples and collection sidecars are unchanged by hash; no creator/CLI edits or sample regeneration.

Resume from [the work log](docs/EA_PDF_WORK_LOG.md) and [B08 evidence](docs/improvement/batch-08-latest-evidence.json). The register has 26 scoped records; 72 explicit N/A sites are inventoried without certifying every predicate. Next: complete rendered-header/source occurrence mapping, broader source/asset inventories and ownership assurance, and remaining source-dependent conditions. Custom-set semantics/external review, full ISO/TS 32005 access and complete standards/inclusion-rule coverage remain open. Earlier checkpoints below are historical.

## B07 — inspect tagging evidence and conditional applicability

**B07 — tagging recommendation evidence verified (2026-10-18):** A structure root or isolated field tag no longer proves complete coverage. Content Set recommendations inspect marked paint, artifacts, Form content, annotation references, MarkInfo and structure correspondence across declared DPart ranges as a conservative superset of custom sets. Header tagging is conditional on custom EA-PDF tags; observed field pairs remain partial WARN until a complete rendered-header inventory exists. Known valid non-body ranges now give explained N/A for body-media comparison; unknown/unresolved bodies remain incomplete.

Verification: **213 Python tests run, 212 pass, one optional PREMIS schema skip**, no failures/errors, in 393.575 seconds. All six profiles PASS independent PDF/A and mandatory EA-PDF in normal and accessibility integrations, including children. Six new PDFs all PASS PDF/A: four expected EA-PDF PASS, one exact two-rule FAIL and one intentional INCOMPLETE for unavailable Core Field text ownership. B05 creator/JAR and saved samples are unchanged by hash; its 138 Java passes are historical.

Resume from [the work log](docs/EA_PDF_WORK_LOG.md) and [B07 evidence](docs/improvement/batch-07-latest-evidence.json). The register has 24 scoped records; 72 explicit N/A call sites are inventoried without certifying all predicates. Next: source/rendered-header inventories, remaining source-dependent conditions and wider ownership assurance. Custom-set semantics/external review, full ISO/TS 32005 access and complete standards/inclusion-rule coverage remain open. Earlier checkpoints below are historical.


## B06 — distinguish unavailable recommendations from N/A

**B06 — recommendation applicability verified (2026-10-18):** Four source/policy recommendations now show unavailable WARN instead of unconditional N/A. Extraction exceptions and missing/duplicate page-analysis results produce unverified WARN and INCOMPLETE. Context assessed on only some pages cannot produce an all-pages text-context PASS. SHOULD findings retain recommendation strength, and mandatory failures remain separate.

Verification: **197 Python tests run, 196 pass, one optional PREMIS schema skip**, no failures/errors, in 397.921 seconds. All six profiles pass PDF/A and mandatory EA-PDF in both normal and accessibility integrations, including children; their unavailable recommendations remain visible. Six new assessment scenarios use two PDF/A-valid PDFs: two normal PASS and four fault-injected INCOMPLETE. B05 creator/JAR and saved examples are unchanged by hash; its 138 Java passes are historical.

Resume from [the work log](docs/EA_PDF_WORK_LOG.md) and [B06 evidence](docs/improvement/batch-06-latest-evidence.json). The register has 21 scoped records; 73 explicit N/A call sites are inventoried without claiming their predicates are all reviewed. Next: custom Content Set/header-tagging applicability, remaining source-dependent conditions and broader inventory/ownership assurance. Full ISO/TS 32005 access and complete applicable errata/inclusion-rule review remain open; public clause 7 corrections are recorded separately. Earlier checkpoints below are historical.


## B05 — resolve effective attributes and preserve shared data

**B05 — effective UserProperties verified (2026-10-18):** ClassMap and direct attributes now share array-order resolution; direct A overrides C and the last P replaces the entire earlier property list. Revisions track updates independently of precedence. Creator updates copy shared attributes locally and preserve unrelated data. Malformed resolution remains visible as INCOMPLETE.

Verification: **138 Java passes; 188 Python tests run, 187 passes and one optional PREMIS schema skip**, no failures/errors. Eighteen new PDFs all pass PDF/A: ten legal cases PASS EA-PDF and eight mutations fail exactly their declared rules. Both normal and accessibility integrations pass all six profiles, including children. Six saved samples and collection PDF/XLSX sidecars were refreshed and verified.

Resume from [the work log](docs/EA_PDF_WORK_LOG.md) and [B05 evidence](docs/improvement/batch-05-latest-evidence.json). The register remains partial. Next: audit remaining explicit N/A recommendations and complete applicable standards/errata records, then wider metadata/source ownership checks. General namespace/attribute inheritance and full source-association assurance remain open. Earlier checkpoints below are historical.


## B04 — compare attributes with their owning source (2026-10-18)

**B04 — source-value recommendations verified (2026-10-18):** Declared mail attributes are now compared with their own inspectable source email. The check follows the enclosing Mail_Message and its XMP GUID before reusing existing source matching, so another email with the same Message-ID cannot satisfy a comparison merely by containing the same text. Raw/decoded fields, internal spaces, dates and counts have distinct comparisons. Changed values warn under the existing SHOULD recommendation; absent sources give explained N/A and ambiguous/unavailable comparisons warn.

Verification: **180 Python tests run: 179 pass, one optional PREMIS schema skip**, no failures/errors. Seventeen new multi-message PDFs all pass PDF/A and mandatory EA-PDF checks: eight source-recommendation PASS, eight expected WARN and one isolated N/A. All six profiles pass normal and accessibility integrations, including children. No Java/creator edits, rebuild or sample regeneration were required; the B01 creator JAR hash matches.

Resume from [the work log](docs/EA_PDF_WORK_LOG.md) and [B04 evidence](docs/improvement/batch-04-latest-evidence.json). Fourteen scoped requirements are documented. Next: attribute class maps, repeated owners and revisions, plus remaining explicit N/A/standards review. Broader source-association assurance and arbitrary header semantics remain open; the overall plan is incomplete. Earlier checkpoints below are historical.

## B03 — PDF-date validation (2026-10-18)

**B03 — Mail_Sent PDF-date validation verified (2026-10-18):** Mail_Sent UserProperties now require a PDF date with valid syntax, calendar day, time and timezone ranges. Permitted shortened dates, legacy timezone apostrophes and decoded UTF-16 dates remain accepted. Raw email date text, XMP Sent checks and the tolerant document-date comparison parser stay separate.

Verification: **170 Python tests run: 169 pass, one optional PREMIS schema skip**, no failures/errors. The 25 new date PDFs have nine valid cases and sixteen exact expected failures; all 25 pass PDF/A. Both normal and accessibility integrations pass PDF/A and EA-PDF for all six profiles, including children. Creator code and saved examples are unchanged; the accepted B01 JAR was reused, with its hash checked. No new Java test run is claimed.

Resume from [the work log](docs/EA_PDF_WORK_LOG.md) and [B03 evidence](docs/improvement/batch-03-latest-evidence.json). Thirteen scoped requirements are documented; the overall plan remains in progress. Next: source-value comparison for UserProperties, remaining explicit N/A recommendations, then attribute-owner/class-map/revision review. Earlier checkpoints below are historical.

## B02 — strict PDF field types and explicit missing-data conditions (2026-10-18)

**B02 — strict field types and missing-data applicability verified (2026-10-18):** The validator now requires actual PDF strings for text/name fields and actual nonnegative integers for counts. Generic email header values allow text, numbers and Booleans only, on their permitted tags. Absent optional raw headers and absent embedded files/parameters have explicit N/A conditions; existing applicable checks remain required. Profile tests now reject INCOMPLETE child results when PDF/A passes.

Verification: **162 Python tests run: 161 pass, one optional PREMIS schema skip**. The new 27-file corpus has nine valid files and eighteen exact expected failures; all 27 pass PDF/A. All six profiles pass PDF/A and EA-PDF in both normal and accessibility integration runs. The creator was inspected and its accepted B01 JAR reused unchanged; Java was not rebuilt and saved examples were not regenerated for this validator-only batch.

Resume from [the work log](docs/EA_PDF_WORK_LOG.md) and [B02 evidence](docs/improvement/batch-02-latest-evidence.json). Twelve scoped requirement records are documented. Next: PDF-date syntax for `Mail_Sent`, then remaining applicability/source comparisons and attribute-owner/class-map/revision handling. The whole plan and standards register remain incomplete. Earlier checkpoints below are historical.

## B01 — array handling and honest rule completion (2026-10-18)

**B01 — first improvement batch verified (2026-10-18):** Resume from [the work log](docs/EA_PDF_WORK_LOG.md). Fixed PDF attribute/root array traversal and creator attribute preservation; unreported rules now need explicit non-applicability or yield INCOMPLETE, and the web display preserves that distinction. Declared container attachment records receive Table 19 checks. Required Sent checks remain mandatory for mail profiles, while optional logical structure stays optional. A creator-derived corpus has three legal representations and four exact failing mutations, all PDF/A valid. The partial register contains eight scoped records; known Table 12 type/coercion defects and the wider explicit-N/A/standards review remain open.

Current verification: **134 Java passes; 151 Python run, 150 passes and one optional PREMIS schema skip**. All six regenerated primary samples, two original collection children and both PDF/A index sidecars pass. c/ci sidecars and m/mi spreadsheet embedding are preserved. Evidence, before/failed/final logs and generated fixtures are durable under `docs/improvement/`. No implementation permission is pending; continue priorities 1 and 2 from the work log. Older R14 counts below are historical.


## R14 — collection-only finding-aid sidecars (2026-10-17)

**R14 — collection-only finding-aid sidecars complete:** The user resolved R13 by restoring sidecars only where ordinary embedded files are prohibited. `1c`/`1ci` now publish requested `_finding_aid.pdf` and `_index.xlsx` beside the collection. Neither is embedded in the parent, and the ordinary PDF/A-4 index sidecar has no embedded files or synthetic EML. Collection index pages remain front matter. `1m`/`1mi` retain XLSX embedding; single-message source/native-attachment handling is unchanged. The strict Java creator and Python validator are byte-identical to the R13 checkpoint; no conformance exception was introduced.

For collections, existing `embedPdfInCollections` requests a PDF sidecar, while `embedExcel` or `excelEnabled` requests an XLSX sidecar; `pdfEnabled` gates all optional finding aids. The two sidecars are independent. They use `OutputTransaction`, including the existing four-asset allowance when PREMIS is enabled at a one-message limit. Configuration/CLI names, defaults and debug output are preserved.

**Current verification:** Full Maven package passes **132 Java tests**, zero failures/errors/skips. Packaged Python discovery runs **140 tests: 139 passed, one optional PREMIS XSD test skipped** because `EAPDF_PREMIS_SCHEMA` is not configured. All **six freshly regenerated primary samples** pass independent PDF/A and EA-PDF validation; both original collection email children pass both checks. Both ordinary finding-aid PDF sidecars independently pass **PDF/A-4**, and both external XLSX files are valid workbooks. Structural probes confirm c/ci contain no auxiliary PDF/XLSX, sidecar PDFs contain no attachments, m/mi retain their embedded XLSX, and the single-message alias matches. `examples/` now has six primary PDFs plus four collection sidecars.

Current build: `/tmp/eapdf-collection-sidecars-20261017/build/`; logs: `/tmp/eapdf-collection-sidecars-{build,python,samples}.log`; reports: `/tmp/eapdf-collection-sidecars-20261017/{integration,samples}/`. [Current sidecar evidence](docs/reimplementation/collection-sidecars-evidence.json) records source/JAR/sample hashes, exact suite totals and validation summaries. The former failing R13 review candidates were moved to `/tmp/eapdf-collection-sidecars-20261017/superseded-direct-xlsx-review/`. R13's output-policy question and default collection rejection are resolved; its earlier evidence remains historical. No work remains on this sidecar correction. External standards/human/live-account/platform/deployment acceptance requirements still apply. All older checkpoint records below are historical.


## Earlier R13 review — superseded by R14 (2026-10-17)

Recovered the interrupted task and verified the PDF/A revision correction. The ordinary finding-aid PDFs directly embed one valid XLSX without synthetic EML or email identity; the focused Maven package and one c/ci regression pass. Both index PDFs and collection review parents pass PDF/A; the parents correctly fail strict EA-PDF content/child checks. Four freshly generated flat-profile review samples pass both standards checks. Normal collection creation rejects the ordinary index and preserves existing outputs. Published samples are unchanged, and full suites were not rerun. This records verified progress only; the c/ci publication policy and release remain unfinished. See [evidence](docs/reimplementation/direct-xlsx-review-evidence.json) and [unfixed.md](unfixed.md).

## R12 — collection finding-aid/index files embedded only (2026-10-17)

Removed the four separate finding-aid PDF/XLSX example files and stopped the sample script from extracting or publishing collection auxiliary copies. Regeneration removes stale collection auxiliary paths after all six candidates pass. For c/ci, `excelEnabled` now embeds the XLSX in the root finding-aid EA-PDF, implies that PDF if other embedding flags are false, and cannot create a separate index. Flat archive XLSX behavior remains unchanged.

Verification: **131 Java tests**, **four packaged Python tests**, **six primary PDF/A/EA-PDF checks** and **four recursive child checks** PASS, zero skips/failures/incomplete results. The examples directory contains only the six primary PDF samples; the single-message alias remains identical. Evidence: [embedded-only-index-evidence.json](docs/reimplementation/embedded-only-index-evidence.json). Prior extracted-copy records below are historical and superseded.

## Explicit sample cleanup and regeneration (2026-10-17)

Removed the 13 old sample paths with exact temporary backups, then recreated six supported primary email-profile PDFs, four extracted finding-aid PDF/XLSX examples and the existing single-message alias. Removed obsolete Facebook/Twitter samples whose generators/fixtures are absent. The fresh Maven package passes **130 Java tests**, zero failures/errors/skips. All six primary samples and four recursive children PASS independent PDF/A and automated EA-PDF checks. Verified root finding-aid placement, no direct collection XLSX, generated provenance, byte-identical extracted examples and alias equality. Application/source fingerprints are unchanged; no full Python suite rerun was needed. Current hashes and report paths: [sample-refresh-evidence.json](docs/reimplementation/sample-refresh-evidence.json).

## Collection-root finding-aid EA-PDF with nested XLSX (2026-10-17)

**R11:** Implemented the requested root finding-aid PDF containing its spreadsheet. To satisfy §7.6 without exempting arbitrary PDFs, the finding aid is a PDF/mail-1s rendering of an explicitly software-generated RFC 822 index message, with its own source and optional XLSX attachment. Its reserved sender, source header and visible text identify it as generated, not original archived correspondence; no mail is sent. ci original email children remain isolated, while the generated auxiliary is s, consistent with mixed child profiles allowed by §7.6. Rule 218 and recursive EA-PDF/PDF-A checks remain enforced.

`embedExcel` implies the root finding-aid PDF; `embedPdfInCollections` requests it independently; `excelEnabled` independently requests an external XLSX; `pdfEnabled` disables optional finding aids. Container PREMIS stays a sidecar. Refreshed all six supported primary samples, four exact extracted auxiliary examples and the existing single-message alias. Updated affected configuration/user/engineering docs and explanatory rule text.

**Verification:** Maven package **130 tests**; complete packaged Python suite **140 tests**, zero failures/errors/skips. Combined acceptance/samples: **36 top-level PDF/A**, **32 recursive child**, **10 PREMIS XSD** passes; **34 overall PASS/two intentional negative FAILs/zero INCOMPLETE**. Byte comparisons verify auxiliary examples match the embedded PDF/XLSX exactly. Evidence and current source/JAR/sample hashes: [nested-finding-aid-evidence.json](docs/reimplementation/nested-finding-aid-evidence.json). External acceptance gates remain open in [unfixed.md](unfixed.md).

## Development handover and collection publication limit (2026-10-17)

Created [docs/DEVELOPMENT_STATUS.md](docs/DEVELOPMENT_STATUS.md) after checking actual source, Git history/dirty-tree inventory, test XML/logs and artifact/sample hashes. Verified that this documentation phase changed no application code. Then resumed development from that checkpoint.

**R10:** The existing packaged creator rejected a valid one-message collection with all three sidecars at `EAPDF_MAX_MESSAGES=1`: the publication manifest still allowed only three files per input. It now allows four (collection PDF, finding-aid PDF, XLSX, PREMIS), retaining size/hash/path/storage validation and a finite manifest bound. A Java regression accepts four entries and rejects five before publishing; a packaged regression publishes all four nonempty outputs for both c and ci at the one-message limit.

**Verification:** Fresh Maven package passes **130 Java tests**; complete packaged Python discovery passes **140 tests**, zero failures/errors/skips. All **30 top-level/24 recursive child PDF/A** and **10 PREMIS XSD** checks pass; overall **28 PASS/two deliberate mutation FAILs/zero INCOMPLETE**. Existing regenerated sample hashes are unchanged; this repair changes publication admission, not PDF serialization. Evidence and current JAR/source hashes: [development-status-evidence.json](docs/reimplementation/development-status-evidence.json). Remaining standards/human/account/platform/deployment/rights gates stay open in [unfixed.md](unfixed.md).

## Additional specification rules and regenerated samples (2026-10-17)

Reviewed the four proposed gaps against the local EA-PDF specification and ISO 32000-2. Three were missing atomic checks; rule 96 already enforced one array element and needed clearer text and focused regression coverage. The validation matrix now reports **219 rule codes**, with the three new numbered entries in `validator_rule_text.json`.

- **`SOURCE_FILE_SET` (217), §§9.1/9.4:** Validates the primary Source file specification's RF dictionary, matching EF keys, and nonempty filename/embedded-stream pairs. Alternatively, original companions use catalog Supplement relationships supported by source-asset XMP size/type/checksum evidence. RF members participate in asset-metadata comparisons. Python validation APIs accept an original `source_file_sets` inventory to detect wholly missing companions; independent Source assets do not imply a set, and undeclared original omissions cannot be inferred from the PDF alone. The creator model supports explicit `AssociatedFile.sourceSetMember` companions, preserves them as Supplement with source XMP, and refuses silent omission. Existing monolithic readers and CLI switches are unchanged.
- **`COLLECTION_CONTENT_TYPES` (218), §7.6:** Every contained non-source file must identify as EA-PDF, even without a Collection Item. Ordinary PDFs, spreadsheets, standalone finding aids and PREMIS XML are rejected. The creator gate enforces this before publication. Collection finding-aid pages remain front matter; requested copied PDFs, XLSX indexes and container PREMIS are now transactional sidecars. Existing `embedExcel`/`embedPdfInCollections` setting names select those sidecars for collections. Email-child PREMIS and ordinary archive behavior remain available.
- **`XMP_UTF8` (219), §7.1.1:** Checks actual UTF-8 bytes and compatible XML declarations in document/object-level metadata, including PDF/A-3. UTF-16/32 and legacy declarations fail even where XML parsing succeeds. The creator gate checks the same invariant. An initial generic object-count cap falsely rejected the full PST sample; it was removed in favor of cycle-safe traversal, cancellation and existing supervised resource controls.
- **`STRUCTTREE_ROOT_DOCUMENT` (96), §9.7:** The existing array-length check was correct. Rule text and diagnostics now explicitly require a Document dictionary or an array containing exactly one Document dictionary; creator gates and positive/negative regressions agree.

**Sample follow-up:** Regeneration exposed six apostrophe-wrapped PST recipients. An IntelliJ logpoint observed `'jimboman@bigfoot.com' | Domain contains illegal character` in Java's mailbox check. Python's permissive domain parser had incorrectly required structured mailboxes for those malformed SMTP values. The validator now distinguishes SMTP domain characters, permits valid local-part apostrophes, and accepts these source values only with exact raw preservation. A changed raw value still fails/remains incomplete; no mailbox was invented. Temporary debugger source/output, session and the agent logpoint were cleaned up.

**Verified:** Maven package passes **129 Java tests**; the full packaged suite passes **139 Python tests**, with no failures/errors/skips. All **six supported email-profile samples** in `examples/` were regenerated from the existing EML/PST fixtures and receive PDF/A and automated EA-PDF **PASS**. Four requested collection sidecars were published, and the existing `output/sample_1s.pdf` alias now has the identical validated bytes. Legacy Facebook/Twitter PDFs remain historical because their generators/fixtures are deleted; no optional scope answer was received.

Independent source-set probes pass for both Supplement and RF forms in single-message and collection outputs. Removing a required companion correctly fails `SOURCE_FILE_SET` while preserving PDF/A. Across suite, sample and probe evidence, **42 top-level PDF/A checks** and **29 recursive child checks** pass. Overall results: **38 PASS**, **four expected negative-mutation FAILs**, zero INCOMPLETE. **10 PREMIS schema checks** pass; the count drops from 11 because the prohibited container XML is now sidecar-only.

Artifacts: `/tmp/eapdf-additional-rules-20261017/final-build/`. Logs: `/tmp/eapdf-additional-rules-final-build.log`, `/tmp/eapdf-additional-rules-verified-acceptance.log`, `/tmp/eapdf-additional-rules-verified-samples.log`. Durable hashes, rule mapping, reports, sample inventory and debugger/probe evidence: [additional-rules-evidence.json](docs/reimplementation/additional-rules-evidence.json). Existing complete-standard/human/account/platform/deployment/rights requirements remain in [unfixed.md](unfixed.md). `target/`, debug directories, CLI arguments and existing debug messages were preserved.

## Supervision, publication and release follow-up (2026-10-17)

Continued from the saved transcript of thread `01a14956-07ae-75e3-8982-89c585898b17`, including its interrupted final acceptance work. The implementation was preserved. This section supersedes the earlier partial R8 and non-SMTP/release checkpoints below.

- **R8 resource containment:** Local/Gmail/monthly entry points and GUI-launched conversions use supervised worker JVMs with private monitored storage, whole-conversion deadlines, bounded JVM/native memory and Linux CPU/file limits. Aggregate image/page/output/font and temporary-file budgets apply, including generated index/front-matter pages. Cancellation reaps workers before cleanup; abandoned storage recovery checks process start identities. Tests cover timeout, temporary-storage limits, aggregate budgets and existing-output preservation. Strict filesystem quotas, blocked-storage watchdogs and non-Linux native acceptance remain operational requirements documented in [CONVERSION_OPERATIONS.md](docs/CONVERSION_OPERATIONS.md).
- **PDF/sidecar publication:** Staged outputs remain private until successful conversion; manifest checksums/sizes and publication preparation are budgeted. Rollback and crash journals protect existing PDFs/sidecars. The collection integration defect is fixed: internal child PDFs remain readable in the worker workspace. Tests cover failed replacement, new-output rollback, interrupted/committed recovery and actual CLI sidecar failure. Gmail completion hashes use staged bytes.
- **Non-SMTP metadata:** EA-PDF §9.8.3 permits raw values when structured types cannot represent them. Exchange/X.500 tokens stay in matching raw Core Fields; invalid SMTP values are excluded from FOAF mailboxes without inventing addresses. Exact source/raw preservation is required; unverifiable values remain `INCOMPLETE`. The real attached-message PST and all seven contained PDFs now pass PDF/A and automated EA-PDF checks.
- **Release packaging:** GUI and CLI shading start from regenerated thin input; provider services and distinct notices/license variants are merged, with exact font notices included. Repeat packaging succeeds with zero duplicate ZIP entries and unchanged implementation, notice and service contents in both runnable artifacts. Maven's generated build-date comment changes. Remaining overlapping LGPL/W3C resources contain identical text; they do not lose distinct notices.
- **Private-mail deployment defaults:** The supplied service/template requires hashed-password authentication, allowed hosts, HTTPS, rate limits and retention cleanup. Route authentication fails closed; host/origin checks have regressions. This verifies application/template behavior, not installation of certificates/services or a live deployment, and does not provide per-user file ownership.

**Resumed acceptance:** Fresh offline Maven package passes **124 Java tests**, with no failures/errors/skips. The complete packaged suite passes **130 Python tests**, with no skips. All **30 top-level** and **24 recursive child** PDF/A checks pass; overall EA-PDF results are **28 PASS** and **two expected negative-mutation FAILs**, with no INCOMPLETE reports in these fixtures. **11 PREMIS schema checks** pass. Repeat packaging passes and preserves executable/resource contents. Logs: `/tmp/eapdf-resumed-build.log`, `/tmp/eapdf-resumed-acceptance.log`, `/tmp/eapdf-resumed-repeat-package.log`. Reports: `/tmp/eapdf-resumed-20261017/acceptance`. Durable hashes, report summaries and source fingerprints: [followup-evidence.json](docs/reimplementation/followup-evidence.json).

Complete ISO/TS 32005 clause coverage, human accessibility/fidelity, live Gmail/interactive GUI, target-platform behavior, deployed security and release-specific rights remain explicit verification requirements in [unfixed.md](unfixed.md). No public CLI arguments, ordinary debug output, saved samples, `target/` or debug directories were changed by this resumed verification.

## Specification-driven repair batch (2026-10-17)

The specification, not agreement between creator and validator, defines correctness. These fixes address the independently reproduced review findings; they do not claim exhaustive ISO/EA-PDF certification.

- **R1:** Image-only HTML no longer falls back to empty text. Both distinct source images survive as PDF image objects.
- **R2:** Collection source filenames are sanitized and bounded, collisions include Unicode case normalization, and the staged-PDF gate rejects invalid file names/collisions before publication. Original source names remain in descriptive metadata.
- **R3:** Wrong single-message `NonFullScreenPageMode` and missing non-empty Subject/Message-ID/In-Reply-To/address metadata are mandatory failures, not warnings. Optional raw representations are not made mandatory. The Java gate also enforces navigation.
- **R4:** Selected RFC 822 body media types retain parameters in DPM. Validator syntax and source comparisons accept equivalent parameter ordering/quoting/charset case while detecting dropped or changed parameters.
- **R5:** PREMIS uses valid version-3 file/event structures and truthful source media types; it no longer claims successful final publication/validation before serialization.
- **R6:** Auxiliary PREMIS/finding-aid files no longer falsely require email-attachment navigation.
- **R7:** Missing image descriptions reach the accessibility warning/generic-description path; deliberate decorative `alt=""` is preserved.

**Additional repaired cases:** Collection PREMIS now orders child objects before events and events before agents, as required by the official schema; the six-profile sidecar/embedded-child schema suite passes 11 XML checks. Source filenames cannot spoof a `<folder-id>` collection prefix; auxiliary names are sanitized too, and every custom schema field is checked for forbidden editability. The unsafe inline-image fallback no longer draws unreferenced/hidden attachments at invented positions. PST DPM now includes the actual UTF-8 charset of its exported RFC 822 source, and real PST/MBOX collections pass independent checks.

**R8 partial operational repair:** MIME traversal has depth/part and cumulative decoded-byte limits; text/attachment reads are bounded; raster dimensions are inspected without allocating pixel buffers; worker waits/shutdown have finite deadlines and cooperative cancellation is checked before PDF publication. Safe regressions exercise deep/wide MIME, enormous image headers, cancelled futures and uncooperative shutdown. This is not complete hostile-input containment; the remaining process-isolation/budget work stays open in `unfixed.md`.

**R9a/R9b:** Export-before-conversion is documented as the §7.2-permitted mailbox policy, not misrepresented as original-container retention. PDF/source descriptions and enabled PREMIS retain the derivation/path/message mapping. Explicit missing configuration now fails, built-in/checked-in XLSX sidecar defaults agree, and web DPM diagnostic expectations use the correct names.

**Final verification:** Maven packaging passes **114 Java tests**; the exact final artifact passes **123 Python tests**, with every generator/schema integration enabled and none skipped. All **30 top-level PDF/A reports** pass, as do **24 recursive child PDF/A checks**. Overall results are 27 PASS, one honest malformed-Exchange-address INCOMPLETE, and two deliberately corrupted EA-PDF mutations correctly rejected despite passing PDF/A. Eleven six-profile PREMIS XML checks plus the original single-message schema probe pass the official Library of Congress 3.0 schema (SHA-256 `03b8a77a20b32b882ad799e12262671d07ad18210c60233f4e613a1289491cba`). Both original image-only images survive, MIME parameters remain intact, and missing descriptions produce the expected warning. Durable hashes, source/config manifest, probe observations and report summaries: [repair-evidence.json](docs/reimplementation/repair-evidence.json). Rewrite contracts: [repair follow-up](docs/reimplementation/09-repair-follow-up.md). Builds/reports use `/tmp`; saved samples, `target/`, and existing debug directories are untouched. Hard containment and the remaining verification gates stay open in `unfixed.md`.

## Source-checksum acceptance and requested output repair (2026-10-16)

The Java pre-publication gate now validates source file-specification checksums,
not only native attachment checksums. A regression first reproduced acceptance
of a 32-byte hexadecimal source checksum, then verifies rejection of malformed,
incorrect and separately corrupted Unicode-stream checksums without replacing
an existing PDF. Maven packaging passes 97 Java tests. The requested
`output/sample_1s.pdf` is recreated from its unchanged email input and passes
independent PDF/A and local EA-PDF checks with no warnings or failures. The
earlier stale-output failure and final reports are recorded in
[docs/VALIDATION_RESULTS.md](docs/VALIDATION_RESULTS.md).

## Validator completion and saved-PDF audit (2026-10-16)

The shared CLI/web engine now validates annotation-only file specifications,
binary embedded-file MD5 format, and body media types against retained MIME
sources. Core Field checks follow real nested structure references and use a
bounded XMP-consistent fallback for recognized older headers. Fixes also cover
isolated-profile source exemptions, recommendation-level ancestor nesting,
FrontMatter subtype scope, strict source dates, and wrapped URL evidence.

Creator repairs add semantic header fields by default, visible multi-page
context, actionable body URLs without HTTP-to-HTTPS retargeting, correct PST
transport-header senders, MAPI subject padding removal, valid GMT date parsing,
and preserved original RTF in reconstructed MIME sources. Maven packaging
passes 96 Java tests; 110 Python tests include packaged six-profile integration.
The six current email examples are regenerated and pass PDF/A with no local
mandatory failures. Genuine malformed-source and isolated-omission warnings
remain visible. Historical/imported failures and the approval required before
replacing historical outputs are listed in [docs/VALIDATION_RESULTS.md](docs/VALIDATION_RESULTS.md).

Earlier verification entries below are historical snapshots.

Updated on 2026-10-16. This file contains 52 completed issues. Original issue numbers are retained; remaining work is in [unfixed.md](unfixed.md). Problem statements, proposed fixes, and original locations describe the review findings; each status describes the completed work.

The review covers the Java readers, PDF generation, shared helpers, desktop interface, Python validator and web interface, configuration, build setup, deployment files, tests, and documentation script. Items marked **Reproduced** were exercised during this review. **Code review** identifies a defect visible in the implementation. **Gap** and **Improvement** describe work that is missing or would make the project more reliable.

## Current verification

Creator-accuracy follow-up (2026-10-16): 91 Java tests and 98 Python tests pass with all three packaged-generator integrations enabled. All six profiles, with default and enhanced accessibility, plus three targeted accuracy outputs pass independent PDF/A validation with no local mandatory-check failures. Dates, direct native annotations, stored placeholder metadata, MIME parameters/body media types, portable filenames, binary MD5 parameter encoding, scoped CSS visibility and staged-PDF rejection now have regressions. Core Field text encoding without identifiable optional field tags is reported as `INCOMPLETE`, not silently `N/A`; this is not certification or a requirement to add optional tags. See [the creator-review follow-up](docs/CREATOR_COVERAGE_REVIEW.md#implementation-follow-up). Earlier verification entries below describe historical builds and policies; saved sample PDFs are unchanged in this follow-up.

Release 1.4.0 verification (2026-10-16): Maven packaging succeeds with 80 Java tests passing, and all 90 Python tests pass with packaged-generator integration enabled. All six standard profiles and all six accessibility-enabled profiles pass independent PDF/A validation with no local EA-PDF failures. All six tracked email samples are regenerated from the existing EML/PST fixtures and independently validated; single-message and collection non-isolated samples receive PASS, while isolated samples and the PST multi-message sample retain documented INCOMPLETE results for omitted attachment contents or malformed source addresses. The single-message source/attachment hierarchy and multi-message finding aid were visually checked. A 178,536,620-byte mailbox with 120 messages converts to both 1m and 1c with a 128 MiB Java heap; both retain 120 metadata records, and the collection retains 120 child PDFs. Final measured process RSS is about 312 MiB and 287 MiB respectively. An independent PDF/UA-2 check of the exercised semantic fixture now reports only the deliberately omitted PDF/UA identification metadata; meaningful alternative text, assistive-technology testing, and complete PDF/UA acceptance remain open in issue 34. Java artifacts and validation evidence use /tmp; target/ and project debug directories remain untouched. Live Gmail and interactive GUI behavior were not exercised.

Resumed-work verification (2026-10-16): Maven packaging succeeds with all 71 Java tests passing headlessly. All 89 Python tests pass with six-profile generation/validation enabled; all six fresh PDFs pass independent PDF/A validation with no local EA-PDF failures. Non-isolated integration outputs receive PASS; isolated outputs remain INCOMPLETE for intentionally absent native attachment contents. Builds and reports use /tmp, leaving target/, project debug directories, and saved samples untouched. Both packaged artifacts report version 1.3.2 outside the checkout, and external CLI conversion succeeds. Live Gmail, interactive GUI behavior, full accessibility conformance, large-export memory limits, and attached-email PST extraction are not claimed verified.

One-message monthly output follow-up (2026-10-16): all 57 Java tests pass headlessly. The new monthly regression first reproduced the stop at a one-message February, then verified January and March archives plus February's single-message PDF, correct profile metadata, visible bodies, unchanged embedded source bytes, and separate summary counts. Compilation and test outputs used `/tmp`; `target/` and project debug directories were unchanged. This follow-up did not run independent PDF/A validation or Maven packaging.

Attachment-listing follow-up (2026-10-16): 56 Java tests and 89 Python tests pass, with six-profile generation/validation integration enabled. Both new listing tests failed before the fix and pass after it. The `1s`, `1si`, `1c`, and `1ci` samples in `examples/` were regenerated from `in/email/test3.eml` and visually checked against the requested source/attachment hierarchy. All four and both contained email PDFs pass independent PDF/A validation with no local EA-PDF failures. The non-isolated samples receive overall PASS; isolated samples remain INCOMPLETE for intentionally omitted original attachment contents. Embedded source bytes are unchanged, and `1m`/`1mi` samples were not regenerated. Java tests ran headlessly to avoid an unavailable desktop display; no application behavior was changed for that environment issue. `target/` and debug directories remain untouched.

Latest grouped-fix verification (2026-10-16): 54 Java tests and 89 Python tests pass, including fresh generation and validation of all six profiles. Fresh copies of all six sample PDFs from the EML/PST fixtures, including all 142 contained email PDFs, pass independent PDF/A validation with no local EA-PDF failures. The single-message sample receives overall PASS; the other samples remain INCOMPLETE for absent native attachment contents or malformed source addresses. Targeted generated outputs for an empty attachment, an encrypted-body notice, and both collection profiles with finding aids and branding disabled also pass independent PDF/A validation with no local EA-PDF failures. The targeted isolated collection remains INCOMPLETE because its original native attachment contents are intentionally absent. A failed-write test confirms that an existing output survives and temporary files are removed. Tests also cover stable parallel filenames, comma-bearing labels, folder-name collisions, duplicate preservation identities, configuration errors, and CLI errors. Java classes and reports are written under `/tmp`; `target/`, debug directories, and the saved sample PDFs are unchanged by this batch. These checks are not independent EA-PDF or accessibility certification.

## Completed In Release 1.4.0

### 7. PST attachments do not have a separate path for attached emails

**Type:** Functionality gap — code review.

**Status:** Fixed and verified with a real PST. The pinned Apache Tika fixture contains an embedded email whose reconstructed RFC 822 form retains its subject, sender, body, and nested DOCX content. A regression also checks that the reconstructed parent source embeds unchanged in non-isolated output. Unnamed attached emails receive a subject-based EML filename. Unsupported attachment methods fail explicitly; nesting remains bounded.

**Problem:** Every PST attachment is read as an ordinary file. The reader never checks whether an attachment is an embedded message or uses the library's embedded-message reader. Attached emails and their own attachments therefore lack the preservation handling already implemented for EML and MSG inputs.

**Proposed fix:** Check the PST attachment method. Export attached emails through a dedicated path that retains their headers, body, and nested attachments. Report unsupported attachment methods clearly instead of treating them as ordinary files. Verify this with a real PST fixture containing an attached email.

**Where:** `src/main/java/eapdf/PstReader.java:172`; the installed PST library exposes `getAttachMethod()` and `getEmbeddedPSTMessage()`.

### 17. Large exports keep too much data in memory

**Type:** Improvement — code review.

**Status:** Fixed for bounded accepted workloads. Message sources/bodies and collection children use private temporary storage; completed message PDFs and collection embeddings stream instead of retaining full output arrays. Heap-aware worker limits, per-message/body limits, input/member counts, and estimated record/warning/checkpoint metadata budgets reject oversized workloads with an explanation. A 178,536,620-byte, 120-message mailbox converts to both 1m and 1c with a 128 MiB Java heap, retaining all message records and collection children. Native/runtime RSS is additional, and arbitrary-size individual messages are deliberately rejected rather than promised unlimited support.

**Problem:** Mailbox files are read completely into memory and copied into strings and message buffers. PST reading keeps every email and attachment in a list. Gmail downloads all selected messages before writing any PDF. Archives also retain rendered message PDFs, and collections retain child PDFs, before finishing the output. Large mailboxes can exhaust memory even with a small number of worker threads.

**Proposed fix:** Read and process messages in bounded batches. Write intermediate PDFs and large attachments to temporary storage, and release each batch once it is incorporated. Apply a consistent worker limit to parsing and rendering, including archive mode.

**Where:** `src/main/java/eapdf/MboxReader.java:12`, `src/main/java/eapdf/PstReader.java:17`, `src/main/java/eapdf/GmailDownloadToPdf.java:66`, `src/main/java/eapdf/PdfMailWriter.java:1043`, `src/main/java/eapdf/PdfCollectionSupport.java:199`.

### 29. Gmail exports cannot resume after a failed download

**Type:** Improvement — code review.

**Status:** Fixed with opt-in persistent recovery. EAPDF_GMAIL_RESUME_DIR freezes selected IDs/order, stages original bytes privately, records checksums and completed outputs/sidecars, isolates accounts/settings/releases, and locks concurrent exports. Reruns reuse verified downloads, recreate missing/changed outputs, and rebuild archives from staged messages. Resume identity uses font/ICC contents rather than temporary extraction paths. Tests cover restart, corrupted staging/output, separate accounts, locking, private permissions, stable asset identity, bounded retry/interruption, and archive reconstruction with an unavailable network transport. Without the setting, private staging remains temporary. Live Gmail access was not exercised.

**Problem:** All selected messages are downloaded before any output is written. A failure late in the download loop abandons the work already done. There is no application-level checkpoint or record of which Gmail messages were completed.

**Proposed fix:** Save progress by Gmail message ID, write ordinary per-message outputs as downloads finish, and support resuming interrupted archive jobs from staged data. Add bounded retries for temporary failures and a clear completion summary.

**Where:** `src/main/java/eapdf/GmailDownloadToPdf.java:66`, `src/main/java/eapdf/GmailDownloadToPdf.java:70`, `src/main/java/eapdf/GmailDownloadToPdf.java:116`.

### 53. Characters missing from the chosen font can be replaced without a useful warning

**Type:** Rendering gap — code review.

**Status:** Fixed. Bundled DejaVu Sans and explicitly configured TTF fallbacks supply glyphs to both HTML rendering and directly drawn index/navigation text. Width calculations and drawing use the same fallback choices. Missing glyphs emit field/code-point warnings in logs and job summaries. Regression tests verify body and generated-text extraction for Latin, Greek, Cyrillic, Armenian, and Georgian, plus explicit unsupported-glyph reporting. Complex-script shaping and source-specific reading order still require review; universal language coverage is not claimed.

**Problem:** The HTML renderer uses one registered font and forces that font throughout the message. Directly drawn text, including finding-aid fields, replaces unsupported characters with `?`. An email's metadata can preserve the correct Unicode text while the visible PDF has lost parts of names or subjects.

**Proposed fix:** Support a controlled set of fallback fonts with the character coverage needed by the input. Record missing characters and affected fields when full rendering is impossible. Test visible text and text extraction for several writing systems, rather than checking only metadata strings.

**Where:** `src/main/java/eapdf/PdfMailWriter.java:513`, `src/main/java/eapdf/PdfMailWriter.java:598`, `src/main/java/eapdf/PdfMailWriter.java:2986`.

## Completed In Resumed Work

### 6. Mailbox parsing can split messages incorrectly and change their contents

**Type:** Bug / format-support gap — code review.

**Status:** Fixed. The streaming parser recognizes envelope dates and following headers, honors Content-Length, and supports explicit mboxo, mboxrd, mboxcl, and mboxcl2 quoting rules. Tests cover ordinary body From lines, repeated quoting, embedded envelope/header text in length-delimited bodies, invalid lengths, trailing blank lines, and LF/CRLF/CR endings. Auto-mode assumptions and ambiguous unescaped boundaries are documented.

**Problem:** Every line beginning with `From ` is treated as a new message, without checking the mailbox variant or a declared message length. The reader also removes all trailing line breaks and only removes quoting from lines beginning with exactly `>From `. This can split a message body, remove meaningful blank lines, or leave extra quoting in mailbox formats that quote several leading `>` characters.

**Proposed fix:** Recognize supported mailbox variants and honor their boundary and quoting rules. Preserve message bytes except for the changes required to remove the mailbox wrapper. Add fixtures with body `From ` lines, repeated quoting, declared lengths, and trailing blank lines.

**Where:** `src/main/java/eapdf/MboxReader.java:37`, `src/main/java/eapdf/MboxReader.java:76`, `src/main/java/eapdf/MboxReader.java:86`.

### 24. Successful plain-text fallback is not clearly reported as reduced rendering

**Type:** Reliability gap — code review.

**Status:** Fixed. Successful plain-text fallback always emits a warning with the primary failure and potential image, formatting, and link losses. The warning appears in per-file logs and job summaries with debug disabled. A forced primary-render failure verifies visible fallback text, warning capture, and unchanged original source bytes.

**Problem:** If HTML rendering fails, the writer retries with plain text. When that succeeds, the code's explanation of the fallback appears only in debug mode. A normal run can report success even though images, formatting, and links were lost.

**Proposed fix:** Always record that fallback was used, the reason, and what content may be missing. Include the warning in the per-file log and final job summary while preserving existing debug output. Keep the original source bytes when the profile allows them.

**Where:** `src/main/java/eapdf/PdfMailWriter.java:487`, `src/main/java/eapdf/PdfMailWriter.java:525`.

### 33. Isolated collection children lose the PREMIS result built for them

**Type:** Bug / functionality gap — code review.

**Status:** Fixed. Collection PREMIS sidecars retain each child output object and conversion event, link children from the aggregate event, and distinguish repeated filenames using folder identity. Tests cover both collection profiles and confirm isolated children contain no embedded PREMIS or source files.

**Problem:** Building a child message PDF returns both PDF bytes and optional PREMIS bytes. Collection assembly keeps only the PDF bytes. For isolated children, PREMIS is deliberately not embedded, so the child-specific preservation record is discarded. The collection-level sidecar does not record each child build as its own output event.

**Proposed fix:** Define preservation records for child outputs and retain them in the collection's sidecar package or link them from an aggregate preservation record. Keep isolated child PDFs free of embedded PREMIS files.

**Where:** `src/main/java/eapdf/PdfMailWriter.java:962`, `src/main/java/eapdf/PdfMailWriter.java:979`, `src/main/java/eapdf/PdfCollectionSupport.java:215`.

### 43. Empty or unsupported input folders can appear to convert successfully

**Type:** Bug — code review.

**Status:** Fixed. CLI jobs report accepted, skipped, completed, failed, and unprocessed inputs, retaining skipped-file reasons and stop-on-failure behavior. Archive inputs become completed only after output succeeds. Tests cover failed and pending inputs, skipped files, warning summaries, and one-worker failure before a later input starts.

**Problem:** An empty directory, or a directory with no accepted inputs, produces an empty input list. Ordinary conversion then exits successfully without writing a PDF. Unsupported `.msg` contents can also be silently excluded during directory scanning. The user gets no useful accounting of what was skipped.

**Proposed fix:** Report clearly when no supported messages are found and return a nonzero result when nothing was converted. Summarize accepted, skipped, completed, and failed inputs, including reasons for skipped files.

**Where:** `src/main/java/eapdf/EmailToPDFA.java:33`, `src/main/java/eapdf/EmailToPDFA.java:84`, `src/main/java/eapdf/EmailToPDFA.java:105`.

### 46. Version numbers and documented launch commands disagree

**Type:** Build and documentation bug — code review.

**Status:** Fixed. The saved release remains 1.3.2 across Maven, VERSION, and launch examples. Maven filters packaged version metadata, and Java reads it before consulting a launch-directory VERSION file. Both packaged artifacts report 1.3.2 outside the checkout, and the packaged CLI successfully converts an email there. GUI class loading and version lookup were checked; interactive GUI behavior was not tested in the headless environment.

**Problem:** `pom.xml` declares `1.3.2`, the README advertises `1.3.1`, and `VERSION` contains `1.3.0`. Maven puts the jars under `target/`, while the README commands use root-level jar names and the Gmail guide uses `target/emailtopdfa.jar`. Java metadata reads the current directory's VERSION file and otherwise reports `dev`, so a distributed jar can identify itself incorrectly.

**Proposed fix:** Use one release version to generate packaged version information, release filenames, and documentation examples. Document the actual artifact locations produced by `mvn package`. Check CLI and GUI launches from outside the repository.

**Where:** `pom.xml:12`, `VERSION:1`, `README.md:5`, `README.md:47`, `docs/GMAIL.md` command examples, `src/main/java/eapdf/Common.java:620`, `validator_web.py:43`.

### 49. Creating PDF objects repeatedly scans every object already created

**Type:** Performance improvement — code review; not benchmarked.

**Status:** Fixed. PDF object allocation caches identity references and the highest object number per document, reuses loaded references, and avoids overwriting occupied numbers. Serialized regression tests cover existing object 900 and 2,000 additional allocations. A five-trial allocation microbenchmark measured median scanning/cached times of 49.561/2.306 ms at 3,000 objects and 861.281/2.508 ms at 12,000 objects. These measurements isolate allocation cost; they are not whole-archive throughput claims.

**Problem:** The shared indirect-object helper scans the document's object pool whenever it needs a new object number. Page and structure creation call it repeatedly. As an archive grows, object allocation can spend increasingly more time looking over existing objects.

**Proposed fix:** Use a safe per-document object-number allocator or an appropriate PDFBox allocation facility, while retaining correct references and unique numbers. Measure large archives before and after the change and keep the serialized document-part tests.

**Where:** `src/main/java/eapdf/PdfDocumentParts.java:26`.

### 51. Tests and documentation tools depend on local setup that is not fully captured

**Type:** Portability improvement — code review.

**Status:** Fixed. Tests load fixtures from the classpath, Maven supplies the existing PST fixture as a test resource, and Surefire declares its working directory. Documentation dependencies are declared separately. Focused Maven reader and attachment-policy tests were launched from /tmp; no duplicate PST fixture or target/ edits were needed. A new eapdf.build.directory property also permits a complete temporary-directory Maven build.

**Problem:** Java tests locate fixtures relative to the current working directory, and a PST preservation test uses `in/email/test.pst` outside the test-resources directory. The decision-tree script imports ReportLab, but the listed Python web requirements do not install it. A fresh checkout or a different test launch directory needs additional setup knowledge.

**Proposed fix:** Load redistributable fixtures from test resources, declare the test working directory where necessary, and document separate dependencies for development and documentation generation. Add a clean-environment test of the documented commands.

**Where:** `src/test/java/eapdf/TestSupport.java:30`, `src/test/java/eapdf/AttachmentProfilePolicyTests.java:125`, `scripts/generate_decision_tree.py:3`, `requirements-web.txt:1`.

## Highest priority

### 1. Collections can silently leave out emails when folder names differ only in capitalization

**Type:** Bug — code review.

**Status:** Fixed. Collection members use the existing folder groups, and repeated case variants within one email's labels are counted once. Regression tests confirm that both emails survive in `1c` and `1ci`.

**Problem:** Folder grouping treats `Work` and `work` as the same folder, but the later step that selects emails for that folder requires an exact match. Emails assigned to one spelling can therefore disappear from the collection even though the folder count includes them.

**Proposed fix:** Use the same folder identity and matching rules throughout collection creation. Build collection members from the groups already created instead of searching for them again by display name. Check that every input email appears in the finished collection.

**Where:** `src/main/java/eapdf/PdfCollectionSupport.java:171`, `src/main/java/eapdf/PdfCollectionSupport.java:199`.

### 2. The web download route exposes files by their original names

**Type:** Bug — reproduced.

**Status:** Fixed. Downloads require a registered random file ID and a PDF confined to the output directory. Direct filenames, unknown IDs, missing files, and links escaping the output directory are rejected. Registered nested outputs remain downloadable.

**Problem:** Anyone who can reach the service and knows a filename can download that file from the shared output directory. The route does not check the generated-file registry or which request produced the file. A temporary, unregistered `private_email.pdf` returned HTTP 200 during the review.

**Proposed fix:** Serve files only through registered, unguessable job links. Check access to the job before serving the file, and stop accepting arbitrary output filenames as download identifiers. Keep each job's files together.

**Where:** `validator_web.py:942`, `validator_web.py:2608`.

### 3. A damaged PDF bookmark list can hang validation

**Type:** Bug — code review.

**Status:** Fixed. PDF tree walks now enforce cycle, depth, and size limits. Web validation runs in a separate process with a time limit and a POSIX memory limit; regression tests exercise cycles, repeated resources, timeout termination, and memory exhaustion.

**Problem:** Bookmark traversal follows the next bookmark until it reaches the end, without remembering bookmarks already visited. A bookmark that points back to itself can keep the validator running and growing its result list indefinitely. Structure-tree traversal also lacks explicit cycle and size checks, although its recursive form will eventually hit Python's recursion limit.

**Proposed fix:** Add visited-object checks, depth limits, and item limits to every PDF tree traversal. Report the damaged structure as a validation failure. Run web validation in a worker that can be stopped when its time or memory limit is reached.

**Where:** `validate_eapdf.py:2248`, `validate_eapdf.py:2393`.

### 4. Fresh non-isolated collections fail the project's source-preservation checks

**Type:** Bug — reproduced.

**Status:** Fixed. Non-isolated collection sources and their XMP descriptions use the shared source-file plan. Isolated collections still omit sources. The regenerated `1c` sample passes the source checks and independent PDF/A validation.

**Problem:** A newly generated `1c` collection fails `SOURCE_AF` and `ASSET_XMP`. Its child PDFs contain email sources, but the outer collection embeds its children as data files and deliberately leaves its source-asset metadata empty. The validator requires source information at the collection level.

**Proposed fix:** Implement the collection-level source preservation and matching metadata required by the chosen profile. Derive the embedded files and their descriptions from one shared plan so they cannot disagree. Add a test that generates a collection and runs the Python checks on it.

**Where:** `src/main/java/eapdf/PdfCollectionSupport.java:298`, `src/main/java/eapdf/PdfMailWriter.java:2077`, `validate_eapdf.py:2896`, `validate_eapdf.py:3388`.

### 5. Emails with attachments fail validation because generation and validation expect different attachment access

**Type:** Bug — reproduced.

**Listing follow-up:** The message body again shows the bold source-email line and an indented list of its original attachment names. Names come from the email's attachment descriptions, not the filtered list of separately embedded files. They are plain text, not extra embedded files or separate-file links. Source-opening annotations and isolated-profile behavior are unchanged.

**Status:** Fixed. In non-isolated message PDFs, visible attachment markers open the existing embedded source email, not a separately embedded native attachment or zero-byte file. Validation names the source email and verifies the original attachment inside it, including ownership, name, size, type, and available checksum. Missing, hidden, detached, or mismatched references still fail; an uninspectable source remains `INCOMPLETE`. Isolated message PDFs have no embedded source and retain their existing zero-byte markers, warnings, and `INCOMPLETE` result. Notice pages no longer show the heading "Attachment contents omitted" and now say "These placeholders identify attachments; their native bytes are not embedded separately." followed by "Open the embedded source email to access the original attachment contents." for source-backed outputs.

**Problem:** A fresh email with one text attachment fails `ATTACHMENT_ANNOTATIONS_PRESENT` in both `1s` and `1si`. The writer retains attachment descriptions in metadata while following the documented policy of omitting separate native attachment files. The validator still requires a visible file-attachment annotation for each description.

**Proposed fix:** Resolve the profile-specific requirement against the EA-PDF requirements and document the supported behavior. Then make rendering, metadata, links, and validation agree. Preserve attachment bytes inside source emails for non-isolated profiles and preserve the documented isolated-output behavior; do not simply suppress the failing rule.

**Where:** `src/main/java/eapdf/PdfMailWriter.java:322`, `src/main/java/eapdf/PdfMailWriter.java:1960`, `validate_eapdf.py:4245`, `README.md` attachment-panel policy.

### 8. Web jobs have no complete resource limit or reliable stop mechanism

**Status:** Fixed. Shared admission slots, upload limits, request deadlines, isolated subprocesses, bounded logs, memory limits, process-group termination, and per-job Docker cleanup bound web work. Defaults and overrides are documented in `docs/DEPLOY_WEB.md`.

**Type:** Reliability gap — code review.

**Problem:** Java generation runs without a timeout and captures all console output in memory. Python PDF checks run directly inside the web request. Upload size is unlimited by default, and the generator has no application-level upload-count limit. A slow or unusually large job can occupy a worker or exhaust memory. The reverse proxy's timeout does not stop the underlying work.

**Proposed fix:** Set practical upload and job limits, use a bounded job queue, and run conversion and validation in separate workers with time and memory limits. Stop the worker and any child process when a job expires. Write large logs to bounded files.

**Where:** `validator_web.py:27`, `validator_web.py:907`, `validator_web.py:1056`, `validator_web.py:2560`, `deploy/nginx-thinkbox.digital.conf:15`.

### 9. Separate web requests can overwrite each other's PDFs

**Status:** Fixed. Each web generation job has its own output directory and registers only its own output files. Ordinary local CLI overwriting is unchanged.

**Type:** Bug — code review.

**Problem:** Upload staging is unique per request, but the generated PDFs go into a shared output directory with predictable names. Two people converting the same subject, or choosing the same archive name, can replace each other's files. A result page can then refer to a different request's output.

**Proposed fix:** Give each web job a separate output directory under `out/` and register the actual files belonging to that job. Keep intentional overwriting for local CLI use; isolate web requests from each other.

**Where:** `validator_web.py:1066`, `validator_web.py:1084`, `validator_web.py:1099`, `src/main/java/eapdf/EmailToPDFA.java:235`.

## Web interface and deployment

### 10. The generated PDF links do not work

**Type:** Bug — reproduced.

**Status:** Fixed by the registered-download handling described in issue 2. Download IDs resolve to their registered PDF, including files in nested output directories. Regression tests check successful downloads and reject unknown IDs, missing files, and paths outside the output directory.

**Problem:** Result pages link to `/generated/<random-id>`, but the download handler treats that ID as a filename and ignores the ID-to-file registry. A registered ID returned HTTP 404. The handler also cannot serve registered files in output subdirectories.

**Proposed fix:** Resolve the ID through the job registry, verify access and file existence, and serve that exact file. Test both the default output directory and a nested output directory.

**Where:** `validator_web.py:952`, `validator_web.py:2608`.

### 11. Validation report links can fail when another server worker handles them

**Status:** Fixed. Atomic, private on-disk capability records are shared across workers and survive restarts. The same registry design covers generated downloads and expiration.

**Type:** Bug — code review.

**Problem:** Report IDs are stored only in the Python process that created them. The supplied deployment starts two worker processes. A report download handled by the other worker cannot find its ID, and restarting the service loses every registered report link.

**Proposed fix:** Store job and report records somewhere all workers can read, such as a small database. Make registration, expiration, and download lookup use that shared store.

**Where:** `validator_web.py:26`, `validator_web.py:840`, `validator_web.py:2614`, `deploy/validator-web.service:12`.

### 12. Links and form submissions ignore the deployed URL prefix

**Status:** Fixed. Flask URL generation supplies the current deployment prefix for navigation, forms, downloads, and report links. Proxy-prefix regression tests cover both pages.

**Type:** Bug — reproduced for report URLs; code review for navigation and forms.

**Problem:** The documented deployment serves the app under `/ea-pdf-validator/`, but links and the generator form use root paths such as `/generate/local` and `/validation-report/...`. They lead outside the application's published location. Setting a forwarded prefix does not change these hardcoded strings.

**Proposed fix:** Generate application URLs with Flask's routing helpers so they include the current deployment prefix. Test page navigation, uploads, and downloads with the documented proxy setup.

**Where:** `validator_web.py:852`, `validator_web.py:958`, `validator_web.py:1559`, `validator_web.py:1567`, `validator_web.py:2471`.

### 13. Generated files and validation reports have no expiration

**Status:** Fixed. Web links expire after the configured retention period. Request cleanup and the supplied hourly timer remove expired job files and abandoned extraction directories without pruning local CLI archives. Pages disclose retention and private responses disable caching.

**Type:** Reliability and privacy gap — code review.

**Problem:** Uploaded PDFs are normally deleted after validation, but HTML reports, generated PDFs, logs, and registry entries have no cleanup schedule. Reports can contain email subjects, addresses, filenames, and other private content. Repeated jobs also consume disk space indefinitely.

**Proposed fix:** Define a retention period for web jobs. Delete their files and registry entries together when that period ends, including abandoned jobs. Tell users how long their results remain available. Keep local archival outputs outside automatic web cleanup.

**Where:** `validator_web.py:25`, `validator_web.py:840`, `validator_web.py:942`, `validator_web.py:2550`, `validate_eapdf.py:815`.

### 14. An interrupted PDF upload leaves its partial file behind

**Status:** Fixed. A whole request staging directory is registered before saving and removed even when a save fails after writing some bytes. A regression test reproduces the interrupted save.

**Type:** Bug — reproduced.

**Problem:** A staged PDF is added to the cleanup list only after saving finishes. If saving writes part of the file and then fails, cleanup does not know about it. A simulated failed save left one file in the temporary upload directory.

**Proposed fix:** Register the destination for cleanup before starting the save, or put each validation request in a temporary directory that is removed as a whole. Test a save that fails after writing some bytes.

**Where:** `validator_web.py:2557`, `validator_web.py:2566`.

### 15. The web generator does not accept mailbox files supported by the CLI

**Type:** Functionality gap — code review.

**Status:** Fixed. Web uploads and the file picker accept .mbox alongside the existing formats. Tests cover mailbox uploads using single-message and archive profile commands, while retaining upload path restrictions.

**Problem:** The CLI and README support `.mbox`, but web uploads accept only `.eml`, `.msg`, and `.pst`. Users cannot use the advertised mailbox workflow through the web interface.

**Proposed fix:** Add `.mbox` to web upload validation and the file-picker text, then test single-message and archive conversions from an uploaded mailbox. Keep the existing path restrictions.

**Where:** `validator_web.py:1060`, `validator_web.py:1093`, `src/main/java/eapdf/EmailToPDFA.java:100`.

### 16. The service file does not use the virtual environment described in the deployment guide

**Status:** Fixed. The service explicitly starts the checkout's virtual-environment Gunicorn and includes Java/Docker command paths. The deployment guide documents dependencies, permissions, timeout alignment, and the cleanup timer. Installing and starting the service remains a deployment step.

**Type:** Deployment bug — code review.

**Problem:** The guide installs dependencies in `.venv`, but the service starts `/usr/bin/env gunicorn` without adding that environment to its search path. On a machine following those instructions alone, the service can fail to find Gunicorn or run a different installation with missing dependencies.

**Proposed fix:** Point the service at the intended virtual environment's Gunicorn executable and document any required Java and veraPDF setup. Verify the service starts without an interactive shell or an already activated environment.

**Where:** `deploy/validator-web.service:12`, `docs/DEPLOY_WEB.md:9`.

## Email reading and preservation

### 18. Some valid inline images are never collected

**Type:** Bug — code review.

**Status:** Fixed. MIME parts with Content-ID and no filename or disposition are retained as inline resources. Body selection and attachment counts stay separate; a regression test verifies an unnamed image.

**Problem:** An image with an inline image ID (`Content-ID`) but no filename or disposition is skipped by the EML attachment collector. Email HTML can reference that image, but the renderer never receives its bytes. Original non-isolated source emails still retain the MIME part; the visible PDF can lose the image.

**Proposed fix:** Collect related resources identified by `Content-ID` even when optional filename and disposition headers are absent. Keep body parts separate from image resources and preserve attachment-count rules. Add a related-message fixture with an unnamed inline image.

**Where:** `src/main/java/eapdf/EmlReader.java:248`, `src/main/java/eapdf/EmlReader.java:259`.

### 19. Different inline images with the same filename can display as the same image

**Type:** Bug — code review.

**Status:** Fixed. Inline resource filenames use the image ID and content checksum, so different images sharing an original filename cannot overwrite each other's renderer bytes. The original filename stays in attachment metadata.

**Problem:** Inline resources are assigned temporary names from their original filenames. Two different image IDs both named `image.png` receive the same file URI and the same entry in the renderer's byte map. The later image replaces the earlier one, so both image references can show the later image.

**Proposed fix:** Give each inline resource a unique internal name based on its image ID or content hash. Preserve the original filename only as descriptive metadata. Test two visibly different images sharing a filename.

**Where:** `src/main/java/eapdf/PdfMailWriter.java:3446`, `src/main/java/eapdf/PdfMailWriter.java:3509`.

### 20. Empty attachments disappear from counts and reconstructed emails

**Type:** Bug — code review.

**Status:** Fixed. Empty ordinary attachments are kept by the EML, MSG, and PST reader paths and by source-email reconstruction. Their zero size, attachment count, and empty-file checksum are retained without changing the PDF embedding policy. Tests cover EML reconstruction and a generated MSG with a genuine zero-byte attachment.

**Problem:** Ordinary zero-byte attachments are discarded by the EML, MSG, and PST readers, and source reconstruction skips them too. An empty file is still a real attachment. Counts and attachment metadata become inaccurate, and a reconstructed PST email loses the attachment entirely.

**Proposed fix:** Distinguish an empty attachment from missing attachment data. Keep its filename, media type, zero size, and checksum, and include it in reconstructed emails and counts. Keep the existing PDF embedding policy.

**Where:** `src/main/java/eapdf/EmlReader.java:266`, `src/main/java/eapdf/MsgReader.java:86`, `src/main/java/eapdf/PstReader.java:191`, `src/main/java/eapdf/Common.java:355`.

### 21. PST recipient recovery stops too early

**Type:** Bug — code review.

**Status:** Fixed. PST To, Cc, and Bcc groups are recovered separately when each is missing. Existing header-derived groups and raw headers are retained, and recovered recipients are deduplicated within their group.

**Problem:** The PST recipient table is used only when To, Cc, and Bcc are all empty. If the transport headers contain To but omit Cc or Bcc, those missing recipients are never recovered from the PST's own recipient table.

**Proposed fix:** Recover each missing recipient group separately and remove duplicates within that group. Retain the original raw headers while keeping the structured recipient lists complete. Test a message whose headers omit Bcc but whose recipient table contains it.

**Where:** `src/main/java/eapdf/PstReader.java:46`, `src/main/java/eapdf/PstReader.java:89`.

### 22. PST exports can use the delivery date as the sent date

**Type:** Bug — code review.

**Status:** Fixed. PST display and sent metadata use the same date: a valid transport Date header, then submission time, then delivery time. Original raw Date headers remain unchanged, including invalid values that need reporting.

**Problem:** The display date always comes from delivery time. When the transport Date header is absent, sent-date metadata also comes from delivery time, even though the PST library exposes client submission time. Sent-folder messages can have a useful submission time and no delivery time, while delayed messages can show inconsistent dates in different parts of the output.

**Proposed fix:** Choose a sent date consistently: use a valid original sent date, then the PST submission time, and use delivery time only as a documented fallback. Keep received and sent dates distinct.

**Where:** `src/main/java/eapdf/PstReader.java:58`, `src/main/java/eapdf/PstReader.java:112`.

### 23. A recovered PST Message-ID can be erased

**Type:** Bug — code review.

**Status:** Fixed. An empty dedicated PST Message-ID no longer erases a recovered transport-header value. A nonempty dedicated value retains precedence; a disagreement records both values in a warning.

**Problem:** Transport-header parsing can recover a valid Message-ID, but the main reader then replaces it unconditionally with the dedicated PST property. If that property is empty, the recovered identifier is lost.

**Proposed fix:** Replace the recovered value only when the dedicated property supplies a usable identifier. Define which value wins when both exist and differ, and retain enough information to report that disagreement.

**Where:** `src/main/java/eapdf/PstReader.java:61`, `src/main/java/eapdf/PstReader.java:119`.

### 25. Folder paths and Gmail labels are stored in a format that changes their meaning

**Type:** Bug / data-model gap — code review.

**Status:** Fixed. PST folder paths and Gmail label memberships have a separate structured list instead of relying on the comma-separated display field. Grouping preserves commas and original identities, and colliding sanitized display names receive distinct suffixes and folder IDs. Legacy display-only records keep their existing interpretation. Tests cover comma-bearing labels and distinct folders that sanitize to the same name.

**Problem:** A comma-separated string represents both a list of Gmail labels and a single PST folder path. A folder or label containing a comma is split into several groups. Replacing path separators and other characters with underscores can also merge different folder names.

**Proposed fix:** Store folder identity and label membership as separate structured values. Sanitize only the final display or file name, and retain a distinct internal identity when display names collide.

**Where:** `src/main/java/eapdf/Models.java:25`, `src/main/java/eapdf/GmailDownloadToPdf.java:195`, `src/main/java/eapdf/PdfCollectionSupport.java:138`.

### 26. Dates are sorted as text instead of actual times

**Type:** Bug — code review.

**Status:** Fixed. Collection and monthly ordering compares parsed times, including time-zone offsets and raw email Date headers. Invalid or absent dates sort last in ascending order, followed by the existing subject and Message-ID tie breakers. The writer uses the shared collection sorter.

**Problem:** Collection ordering compares date strings. The usual Java-generated UTC dates sort correctly, but the fallback compares raw email Date headers, which often begin with a weekday. Dates with different time-zone offsets also need time conversion before comparison. The methods' chronological-order promise does not hold for these inputs.

**Proposed fix:** Parse the best available date into a time value and compare those values. Use an explicit rule for missing or invalid dates, followed by stable tie breakers.

**Where:** `src/main/java/eapdf/PdfCollectionSupport.java:153`, `src/main/java/eapdf/PdfCollectionSupport.java:460`, `src/main/java/eapdf/PdfMailWriter.java:3185`.

### 27. Reply relationships are missing from generated metadata

**Type:** Functionality gap — code review.

**Status:** Fixed. EML, MSG, and PST readers retain reply headers. Reconstructed source emails retain In-Reply-To and References, and both single-message and aggregate XMP include the supported In-Reply-To values as an unordered list. Missing Message-IDs are still not invented.

**Problem:** The shared email model has no In-Reply-To or References fields, so readers cannot carry these relationships into generated metadata or reconstructed PST emails. The validator already looks for In-Reply-To when the source contains it.

**Proposed fix:** Read and retain reply identifiers without replacing or inventing Message-IDs. Write the supported reply metadata and preserve the headers in reconstructed source emails. Test a short email conversation.

**Where:** `src/main/java/eapdf/Models.java:24`, `src/main/java/eapdf/Common.java:338`, `src/main/java/eapdf/PdfMailWriter.java:1928`, `validate_eapdf.py:3681`.

### 28. Custom Gmail label names are not resolved before requesting messages

**Type:** Bug / usability gap — code review.

**Status:** Fixed. Gmail selection resolves exact label names through the loaded ID/name map while continuing to accept actual IDs and all-mail aliases. Unknown or ambiguous names produce an error. A complete matching label name takes precedence over comma splitting.

**Problem:** The program loads a mapping between Gmail label IDs and names, but uses it only for display. Values entered in the folder field are passed unchanged to the API's label-ID field. A visible custom label name such as `Projects` is not converted to its actual ID.

**Proposed fix:** Resolve entered label names using the mapping already loaded. Continue accepting actual label IDs and the existing all-mail aliases. Give a clear error for an unknown or ambiguous label.

**Where:** `src/main/java/eapdf/GmailDownloadToPdf.java:56`, `src/main/java/eapdf/GmailDownloadToPdf.java:159`, `src/main/java/eapdf/GmailDownloadToPdf.java:218`, `docs/GMAIL.md` folder/label behavior.

### 30. A month containing one email stops the monthly archive tool

**Type:** Bug — code review and reproduced by regression test.

**Status:** Fixed. At the user's request, one-message months produce `YYYY-MM_1s.pdf` using the single-message writer and preserve the original source email. Months with two or more messages continue producing `YYYY-MM_1m.pdf`. Later months continue processing, and the final summary reports archive and single-message output counts separately. The regression failed before the fix and passes afterward, checking profile metadata, visible message bodies, unchanged embedded source bytes, filenames, later-month output, and summary counts.

**Problem:** The monthly tool always requests profile `1m`, but that writer requires at least two emails. A month with one message throws an error and prevents later months from being processed.

**Proposed fix:** Decide explicitly how to handle one-message months: produce an appropriately named single-message PDF or report and skip the month. Continue processing other months and summarize any skipped outputs. Do not label a one-message PDF as `1m`.

**Where:** `src/main/java/eapdf/MonthlyFilenameArchiveToPdf.java:51`, `src/test/java/eapdf/MonthlyFilenameArchiveTests.java:18`.

## PDF features and preservation metadata

### 31. Collection output ignores the Finding Aid and Branding switches

**Type:** Bug — code review.

**Status:** Fixed. Collections honor Finding Aid and Branding settings. Disabling the finding aid keeps a small required front-matter page, without the optional index, embedded finding-aid PDF, or related spreadsheet. Both collection profiles and their message children are tested with these settings disabled.

**Problem:** Archive output checks whether the finding aid is enabled, but collection output always builds a finding-aid front document. It also draws branding without checking the branding switch. The desktop controls can therefore promise that these features are disabled while a collection still includes them.

**Proposed fix:** Apply the same configuration checks in collection generation. Keep any required collection front matter, but distinguish that requirement from the optional finding aid and branding. Test the desktop overrides for all output profiles.

**Where:** `src/main/java/eapdf/EapdfGui.java:375`, `src/main/java/eapdf/PdfMailWriter.java:1099`, `src/main/java/eapdf/PdfMailWriter.java:1196`, `src/main/java/eapdf/PdfMailWriter.java:1203`.

### 32. PREMIS can merge separate emails or files into one preservation record

**Type:** Bug — code review.

**Status:** Fixed. PREMIS email objects and conversion-event links use Mail_GUID. Embedded file identities include their owning email, content checksum, and original filename, so repeated Message-IDs and filenames do not merge distinct records. Original Message-IDs remain descriptive identifiers.

**Problem:** PREMIS identifies emails by Message-ID or a short hash of subject and date. It identifies embedded files by filename alone. Duplicate Message-IDs, repeated subjects and dates, or different files sharing a filename can be recorded as one object even though the conversion correctly assigned separate internal email IDs.

**Proposed fix:** Use each email's internal Mail_GUID as its preservation identity. Identify file occurrences using their owning email and content hash, while keeping original names and Message-IDs as descriptive fields. Use the same identities in conversion-event links.

**Where:** `src/main/java/eapdf/PremisBuilder.java:95`, `src/main/java/eapdf/PremisBuilder.java:120`, `src/main/java/eapdf/PremisBuilder.java:158`.

### 35. Output replacement can leave a damaged PDF after an interruption

**Type:** Reliability improvement — code review.

**Status:** Fixed. PDFs, spreadsheets, and PREMIS sidecars are written to temporary sibling files before replacement. Collection repairs finish on temporary files before the destination changes. Failed writes remove their temporary files and leave the earlier output intact. Existing local overwrite behavior remains; atomic replacement is used where supported. Separate output files are not a single transaction.

**Problem:** PDFs and sidecars are written directly to their final locations. An interrupted write can replace a good existing archive with a partial file. Collection generation also reopens and rewrites the saved PDF during its repair step.

**Proposed fix:** Finish and check each output in a temporary file next to its destination, then replace the destination atomically where supported. Complete collection repairs before replacement. Preserve the existing decision to overwrite local outputs intentionally.

**Where:** `src/main/java/eapdf/PdfMailWriter.java:929`, `src/main/java/eapdf/PdfMailWriter.java:1153`, `src/main/java/eapdf/PdfCollectionSupport.java:333`, `src/main/java/eapdf/PdfFindingAidSupport.java:252`.

## Validator correctness and coverage

### 36. Archive header comparisons inspect only the first source email

**Status:** Fixed. Each email's metadata and attachments are compared within a matched source. Duplicate Message-IDs do not identify an email uniquely; ambiguous matches produce INCOMPLETE. Supplied headers, dates, sizes, counts, and checksums are checked, while optional fields remain optional.

**Type:** Coverage bug — code review.

**Problem:** Required sent dates and internal IDs are checked across the email records, but detailed source-header comparisons use only the first embedded source email. Some address helpers collect values across the whole document. A later email's incorrect headers can be missed, and values from several emails can be compared against just one email.

**Proposed fix:** Match each metadata record to its source email and perform header, date, subject, size, and attachment comparisons within that match. Report when a match cannot be established rather than silently using the first source. Do not assume original Message-IDs are unique.

**Where:** `validate_eapdf.py:3477`, `validate_eapdf.py:3595`, `validate_eapdf.py:3892`, `validate_eapdf.py:3965`.

### 37. Attached emails are counted differently by the reader and validator

**Status:** Fixed. An attached RFC 822 message counts once and retains its decoded original MIME bytes for size/checksum checks. Unit and six-profile cross-language tests include an attached email with another file inside it.

**Type:** Coverage bug — code review.

**Problem:** The Java reader correctly keeps an attached EML as one complete attachment. The Python validator walks into attached messages, skips multipart objects, and counts their inner files as attachments of the outer email. It can miss the attached EML itself and compare the wrong counts and filenames.

**Proposed fix:** Treat an attached email as one attachment at its parent's level. Validate its contents separately if needed. Add a cross-language fixture containing an attached email with another attachment inside it.

**Where:** `src/main/java/eapdf/EmlReader.java:230`, `validate_eapdf.py:3800`, `validate_eapdf.py:3817`, `validate_eapdf.py:3914`.

### 38. Impossible dates can pass, while a bad time-zone offset can interrupt checks

**Status:** Fixed. Calendar values and timezone ranges are validated, including leap days and valid midnight notation. Invalid PDF offsets return a failed comparison instead of raising out of the parser.

**Type:** Bug — reproduced.

**Problem:** A sent date of `2026-99-99T99:99:99Z` receives a passing date-format finding because validation checks only its text pattern. Separately, parsing a PDF date with an offset of `+99'00'` raises an exception before the parser's error handler, interrupting the remaining checks for that PDF.

**Proposed fix:** Check both the allowed text form and whether it represents a real date and time. Validate offset ranges inside the error-handling block. Report invalid values against the relevant date rule and continue other checks.

**Where:** `validate_eapdf.py:1596`, `validate_eapdf.py:1613`, `validate_eapdf.py:4847`.

### 39. PDFs with the same filename overwrite each other's validation reports

**Status:** Fixed. Default CLI report filenames include a fresh identity, and web reports live in separate job directories. Explicit user-selected CLI report paths retain their existing behavior.

**Type:** Bug — reproduced at report-path selection.

**Problem:** Default HTML report names use only the PDF's filename. Validating `/one/archive.pdf` and `/two/archive.pdf` selects the same report path. The later run replaces the earlier report, and older report links can display the wrong findings.

**Proposed fix:** Give reports a unique job identity or include a stable source-path identifier in their storage names. Keep the friendly PDF filename in the report itself. Test same-name files from different directories and simultaneous jobs.

**Where:** `validate_eapdf.py:815`, `validator_web.py:840`.

### 40. Batch summaries turn incomplete validation into failure

**Status:** Fixed. Text and JSON report FAIL only when a file fails, otherwise INCOMPLETE when any file is incomplete. Separate counters and CLI regression tests cover both states. Non-passing batches still exit nonzero.

**Type:** Bug — code review.

**Problem:** Individual PDFs distinguish PASS, FAIL, and INCOMPLETE, but text and JSON batch summaries label every non-passing batch as FAIL. A batch with only incomplete checks is therefore described as containing failed files, contrary to the documented status distinction.

**Proposed fix:** Report FAIL when any file fails, INCOMPLETE when none fail but at least one is incomplete, and PASS only when all pass. Count failed and incomplete files separately. Keep a nonzero exit code for results that are not fully passing.

**Where:** `validate_eapdf.py:5025`, `validate_eapdf.py:5030`, `validate_eapdf.py:5091`, `validate_eapdf.py:5105`.

### 41. Some content checks search raw PDF bytes instead of reading PDF instructions

**Status:** Fixed. Parsed instructions and nesting state drive text and tag checks, including named properties, content arrays, nested sequences, Form XObjects, and marked-content associated files. Strings and comments cannot impersonate instructions.

**Type:** Validation improvement — code review.

**Problem:** Several checks use text searches or regular expressions to find text objects and tagged content. They can mistake text inside a PDF string for an instruction, miss tags stored in resource dictionaries, and stop at the wrong end marker when content is nested.

**Proposed fix:** Read parsed PDF instructions and maintain the nesting state of tagged content. Resolve named properties and inspect referenced drawing objects where required. Add tests where instruction-like text occurs inside an ordinary displayed string.

**Where:** `validate_eapdf.py:1857`, `validate_eapdf.py:1864`, `validate_eapdf.py:1882`, `validate_eapdf.py:2511`.

### 42. Collection children and complete tag-to-page connections are still unverified

**Status:** Fixed. Contained email PDFs receive independent PDF/A and EA-PDF checks, with nested results propagated to the parent and bounded recursion, occurrence count, and bytes. MCIDs, MCRs, OBJRs, parent pointers, declared pages, and ParentTree entries are cross-checked in both directions. Unavailable or budget-limited checks remain INCOMPLETE; genuine failures remain FAIL.

**Type:** Documented functionality gap.

**Problem:** The validator explicitly leaves independent checks of embedded child PDFs and complete tag-to-page connections unfinished. Collections and tagged outputs therefore cannot establish full conformance through the current validator, even when all implemented checks pass.

**Proposed fix:** Validate each contained email PDF with both the PDF/A and EA-PDF checks, and include its result in the parent report. Check that page markings, structure elements, and the parent lookup tree agree in both directions. Limit recursion and total extracted bytes. Keep INCOMPLETE until those checks are actually implemented.

**Where:** `validate_eapdf.py:4932`, `docs/VALIDATOR_COVERAGE.md`.

## CLI, build, configuration, and maintenance

### 44. Invalid command arguments are not reported consistently

**Type:** Bug / usability improvement — code review.

**Status:** Fixed. All three Java command parsers reject unknown switches, missing values, and extra positional arguments before doing conversion or contacting Gmail. Existing profile names and numeric ranges are validated with short user-facing errors. Existing switch names and valid invocations are unchanged.

**Problem:** Local conversion treats unknown switches and switches missing values as positional input. Gmail silently ignores unrecognized arguments. Invalid numeric values can escape as stack traces, and negative values do not receive a clear explanation. A typo can therefore change the input or selection unexpectedly.

**Proposed fix:** Validate the existing switches, required values, accepted profiles, and numeric ranges before doing work. Reject unknown switches and extra positional arguments with a short usage error. Preserve existing switch names and valid invocations.

**Where:** `src/main/java/eapdf/EmailToPDFA.java:50`, `src/main/java/eapdf/GmailDownloadToPdf.java:231`, `src/main/java/eapdf/MonthlyFilenameArchiveToPdf.java:75`.

### 45. Parallel conversion gives the same inputs different output names on later runs

**Type:** Reliability improvement — code review.

**Status:** Fixed. Parallel conversion reserves message numbers in input order before scheduling rendering. It keeps only the configured number of input batches in flight, preserves concurrent rendering, and produces the same source-to-filename mapping on repeated runs.

**Problem:** The global output number is assigned after each input finishes parsing. Thread timing decides which input receives which number. Repeating the same conversion can change filenames and which earlier files are replaced, making exports harder to compare or resume.

**Proposed fix:** Assign output identities in a stable input-and-message order before concurrent rendering, or derive them from a stable source identity. Keep concurrency for the expensive work. Test repeated parallel runs for a consistent source-to-output mapping.

**Where:** `src/main/java/eapdf/EmailToPDFA.java:187`, `src/main/java/eapdf/EmailToPDFA.java:219`, `src/main/java/eapdf/EmailToPDFA.java:235`.

### 47. Some configuration settings are accepted but have no effect

**Type:** Configuration gap — code review.

**Status:** Fixed. Configuration loading checks version 1, setting names, nesting, and value types. Unknown keys warn with their full names; unavailable experimental accessibility settings warn when enabled. Documentation and the decision-tree script no longer advertise the nonexistent PREMIS writeSidecar setting.

**Problem:** The three experimental accessibility settings are declared but never used outside configuration initialization. The configuration version is not checked, and unknown JSON keys are silently ignored. The decision-tree script also advertises a PREMIS `writeSidecar` setting that does not exist. Users can believe they changed output behavior when nothing changed.

**Proposed fix:** Validate the configuration version and supported keys. Implement advertised settings or identify them clearly as unavailable. Warn about misspelled or unsupported settings, and make configuration documentation match the loader.

**Where:** `src/main/java/eapdf/EapdfConfig.java:41`, `src/main/java/eapdf/EapdfConfig.java:47`, `src/main/java/eapdf/EapdfConfig.java:74`, `scripts/generate_decision_tree.py:280`.

### 48. Collection behavior has duplicate implementations that already differ

**Type:** Maintenance improvement — code review.

**Status:** Fixed. Collection rendering, grouping, sorting, schemas, embedding, and repair run through PdfCollectionSupport. Existing writer entry points delegate there, and unused duplicate builders were removed. Tests cover the compatibility entry points and the six profile conversion paths.

**Problem:** Collection generation, sorting, schemas, and embedding are implemented in both `PdfMailWriter` and `PdfCollectionSupport`. For example, one top-level schema builder includes the required email fields while another omits them. Fixing one implementation does not ensure that another caller receives the same correction.

**Proposed fix:** Check the callers, route collection behavior through one implementation, and remove unused copies. Keep the existing shared document-part builder as the single place for that structure. Test public conversion paths after consolidation.

**Where:** `src/main/java/eapdf/PdfMailWriter.java:1638`, `src/main/java/eapdf/PdfMailWriter.java:3082`, `src/main/java/eapdf/PdfCollectionSupport.java:224`, `src/main/java/eapdf/PdfCollectionSupport.java:341`.

### 50. Passing unit tests do not establish that generated profiles pass validation

**Status:** Fixed for the regression workflow described here. `tests/test_generated_profiles.py` generates all six profiles and validates those exact outputs, with a nested email attachment, duplicate IDs, case-variant folders, missing optional headers, configuration overrides, and a deliberately corrupted date. Enable it with `EAPDF_GENERATOR_CLASSPATH`; independent PDF/A validation runs when Docker is usable. The normal suite clearly reports a skip when no generator classpath is supplied.

**Type:** Testing gap — demonstrated by review results.

**Problem:** All 33 Java tests and 29 Python tests pass, yet fresh collections and fresh emails with attachments fail checks in the same repository. Separate tests of the writer and validator do not catch disagreements between them. Saved examples also include failures that the normal test commands do not surface.

**Proposed fix:** Add an automated workflow that generates all six profiles and runs the validator on those exact outputs. Include attachments, nested emails, missing headers, multiple folders, configuration changes, and corrupted-input cases. Run independent PDF/A checks when available and preserve INCOMPLETE when they are unavailable. Regenerate saved examples only after their expected results are understood.

**Where:** `src/test/java/eapdf/`, `tests/test_preservation_safety.py`, `examples/`, `README.md` testing instructions.

## Additional rendering gaps

### 52. Encrypted email bodies can produce a normal-looking PDF without their message text

**Type:** Functionality and reporting gap — code review.

**Status:** Fixed. Encrypted and unsupported EML body formats produce an explicit visible notice and a warning instead of a misleading empty body. No decryption is attempted. Source bytes remain unchanged in non-isolated output; supported alternatives still win over unsupported ones. Intentionally empty supported text messages do not receive these warnings.

**Problem:** Body extraction handles plain text, HTML, and multipart containers, but has no handling for encrypted message bodies such as S/MIME enveloped content. Such a message can reach PDF generation with an empty extracted body and no clear explanation that its content was unreadable. An intentionally empty email and an unsupported encrypted body are treated alike.

**Proposed fix:** Detect encrypted and unsupported body formats before rendering. Report that the body cannot be converted, preserve the original bytes where the profile permits, and make the result visibly different from a fully rendered email. Add decryption only as a separately designed capability with explicit key handling.

**Where:** `src/main/java/eapdf/EmlReader.java:137`, `src/main/java/eapdf/EmlReader.java:151`, `src/main/java/eapdf/PdfMailWriter.java:580`.

## Earlier verification history

### Attachment-source reference follow-up

Latest verification (2026-10-16): 34 Java tests and 87 Python tests pass, with six-profile generation/validation integration enabled. All six saved samples and all 142 contained email PDFs pass independent PDF/A validation with no local EA-PDF failures. The regenerated `output/Agenda and timeline slides_000001.pdf` and `examples/sample_1s.pdf` receive overall `PASS`. The Agenda PDF contains exactly one embedded file: the unchanged original source EML; its PowerPoint attachment is verified inside that email. The other saved samples remain `INCOMPLETE` where original attachment contents are absent or source addresses are malformed. These automated checks are not independent EA-PDF certification. Compilation and testing used `/tmp`; project `target/` and debug directories were not changed. Deployment configuration has not been installed into the running service.


The following records describe earlier stages of the fixes, not the latest output status. The source-email reference behavior and verification results above supersede earlier references to zero-byte markers in non-isolated outputs.

Implementation verification for issues 1–5: 34 Java tests and 41 Python tests pass. The six email profile samples were regenerated from the existing `in/email/test3.eml` and `in/email/test.pst` inputs. All six pass independent veraPDF validation and have no local EA-PDF failures. Overall EA-PDF results remain `INCOMPLETE` because of the documented remaining coverage gaps and unverified native contents in empty attachment placeholders. Java compilation and testing used `/tmp`; project `target/` and debug directories were not changed.

Validator follow-up: issues 36–42 and the related web reliability/deployment issues 8, 9, 11–14, and 16 are fixed. Issue 50 now has an opt-in six-profile generator/validator integration test. All 71 Python tests pass with integration enabled, including nested attachments, duplicate Message-IDs, missing optional headers, folder case variants, configuration changes, and corrupted metadata. The six saved samples and all 142 contained email PDFs pass independent PDF/A validation with no EA-PDF failures. Overall sample results remain `INCOMPLETE` for empty attachment placeholders and, where present, malformed source addresses, not because child/tag checking is missing. This follow-up does not change the Java generator, saved samples, `target/`, or debug directories. Deployment configuration is updated but has not been installed into the running service.

DPart diagnostic follow-up (2026-10-16): the page check previously reported only whether a `/DPart` entry existed, without distinguishing a missing link from a missing content-set range. It now checks both directions: every page must be covered by a reachable content-set range, and its link must reference a leaf whose range includes that page. Findings name the actual 1-based PDF page and show page numbers separately from the affected-page count. In `output/bfo.pdf`, PDF page 2 lacks both the link and range coverage; it is not page 1 or missing front matter. Eight self-contained regression tests cover these distinctions, malformed links, multi-page ranges, bounded diagnostics, and web/standalone report rendering. All 79 Python tests pass with six-profile generation/validation integration enabled, and all six saved samples pass the updated DPart check. Generator code and original PDFs are unchanged.

## Original review checks (2026-10-15)

These checks preceded the fixes and sample regeneration. Their failures, test counts, and unavailable checks are historical results.

- **Python:** `python3 -B -m unittest discover -s tests -v` — 29 tests passed.
- **Java:** Compiled all current main and test sources into `/tmp`, using the existing application jar for dependencies and the local JUnit libraries. Ran the existing tests through JUnit — 33 tests passed. This checks the current sources but does not replace a fresh Maven package/shading check.
- **Fresh PDF generation:** Generated all six profiles from the repository's EML and mailbox fixtures, writing outputs only under `/tmp`. Local EA-PDF checks rejected `1c` for missing source files and source-asset metadata. The other five fixture outputs returned INCOMPLETE because of the documented remaining validation coverage.
- **Attachment reproduction:** Generated fresh `1s` and `1si` PDFs from a synthetic email with one text attachment. Both failed the attachment-annotation rule.
- **Web and date reproductions:** Confirmed the broken generated-file ID link, direct unregistered filename access, missing report URL prefix, partial-upload cleanup failure, same-name report-path collision, impossible sent date passing its format check, and invalid PDF time-zone offset raising an exception.
- **Existing examples:** Ran local EA-PDF checks against the six main checked-in profile samples; all six failed at least one check. Their results are separate from the fresh-generation results because saved samples can be older than the current code.
- **Limits of these checks:** Did not run Docker/veraPDF, a live Gmail export, browser accessibility testing, a deployed multi-worker server, or `mvn package`. The PDF results above describe local EA-PDF checks, not independent PDF/A or EA-PDF certification. Project `target/` and debug directories were left untouched.
