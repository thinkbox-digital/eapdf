# Validator exit checklist

**Current review:** [current-status.md](current-status.md) reconciles V84 execution and the eleven local decisions. The V23 tables below remain historical; they are not the current open-question or profile-execution counts.

Recovered thread: `01a159dd-2541-73c3-92d8-a63e0f535f7a`.
This completes its interrupted whole-validator evidence census. The authority
remains [validator_rule_text.json](../../../validator_rule_text.json).

The measured V23 cohort has 301 specification entries, 224 check codes,
177 mandatory/conditional file entries and no unmapped mandatory file entries.
It ran 487 tests: 447 passed and 40 skipped. All 202 retained custom fixtures
matched their accepted outcomes. These counts establish an evidence inventory;
they do not certify that every obligation is implemented correctly.

## Follow-up evidence

The matrices below retain their V23 census boundary. Subsequent work has now
exercised all nine missing production observations ([V24](../validator-24/README.md)),
added actual positive/negative controls for all five allowed PDF/A profiles,
recorded real ISO failure/missing/corrupt-profile transitions, and collected
502 mapped upstream predicate-reference rows with explicit present/absent
object witnesses ([upstream evidence](../upstream-profile-execution/README.md)).
[V25](../validator-25/README.md) adds universally contradicted source size/media
failures. [V26](../validator-26/README.md) corrects source parameter evidence and
known checksum/name comparisons; [V27](../validator-27/README.md) preserves exact
source filename text. Ten actual embedded/missing-font controls now address the
geometric controls' no-font-object gap across all five profiles. Creator acceptance is currently failed on V24's independently proved
operand mismatch; eleven decisions and the broader obligation-level gates
remain open. These follow-ups do not turn the historical matrices into a
complete coverage certification.

## Gates

| Gate | Required exit evidence | V23 disposition |
| --- | --- | --- |
| G1: requirements | Every clause/table obligation has reviewed strength, applicability, permitted forms, exceptions and ownership. Every custom assertion has authority. Resolve interpretation/dependency questions or explicitly bound the resulting claim. | Open: eleven questions. [Question dispositions](questions.json), [plain explanations](../specification-audit/review-questions.md). |
| G2: upstream execution | Exercise all five allowed PDF/A profiles with independent passing and failing inputs. For each of eleven delegated checks and sixty shared/custom splits, link the actual upstream predicate to exercised evidence and verify the retained EA-PDF assertion separately. Exercise required ISO execution and unavailable/failure transitions. | Open: all 29 current full-pipeline reports use PDF/A-4F; the other four profiles have static ownership evidence only in this cohort. No actual ISO failure occurs in this cohort. |
| G3: custom assertions | For each mapped automatable obligation, review an asserted positive, negative, permitted-form and unavailable-evidence case, or record why a category is inapplicable. Review recommendation warnings and companion-code failures with their actual meaning. | Open: 201 codes retain custom assertions; nine have no non-N/A production observation. The [review queues](review-queues.md) list these and codes without observed FAIL. Reached results and literal test references alone do not close a row. |
| G4: ownership and source evidence | Check message/source/asset ownership, differing candidate and occurrence assignments, shared payloads, absent originals, missing inventories and collection-child boundaries. Avoid selecting an owner from the value under test. | Open: attachment/source occurrence assignment and source-asset association remain incomplete; original-inventory completeness needs external evidence. V18–V23 establish only their documented bounded repairs. |
| G5: operational outcomes | Parsing, extraction, required upstream/schema and child-worker failures must remain distinguishable from established N/A and prevent unsupported PASS. Verify CLI and isolated-worker status agreement. | Open: targeted gates exist, but the twelve operational codes still need obligation-level closure. Unit mocks do not prove real engine or worker execution. |
| G6: acceptance boundary | Freeze inputs and expected findings; retain exact PDF bytes, hashes, real engine reports and relevant entry-point results. Explain required skips and carry every unclosed row into the published limits. | Open: the current census has 40 integration/schema skips. Historical full runs remain separate evidence; this census is not a new packaged or Java acceptance gate. |

The validator phase ends when each applicable gate has its stated evidence,
and every remaining unavailable or disputed obligation has an explicit,
reviewed limit. An external decision cannot be replaced by a passing fixture.
The creator phase remains deferred under the plan's existing sequence.

## Concrete work queue

1. Exercise the nine checks without non-N/A production observations. Seven are
   PDF/A-3 Info/XMP checks; review them together with real PDF/A-3 execution.
   Review custom operand correspondence by content identity, not just a global
   vocabulary match. Test the generic source media recommendation as WARN.
2. Add independent passing/failing upstream controls for PDF/A-3A, 3U, 4 and
   4E; retain 4F controls. Record the actual detected profile and predicate
   failures. A renamed declaration alone is not a passing control.
3. Review all shared/custom splits against actual reports and their retained
   assertions, then the remaining custom family queues. Use the 301-entry
   [obligation matrix](obligation-matrix.csv) to prevent broad code-level
   results from hiding untested clauses.
4. Continue source/attachment occurrence and source-asset ownership cases.
5. Close real operational failure transitions and required dependency gates.
6. Resolve the eleven cited questions with authoritative material or explicit
   project decisions. No external inquiry has been sent.

Steps can expose further defects. The remaining repair count and completion
percentage cannot be inferred from this census.

## Reproduction and evidence

Reproduction requires an isolated tree containing the V23 manifest inputs;
the current V24 tree intentionally differs. In that isolated tree run
`python3 -B scripts/collect_eapdf_coverage_census.py`, then
`python3 -B scripts/build_eapdf_coverage_exit_review.py`.
The collector verifies runtime, rule, test and fixture hashes before observing
production Report emissions. It separates fallback, delegation and direct
synthetic findings; mocks can still be used by individual unit tests.

- [Summary](summary.json), [input and artifact hashes](evidence.json).
- [Rule matrix CSV](rule-matrix.csv), [detailed observations](rule-matrix.json).
- [Obligation matrix CSV](obligation-matrix.csv), [JSON](obligation-matrix.json).
- [Actual pipeline profiles and reports](pipeline-evidence.json).
- [Execution census](execution-census.json).

Runtime, rules, CLI, Java and creator outputs are unchanged by this review.
Subsequent repairs must retain this V23 boundary and publish their own evidence.
