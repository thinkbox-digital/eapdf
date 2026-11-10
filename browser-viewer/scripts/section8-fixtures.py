"""Isolated mutations of published real-message PDFs for reader acceptance."""
from pathlib import Path
import io
import sys
import pikepdf
work=Path(sys.argv[1])
source=Path('../examples/eml/eml_Agenda_and_timeline_slides_1s.pdf')
with pikepdf.open(source) as pdf:
    for obj in pdf.objects:
        if isinstance(obj,pikepdf.Dictionary) and obj.get('/Type')==pikepdf.Name('/Filespec'):
            obj.Desc='Section 8 description — 日本語'
    for page in pdf.pages:
        for annot in page.obj.get('/Annots',[]):
            if annot.get('/Subtype')==pikepdf.Name('/FileAttachment'):
                annot.Contents='Section 8 attachment comment — 日本語'
    pdf.Root.PageMode=pikepdf.Name('/UseAttachments')
    pdf.save(work/'attachments.pdf')
    pdf.Root.PageMode=pikepdf.Name('/UseOutlines')
    pdf.save(work/'outlines.pdf')
collection=Path('../examples/eml/eml_Agenda_and_timeline_slides_archive_1c.pdf')
with pikepdf.open(collection) as pdf:
    def mutate(tree):
        for kid in tree.get('/Kids',[]):
            if mutate(kid):return True
        names=tree.get('/Names',[])
        for i in range(0,len(names),2):
            spec=names[i+1]
            if str(spec.get('/UF','')).endswith('.pdf'):
                key=str(names[i]);prefix=key[:key.index('>')+1] if key.startswith('<') else ''
                names[i]=prefix+'bad/name.pdf';spec.UF='bad/name.pdf';spec.F='bad/name.pdf';return True
        return False
    assert mutate(pdf.Root.Names.EmbeddedFiles)
    pdf.save(work/'invalid-name.pdf')
with pikepdf.open(collection) as pdf:
    for obj in pdf.objects:
        if isinstance(obj,pikepdf.Dictionary) and obj.get('/Type')==pikepdf.Name('/Filespec') and str(obj.get('/UF','')).endswith('.pdf'):
            stream=obj.EF.get('/UF',obj.EF.get('/F'))
            with pikepdf.open(io.BytesIO(stream.read_bytes())) as child:
                raw=child.Root.Metadata.read_bytes()
                assert b'<pdfmailid:version>1</pdfmailid:version>' in raw
                child.Root.Metadata.write(raw.replace(b'<pdfmailid:version>1</pdfmailid:version>',b'<pdfmailid:version>9</pdfmailid:version>'))
                data=io.BytesIO();child.save(data)
            stream.write(data.getvalue())
            break
    pdf.save(work/'unsupported-child.pdf')
