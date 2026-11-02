from pathlib import Path

from reportlab.lib import colors
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfgen import canvas


OUT_PDF = Path("out/eapdf_decision_tree.pdf")
DOCS_PDF = Path("docs/eapdf_decision_tree.pdf")

W, H = 2800, 1800
MARGIN = 50
PDF_W, PDF_H = 1260, 810
PDF_SCALE_X = PDF_W / W
PDF_SCALE_Y = PDF_H / H

BG = (249, 247, 241)
INK = (34, 34, 34)
MUTED = (92, 92, 92)
LINE = (198, 194, 188)
BLUE = (26, 95, 148)
BLUE_BG = (232, 241, 248)
GREEN = (67, 125, 83)
GREEN_BG = (232, 244, 235)
AMBER = (163, 108, 26)
AMBER_BG = (248, 238, 221)
RED = (145, 59, 56)
RED_BG = (248, 232, 230)

PDF_BG = colors.Color(*(c / 255.0 for c in BG))
PDF_INK = colors.Color(*(c / 255.0 for c in INK))
PDF_MUTED = colors.Color(*(c / 255.0 for c in MUTED))
PDF_LINE = colors.Color(*(c / 255.0 for c in LINE))
PDF_BLUE = colors.Color(*(c / 255.0 for c in BLUE))
PDF_BLUE_BG = colors.Color(*(c / 255.0 for c in BLUE_BG))
PDF_GREEN = colors.Color(*(c / 255.0 for c in GREEN))
PDF_GREEN_BG = colors.Color(*(c / 255.0 for c in GREEN_BG))
PDF_AMBER = colors.Color(*(c / 255.0 for c in AMBER))
PDF_AMBER_BG = colors.Color(*(c / 255.0 for c in AMBER_BG))
PDF_RED = colors.Color(*(c / 255.0 for c in RED))
PDF_RED_BG = colors.Color(*(c / 255.0 for c in RED_BG))

PDF_HEAD = ("Helvetica-Bold", 28)
PDF_SUB = ("Helvetica", 14)
PDF_TITLE = ("Helvetica-Bold", 18)
PDF_BODY = ("Helvetica", 12.5)
PDF_SMALL = ("Helvetica", 11)


def sx(x: float) -> float:
    return x * PDF_SCALE_X


def sy(y: float) -> float:
    return PDF_H - (y * PDF_SCALE_Y)


def sw(w: float) -> float:
    return w * PDF_SCALE_X


def sh(h: float) -> float:
    return h * PDF_SCALE_Y


def pdf_color(rgb):
    return colors.Color(*(c / 255.0 for c in rgb))


def pdf_wrap(text: str, font_name: str, font_size: float, width: float):
    words = text.split()
    if not words:
        return [""]
    lines = []
    current = words[0]
    for word in words[1:]:
        candidate = current + " " + word
        if pdfmetrics.stringWidth(candidate, font_name, font_size) <= width:
            current = candidate
        else:
            lines.append(current)
            current = word
    lines.append(current)
    return lines


def pdf_wrapped_height(text: str, font_name: str, font_size: float, width: float, line_gap=1.25):
    return len(pdf_wrap(text, font_name, font_size, width)) * font_size * line_gap


def pdf_bullets_height(items, font_name: str, font_size: float, width: float):
    total = 0.0
    line_h = font_size * 1.28
    bullet_w = 14
    for item in items:
        total += len(pdf_wrap(item, font_name, font_size, width - bullet_w)) * line_h + 2
    return total


def pdf_draw_wrapped(c, x, y, text, font_name, font_size, color, width, line_gap=1.25):
    lines = pdf_wrap(text, font_name, font_size, width)
    line_h = font_size * line_gap
    yy = y
    c.setFont(font_name, font_size)
    c.setFillColor(color)
    for line in lines:
        c.drawString(x, yy, line)
        yy -= line_h


def pdf_draw_bullets(c, x, y, items, width, font_name, font_size):
    yy = y
    line_h = font_size * 1.28
    bullet_w = 14
    c.setFont(font_name, font_size)
    c.setFillColor(PDF_INK)
    for item in items:
        lines = pdf_wrap(item, font_name, font_size, width - bullet_w)
        c.drawString(x, yy, "•")
        c.drawString(x + bullet_w, yy, lines[0])
        yy -= line_h
        for line in lines[1:]:
            c.drawString(x + bullet_w, yy, line)
            yy -= line_h
        yy -= 2


