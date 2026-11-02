"""Generate expert review and coverage views from the authoritative rule JSON.

This module does not infer rule ownership. It verifies the authored dispositions
against retained profile evidence and publishes them for review.
"""
from collections import Counter
from hashlib import sha256
from html import escape
import json
from pathlib import Path
from xml.etree import ElementTree as ET
from eapdf_rule_review import REVIEW_PATH


ROOT = Path(__file__).resolve().parents[1]
PROFILE_DIR = ROOT / "docs/improvement/verapdf-coverage"
AUDIT_DIR = ROOT / "docs/improvement/specification-audit"
NS = {"v": "http://www.verapdf.org/ValidationProfile"}
DECISIONS = {
    "retain_custom_eapdf_requirement", "delegate_to_verapdf", "operational_only",
    "split_custom_and_verapdf", "split_custom_declaration_and_external_structure",
}


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def verify_profile_evidence(rules):
    """Fail if a claimed upstream rule reference or pinned XML has drifted."""
    manifest = json.loads((PROFILE_DIR / "bundled-profiles.json").read_text())
    index_path = PROFILE_DIR / "profile-rule-index.json"
    retained_index = json.loads(index_path.read_text()) if index_path.exists() else None
    index = {}
    expected = {"PDFA-3A", "PDFA-3U", "PDFA-4", "PDFA-4F", "PDFA-4E"}
    for entry in manifest:
        profile = entry["profile"]
        if profile not in expected:
            continue  # Composite provenance is verified separately below.
        path = PROFILE_DIR / "profiles" / (profile + ".xml")
        data = path.read_bytes()
        assert sha256(data).hexdigest() == entry["sha256"], profile
        parsed = []
        for element in ET.fromstring(data).findall("v:rules/v:rule", NS):
            ident = element.find("v:id", NS)
            parsed.append(dict(specification=ident.attrib["specification"],
                clause=ident.attrib["clause"], test_number=ident.attrib["testNumber"],
                object=element.attrib["object"], test=element.findtext("v:test", namespaces=NS)))
        assert len(parsed) == entry["rule_count"], profile
        if retained_index is not None:
            assert parsed == retained_index[profile], profile
        index[profile] = parsed
    assert set(index) == expected
    assert {entry["profile"] for entry in manifest if entry["profile"] in expected} == expected
    selection = json.loads((PROFILE_DIR / "iso32005-profile.json").read_text())
    source = ROOT / selection["source"]
    selected = ROOT / selection["profile"]
    source_pin = next(e for e in manifest if e["profile"] == "PDFUA-2-ISO32005")
    assert sha256(source.read_bytes()).hexdigest() == source_pin["sha256"] == selection["source_sha256"]
    assert sha256(selected.read_bytes()).hexdigest() == selection["profile_sha256"]
    upstream = ET.fromstring(source.read_bytes()).findall("v:rules/v:rule", NS)
    output = ET.fromstring(selected.read_bytes())
    actual = output.findall("v:rules/v:rule", NS)
    ET.register_namespace("", NS["v"])
    canonical = lambda r: ET.canonicalize(ET.tostring(r, encoding="unicode"))
    iso = [r for r in upstream if r.find("v:id", NS).get("specification") == "ISO_32005"]
    assert len(upstream) == 1727 and len(iso) == len(actual) == selection["selected_rules"] == 1636
    assert all(r.find("v:id", NS).get("specification") == "ISO_14289_2" for r in upstream if r not in iso)
    payload = "".join(map(canonical, iso))
    assert list(map(canonical, actual)) == list(map(canonical, iso))
    assert sha256(payload.encode()).hexdigest() == selection["unchanged_rule_payload_sha256"]
    assert output.get("flavour") == selection["model_selector"] == "PDFUA_2"
    assert output.findtext("v:details/v:name", namespaces=NS) == selection["profile_name"]
    pin = json.loads((ROOT / "config/verapdf/ISO-32005.json").read_text())
    archived_pin = json.loads(REVIEW_PATH.read_text())["removed_rules"]["ISO32005_UPSTREAM"]["validation_scope"]["external_profile"]
    assert all(archived_pin[key] == value for key, value in selection.items())
    assert all(archived_pin[key] == value for key, value in pin.items())
    verified = {p: {tuple((k, row[k]) for k in ("specification", "clause", "test_number", "object"))
                    for row in rows} for p, rows in index.items()}
    for code, rule in rules.items():
        scope = rule["validation_scope"]
        assert scope["decision"] in DECISIONS, code
        assert scope.get("reason") or scope.get("custom_requirement"), code
        for profile, refs in scope.get("profile_rule_references", {}).items():
            assert profile in verified, (code, profile)
            for ref in refs:
                key = tuple((k, ref[k]) for k in ("specification", "clause", "test_number", "object"))
                assert key in verified[profile], (code, profile, ref)
        if scope["decision"] == "delegate_to_verapdf":
            assert any(scope.get("profile_rule_references", {}).values()), code
    return index


