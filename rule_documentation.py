"""Render current rule conditions without presenting paraphrases as quotations."""
from html import escape


def specification_context_html(rule):
    blocks = []
    if rule.get("validation_scope", {}).get("prerequisite") == "verapdf_pdfa_pass":
        blocks.append("<p><strong>Starting requirement:</strong> The finished file must pass "
                      "veraPDF for an EA-PDF-permitted PDF/A level. This rule covers an additional "
                      "EA-PDF condition; its result alone does not establish overall conformance.</p>")
    check = rule.get("validation_scope", {}).get("check", {})
    if check:
        blocks.append("<div class='rule-check'>")
        for label, key in (("This rule checks", "assertion"), ("Rule strength", "strength"),
                           ("Rule applies when", "applies_when"), ("Evidence needed for this rule", "evidence_needed")):
            if check.get(key):
                blocks.append(f"<p><strong>{label}:</strong> {escape(str(check[key]))}</p>")
        blocks.append("</div>")
    for assertion in rule.get("requirements_review", {}).get("assertions", []):
        blocks.append("<div class='spec-condition'>")
        for label, key in (("Linked requirement", "id"), ("Source strength", "strength"), ("Source scope", "scope"),
                           ("Source applies when", "applies_when"), ("Full linked requirement (paraphrase)", "assertion"),
                           ("Evidence needed", "evidence_needed"),
                           ("Authority", "authority"), ("Local interpretation", "local_interpretation")):
            value = assertion.get(key)
            if value:
                blocks.append(f"<p><strong>{label}:</strong> {escape(str(value))}</p>")
        blocks.append("</div>")
    return "".join(blocks)
