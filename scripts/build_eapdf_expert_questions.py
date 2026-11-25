"""Build the specification decision brief from the reviewed question data."""

from datetime import datetime
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path

from docx import Document
from docx.enum.style import WD_STYLE_TYPE
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.opc.constants import RELATIONSHIP_TYPE
from docx.shared import Inches, Pt, RGBColor
import pymupdf


ROOT = Path(__file__).resolve().parents[1]
REVIEW = ROOT / "docs" / "specification-review"
DATA = REVIEW / "expert-questions.json"
DESTINATION = REVIEW / "EA_PDF_Specification_Questions_for_Experts.docx"
SPECIFICATION = ROOT / "docs" / "reference" / "EA-PDF-v1.pdf"
SPECIFICATION_URL = "https://pdfa.org/download-area/specifications/EA-PDF-v1.pdf"
ISSUES_URL = "https://github.com/pdf-association/report-an-issue/issues"
LICENSE_URL = "https://creativecommons.org/licenses/by/4.0/"
PRINTED_PAGE_OFFSET = 7

HEADING_COLOR = "244C69"
LINK_COLOR = "175A87"
SECONDARY_COLOR = "475569"


def add_link(paragraph, label, *, url=None, anchor=None):
    hyperlink = OxmlElement("w:hyperlink")
    if url is not None:
        relationship = paragraph.part.relate_to(
            url, RELATIONSHIP_TYPE.HYPERLINK, is_external=True
        )
        hyperlink.set(qn("r:id"), relationship)
    else:
        hyperlink.set(qn("w:anchor"), anchor)

    run = OxmlElement("w:r")
    formatting = OxmlElement("w:rPr")
    color = OxmlElement("w:color")
    color.set(qn("w:val"), LINK_COLOR)
    formatting.append(color)
    underline = OxmlElement("w:u")
    underline.set(qn("w:val"), "single")
    formatting.append(underline)
    run.append(formatting)
    text = OxmlElement("w:t")
    text.text = label
    run.append(text)
    hyperlink.append(run)
    paragraph._p.append(hyperlink)


def add_bookmark(paragraph, name, number):
    start = OxmlElement("w:bookmarkStart")
    start.set(qn("w:id"), str(number))
    start.set(qn("w:name"), name)
    end = OxmlElement("w:bookmarkEnd")
    end.set(qn("w:id"), str(number))
    paragraph._p.insert(1, start)
    paragraph._p.append(end)


def configure_document(document):
    section = document.sections[0]
    section.top_margin = Inches(0.64)
    section.bottom_margin = Inches(0.63)
    section.left_margin = Inches(0.76)
    section.right_margin = Inches(0.76)
    section.footer_distance = Inches(0.34)

    normal = document.styles["Normal"]
    normal.font.name = "Calibri"
    normal.font.size = Pt(11)
    normal.paragraph_format.space_after = Pt(5)

    contents = document.styles.add_style("Question contents", WD_STYLE_TYPE.PARAGRAPH)
    contents.base_style = normal
    contents.font.size = Pt(10)
    contents.paragraph_format.space_after = Pt(2)
    contents.paragraph_format.line_spacing = 1

    for name, size in (("Title", 27), ("Heading 1", 16), ("Heading 2", 13)):
        style = document.styles[name]
        style.font.name = "Calibri"
        style.font.size = Pt(size)
        style.font.color.rgb = RGBColor.from_string(HEADING_COLOR)
        style.paragraph_format.keep_with_next = True

    heading = document.styles["Heading 1"]
    heading.paragraph_format.space_before = Pt(14)
    heading.paragraph_format.space_after = Pt(5)

    for name in ("Subtitle", "Caption", "Footer"):
        document.styles[name].font.color.rgb = RGBColor.from_string(SECONDARY_COLOR)
    document.styles["Caption"].font.size = Pt(9)
    document.styles["Caption"].font.italic = False
    document.styles["Footer"].font.size = Pt(9)

    footer = section.footer.paragraphs[0]
    footer.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    footer.add_run("Thinkbox.DIGITAL  ·  EA-PDF specification review  ·  ")
    page_field = OxmlElement("w:fldSimple")
    page_field.set(qn("w:instr"), "PAGE")
    footer._p.append(page_field)


def add_contents(document, questions, sections, body_width):
    document.add_heading(f"Questions at a glance ({len(questions)})", level=1)
    table = document.add_table(rows=0, cols=2)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    for column in table.columns:
        column.width = body_width // 2

    for index in range(0, len(questions), 2):
        row = table.add_row()
        row._tr.get_or_add_trPr().append(OxmlElement("w:cantSplit"))
        for cell, question in zip(row.cells, questions[index:index + 2]):
            cell.width = body_width // 2
            paragraph = cell.paragraphs[0]
            paragraph.style = "Question contents"
            add_link(
                paragraph,
                f"{question['id']}  {question['title']}",
                anchor=question["id"],
            )

    paragraph = document.add_paragraph()
    paragraph.paragraph_format.space_before = Pt(7)
    for index, (label, anchor) in enumerate(sections):
        if index:
            paragraph.add_run("  ·  ")
        add_link(paragraph, label, anchor=anchor)


def add_labeled_paragraph(document, label, text):
    paragraph = document.add_paragraph()
    paragraph.add_run(f"{label}  ").bold = True
    paragraph.add_run(text)
    return paragraph


