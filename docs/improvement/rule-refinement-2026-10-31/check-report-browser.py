"""Check every Finding/Specification row against an actual saved validation report."""
import argparse
from collections import defaultdict
from hashlib import sha256
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
import validator_web as web
from playwright.sync_api import sync_playwright


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    report_bytes = args.report.read_bytes()
    (args.output / "input-report.json").write_bytes(report_bytes)
    report = json.loads(report_bytes)
    rules = json.loads((ROOT / "validator_rule_text.json").read_text())
    assert report["summary"]["rulebook_sha256"] == sha256((ROOT / "validator_rule_text.json").read_bytes()).hexdigest()
    observations = defaultdict(list)
    for item in report["items"]:
        observations[item["code"]].append(item)
    with web.app.test_request_context("/"):
        rendered = web.render_page([report])
    page_path = args.output / "validation-report.html"
    page_path.write_text(rendered)
    errors, requests, checked = [], [], []
    normalize = lambda value: " ".join(value.split())
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        context = browser.new_context(offline=True, viewport={"width": 1440, "height": 1000})
        page = context.new_page()
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.on("request", lambda request: requests.append(request.url)
                if request.url.startswith(("http:", "https:")) else None)
        page.goto(page_path.resolve().as_uri())
        assert page.locator("tr.rule-row").count() == len(rules)
        for code, rule in rules.items():
            row = page.locator(f'tr.rule-row[data-rule-code="{code}"]')
            finding = row.locator("details").nth(0)
            specification = row.locator("details").nth(1)
            finding.evaluate("element => element.open = true")
            specification.evaluate("element => element.open = true")
            found = normalize(finding.inner_text())
            spec = normalize(specification.inner_text())
            assert finding.locator(".finding-occurrence").count() == max(1, len(observations[code])), code
            for item in observations[code]:
                assert normalize(item["message"]) in found, (code, item["message"])
            assert "paraphrase, not a quotation" in spec.casefold(), code
            for value in rule["validation_scope"].get("check", {}).values():
                assert normalize(str(value)) in spec, (code, "current predicate")
            for assertion in rule["requirements_review"]["assertions"]:
                for key in ("id", "strength", "scope", "applies_when", "assertion", "evidence_needed", "authority"):
                    assert normalize(str(assertion[key])) in spec, (code, key)
                if assertion.get("local_interpretation"):
                    assert normalize(assertion["local_interpretation"]) in spec, code
            if rule["validation_scope"]["prerequisite"] == "verapdf_pdfa_pass":
                assert "finished file must pass veraPDF" in spec, code
            checked.append(code)
            if code in ("ASSET_NUMBERMESSAGES_TYPE", "ASSET_NUMBERMESSAGES_MATCH_RENDERED", "EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED"):
                row.screenshot(path=str(args.output / (code.lower() + ".png")))
            finding.evaluate("element => element.open = false")
            specification.evaluate("element => element.open = false")
        assert not errors, errors
        assert not requests, requests
        browser.close()
    receipt = {"status": "PASS", "report": str(args.report),
               "report_sha256": sha256(report_bytes).hexdigest(),
               "rules_sha256": sha256((ROOT / "validator_rule_text.json").read_bytes()).hexdigest(),
               "html_sha256": sha256(page_path.read_bytes()).hexdigest(),
               "rules_checked": len(checked),
               "assertion_contexts_checked": sum(len(rule["requirements_review"]["assertions"]) for rule in rules.values()),
               "observations_checked": sum(len(observations[code]) for code in rules),
               "javascript_errors": errors, "external_requests": requests,
               "boundary": "Exact report messages and all rule contexts checked in offline Chromium; visual screenshots retained."}
    (args.output / "browser-verification.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
