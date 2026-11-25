# EA-PDF specification review

The [questions for specification experts](EA_PDF_Specification_Questions_for_Experts.docx)
were reviewed on 23 November 2026 against EA-PDF 1.0, public issue discussions,
and the Main creator and validator release **2.4.0-beta.5**. The brief records the
published requirements, current handling, and proposed decisions for the
specification authors.

The [historical rule review for specification authors](EA_PDF_Rule_Review_for_Specification_Authors.docx)
preserves the earlier rule-by-rule analysis and source exhibits. Its maintenance
script, `scripts/sync_eapdf_author_review.py`, needs the original local review
inputs.

To regenerate the expert questions from a clean checkout, install `python-docx`
and `PyMuPDF` in the Python environment and run the following from the repository
root. The generator reads [the reviewed question data](expert-questions.json)
and verifies the checksum of the [pinned specification](../reference/EA-PDF-v1.pdf).

```sh
python3 scripts/build_eapdf_expert_questions.py
```

Use [current status](../PROJECT_STATUS.md), [coverage](../VALIDATOR_COVERAGE.md)
and [architecture](../ARCHITECTURE.md) for the current project. Keep new
correspondence private and contributors anonymous in first-party public prose.