def pdf_rounded_box(c, x, y, w, h, title, body, fill, outline, font_name="Helvetica", font_size=12.5):
    px = sx(x)
    py = sy(y + h)
    pw = sw(w)
    ph = sh(h)
    c.setFillColor(fill)
    c.setStrokeColor(outline)
    c.setLineWidth(1.8)
    c.roundRect(px, py, pw, ph, sw(18), stroke=1, fill=1)

    title_size = min(PDF_TITLE[1], max(12.0, ph * 0.09))
    c.setFillColor(outline)
    c.setFont(PDF_TITLE[0], title_size)
    c.drawString(sx(x + 18), sy(y + 16 + title_size), title)

    title_band = max(50.0, title_size + 28.0)
    body_top = y + title_band + 8.0
    body_y = sy(body_top)
    body_w = sw(w - 36)
    available_h = sh(h - title_band - 22)
    working_font_size = font_size
    if isinstance(body, str):
        while working_font_size > 8.0 and pdf_wrapped_height(body, font_name, working_font_size, body_w) > available_h:
            working_font_size -= 0.4
    else:
        while working_font_size > 8.0 and pdf_bullets_height(body, font_name, working_font_size, body_w) > available_h:
            working_font_size -= 0.4

    if isinstance(body, str):
        pdf_draw_wrapped(c, sx(x + 18), body_y, body, font_name, working_font_size, PDF_INK, body_w)
    else:
        pdf_draw_bullets(c, sx(x + 18), body_y, body, body_w, font_name, working_font_size)


def pdf_arrow(c, x1, y1, x2, y2, color=PDF_BLUE, width=2):
    px1, py1 = sx(x1), sy(y1)
    px2, py2 = sx(x2), sy(y2)
    c.setStrokeColor(color)
    c.setFillColor(color)
    c.setLineWidth(width)
    c.line(px1, py1, px2, py2)
    if abs(px2 - px1) >= abs(py2 - py1):
        if px2 >= px1:
            pts = [px2, py2, px2 - 10, py2 - 6, px2 - 10, py2 + 6]
        else:
            pts = [px2, py2, px2 + 10, py2 - 6, px2 + 10, py2 + 6]
    else:
        if py2 <= py1:
            pts = [px2, py2, px2 - 6, py2 + 10, px2 + 6, py2 + 10]
        else:
            pts = [px2, py2, px2 - 6, py2 - 10, px2 + 6, py2 - 10]
    path = c.beginPath()
    path.moveTo(pts[0], pts[1])
    path.lineTo(pts[2], pts[3])
    path.lineTo(pts[4], pts[5])
    path.close()
    c.drawPath(path, stroke=0, fill=1)


sources = [
    ("Local Email Files", [
        "Input: .eml, .msg, .pst, .mbox",
        "CLI: EmailToPDFA",
        "Best when the source is already local",
    ], GREEN_BG, GREEN),
    ("Gmail", [
        "Input: live Gmail mailbox or folder",
        "CLI: GmailDownloadToPdf",
        "OAuth download plus conversion",
    ], BLUE_BG, BLUE),
]

profiles = [
    ("Local Email Profiles", [
        "Default: one 1s PDF per message.",
        "Archive profiles via --archive-profile: 1m, 1mi, 1c, 1ci.",
        "--archive-output chooses the container filename.",
        "--omit-source suppresses source where the profile allows it.",
    ], GREEN_BG, GREEN),
    ("Gmail Profiles", [
        "Use --archive-profile for 1m, 1mi, 1c, or 1ci.",
        "--folder selects the mailbox or label.",
        "--limit and --unread-only trim the fetched set.",
        "--omit-source affects non-isolated outputs.",
    ], BLUE_BG, BLUE),
]


