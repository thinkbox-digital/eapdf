"""Add a Latin line to the unchanged upstream RTL fixture for browser regression."""
import sys
import pikepdf
with pikepdf.open(sys.argv[1]) as pdf:
    page = pdf.pages[0]
    page.Resources.Font.Latin = pdf.make_indirect(pikepdf.Dictionary(
        Type=pikepdf.Name.Font, Subtype=pikepdf.Name.Type1, BaseFont=pikepdf.Name.Helvetica))
    page.Contents = pikepdf.Array([page.Contents, pdf.make_stream(
        b'BT /Latin 16 Tf 40 750 Td (Mixed Latin 123) Tj ET')])
    pdf.save(sys.argv[2])