def add_question(document, question, index):
    heading = document.add_heading(f"{question['id']}  {question['title']}", level=1)
    add_bookmark(heading, question["id"], index)

    source = add_labeled_paragraph(document, "Source", question["source"])
    source.paragraph_format.keep_with_next = True
    rule = add_labeled_paragraph(document, "Specification", question["rule"])
    rule.paragraph_format.keep_with_next = True
    add_labeled_paragraph(document, "Example", question["example"])
    add_labeled_paragraph(document, "Current release", question["release"])
    decision = add_labeled_paragraph(document, "Decision requested", question["ask"])
    decision.paragraph_format.keep_with_next = True
    add_labeled_paragraph(document, "Proposed resolution", question["proposal"])

    if question.get("refs"):
        paragraph = document.add_paragraph()
        paragraph.add_run("References  ").bold = True
        for index, (label, url) in enumerate(question["refs"]):
            if index:
                paragraph.add_run("  ·  ")
            add_link(paragraph, label, url=url)


def add_section(document, sections, position, question_count):
    label, anchor = sections[position]
    heading = document.add_heading(label, level=1)
    add_bookmark(heading, anchor, question_count + position + 1)
    return heading


def add_detail_table(document, first_heading, second_heading, rows, body_width, issue_links=False):
    table = document.add_table(rows=1, cols=2)
    table.style = "Light Shading Accent 1"
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    first_width = Inches(0.68 if issue_links else 1.44)
    widths = (first_width, body_width - first_width)
    for column, width in zip(table.columns, widths):
        column.width = width

    header = table.rows[0]
    header._tr.get_or_add_trPr().append(OxmlElement("w:tblHeader"))
    for cell, label, width in zip(header.cells, (first_heading, second_heading), widths):
        cell.width = width
        cell.text = label

    for label, description in rows:
        row = table.add_row()
        row._tr.get_or_add_trPr().append(OxmlElement("w:cantSplit"))
        first, second = row.cells
        first.width, second.width = widths
        if issue_links:
            add_link(first.paragraphs[0], f"#{label}", url=f"{ISSUES_URL}/{label}")
        else:
            first.text = str(label)
        second.text = description

    for row in table.rows:
        for cell in row.cells:
            for paragraph in cell.paragraphs:
                paragraph.paragraph_format.space_before = Pt(3)
                paragraph.paragraph_format.space_after = Pt(3)


def add_source_exhibits(document, pages, heading, body_width):
    heading.paragraph_format.page_break_before = True
    with pymupdf.open(SPECIFICATION) as pdf:
        for index, printed_page in enumerate(pages):
            pdf_page = printed_page + PRINTED_PAGE_OFFSET
            caption = document.add_heading(
                f"Printed page {printed_page} · PDF page {pdf_page}", level=2
            )
            caption.paragraph_format.page_break_before = index > 0
            credits = document.add_paragraph(style="Caption")
            credits.paragraph_format.keep_with_next = True
            credits.paragraph_format.space_before = Pt(0)
            add_link(credits, "EA-PDF 1.0 Specification", url=SPECIFICATION_URL)
            credits.add_run("  ·  © 2025 PDF Association  ·  ")
            add_link(credits, "CC BY 4.0", url=LICENSE_URL)

            page = pdf[pdf_page - 1]
            image = page.get_pixmap(dpi=150, alpha=False)
            width = int(min(body_width, Inches(8.40) * page.rect.width / page.rect.height))
            shape = document.add_picture(BytesIO(image.tobytes("png")), width=width)
            shape._inline.docPr.set(
                "descr",
                f"EA-PDF 1.0, printed page {printed_page}. "
                "The linked specification provides selectable text.",
            )
            paragraph = document.paragraphs[-1]
            paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
            paragraph.paragraph_format.space_after = Pt(0)


def build_document(data, destination):
    digest = sha256(SPECIFICATION.read_bytes()).hexdigest()
    if digest != data["specification_sha256"]:
        raise ValueError(f"Source PDF SHA-256 mismatch for {SPECIFICATION}. Found {digest}.")

    document = Document()
    configure_document(document)
    section = document.sections[0]
    body_width = section.page_width - section.left_margin - section.right_margin
    questions = data["questions"]
    sections = (
        ("Publication corrections", "PublicationCorrections"),
        ("Public issue review", "PublicIssueReview"),
        ("Sources", "Sources"),
        ("Source exhibits", "SourceExhibits"),
    )
    document.core_properties.title = data["title"]
    document.core_properties.subject = data["subtitle"]
    document.core_properties.author = "Thinkbox.DIGITAL"
    review_date = datetime.fromisoformat(data["review_date"])
    document.core_properties.created = review_date
    document.core_properties.modified = review_date
    document.core_properties.last_modified_by = document.core_properties.author
    document.core_properties.comments = ""

    document.add_heading(data["title"], level=0)
    document.add_paragraph(data["subtitle"], style="Subtitle")
    for paragraph in data["intro"]:
        document.add_paragraph(paragraph)
    add_contents(document, questions, sections, body_width)

    for index, question in enumerate(questions, start=1):
        add_question(document, question, index)

    add_section(document, sections, 0, len(questions))
    add_detail_table(document, "Location", "Correction", data["corrections"], body_width)

    add_section(document, sections, 1, len(questions))
    add_detail_table(
        document, "Issue", "Status and review", data["issues"], body_width, issue_links=True
    )

    add_section(document, sections, 2, len(questions))
    for label, url in data["sources"]:
        add_link(document.add_paragraph(), label, url=url)
    add_labeled_paragraph(document, "Pinned local PDF", str(SPECIFICATION.relative_to(ROOT)))
    add_labeled_paragraph(document, "SHA-256", digest)

    exhibit_heading = add_section(document, sections, 3, len(questions))
    add_source_exhibits(document, data["exhibits"], exhibit_heading, body_width)
    document.save(destination)


def main():
    data = json.loads(DATA.read_text(encoding="utf-8"))
    build_document(data, DESTINATION)
    print(DESTINATION)


if __name__ == "__main__":
    main()