def build_pdf(path: Path):
    c = canvas.Canvas(str(path), pagesize=(PDF_W, PDF_H))
    c.setTitle("EA-PDF Workflow Decision Tree")
    c.setAuthor("Codex")
    c.setSubject("EA-PDF workflow selection, switches, config flags, and output behavior.")
    c.setFillColor(PDF_BG)
    c.rect(0, 0, PDF_W, PDF_H, fill=1, stroke=0)

    c.setFillColor(PDF_INK)
    c.setFont(PDF_HEAD[0], PDF_HEAD[1])
    c.drawString(sx(MARGIN), sy(55), "EA-PDF Workflow Decision Tree")
    c.setFillColor(PDF_MUTED)
    c.setFont(PDF_SUB[0], PDF_SUB[1])
    c.drawString(sx(MARGIN), sy(95), "Inputs, profile choices, switches, config flags, and output behavior in one page.")
    c.setStrokeColor(PDF_LINE)
    c.setLineWidth(1.1)
    c.line(sx(MARGIN), sy(125), sx(W - MARGIN), sy(125))

    start_x, start_y, start_w, start_h = 1070, 145, 660, 108
    pdf_rounded_box(c, start_x, start_y, start_w, start_h, "Start Here", "What are you converting?", PDF_BLUE_BG, PDF_BLUE, PDF_BODY[0], PDF_BODY[1])

    top_y = 340
    gap = 58
    box_w = (W - 2 * MARGIN - gap * (len(sources) - 1)) / len(sources)
    x_positions = [MARGIN + index * (box_w + gap) for index in range(len(sources))]

    for i, (title, body, fill, outline) in enumerate(sources):
        pdf_rounded_box(c, x_positions[i], top_y, box_w, 170, title, body, pdf_color(fill), pdf_color(outline), PDF_BODY[0], PDF_BODY[1])
        pdf_arrow(c, start_x + start_w // 2, start_y + start_h, x_positions[i] + box_w // 2, top_y, PDF_BLUE, 1.8)

    mid_y = 545
    for i, (title, body, fill, outline) in enumerate(profiles):
        pdf_rounded_box(c, x_positions[i], mid_y, box_w, 215, title, body, pdf_color(fill), pdf_color(outline), PDF_BODY[0], PDF_BODY[1])

    bottom_y = 800
    col_gap = 40
    left_w = (W - 2 * MARGIN - col_gap) // 2
    right_w = left_w
    left_x = MARGIN
    right_x = MARGIN + left_w + col_gap

    pdf_rounded_box(
        c,
        left_x,
        bottom_y,
        left_w,
        530,
        "CLI Switches",
        [
            "EmailToPDFA: --out, --font, --icc, --global-id/-g, --email/-e, --jobs/-j, --omit-source, --archive-profile, --archive-output, --debug, --debug-dir.",
            "GmailDownloadToPdf: --user, --credentials, --token-dir, --folder, --out-pdf, --limit, --unread-only, plus the archive and rendering switches above.",
        ],
        PDF_BLUE_BG,
        PDF_BLUE,
        PDF_BODY[0],
        PDF_BODY[1],
    )

    pdf_rounded_box(
        c,
        right_x,
        bottom_y,
        right_w,
        530,
        "Config JSON Flags",
        [
            "branding: enabled.",
            "findingAid: pdfEnabled, excelEnabled, embedExcel, embedPdfInCollections.",
            "accessibility: addPdfUaFeatures, markInfoMarked, language, readingDirection, pageLabels.*.",
            "premis: enabled (sidecar is written when enabled).",
        ],
        PDF_AMBER_BG,
        PDF_AMBER,
        PDF_BODY[0],
        PDF_BODY[1],
    )

    pdf_rounded_box(
        c,
        left_x,
        1365,
        left_w,
        315,
        "Output Behavior",
        [
            "1s / 1si: one PDF per message.",
            "1m: one archive PDF with messages rendered inside.",
            "1mi: isolated archive PDF with no source embedding.",
            "1c / 1ci: collection or portfolio outputs.",
            "Per-file logs are written to out/logs.",
        ],
        PDF_GREEN_BG,
        PDF_GREEN,
        PDF_BODY[0],
        PDF_BODY[1],
    )

    pdf_rounded_box(
        c,
        right_x,
        1365,
        right_w,
        315,
        "Parallel Processing",
        [
            "Local non-archive conversions use a worker pool via --jobs.",
            "Archive input reading now runs in parallel while preserving order.",
            "Archive message rendering and collection member generation are parallelized.",
            "Gmail remains mostly API-bound and therefore less parallel.",
        ],
        PDF_RED_BG,
        PDF_RED,
        PDF_BODY[0],
        PDF_BODY[1],
    )

    pdf_rounded_box(
        c,
        MARGIN,
        1705,
        W - 2 * MARGIN,
        70,
        "Quick Guide",
        "Use 1m or 1c for source-rich preservation and 1si / 1mi / 1ci for isolated outputs.",
        colors.Color(239 / 255.0, 243 / 255.0, 246 / 255.0),
        PDF_LINE,
        PDF_SMALL[0],
        PDF_SMALL[1],
    )

    c.showPage()
    c.save()


OUT_PDF.parent.mkdir(parents=True, exist_ok=True)
DOCS_PDF.parent.mkdir(parents=True, exist_ok=True)
build_pdf(OUT_PDF)
build_pdf(DOCS_PDF)
print(OUT_PDF)
