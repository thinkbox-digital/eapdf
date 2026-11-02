"""Build the offline, single-file specification/rule browser.

Runtime rules are authored in validator_rule_text.json; review records are in
validator-rule-review.json. Source highlights are
navigation aids: exact table cells where available, otherwise explicitly labeled
lexical passage suggestions. They are not newly authored normative quotations.
Requires PyMuPDF and Pillow (generation only; viewing needs just a browser).
"""
import base64
from collections import Counter
from hashlib import sha256
from io import BytesIO
import json
import math
from pathlib import Path
from eapdf_rule_review import REVIEW_PATH, load_review_rules
import re

import fitz
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs/improvement/specification-audit/rule-browser.html"
STOP = set("a an and are as at be been both by can contains corresponding defined do each either every file files for from has have if in include includes into is it its may must no not of on one only or other pdf present required same shall should source specified such that the their them these they this those to under use used value values when where which with xmp ea email pdfmail".split())


def tokens(text):
    return [w for w in re.findall(r"[a-z0-9_]+(?:[-:][a-z0-9_]+)*", text.lower())
            if len(w) > 1 and w not in STOP]


def page_text(page):
    lines, blocks = [], []
    for block in page.get_text("dict")["blocks"]:
        if "lines" not in block:
            continue
        group = []
        for line in block["lines"]:
            text = "".join(span["text"] for span in line["spans"]).strip()
            box = list(line["bbox"])
            if text and 65 < box[1] < 725:
                item = {"text": text, "box": [round(x, 2) for x in box]}
                lines.append(item)
                group.append(item)
        if group:
            blocks.append({"text": " ".join(x["text"] for x in group),
                           "boxes": [x["box"] for x in group]})
    # This publication sometimes splits a single paragraph into PDF text blocks.
    # Rejoin adjacent full-width body lines without merging table columns.
    joined = []
    for block in blocks:
        first = block["boxes"][0]
        if joined:
            previous = joined[-1]
            last = previous["boxes"][-1]
            if (first[0] < 90 and last[0] < 90 and abs(first[0] - last[0]) < 5
                    and first[2] - first[0] > 220 and last[2] - last[0] > 220
                    and 0 <= first[1] - last[3] < 5
                    and first[3] - first[1] < 17 and last[3] - last[1] < 17):
                previous["text"] += " " + block["text"]
                previous["boxes"].extend(block["boxes"])
                continue
        joined.append(block)
    return lines, joined


def table_highlight(row, pages):
    """Use an exact field label in a ruled table, never an assertion paraphrase."""
    if not row.get("table"):
        return None
    key = re.sub(r"^T\d+-", "", row["id"])
    aliases = {"Original-message-size": "Original message size", "Number-of-attachments": "Number of attachments"}
    key = aliases.get(key, key)
    for number in row["pdf_pages"]:
        p = pages[number - 1]
        captions = [(int(m.group(1)), l["box"]) for l in p["lines"]
                    if (m := re.match(r"^Table (\d+):", l["text"]))]
        start = max([box[3] for n, box in captions if n < row["table"]] + [65])
        stop = min([box[1] for n, box in captions if n == row["table"]] + [725])
        for line in p["lines"]:
            text = re.sub(r"\s+\d+$", "", line["text"]).strip()
            if text != key or line["box"][0] > 240 or not start < line["box"][1] < stop:
                continue
            x0, y0, x1, y1 = line["box"]
            if row["table"] == 11:
                # Parent cells span several occurrence/child rows. Include that
                # whole group; the printed Mail_ContentField is a child typo.
                next_parent = [l["box"][1] for l in p["lines"]
                               if abs(l["box"][0] - x0) < 2 and l["box"][1] > y1
                               and l["text"].startswith("Mail_")]
                captions = [l["box"][1] for l in p["lines"] if l["text"].startswith("Table 11:")]
                bottom = min(next_parent + captions + [690]) - 3
                box = [90, y0 - 3, 515, bottom]
                excerpt = " ".join(l["text"] for l in p["lines"] if box[1] <= l["box"][1] and l["box"][3] <= box[3] + 2)
                return {"page": number, "boxes": [box], "kind": "Exact table parent; child-group context", "excerpt": excerpt}
            above = [y for y in p["horizontal"] if y <= y0 + 1]
            below = [y for y in p["horizontal"] if y >= y1 - 1]
            if above and below and 8 < min(below) - max(above) < 400:
                box = [max(50, x0 - 4), max(above), 560, min(below)]
                excerpt = " ".join(l["text"] for l in p["lines"]
                                   if box[1] <= l["box"][1] and l["box"][3] <= box[3] + 2)
                return {"page": number, "boxes": [box], "kind": "Exact table field; row context", "excerpt": excerpt}
            return {"page": number, "boxes": [line["box"]], "kind": "Exact field label", "excerpt": line["text"]}
    return None


