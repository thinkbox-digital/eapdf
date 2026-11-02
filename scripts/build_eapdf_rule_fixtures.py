"""Generate reviewed EA-PDF rule fixtures using the current creator."""
from decimal import Decimal
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import sys

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as validator


def structure_elements(document):
    pending = [document.Root.StructTreeRoot.K]
    while pending:
        item = pending.pop()
        if isinstance(item, pikepdf.Array):
            pending.extend(reversed(list(item)))
        elif isinstance(item, pikepdf.Dictionary) and "/S" in item:
            yield item
            child = item.get("/K")
            if child is not None:
                pending.append(child)

def attribute_form(document,message,attribute,form):
    """Independent object recipes for ISO 32000 14.7.6 precedence."""
    if not form:
        return
    bad=pikepdf.Dictionary(attribute)
    bad.P=pikepdf.Array([pikepdf.Dictionary(prop) for prop in attribute.P])
    next(prop for prop in bad.P if str(prop.N)=="Mail_MessageID").V=42
    short=pikepdf.Dictionary(attribute)
    short.P=pikepdf.Array([pikepdf.Dictionary(prop) for prop in attribute.P if str(prop.N)!="Mail_GUID"])
    if form in {"class-dictionary","class-array","class-revision","class-bad","class-array-bad"}:
        active=bad if form.endswith("bad") else attribute
        document.Root.StructTreeRoot.ClassMap=pikepdf.Dictionary(Email=
            pikepdf.Array([pikepdf.Dictionary(O=pikepdf.Name.Layout),active]) if "array" in form else active)
        message.C=pikepdf.Name.Email
        if form=="class-revision":
            message.C=pikepdf.Array([pikepdf.Name.Email,0])
            message.R=5
        del message.A
    elif form in {"classes-later","classes-bad","classes-replace-p"}:
        first,last=(bad,attribute) if form=="classes-later" else (attribute,bad if form=="classes-bad" else short)
        document.Root.StructTreeRoot.ClassMap=pikepdf.Dictionary(First=first,Last=last)
        message.C=pikepdf.Array([pikepdf.Name.First,5,pikepdf.Name.Last,0])
        message.R=5
        del message.A
    elif form in {"direct-override","direct-bad"}:
        document.Root.StructTreeRoot.ClassMap=pikepdf.Dictionary(Email=bad if form=="direct-override" else attribute)
        message.C=pikepdf.Name.Email
        message.A=attribute if form=="direct-override" else bad
    elif form in {"direct-later","direct-revisions","direct-last-bad","direct-replace-p","direct-empty-p"}:
        first,last=(bad,attribute) if form in {"direct-later","direct-revisions"} else (attribute,
            bad if form=="direct-last-bad" else short if form=="direct-replace-p" else
            pikepdf.Dictionary(O=pikepdf.Name.UserProperties,P=pikepdf.Array()))
        message.A=pikepdf.Array([first,0 if form=="direct-revisions" else 5,last,5 if form=="direct-revisions" else 0])
        message.R=5
    elif form=="missing-class":
        message.C=pikepdf.Name.Undefined
    else:
        raise AssertionError(form)


def fixture_variant(seed, output, case):
    with pikepdf.Pdf.open(seed) as document:
        elements = list(structure_elements(document))
        tag = case.get("tag", "Mail_Message")
        message = next(item for item in elements if str(item.S) == "/" + tag)
        attributes = message.A
        if isinstance(attributes, pikepdf.Array):
            attributes = next(item for item in attributes if isinstance(item, pikepdf.Dictionary)
                              and item.get("/O") == pikepdf.Name.UserProperties)
        properties = attributes.P
        property_name = case.get("property", "Mail_MessageID")
        target = next((item for item in properties if str(item.N) == property_name), None)
        if target is None:
            assert "value_kind" in case, case
            target = pikepdf.Dictionary(N=property_name)
            properties.append(target)
        if "value_kind" not in case:
            assert str(target.V) == "<batch01@example.invalid>"
        if "value_kind" in case:
            value = case.get("value")
            kind = case["value_kind"]
            if kind == "name":
                value = pikepdf.Name('/' + value)
            elif kind == "dictionary":
                value = pikepdf.Dictionary(Example=1)
            elif kind == "array":
                value = pikepdf.Array(["text"])
            elif kind == "real":
                value = Decimal(value)
            elif kind == "utf16-string":
                value = pikepdf.String(b"\xfe\xff" + value.encode("utf-16-be"))
            else:
                assert kind in {"string", "integer", "boolean", "null"}, kind
            # A null dictionary entry is equivalent to an absent entry in PDF.
            # pikepdf requires deletion rather than assigning Python None.
            if kind == "null":
                if "/V" in target:
                    del target.V
            else:
                target.V = value
        if case["mutation"] == "nonascii-name":
            target.N = pikepdf.String("Mail_MessageÏD")
        elif case["mutation"] == "integer-value":
            target.V = 42
        else:
            assert case["mutation"] is None
        if case["array_form"]:
            root = document.Root.StructTreeRoot.K
            if not isinstance(root, pikepdf.Array):
                document.Root.StructTreeRoot.K = pikepdf.Array([root])
            for element in elements:
                attribute = element.get("/A")
                if isinstance(attribute, pikepdf.Dictionary):
                    element.A = pikepdf.Array([attribute, 0])
        if case.get("untagged"):
            metadata = document.Root.Metadata.read_bytes()
            metadata, removed = re.subn(br"<pdfd:declarations>.*?</pdfd:declarations>", b"", metadata, flags=re.S)
            assert removed == 1
            document.Root.Metadata.write(metadata)
            del document.Root.StructTreeRoot
            if "/MarkInfo" in document.Root:
                del document.Root.MarkInfo
            for page in document.pages:
                instructions = [(operands, operator) for operands, operator in pikepdf.parse_content_stream(page)
                                if str(operator) not in {"BMC", "BDC", "EMC", "MP", "DP"}]
                page.Contents = document.make_stream(pikepdf.unparse_content_stream(instructions))
                if "/StructParents" in page.obj:
                    del page.obj.StructParents
                for annotation in page.obj.get("/Annots", []):
                    if "/StructParent" in annotation:
                        del annotation.StructParent
        attribute_form(document,message,attributes,case.get("attribute_form"))
        document.save(output)


