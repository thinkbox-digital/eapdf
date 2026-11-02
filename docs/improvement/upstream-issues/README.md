# EA-PDF upstream issue review — 2026-10-19

Authority: [validator_rule_text.json](../../../validator_rule_text.json), under `VALIDATION_INCOMPLETE.specification_review.upstream_issue_review`. The [generated expert view](../specification-audit/expert-review.html#upstream) and [structured dispositions](../specification-audit/upstream-issues.json) contain all **26 open/closed issues and 47 comments**, reviewed through current GitHub REST pagination. The ordinary web issue listing was stale; the review records the actual API issue states and update timestamps.

Seven reports directly concern EA-PDF: **#8, #10, #13, #14, #17, #21 and #22**. Every other issue has an explicit dependency, adjacent-resource or out-of-scope disposition. Author association, comment permalinks, update dates and content hashes preserve the basis for each decision.

## Applied corrections

- **Associated-file evidence:** [tracker #1](https://github.com/pdf-association/report-an-issue/issues/1) leads to [ISO-approved erratum 374](https://pdf-issues.pdfa.org/32000-2-2020/clause14.html#H14.13.5). Named property dictionaries use `MCAF` for `/AF /resource BDC`. The parser now reads this on pages and inside Form XObjects, retaining separate direct file specifications. It ignores MCAF under an unrelated tag or marked-content point. Existing legacy `/AF` observations remain evidence; reading them is not a generic conformance assertion.
- **Creation dates:** [#22](https://github.com/pdf-association/report-an-issue/issues/22) does not establish a new normative definition. Pinned EA-PDF 9.8 still requires the EA-PDF creation date. The validator now reports unavailable independent provenance instead of rejecting equality/ordering against source Sent or declaring provenance verified merely because dates differ. Required CreateDate presence remains checked.
- **Adobe extension declaration:** #22's proposal for “at least 5” is not adopted. The published requirement and repository-member response support exactly integer `5` and name `/1.7`. The validator now also rejects a text-string `5` or text-string BaseVersion.
- **DPM names:** [#13](https://github.com/pdf-association/report-an-issue/issues/13) is superseded by [ISO-approved erratum 86](https://pdf-issues.pdfa.org/32000-2-2020/clause14.html#H14.12.4.2), which permits names and requires valid UTF-8 after name-escape decoding. No text-string replacement is made. Exact model traversal and decoding coverage remains a dependency follow-up, not a newly claimed PASS.
- **Source quality:** [#8](https://github.com/pdf-association/report-an-issue/issues/8) is independently confirmed in the pinned publication: `/Nums` arrays in objects `335 0` and `336 0` have lengths 457 and 459. Its text remains specification evidence with a source-quality qualification; its PDF objects are not positive conformance fixtures. The source bytes and hash are preserved.

## Retained requirements and unresolved reports

- **#10:** use the normative PDF/A-4e flavour; do not invent PDF/A-4fe from the overview typo.
- **#14:** keep the published NonFullScreenPageMode requirements despite the removal proposal. Keep Table 16 asset `ContentType`/`Checksum` distinct from Table 19 attachment `Content-Type`/`CheckSum`. Compatibility aliases still need the remaining schema audit; the complaint is not permission to rename canonical properties.
- **#17:** Bcc remains a required collection schema field even when a message value is explicitly blank, as the repository-member explanation describes. Table 6's explicit Message-ID requirement remains in force. Three new expert questions cover Source-AF cardinality (#14), original-email versus embedded-PDF Size semantics (#17), and the Table 5/6 Message-ID list discrepancy (#17). There are now **11 open questions**, while the inventory remains 301 records/220 codes and six unmapped file requirements.
- **#21:** retain ISO/TS 32005 SHALL and existing role-map requirements until authoritative changes exist. Extend the existing namespace/role-map question with this report. The report reverses the first Table 9 pair compared with the pinned PDF; the pinned `Artifact → Private` row is retained. A proposal does not mandate optional PDF/UA-2 or resolve the standalone ISO/TS validation dependency.
- **#12/#23:** missing PDF/A-4 declaration-schema guidance and accessibility issues in a supplementary inclusion-rules spreadsheet are dependency limitations, not new mandatory artifacts or evidence that structure validation ran.
- **EC2/EC3 publication reports:** #7/#9/#15/#16/#19/#20 are recorded as reference/publication maintenance. Closed issues and future-release comments do not prove the local EC2 reference has been replaced. Plain extracted base text can omit errata present only as PDF annotations; applicable published correction anchors must also be reviewed.
- **#24/#25/#26:** preserve canonical reference anchors and distinguish general navigation/accessibility discussion from EA-PDF obligations. No blanket PDF/UA, Alt or QuadPoints requirement is introduced.

## Verification

[Authored regressions](../../../tests/test_upstream_eapdf_issues.py) cover the runtime changes and the retained blank-Bcc schema requirement. [Six immutable probes](../../../tests/fixtures/eapdf/upstream-issues/README.md) expose the prior date false rejections and missed MCAF/name-tree inconsistency. [Evidence](evidence.json) records source hashes, before-state behavior, test counts, profile results and actual CLI/web-worker checks.

## Continuing this review

1. Refresh `https://api.github.com/repos/pdf-association/report-an-issue/issues?state=all&per_page=100`, follow every next-page link and read every comment page. Compare each issue/comment revision with the recorded dates and hashes.
2. Read linked published corrections and distinguish ISO approval, other published authority, repository-member explanation, unresolved proposal and local interpretation. An open report may already be superseded by a published correction; a closed report may concern a future publication.
3. Update the authoritative JSON's dispositions, rule/requirement links and expert questions. Keep unchanged mandatory requirements explicit and record implementation gaps separately.
4. Add requirement-derived regressions before a runtime repair; verify actual PDF objects and applicable upstream validation. Preserve CLI/debug behavior and the requirements → validator → creator sequence established by the user.
5. Regenerate with `env -u PYTHONPATH python3 -B scripts/build_eapdf_requirements_audit.py` and check all issue, rule and requirement links. Regeneration publishes reviewed local decisions; it does not silently fetch or accept new upstream policies.
