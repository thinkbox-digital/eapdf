# EA-PDF validator requirements

Current basis, 2026-10-27: **213 runtime rules / 301 inventory records**. Edit
[`validator_rule_text.json`](../validator_rule_text.json) for runtime descriptions
and [the separate review JSON](improvement/specification-audit/validator-rule-review.json)
for inventory, interpretations and evidence. The [reexamination report](improvement/specification-audit/specification-reexamination-2026-10-27.txt)
records 52 questions and working assumptions. The creator, Python validator,
standalone Java validator and [Rule Review DOCX](../EA_PDF_Rule_Review_for_Specification_Authors.docx)
use those current decisions. Local assumptions do not amend the published standard.

Regenerate current audit views with `python3 -B scripts/build_eapdf_requirements_audit.py`
and the Word review with `python3 -B scripts/sync_eapdf_author_review.py`.
Mapping proves traceability, not complete runtime coverage or source preservation.

## Historical checkpoints

The dated counts, ownership totals and outstanding-work descriptions below record
earlier review states. They do not override the current files and report above.

**Historical review — 2026-10-19:** [Open the standalone two-pane rule browser](improvement/specification-audit/rule-browser.html): all **301 records / 220 check definitions**, **249 file records**, and all **97 original specification pages** in one offline HTML file. Selecting a rule or scrolling either pane synchronizes browsing; relevant source passages are highlighted. Exact table matches and suggested passage matches are labeled separately. [Usage and regeneration](improvement/rule-browser.md), [browser verification](improvement/rule-browser-verification.json).

**Upstream issue review — 2026-10-19:** Reviewed all **26 issues and 47 comments**, including seven direct EA-PDF reports. Applied the published MCAF correction, strict ADBE object types, and honest unavailable CreateDate-provenance reporting. All **301 tests pass with one optional skip** (302 run); all **12 profile reports PASS**; all six PDF probes pass PDF/A and match their expected EA-PDF outcomes; four actual CLI/web-worker checks agree. **11 expert/dependency questions and six unmapped mandatory/conditional file requirements remain open.** [Decisions and limitations](improvement/upstream-issues/README.md), [evidence](improvement/upstream-issues/evidence.json). Earlier checkpoints below retain their historical counts.

**V03 XMP/GUID repair — 2026-10-19:** The validator now preserves exact Mail_GUID identity across XMP, DPM, structure and attachment ownership; reads tested equivalent XMP forms; rejects URI/array/foreign-name GUID substitutions; and checks record/address array kinds and actual email cardinality. Verification: **290 broad-suite passes and one optional PREMIS skip; 272 final-source unit passes with 21 skips; all 18 focused regressions pass; all 12 normal/accessibility profile reports PASS; all 11 PDF probes and four actual CLI/web-worker checks meet expectations.** The two final scalar/cardinality guards were verified after the broad run; see the recorded boundary. Eight questions and six unmapped requirements remain open. [Evidence](improvement/validator-03-evidence.json), [implementation](improvement/validator-03/README.md).

**Authoritative rule document: [validator_rule_text.json](../validator_rule_text.json).** The specification remains the authority for requirement accuracy. The user's 2026-10-19 correction explicitly adopts `Mail_Field` for printed `Mail_ContentField`; it is recorded as a local approved interpretation, not published errata. The user also authorized validator improvements.

The [expert review](improvement/specification-audit/expert-review.html) is generated from that JSON and contains all **220 rule descriptions and 301 requirement/guidance records**, representation contracts and **eight remaining questions**. Edit the JSON and regenerate views; the historical DOCX and repair register are not competing authorities.

Custom scope remains requirements not covered by the selected veraPDF profiles. Ownership is 138 custom, 11 delegated, 11 operational and 60 mixed. Generic syntax/PDF-A prerequisites and uncovered EA-PDF semantics remain separate. Runtime routing now delegates the 11 fully upstream-owned rules. Delegated N/A rows carry separate upstream evidence and do not infer per-rule PASS. Mixed-rule fragment routing remains unfinished. [Coverage evidence](improvement/verapdf-coverage/README.md).

The V02 repair also corrects web coverage visibility and JSON-only cache invalidation. See [routing evidence](improvement/validator-02-evidence.json).

The first authorized repair enforces Table 11 immediate custom children and the FieldValue parent, retains optional custom tagging and standard explicit Documents, and fixes report attribution. New rule 220 maps the previously uncovered Table 11 row. Six other mandatory/conditional file records still have no executed mapping. Eight unrelated expert/dependency questions remain explicit. [Repair evidence](improvement/validator-01-evidence.json), [remaining questions](improvement/specification-audit/expert-questions.json), [unmapped records](improvement/specification-audit/unmapped-file-requirements.json).

Verification: 262 Python tests run, 261 pass and one optional schema check skips; 13 final focused tests pass. All twelve normal/accessibility profile reports PASS and the five-case PDF/A-valid containment corpus meets exact expectations. See the evidence for the final focused guard and full-suite source boundary. Java sources and saved samples are unchanged.

Regenerate audit views with `python3 -B scripts/build_eapdf_requirements_audit.py`. Continue scoped validator repairs and their independent acceptance, then improve the creator. Broader preservation work remains deferred.