def main():
    fixture_set = os.environ.get("EAPDF_RULE_FIXTURE_SET", "batch01")
    assert fixture_set in {"batch01", "batch02", "batch03", "batch05"}, fixture_set
    fixtures = ROOT / "tests/fixtures/eapdf" / fixture_set
    batch = fixture_set[-2:]
    output = Path(os.environ.get("EAPDF_RULE_FIXTURE_DIR", ROOT / f"docs/improvement/batch-{batch}-corpus")).resolve()
    output.mkdir(parents=True, exist_ok=True)
    version = (ROOT / "VERSION").read_text().strip()
    classpath = os.environ.get("EAPDF_GENERATOR_CLASSPATH", str(ROOT / "target" / f"emailtopdfa-{version}.jar"))
    config = output / "config.json"
    config.write_text(json.dumps({"branding":{"enabled":False}, "premis":{"enabled":False}}))
    command = ["java", "-Djava.awt.headless=true", "-Xmx256m", "-cp", classpath,
               "eapdf.EmailToPDFA", "--out", str(output / "seed"), str(fixtures / "source.eml")]
    process = subprocess.run(command, cwd=ROOT, env=dict(os.environ, EAPDF_CONFIG=str(config)),
                             capture_output=True, text=True, timeout=90)
    (output / "generation.log").write_text(process.stdout + process.stderr)
    if process.returncode:
        raise RuntimeError(f"Seed generation failed; see {output / 'generation.log'}")
    seeds = list((output / "seed").glob("*.pdf"))
    assert len(seeds) == 1, seeds
    results = []
    for case in json.loads((fixtures / "cases.json").read_text()):
        pdf = output / (case["id"] + ".pdf")
        fixture_variant(seeds[0], pdf, case)
        report = validator.validate_one(str(pdf), html_path=str(output / (case["id"] + ".html")))
        (output / (case["id"] + ".json")).write_text(json.dumps(report, default=str, indent=2))
        failures = {item["code"] for item in report["items"] if item["level"] == "FAIL"}
        assert report["pdfa"]["status"] == "PASS", (case["id"], report["pdfa"])
        assert report["summary"]["status"] == case["expected_eapdf"], (case["id"], report["summary"], failures)
        assert failures == set(case["required_failures"]), (case["id"], failures)
        unverified = {item["code"] for item in report["items"]
                      if isinstance(item.get("details"), dict) and item["details"].get("state") == "unverified"}
        assert unverified == set(case.get("expected_unverified", [])), (case["id"], unverified)
        if "expected_recommendation" in case:
            levels={item["level"] for item in report["items"] if item["code"]=="USERPROPS_VALUES_UNMODIFIED"}
            actual="WARN" if "WARN" in levels else "PASS" if levels=={"PASS"} else "N/A"
            assert actual==case["expected_recommendation"], (case["id"],levels)
        results.append({"case":case["id"], "sha256":hashlib.sha256(pdf.read_bytes()).hexdigest(),
                        "pdfa":report["pdfa"]["status"], "eapdf":report["summary"]["status"],
                        "failures":sorted(failures), "expected_result_observed":True})
        print(case["id"], "PDF/A", report["pdfa"]["status"], "EA-PDF", report["summary"]["status"], flush=True)
    (output / "results.json").write_text(json.dumps(results, indent=2) + "\n")


if __name__ == "__main__":
    main()
