"""Exercise the generated standalone page in an offline Chromium browser."""
from hashlib import sha256
import json
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[2]
ARTIFACT = ROOT / "docs/improvement/specification-audit/rule-browser.html"
EVIDENCE = ROOT / "docs/improvement/rule-browser-verification.json"
EXPECTED_RULES = len(json.loads((ROOT / "validator_rule_text.json").read_text()))


def main():
    checks, errors, external = [], [], []
    with sync_playwright() as w:
        browser = w.chromium.launch(headless=True)
        context = browser.new_context(viewport={"width": 1480, "height": 980}, offline=True)
        page = context.new_page()
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.on("request", lambda req: external.append(req.url) if req.url.startswith(("http:", "https:")) else None)
        page.goto(ARTIFACT.as_uri())
        page.wait_for_timeout(350)
        assert page.locator(".rule").count() == 278
        assert page.locator(".page").count() == 97
        assert "0 rules without checks" in page.locator("#metrics").inner_text()
        checks.append("Default: all 278 file/creation/inherited/definition/guidance records; all 97 original pages")
        page.locator("#scope").select_option("all")
        assert page.locator(".rule").count() == 301
        assert page.evaluate("new Set(DATA.rows.map(r=>r.id)).size") == 301
        assert page.evaluate("Object.keys(DATA.rules).length") == EXPECTED_RULES
        assert page.evaluate("DATA.rows.every(r=>r.plain_language.summary && r.plain_language.when)")
        checks.append(f"All 301 plain summaries and {EXPECTED_RULES} check definitions embedded, with full rule wording retained")
        page.locator("#search").fill("EMBEDDED-PARAMS")
        page.locator(".rule-details > summary").click()
        assert page.locator(".check-predicate").count() == 3
        predicates = page.locator(".rule-details").inner_text()
        for code in ("EMBEDDED_MEDIATYPE_PARAMETERS_TYPE", "EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED",
                     "SOURCE_MEDIATYPE_PARAMETERS_MATCH"):
            assert code in predicates
        assert "PDF string" in predicates and "independently associated" in predicates
        checks.append("Shared parameter source clause exposes three separately numbered type, presence and original-value predicates")
        page.locator("#search").fill("")
        page.locator("#scope").select_option("file")
        assert page.locator(".rule").count() == 249
        page.locator("#review").select_option("unmapped")
        assert page.locator(".rule").count() == 0
        checks.append("All 249 file records; no unmapped mandatory/conditional file records")
        page.locator("#reset").click()
        page.locator("#search").fill("PDF-VERSION")
        assert page.locator(".rule").count() == 1
        assert page.locator("#pageInput").input_value() == "68"
        assert page.locator("#pdf-page-68 .highlight").count() == 2
        page.wait_for_function("pageElements[67].querySelector('img').complete && pageElements[67].querySelector('img').naturalWidth > 0")
        page.evaluate("pageElements[67].querySelector('img').decode()")
        page.wait_for_timeout(150)
        page.screenshot(path="/tmp/eapdf-rule-browser-desktop.png")
        checks.append("Rule search aligns original PDF page 68 and highlights the full two-line PDF-version paragraph")
        page.locator("#search").fill("T11-Mail_ContentSet")
        assert page.locator("#pageInput").input_value() == "83"
        assert page.evaluate("active.source_highlight.excerpt.includes('Mail_ContentField')")
        assert "Mail_Field" in page.locator(".assertion").inner_text()
        checks.append("Original Table 11 typo retained and highlighted beside the approved Mail_Field interpretation")
        page.locator("#search").fill("T18-Mail_GUID")
        assert page.locator("#pageInput").input_value() == "94"
        assert page.locator("#pdf-page-94 .highlight").count() == 1
        assert page.evaluate("active.source_highlight.excerpt.includes('uniquely identifies')")
        checks.append("Exact Mail_GUID table row, source excerpt and derived requirement agree")
        page.locator("#search").fill("T15-version")
        assert page.locator(".assertion").inner_text() == "pdfmailid:version = 1 (integer)."
        assert page.locator(".rule .when").count() == 0
        page.locator(".rule-details > summary").click()
        body = page.locator(".rule").inner_text()
        assert "EAPDF_VERSION" in body
        assert "Full rule wording" not in body and "Review limits" not in body and "Evidence:" not in body
        assert body.count("pdfmailid:version") == 1
        assert page.locator('.rule [data-page="91"]').count() == 1
        page.wait_for_function("pageElements[90].querySelector('img').complete && pageElements[90].querySelector('img').naturalWidth > 0")
        page.evaluate("pageElements[90].querySelector('img').decode()")
        page.screenshot(path="/tmp/eapdf-rule-browser-simple-rule.png")
        checks.append("Simple rules state the requirement once, omit all-profile boilerplate and keep source/check links")
        page.locator("#reset").click()
        before = page.locator(".rule.active").get_attribute("id")
        page.locator("#rulePane").hover()
        page.mouse.wheel(0, 1200)
        page.wait_for_timeout(600)
        after = page.locator(".rule.active").get_attribute("id")
        assert before != after
        assert int(page.locator("#pageInput").input_value()) == page.evaluate("active.source_highlight.page")
        checks.append("Scrolling rule rows updates the selected requirement, source page and highlights")
        page.locator("#sourcePane").dispatch_event("wheel", {"deltaY": 1})
        page.evaluate("sourcePane.scrollTop=pageElements[93].offsetTop+510.35/DATA.pages[93].height*pageElements[93].offsetHeight-80")
        page.wait_for_timeout(500)
        assert page.locator(".rule.active").get_attribute("id") == "rule-T18-Mail_GUID"
        checks.append("Scrolling original specification selects and aligns the corresponding Mail_GUID rule")
        page.locator("#sync").uncheck()
        original = page.evaluate("sourcePane.scrollTop")
        page.locator("#search").fill("PDF-VERSION")
        assert abs(page.evaluate("sourcePane.scrollTop") - original) < 3
        checks.append("Synchronization off keeps the original pane position when selecting other rules")
        page.locator("#sync").check()
        page.locator("#reset").click()
        first = page.locator(".rule.active").get_attribute("id")
        page.locator("#next").click()
        assert page.locator(".rule.active").get_attribute("id") != first
        page.locator("#rulePane").focus()
        page.keyboard.press("k")
        assert page.locator(".rule.active").get_attribute("id") == first
        checks.append("Next/previous buttons and J/K keyboard navigation")
        page.locator("#about").click()
        assert page.locator("#notes").is_visible()
        assert "XMP is PDF metadata" in page.locator("#aboutBody").inner_text()
        page.locator("#closeNotes").click()
        page.locator("#questions").click()
        assert page.locator("#questionNotes").is_visible()
        assert page.locator(".question-card").count() == 11
        assert "11 review decisions" in page.locator("#questionCount").inner_text()
        page.screenshot(path="/tmp/eapdf-rule-browser-questions.png")
        assert page.locator(".question-card [data-source-page]").count() >= 11
        with page.expect_download() as profiles_download:
            page.locator('[download="bundled-profiles.json"]').click()
        profiles = json.loads((ROOT / "docs/improvement/verapdf-coverage/bundled-profiles.json").read_text())
        assert json.loads(Path(profiles_download.value.path()).read_text()) == profiles
        checks.append("Pinned profile evidence downloads from the standalone page without a network request")
        card = page.locator("#question-SPEC-INREPLYTO-ARRAY")
        assert "unordered XMP Bag" in card.inner_text()
        card.locator('[data-source-page="95"]').click()
        assert not page.locator("#questionNotes").is_visible()
        assert page.locator("#pageInput").input_value() == "95"
        page.locator("#questions").click()
        page.locator('#question-SPEC-T13-CONTAINER-PARENT [data-rule="T13-SET-PARENT"]').click()
        assert page.locator(".rule.active").get_attribute("id") == "rule-T13-SET-PARENT"
        assert "Proven document-wide content and collections" in page.locator(".rule.active .assertion").inner_text()
        assert page.locator(".rule.active .open-question").count() == 0
        assert page.evaluate("active.assertion.includes('A type label alone does not establish the exception.')")
        page.locator("#questions").click()
        assert "Project decision:" in page.locator("#question-SPEC-T13-CONTAINER-PARENT").inner_text()
        assert "Collections retain their document-level exception and prohibition on Mail_Message" in page.locator("#question-SPEC-T13-CONTAINER-PARENT").inner_text()
        page.locator("#closeQuestions").click()
        page.locator("#search").fill("ANNOTATION-CONTENTS")
        page.locator("#questions").click()
        assert "Every FileAttachment annotation must contain" in page.locator("#question-REQ-ANNOTATION-CONTENTS").inner_text()
        page.locator("#closeQuestions").click()
        checks.append("Eleven project decisions have sources and rationale; source/rule jumps work; resolved decisions are not labeled open")
        with page.expect_download() as download:
            page.locator("#download").click()
        source = (ROOT / "docs/reference/EA-PDF-v1.pdf").read_bytes()
        assert sha256(Path(download.value.path()).read_bytes()).hexdigest() == sha256(source).hexdigest()
        checks.append("Embedded PDF download exactly matches the pinned source SHA-256")
        page.locator("#search").fill("PDF-VERSION")
        page.set_viewport_size({"width": 390, "height": 844})
        page.wait_for_timeout(500)
        assert page.locator("#pageInput").input_value() == "68"
        assert page.locator(".rule.active").get_attribute("id") == "rule-PDF-VERSION"
        page.wait_for_function("pageElements[67].querySelector('img').complete && pageElements[67].querySelector('img').naturalWidth > 0")
        assert page.evaluate("document.documentElement.scrollWidth<=innerWidth")
        assert page.locator("#sourcePane").bounding_box()["height"] > 100
        assert page.locator("#rulePane").bounding_box()["height"] > 100
        page.screenshot(path="/tmp/eapdf-rule-browser-mobile.png")
        checks.append("390px mobile layout keeps both panes usable without horizontal overflow")
        assert not errors, errors
        assert not external, external
        checks.append("No JavaScript errors or external network requests; browser context offline throughout")
        browser.close()
    EVIDENCE.write_text(json.dumps({"artifact": str(ARTIFACT.relative_to(ROOT)),
        "sha256": sha256(ARTIFACT.read_bytes()).hexdigest(), "bytes": ARTIFACT.stat().st_size,
        "browser": "Playwright Chromium, offline context", "checks": checks,
        "javascript_errors": errors, "external_requests": external}, indent=2) + "\n")
    print(f"PASS: {len(checks)} browser checks; evidence {EVIDENCE.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