def generate_review_views(rules, review):
    index = verify_profile_evidence(rules)
    write_json(PROFILE_DIR / "profile-rule-index.json", index)
    counts = dict(Counter(v["validation_scope"]["decision"] for v in rules.values()))
    write_json(PROFILE_DIR / "ownership-summary.json", counts)
    write_json(PROFILE_DIR / "rule-ownership.json", {
        "authority": "validator_rule_text.json", "view_only": True,
        "coverage_basis": "Five pinned veraPDF 1.31.18 PDF/A profile XMLs; static predicates, not a validation run",
        "profiles": {p: len(rows) for p, rows in index.items()},
        "counts": counts,
        "rules": {code: rule["validation_scope"] for code, rule in rules.items()},
    })
    rows = review["inventory_records"]
    findings = {f["id"]: f for f in review["findings"]}
    questions = review["expert_questions"]
    assert {q["finding_id"] for q in questions} == {f["id"] for f in findings.values() if f["status"] in {"open", "locally_resolved"}}
    open_questions = sum(q.get("status", "open") == "open" for q in questions)
    local_decisions = sum(q.get("status") == "locally_resolved" for q in questions)
    assert len({q["finding_id"] for q in questions}) == len(questions)
    write_json(AUDIT_DIR / "expert-questions.json", questions)
    question_doc = ["# EA-PDF review questions", "",
        f"{local_decisions} questions have user-authorized project interpretations; {open_questions} await a project decision. These are local working decisions, not official errata or external approval. Implementation evidence is recorded separately.", "",
        "Open the [two-pane rule page](rule-browser.html) to compare rules with the original specification.", "",
        "Profiles: s = one email; m = multiple emails; c = collection. Add i when the original source link is missing or unreliable.", "",
        "XMP is the PDF's metadata. FOAF describes people and their email addresses. Bag means an unordered list; Seq means an ordered list.", "",
        "The [2026-10-27 reexamination](specification-reexamination-2026-10-27.txt) records 52 findings and working assumptions. Its implementation section and the current resolutions below supersede conflicting older answers; historical resolutions remain in the review JSON.", ""]
    for number, q in enumerate(questions, 1):
        question_doc += [f'## {number}. {q["title"]}', "", q["question"], "",
            f'**Why:** {q["why_it_matters"]}', "", f'**Project decision:** {q["interim_boundary"]}', "",
            f'**Basis:** {q.get("resolution", {}).get("rationale", "Unresolved")}', "",
            f'**Confidence:** {q.get("resolution", {}).get("confidence", "Unresolved")}. **Verification:** {q.get("resolution", {}).get("implementation_status", "Not verified")}', "", "**Sources:**", ""]
        for source in q["support"]:
            url = f'../../reference/EA-PDF-v1.pdf#page={source["pdf_page"]}' if "pdf_page" in source else source["url"]
            question_doc.append(f'- [{source["label"]}]({url})')
        question_doc += ["", "**Related rules:** " + ", ".join(
            f'[{ident}](rule-browser.html#{ident})' for ident in q["requirement_ids"]), "", f'Review ID: `{q["finding_id"]}`', ""]
    question_doc += ["## Source limits", "",
        "The pinned EA-PDF 1.0 specification is the source for these questions. Issue reports explain concerns; they are not approved changes.", "",
        "The public Adobe XMP text is from April 2012. It helps explain XMP but does not prove equivalence to the cited 2019 ISO edition.", "",
        "[Prior source review](external-sources-reviewed.json) and [pinned veraPDF coverage](../verapdf-coverage/README.md) retain the supporting evidence.", ""]
    (AUDIT_DIR / "review-questions.md").write_text("\n".join(question_doc))
    write_json(AUDIT_DIR / "representation-contracts.json", review["representation_contracts"])
    write_json(AUDIT_DIR / "external-sources-reviewed.json", review["external_sources_reviewed"])
    upstream = review.get("upstream_issue_review")
    if upstream:
        assert upstream["issue_count"] == len(upstream["issues"])
        assert len({issue["number"] for issue in upstream["issues"]}) == len(upstream["issues"])
        retired = set(json.loads(REVIEW_PATH.read_text())["removed_rules"])
        assert all(set(issue["affected_rule_codes"]) <= set(rules) | retired for issue in upstream["issues"])
        assert upstream["comment_count"] == sum(issue["comments_reviewed"] for issue in upstream["issues"])
        write_json(AUDIT_DIR / "upstream-issues.json", upstream)
    gaps = [r for r in rows if not r["candidate_rule_codes"] and r["scope"] == "file"
            and r["strength"] in {"SHALL", "SHALL_NOT", "inherited_requirement", "conditional_requirement", "required_if_present", "conditional"}]
    write_json(AUDIT_DIR / "unmapped-file-requirements.json", gaps)

    def e(value):
        return escape(str(value), quote=True)

    def links(ids, prefix):
        return ", ".join(e(i) + " (upstream pipeline)" if prefix == "rule-" and i not in rules
                         else f'<a href="#{prefix}{e(i)}">{e(i)}</a>' for i in ids) or "None"

    html = ['<!doctype html><html lang="en"><meta charset="utf-8">',
        '<meta name="viewport" content="width=device-width,initial-scale=1">',
        '<title>EA-PDF validation requirements: expert review</title>',
        '<style>body{font:16px/1.5 system-ui,sans-serif;max-width:1100px;margin:auto;padding:24px;color:#17212b}',
        'a{color:#075b92}h2{margin-top:2em}article{border:1px solid #bdcbd6;padding:16px;margin:12px 0;border-radius:6px}',
        'details{margin:10px 0}summary{cursor:pointer}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f4f7f9;padding:10px}',
        'small{color:#44576a}input,select{font:inherit;padding:6px;margin:6px}nav{background:#eef4f8;padding:12px}',
        '[hidden]{display:none!important}@media print{nav,.filters{display:none}article{break-inside:avoid}details{display:block}}</style>',
        '<h1>EA-PDF validation requirements: expert review</h1>',
        '<p>Generated from <a href="../../../validator_rule_text.json">validator_rule_text.json</a>, the runtime rule document, plus validator-rule-review.json for review records. ',
        'This is a supporting review view. Assertions are reviewed paraphrases, not verbatim quotations. ',
        'The pinned EA-PDF v1.0 specification remains the specification authority.</p>',
        f'<p>{len(rules)} existing rule descriptions; {len(rows)} requirement/guidance records; ',
        f'{open_questions} open questions; {local_decisions} local decisions; {len(gaps)} unmapped mandatory or conditional file records. ',
        'A rule link or ownership disposition does not certify implementation, execution or complete conformance.</p>',
        f'<p><strong>Requirements gate:</strong> {e(review["full_requirements_gate"])}</p>',
        '<nav><a href="rule-browser.html">Two-pane specification / rule browser</a> · ',
        '<a href="#questions">Review decisions</a> · <a href="#contracts">Value representations</a> · ',
        '<a href="#gaps">Unmapped requirements</a> · <a href="#rules">Rules</a> · ',
        '<a href="#requirements">Specification inventory</a> · <a href="#sources">Sources</a> · ',
        '<a href="#upstream">Upstream issue review</a></nav>',
        '<p>Review whether every obligation is present, the strength and conditions match the specification, ',
        'permissions remain allowed, and each custom assertion is outside the selected veraPDF coverage. ',
        'Record proposed corrections by rule code, requirement ID or finding ID in the authoritative JSON. ',
        'No external review or publication is claimed by this generated document.</p>',
        '<h2 id="questions">Review questions and project decisions</h2>']
    for q in questions:
        f = findings[q["finding_id"]]
        support = []
        for source in q["support"]:
            url = f'../../reference/EA-PDF-v1.pdf#page={source["pdf_page"]}' if "pdf_page" in source else source["url"]
            support.append(f'<a href="{e(url)}">{e(source["label"])}</a>')
        html += [f'<article id="finding-{e(f["id"])}"><h3>{e(q["title"])}</h3>',
            f'<p><strong>{e(q["question"])}</strong></p>',
            f'<p><strong>Why:</strong> {e(q["why_it_matters"])}</p>',
            f'<p><strong>Project decision:</strong> {e(q["interim_boundary"])}</p>',
            f'<p><strong>Status:</strong> {e(q.get("status", "open"))}; {e(q.get("resolution", {}).get("implementation_status", "Not verified"))}</p>',
            '<p><strong>Sources:</strong> ' + ' · '.join(support) + '</p>',
            '<details><summary>Review record</summary>',
            f'<p><strong>Authority location:</strong> {e(f["authority_location"])}</p>',
            f'<p>{e(f["finding"])}</p>',
            f'<p>Related checks: {links(f["affected_rule_codes"], "rule-")}</p></details></article>']
    html.append('<h2 id="contracts">Value representation contracts</h2>')
    for contract in review["representation_contracts"]:
        html += [f'<article><h3>{e(contract["id"])}</h3>',
            f'<p>{e(contract["requirement"])}</p><p><small>{e(contract["status"])}</small></p>',
            f'<details><summary>Authority, equivalent forms and acceptance contract</summary><pre>{e(json.dumps(contract, ensure_ascii=False, indent=2))}</pre></details></article>']
    html += ['<h2 id="gaps">Unmapped file requirements</h2>',
        '<p>These records have no executed rule mapping. They remain gaps even when an audit finding about missing documentation has been resolved.</p>']
    for row in gaps:
        html.append(f'<p>{links([row["id"]], "requirement-")}: {e(row["assertion"])}</p>')
    if upstream:
        html += ['<h2 id="upstream">Upstream issue review</h2>',
            f'<p>{upstream["issue_count"]} open/closed reports and {upstream["comment_count"]} comments reviewed. ',
            f'Checked {e(upstream["reviewed_at_utc"])}. {e(upstream["decision_policy"])}</p>']
        for issue in upstream["issues"]:
            html += [f'<article id="upstream-issue-{issue["number"]}">',
                f'<h3><a href="{e(issue["url"])}">#{issue["number"]}: {e(issue["title"])}</a></h3>',
                f'<p><strong>GitHub:</strong> {e(issue["github_state"])}; ',
                f'<strong>Scope:</strong> {e(issue["classification"])}; ',
                f'<strong>Evidence:</strong> {e(issue["authority_level"])}</p>',
                f'<p>{e(issue["disposition"])}</p>',
                f'<p>Related rules: {links(issue["affected_rule_codes"], "rule-")}</p>',
                f'<details><summary>Revision and comment evidence</summary><pre>{e(json.dumps(issue, ensure_ascii=False, indent=2))}</pre></details></article>']
    html += ['<h2 id="rules">Existing rule descriptions and ownership</h2>',
        '<div class="filters"><label>Filter rules <input id="query" type="search"></label>',
        '<label>Owner <select id="owner"><option value="">All</option>',
        '<option value="retain_custom">Custom</option><option value="delegate_to">veraPDF</option>',
        '<option value="split_">Mixed</option><option value="operational">Operational</option></select></label>',
        '<span id="shown" role="status"></span></div>']
    for code, rule in rules.items():
        scope = rule["validation_scope"]
        check = scope.get("check")
        predicate = (f'<p><strong>This rule:</strong> {e(check["strength"])}. {e(check["assertion"])}</p>'
                     f'<p><strong>Applies when:</strong> {e(check["applies_when"])}</p>'
                     f'<p><strong>Evidence needed:</strong> {e(check["evidence_needed"])}</p>'
                     if check else '')
        html += [f'<article class="rule" id="rule-{e(code)}" data-owner="{e(scope["decision"])}">',
            f'<h3>{rule["rule_number"]}. {e(code)}</h3><p>{e(rule["spec_excerpt"])}</p>',
            predicate,
            f'<p><strong>Authority:</strong> {e(rule["spec_section"])}</p>',
            f'<p><strong>Ownership:</strong> {e(scope["decision"])}. {e(scope.get("reason", scope.get("custom_requirement", "")))}</p>',
            f'<p>Requirements: {links(rule["requirements_review"]["requirement_ids"], "requirement-")}</p>',
            '<p>Upstream reports: ' + (", ".join(
                f'<a href="{e(url)}">#{e(url.rsplit("/", 1)[-1])}</a>'
                for url in rule.get("upstream_issue_references", [])) or "None") + '</p>',
            f'<details><summary>Profile references, uncovered fragments and review status</summary><pre>{e(json.dumps(scope, ensure_ascii=False, indent=2))}</pre></details></article>']
    html.append('<h2 id="requirements">Full specification inventory</h2>')
    for row in rows:
        html += [f'<article id="requirement-{e(row["id"])}"><h3>{e(row["id"])}</h3>',
            f'<p><strong>Clause:</strong> {e(row["section"])}; printed pages {e(row["printed_pages"])}; ',
            f'<strong>Strength:</strong> {e(row["strength"])}; <strong>Scope:</strong> {e(row["scope"])}</p>',
            f'<p><strong>Condition:</strong> {e(row["applies_when"])}</p><p>{e(row["assertion"])}</p>',
            f'<p>{e(row["custom_validator_scope"])}</p><p>Candidate rules: {links(row["candidate_rule_codes"], "rule-")}</p>',
            f'<details><summary>Evidence, dependencies, issues and implementation status</summary><pre>{e(json.dumps(row, ensure_ascii=False, indent=2))}</pre></details></article>']
    html.append('<h2 id="sources">Sources reviewed</h2>')
    for source in review["external_sources_reviewed"]:
        html += [f'<article><h3>{e(source["id"])}</h3><p><a href="{e(source["url"])}">Official source</a></p>',
            f'<p>{e(source["conclusion"])}</p><pre>{e(json.dumps(source, ensure_ascii=False, indent=2))}</pre></article>']
    html += ['<script>const query=document.getElementById("query"),owner=document.getElementById("owner"),',
        'items=[...document.querySelectorAll(".rule")];function filter(){let n=0;',
        'for(const item of items){item.hidden=!(item.textContent.toLowerCase().includes(query.value.toLowerCase())',
        '&&item.dataset.owner.startsWith(owner.value));if(!item.hidden)n++;}',
        'document.getElementById("shown").textContent=n+" / "+items.length+" rules shown";}',
        'query.addEventListener("input",filter);owner.addEventListener("change",filter);',
        'window.addEventListener("hashchange",()=>{const target=document.getElementById(decodeURIComponent(location.hash.slice(1)));',
        'if(target&&target.classList.contains("rule")&&target.hidden){query.value="";owner.value="";filter();target.scrollIntoView();}});filter();</script></html>']
    (AUDIT_DIR / "expert-review.html").write_text("\n".join(html) + "\n")
    (PROFILE_DIR / "README.md").write_text(
        "# veraPDF coverage and custom-rule ownership\n\n"
        "Authority: [validator_rule_text.json](../../../validator_rule_text.json). "
        "These are generated supporting views.\n\n"
        f"All {len(rules)} current rule codes have ownership dispositions against the five pinned "
        "veraPDF 1.31.18 PDF/A profiles. Generic PDF/A prerequisites stay upstream; "
        "custom or mixed rules retain only uncovered assertions. A profile's presence "
        "is not evidence that it ran. The local ISO 32005 selection retains all 1636 unchanged "
        "upstream ISO predicates and excludes all 91 PDF/UA predicates. Its PDFUA_2 flavour "
        "selects the verifier model; it does not impose PDF/UA conformance. "
        "This selection is not an officially published standalone profile.\n\n"
        + "\n".join(f"- `{key}`: {value}" for key, value in sorted(counts.items())) + "\n\n"
        "[Ownership details](rule-ownership.json), [profile predicates](profile-rule-index.json), "
        "[profile hashes](bundled-profiles.json), [expert review](../specification-audit/expert-review.html).\n\n"
        "Regenerate with `python3 -B scripts/build_eapdf_requirements_audit.py`. "
        "The generator verifies every claimed upstream rule reference against the retained "
        "XML and index. The runtime delegates the 11 fully owned rules as N/A entries "
        "with separate upstream run evidence; see [routing evidence](../validator-02-evidence.json). "
        "[ISO selection provenance](iso32005-profile.json) is verified against the pinned composite "
        "and authoritative execution rule; unavailable or unverified required execution prevents PASS. "
        "Mixed-rule fragment routing remains unfinished. Ownership dispositions do not certify "
        "complete normative coverage or replace unresolved expert questions.\n")
