# EA-PDF rules and source pages

Open the [two-pane page](specification-audit/rule-browser.html). It works offline.
The left pane shows the original specification; the right shows short rule summaries.

- Select a rule or scroll either pane to follow the matching source.
- Search summaries, full wording, field names or check codes.
- Page buttons open the source. **Checks** lists the check codes.
- Select **11 questions** for plain questions, reasons, current handling and source links.
- Turn **Sync panes** off to browse independently. Use ↑ / ↓ or J / K to move between rules.

The page includes **301 summaries, 224 checks and 97 original pages**. There are
249 PDF rules, 11 open questions and no unmapped mandatory/conditional PDF records.
A linked check does not prove complete coverage.

The original PDF and pinned profile evidence can be downloaded from the page.
External reference links need a connection. Table highlights use exact fields or
groups; other highlights are suggestions that need review.

[Review questions with sources](specification-audit/review-questions.md) are also
available as a document. `validator_rule_text.json` holds both the full rules and
short summaries. Repeated wording and generic audit notes are omitted from rows. Summaries do not change a rule's strength or conditions. The
Mail_ContentField → Mail_Field correction remains user-approved, not published errata.

## Regenerate

Requires PyMuPDF and Pillow; browser checks require Playwright Chromium.
Run in this order from the project root:

```sh
python3 -B scripts/build_eapdf_rule_browser.py
python3 -B docs/improvement/verify-rule-browser.py
python3 -B scripts/build_eapdf_requirements_audit.py
```

When a full rule changes, update its short summary and its basis hash too.
The generator rejects stale summaries.

[Browser checks](rule-browser-verification.json) · [Wording review](plain-language-review/README.md)
