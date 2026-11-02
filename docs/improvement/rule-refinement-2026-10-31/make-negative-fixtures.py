"""Make single-defect derivatives of small real-input outputs; preserve originals."""
import argparse
from hashlib import sha256
import json
from pathlib import Path

from lxml import etree
import pikepdf

NS = {"m": "http://www.pdfa.org/eapdf/ns/meta/",
      "rdf": "http://www.w3.org/1999/02/22-rdf-syntax-ns#"}


def xmp(pdf):
    return etree.fromstring(pdf.Root.Metadata.read_bytes())


def store_xmp(pdf, root):
    pdf.Root.Metadata.write(etree.tostring(root, encoding="utf-8"))


def incorrect_count(pdf):
    root = xmp(pdf)
    assets = root.xpath("//m:assets/rdf:Bag/rdf:li", namespaces=NS)
    assert len(assets) == 1
    field = assets[0].find("m:NumberMessages", namespaces=NS)
    if field is None:
        field = etree.SubElement(assets[0], "{" + NS["m"] + "}NumberMessages")
    field.text = "2"
    store_xmp(pdf, root)


def missing_subject(pdf):
    root = xmp(pdf)
    fields = root.xpath("//m:email/rdf:Bag/rdf:li/m:Subject", namespaces=NS)
    assert len(fields) == 1 and fields[0].text
    fields[0].getparent().remove(fields[0])
    store_xmp(pdf, root)


def missing_parameters(pdf):
    count = 0
    for obj in pdf.objects:
        if isinstance(obj, pikepdf.Stream) and str(obj.get("/Subtype", "")) == "/text/plain" and "/Mail_MediaTypeParameters" in obj:
            del obj["/Mail_MediaTypeParameters"]
            count += 1
    assert count == 1
    root = xmp(pdf)
    fields = root.xpath("//m:attachments//m:Content-Type", namespaces=NS)
    assert len(fields) == 1 and ";" in fields[0].text
    fields[0].text = fields[0].text.split(";", 1)[0]
    store_xmp(pdf, root)


def wrong_body_type(pdf):
    found = []
    for obj in pdf.objects:
        if isinstance(obj, pikepdf.Dictionary) and "/DPM" in obj:
            dpm = obj.DPM
            if str(dpm.get("/Mail_ContentSetType", "")) == "/BodyRendering":
                found.append(dpm)
    assert len(found) == 1
    found[0].Mail_Subtype = pikepdf.Name("/image/png")


def clipped_icon(pdf):
    found = []
    for page in pdf.pages:
        for annotation in page.obj.get("/Annots", []):
            if str(annotation.get("/Subtype", "")) == "/FileAttachment":
                found.append((page, annotation))
    assert len(found) == 1
    page, annotation = found[0]
    box = list(page.cropbox)
    annotation.Rect = pikepdf.Array([box[2] - 4, box[1] + 20, box[2] + 9, box[1] + 30])


def wrong_owner(pdf):
    owners = []
    for obj in pdf.objects:
        if isinstance(obj, pikepdf.Dictionary) and "/DPM" in obj and "/Mail_GUID" in obj.DPM:
            owners.append(obj.DPM)
    assert len(owners) == 2 and str(owners[0].Mail_GUID) != str(owners[1].Mail_GUID)
    owners[0].Mail_GUID = pikepdf.String(str(owners[1].Mail_GUID))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("single", type=Path)
    parser.add_argument("multiple", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    receipts = []
    cases = [("wrong-count", incorrect_count, "ASSET_NUMBERMESSAGES_MATCH_RENDERED", args.single),
             ("missing-original-subject", missing_subject, "MESSAGE_CORE_FIELDS_SOURCE_REQUIRED", args.single),
             ("parameters-omitted-twice", missing_parameters, "EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED", args.single),
             ("wrong-body-type", wrong_body_type, "DPM_BODY_MEDIA_TYPE_MATCH_SOURCE", args.single),
             ("clipped-attachment-icon", clipped_icon, "ATTACHMENT_ANNOTATIONS_VISIBLE_WITHIN_CROPBOX", args.single),
             ("wrong-email-owner", wrong_owner, "DPM_EMAIL_CONTENT_SET_OWNERSHIP", args.multiple)]
    for name, mutate, rule, source in cases:
        before = sha256(source.read_bytes()).hexdigest()
        destination = args.output / (name + ".pdf")
        with pikepdf.open(source) as pdf:
            mutate(pdf)
            pdf.save(destination)
        assert sha256(source.read_bytes()).hexdigest() == before
        receipts.append({"case": name, "expected_failure_rule": rule,
                         "source": str(source), "source_sha256": before,
                         "path": str(destination), "sha256": sha256(destination.read_bytes()).hexdigest(),
                         "validation": "pending; must independently confirm finished bytes still pass veraPDF"})
    (args.output / "mutations.json").write_text(json.dumps(receipts, indent=2) + "\n")
    print(f"Created {len(receipts)} derivatives; originals unchanged.")


if __name__ == "__main__":
    main()
