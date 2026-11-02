# PDF/A-4 revision coverage

Question: Do the installed veraPDF checks cover the 2024 draft required by
EA-PDF 1.0?

## Known facts

- EA-PDF §4, printed p.15, explicitly cites ISO/DIS 19005-4:2024 and explains
  that its embedded-file changes matter to EA-PDF.
- The retained veraPDF 1.31.18 rule files identify ISO 19005-4:2020. Their date
  labels alone do not establish whether individual draft changes are covered.
- ISO currently lists a later DIS 19005-4.2 as under development. That later
  draft does not establish the contents of the exact 2024 reference.

## How to close the question

1. Obtain lawful access to the exact 2024 draft and the 2020 edition, through
   ISO, a standards library or PDF Association access.
2. List the changed requirements. Map each to the installed veraPDF rules and
   tests, identifying any missing checks.
3. Ask veraPDF maintainers to confirm uncertain mappings. If changing EA-PDF's
   dated reference is proposed, obtain clarification from its maintainers.
4. Add positive and negative PDF cases for confirmed gaps, then rerun validation.

## Draft question for veraPDF maintainers

> EA-PDF 1.0 §4 explicitly cites ISO/DIS 19005-4:2024. We use veraPDF 1.31.18;
> its PDF/A-4, 4f and 4e XML profiles identify ISO 19005-4:2020. Does this version
> implement the 2024 draft changes, particularly those concerning embedded
> files? Which rules/tests demonstrate coverage, and which changes remain
> unsupported? Does support concern the exact 2024 draft or a later revision?

Prepared locally; no external inquiry has been sent. The question remains open.

## Evidence

- [EA-PDF 1.0 §4](../reference/EA-PDF-v1.pdf#page=22).
- [Retained veraPDF rules and hashes](verapdf-coverage/bundled-profiles.json).
- [ISO revision status](https://www.iso.org/standard/86943.html), checked 2026-10-20.
- [veraPDF rule repository](https://github.com/veraPDF/veraPDF-validation-profiles).
- [veraPDF issue tracker](https://github.com/veraPDF/veraPDF-library/issues).
