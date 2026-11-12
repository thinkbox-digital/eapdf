"""Build the concise specification decision brief from its reviewed question data."""
from pathlib import Path
import hashlib,json,re
from io import BytesIO
from docx import Document
from docx.shared import Inches,Pt,RGBColor
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.opc.constants import RELATIONSHIP_TYPE as RT
import fitz
ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'docs/improvement/specification-questions-2026-11-02'
DEST=ROOT/'docs/specification-review/EA_PDF_Specification_Questions_for_Experts.docx'
def link(p,text,url):
 h=OxmlElement('w:hyperlink');h.set(qn('r:id'),p.part.relate_to(url,RT.HYPERLINK,is_external=True));r=OxmlElement('w:r');pr=OxmlElement('w:rPr');c=OxmlElement('w:color');c.set(qn('w:val'),'175A87');pr.append(c);r.append(pr);t=OxmlElement('w:t');t.text=text;r.append(t);h.append(r);p._p.append(h)
def label(doc,name,text):
 p=doc.add_paragraph();p.add_run(name+' ').bold=True;p.add_run(text);return p

def main():
 data=json.loads((DATA/'questions.json').read_text());doc=Document();sec=doc.sections[0];sec.top_margin=sec.bottom_margin=Inches(.65)
 normal=doc.styles['Normal'];normal.font.name='Calibri';normal.font.size=Pt(11);normal.paragraph_format.space_after=Pt(6)
 for name in ['Heading 1','Heading 2']:doc.styles[name].font.color.rgb=RGBColor.from_string('244C69')
 doc.add_heading('EA-PDF: decisions needed from the specification experts',0)
 doc.add_paragraph('Plain-language decision brief • 2 November 2026 • Thinkbox.DIGITAL')
 doc.add_paragraph('This brief identifies 25 decisions needed to make EA-PDF 1.0 consistent and interoperable. Each question gives the source, the practical problem, current project handling and a proposed way forward. Proposals are for discussion; they are not approved corrections.')
 doc.add_paragraph('The highest-impact decisions concern truthful missing dates, Core Field requirements, source identity and what a validator can actually prove. Resolve those before tightening validation or requiring changes to existing archives.')
 doc.add_paragraph('The baseline is the PDF Association EA-PDF 1.0 Specification, February 2025: 97 PDF pages, with 90 printed content pages. Page references below are printed pages; add seven for the PDF page. PDF/A validation is delegated to veraPDF on the finished bytes. Separate applicable ISO/TS 32005 requirements remain in scope.')
 doc.add_paragraph('For each decision, the experts should record: agreed meaning; exact replacement wording and location; applicable edition/revision; treatment of existing files; and at least one passing and one failing example. Any schema change also needs exact property names, data types and a complete example packet.')
 doc.add_heading('Questions at a glance',1)
 for item in data['items']:doc.add_paragraph(item['id']+' — '+item['title'])
 for item in data['items']:
  doc.add_heading(item['id']+' — '+item['title'],1)
  pages=', '.join(str(p) for p in item['pages'])
  label(doc,'Source:',item['source']+'; printed pages '+pages+'.')
  doc.add_paragraph(item['problem'])
  label(doc,'Example:',item['example'])
  label(doc,'Current handling:',item['current'])
  label(doc,'Decision needed:',item['decision'])
  label(doc,'Suggested resolution:',item['proposal'])
  if item['discussion']:
   p=doc.add_paragraph('Discussion: ');link(p,'PDF Association issue #'+str(item['discussion']),'https://github.com/pdf-association/report-an-issue/issues/'+str(item['discussion']))
 doc.add_heading('Publication corrections to settle alongside the policy decisions',1)
 doc.add_paragraph('These repairs should be published as an itemized errata sheet and corrected examples. They do not justify changing valid underlying PDF syntax to match a faulty illustration.')
 rows=[('p9, Table 2','Replace PDF/A-4fe with the intended published conformance level; see Q01.'),('pp24–25, Figure 6','Align the diagram’s identification/schema names with Tables 14–15, including the version property.'),('pp46–48, Examples 3–4','Correct MIME-parameter and PDF-object examples; provide a syntactically valid, machine-tested replacement.'),('pp51 and68, Collection/Collections','Use the PDF catalog key Collection consistently. A plural spelling is not a new PDF key.'),('pp53–55, collection example','Reconcile ownership descriptions and duplicate object numbering with the actual embedded child objects.'),('pp72–74, DPart example','Publish a valid nested child-array/Parent/page-range example; apply the published inherited correction concerning DPart Metadata streams.'),('p76, Table 11','Confirm Mail_Field replaces the undefined Mail_ContentField; see the content-set ancestry question.'),('pp81–83, Example 7','Use the intended content-set tag, valid TJ array operands, explicit namespace connections and the permitted root representation.'),('Tables 16 and19','Correct case-sensistive. Preserve the current distinct ContentType/Checksum and Content-Type/CheckSum spellings until any deliberate schema change is specified.'),('Specification PDF itself','Repair the reported malformed number tree in a separately identified publication. Retain the original pinned source for traceability.')]
 t=doc.add_table(rows=1,cols=2);t.style='Light Shading Accent 1';t.rows[0].cells[0].text='Location';t.rows[0].cells[1].text='Requested correction'
 for a,b in rows:c=t.add_row().cells;c[0].text=a;c[1].text=b
 doc.add_heading('Points already explicit in the published text',1)
 doc.add_paragraph('The project issue discussions also raised points that the pinned source already answers: section 9.8/Table 14 requires pdfmail as well as pdfmailid and pdfmailmeta; section 9.3 requires attachment annotations in each applicable body rendering; and section 9.8 names the EA-PDF file creation date. A different policy would be an amendment, not merely a validator bug fix.')
 doc.add_paragraph('The single Document root may be role-mapped under the applicable PDF rules; a missing End in a single-page DPart defaults to Start. Embedded PDF CheckSum is a 16-byte binary MD5 value, unlike the 32-character hexadecimal XMP form. These implementation repairs should not be reopened as new EA-PDF ambiguities.')
 doc.add_paragraph('PDF/A font rules, including CIDSet, belong to the selected PDF/A edition and engine. Do not assume a proposed future amendment is already effective. Viewer behaviour, store approval and limitations in a particular source parser are separate from the meaning of EA-PDF.')
 doc.add_heading('Source extracts: the conflicting Core Field tables',1)
 doc.add_paragraph('The next three pages reproduce the published table pages for direct comparison. In particular compare Sent, Raw-Sent, original size, attachment count, Content-Type and In-Reply-To. Other source locations are given with each question. Copyright 2025 PDF Association, CC BY 4.0.')
 pdf=fitz.open(ROOT/'docs/reference/EA-PDF-v1.pdf')
 for page in [37,87,88]:
  doc.add_page_break();doc.add_heading(f'Published source — printed page {page} (PDF page {page+7})',2)
  pix=pdf[page+6].get_pixmap(matrix=fitz.Matrix(1.6,1.6));doc.add_picture(BytesIO(pix.tobytes('png')),width=Inches(5.65))
 doc.add_page_break();doc.add_heading('Sources and review status',1)
 doc.add_paragraph('Source PDF: docs/reference/EA-PDF-v1.pdf\nSHA-256: '+hashlib.sha256((ROOT/'docs/reference/EA-PDF-v1.pdf').read_bytes()).hexdigest())
 doc.add_paragraph('Current implementation reference: release 1.6.0-beta.5 and the 229-rule catalogue. Earlier audit decisions were checked for superseded implementation statements; repaired rule extraction or validator defects are not listed as outstanding policy questions.')
 doc.add_paragraph('Public discussions were reread on 2 November 2026. The PDF Association repository returned 26 issues, including broader publication/dependency matters; its EA-PDF issues 8, 10, 13, 14, 17, 21 and 22 remain open. The project repository returned two issues. An open issue or a comment is not adopted errata.')
 for url in ['https://github.com/pdf-association/report-an-issue/issues','https://github.com/thinkbox-digital/eapdf/issues']:
  link(doc.add_paragraph(),url,url)
 doc.add_paragraph('Project issue #2 was closed on 2 November 2026: its final comment says most implementation issues are addressed in recent builds, while remaining matters need adjudication by the PDF Association and others. Closure does not adopt a specification amendment.')
 doc.add_paragraph('Supporting local review: docs/improvement/specification-audit/specification-reexamination-2026-10-31.txt; docs/improvement/rule-refinement-2026-10-31/project-issues-review.txt; docs/specification-review/EA_PDF_Rule_Review_for_Specification_Authors.docx. This decision brief is self-contained; those files provide the fuller extraction history and rule-by-rule exhibits.')
 footer=sec.footer.paragraphs[0];footer.add_run('Thinkbox.DIGITAL • EA-PDF specification questions • ');field=OxmlElement('w:fldSimple');field.set(qn('w:instr'),'PAGE');footer._p.append(field)
 doc.core_properties.author='Thinkbox.DIGITAL';doc.core_properties.title='EA-PDF specification: decisions needed from experts'
 doc.save(DEST)
 (DATA/'build.json').write_text(json.dumps({'document':DEST.name,'sha256':hashlib.sha256(DEST.read_bytes()).hexdigest(),'questions':len(data['items']),'source_pages_reproduced':[37,87,88],'source_sha256':hashlib.sha256((ROOT/'docs/reference/EA-PDF-v1.pdf').read_bytes()).hexdigest()},indent=2)+'\n')
 print(DEST)
if __name__=='__main__':main()