def locate(row, pages, weights):
    if not row["pdf_pages"]:
        return {"page": None, "boxes": [], "kind": "Validation policy; no specification passage", "excerpt": ""}
    exact = table_highlight(row, pages)
    if exact:
        return exact
    query = set(tokens(row["assertion"]))
    candidates = []
    for number in row["pdf_pages"]:
        for block in pages[number - 1]["blocks"]:
            terms = set(tokens(block["text"]))
            overlap = query & terms
            score = sum(weights.get(w, 1) for w in overlap) / (max(12, len(terms)) ** .3)
            candidates.append((score, number, block))
    score, number, block = max(candidates, key=lambda x: x[0])
    if score < 1:
        return {"page": number, "boxes": [[50, 70, 562, 720]], "kind": "Cited page context; no precise passage match", "excerpt": ""}
    return {"page": number, "boxes": block["boxes"], "kind": "Suggested passage; verify against cited section", "excerpt": block["text"]}


def build():
    rule_bytes = (ROOT / "validator_rule_text.json").read_bytes()
    rules = load_review_rules()
    review = rules["VALIDATION_INCOMPLETE"]["specification_review"]
    source = (ROOT / review["specification_source"]).read_bytes()
    assert sha256(source).hexdigest() == review["specification_sha256"]
    pdf = fitz.open(stream=source, filetype="pdf")
    pages, assets = [], []
    for page in pdf:
        lines, blocks = page_text(page)
        horizontal = sorted({round(float(d["rect"].y0), 2) for d in page.get_drawings()
                             if d["rect"].width > 180 and d["rect"].height < 1})
        pages.append({"lines": lines, "blocks": blocks, "horizontal": horizontal})
        pix = page.get_pixmap(matrix=fitz.Matrix(1.65, 1.65), alpha=False)
        image = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
        buffer = BytesIO()
        image.save(buffer, format="WEBP", quality=84, method=6)
        assets.append({"width": page.rect.width, "height": page.rect.height,
                       "image": base64.b64encode(buffer.getvalue()).decode(), "lines": lines})
    df = Counter(w for page in pages for b in page["blocks"] for w in set(tokens(b["text"])))
    weights = {w: 1 + math.log(1 + 100 / count) for w, count in df.items()}
    rows = []
    for row in review["inventory_records"]:
        plain = row["plain_language"]
        basis = {key: row[key] for key in ("assertion", "applies_when", "strength", "scope")}
        assert plain["basis_sha256"] == sha256(json.dumps(basis, sort_keys=True, ensure_ascii=False).encode()).hexdigest(), row["id"]
        assert plain["summary"].strip() and len(plain["summary"].split()) <= 55, row["id"]
        assert plain["when"].strip(), row["id"]
        item = dict(row)
        item["source_highlight"] = locate(row, pages, weights)
        rows.append(item)
    assert len({r["id"] for r in rows}) == len(rows)
    ids = {r["id"] for r in rows}
    for question in review["expert_questions"]:
        assert question["title"] and question["why_it_matters"] and question["support"]
        assert set(question["requirement_ids"]) <= ids, question["finding_id"]
        for source_link in question["support"]:
            assert source_link["label"]
            assert 1 <= source_link["pdf_page"] <= len(pdf) if "pdf_page" in source_link else source_link["url"]
    assert all(r["source_highlight"]["page"] in r["pdf_pages"] for r in rows if r["pdf_pages"])
    data = {"rows": rows, "pages": assets, "rules": rules,
            "questions": review["expert_questions"], "findings": review["findings"],
            "profileEvidence": json.loads((ROOT / "docs/improvement/verapdf-coverage/bundled-profiles.json").read_text()),
            "sourceHash": sha256(source).hexdigest(), "rulesHash": sha256(rule_bytes).hexdigest(), "reviewHash": sha256(REVIEW_PATH.read_bytes()).hexdigest(),
            "sourcePdf": base64.b64encode(source).decode()}
    encoded = json.dumps(data, ensure_ascii=False, separators=(",", ":")).replace("<", "\\u003c")
    template = Path(__file__).with_name("eapdf_rule_browser_template.html").read_text()
    OUTPUT.write_text(template.replace("__EMBEDDED_DATA__", encoded))
    manifest = {"artifact": str(OUTPUT.relative_to(ROOT)), "records": len(rows),
                "file_records": sum(r["scope"] == "file" for r in rows),
                "rule_codes": len(rules), "source_pages": len(pdf),
                "source_sha256": data["sourceHash"], "rules_sha256": data["rulesHash"],
                "plain_language_summaries": len(rows), "supported_questions": len(data["questions"]),
                "highlight_policy": "Exact field/table row context where located; otherwise labeled lexical passage suggestion, never a certified quotation-to-rule mapping.",
                "highlight_counts": dict(Counter(r["source_highlight"]["kind"] for r in rows)),
                "highlights": {r["id"]: r["source_highlight"] for r in rows}}
    OUTPUT.with_suffix(".json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    print(f"Built {OUTPUT.relative_to(ROOT)}: {len(rows)} records, {len(pdf)} source pages, {OUTPUT.stat().st_size:,} bytes")


if __name__ == "__main__":
    build()
