# Plain-language rules and review questions

The [two-pane page](../specification-audit/rule-browser.html) now shows short
summaries for all 301 rules. Each row states the requirement once, with source links and a short Checks list.
Repeated wording and generic audit notes are removed. Labels and help are shorter. The eleven questions have a dedicated
panel with reasons, current handling and source links.

The [question document](../specification-audit/review-questions.md) has the same
questions and supporting references. The original PDF and pinned veraPDF profile
evidence remain available offline from the page.

## Verification

- Sixteen offline browser checks pass, including source links, both scroll directions, question/rule jumps, downloads and mobile layout.
- Thirteen existing validator ownership tests pass.
- The requirements audit regenerates successfully.
- All requirement assertions, strengths, conditions, rule descriptions and ownership decisions are unchanged.
- The checker and Java creator code are unchanged. All eleven questions remain open.

[Browser checks](../rule-browser-verification.json) · [Change boundary](semantic-boundary.json) · [Source check](source-check.json)

The public Adobe XMP reference is from 2012. It explains array and structure
forms but does not prove equivalence to the cited 2019 ISO edition. Issue reports
remain supporting evidence, not approved changes.
