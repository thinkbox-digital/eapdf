"""Correct an explicitly identified old count without rewriting pinned fixtures."""
from contextlib import contextmanager
from pathlib import Path
import tempfile

from lxml import etree
import pikepdf


@contextmanager
def corrected_rendered_count(source, filename, before, after):
    ns = {"m": "http://www.pdfa.org/eapdf/ns/meta/",
          "rdf": "http://www.w3.org/1999/02/22-rdf-syntax-ns#"}
    with tempfile.TemporaryDirectory(prefix="eapdf-count-fixture-") as directory:
        output = Path(directory) / "corrected.pdf"
        with pikepdf.open(source) as pdf:
            root = etree.fromstring(pdf.Root.Metadata.read_bytes())
            rows = root.xpath("//m:assets/rdf:Bag/rdf:li[m:Filename=$name]", namespaces=ns, name=filename)
            assert len(rows) == 1
            count = rows[0].find("m:NumberMessages", namespaces=ns)
            assert count is not None and count.text == str(before)
            count.text = str(after)
            pdf.Root.Metadata.write(etree.tostring(root, encoding="utf-8"))
            pdf.save(output)
        yield output
