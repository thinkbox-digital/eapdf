"""Build the user manual from its editable HTML, then verify PDF/A-2b with veraPDF.
Requires requirements-docs.txt, Java 21 and the creator/validator runtime JARs.
All intermediate files are private temporary files and removed on exit.
"""
import argparse
import hashlib
from html import escape
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from reportlab import rl_config
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import BaseDocTemplate, Frame, Image, KeepTogether, ListFlowable, ListItem, PageBreak, PageTemplate, Paragraph, Spacer, Table, TableStyle
from reportlab.platypus.tableofcontents import TableOfContents

ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'docs/manual/user-manual.html'
MANUAL=ROOT/'docs/EA-PDF_User_Manual.pdf'


def sha(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream,'sha256').hexdigest()


def inline(element):
    result=escape(element.text or '')
    for child in element:
        content=inline(child)
        if child.tag in ('strong','b'): content='<b>'+content+'</b>'
        elif child.tag in ('em','i'): content='<i>'+content+'</i>'
        elif child.tag=='code': content='<font name="ManualCode" size="9">'+content+'</font>'
        elif child.tag=='br': content='<br/>'
        elif child.tag=='a': content='<a href="'+escape(child.get('href',''),quote=True)+'" color="#155b91">'+content+'</a>'
        result+=content+escape(child.tail or '')
    return result


class ManualDocument(BaseDocTemplate):
    def afterFlowable(self,flowable):
        if isinstance(flowable,Paragraph) and flowable.style.name in ('Chapter','Subheading'):
            level=0 if flowable.style.name=='Chapter' else 1
            key='heading-'+str(self.seq.nextf('heading'))
            self.canv.bookmarkPage(key)
            self.canv.addOutlineEntry(flowable.getPlainText(),key,level=level,closed=False)
            if level==0: self.notify('TOCEntry',(0,flowable.getPlainText(),self.page,key))


def build(source,destination,bold_font):
    regular=ROOT/'src/main/resources/assets/DejaVuSans.ttf';mono=ROOT/'assets/LiberationSans-Regular.ttf'
    pdfmetrics.registerFont(TTFont('ManualSans',str(regular)))
    pdfmetrics.registerFont(TTFont('ManualBold',str(bold_font)))
    pdfmetrics.registerFont(TTFont('ManualCode',str(mono)))
    pdfmetrics.registerFontFamily('ManualSans',normal='ManualSans',bold='ManualBold',italic='ManualSans',boldItalic='ManualBold')
    rl_config.invariant=1
    blue=colors.HexColor('#103f5b');ink=colors.HexColor('#173047')
    body=ParagraphStyle('Body',fontName='ManualSans',fontSize=10.5,leading=15,textColor=ink,spaceAfter=9,allowWidows=0,allowOrphans=0)
    styles={'body':body,'chapter':ParagraphStyle('Chapter',parent=body,fontName='ManualBold',fontSize=23,leading=29,textColor=blue,spaceAfter=18,keepWithNext=True),
        'h2':ParagraphStyle('Subheading',parent=body,fontName='ManualBold',fontSize=14,leading=19,textColor=blue,spaceBefore=15,spaceAfter=8,keepWithNext=True),
        'h3':ParagraphStyle('SmallHeading',parent=body,fontName='ManualBold',fontSize=11.5,leading=16,keepWithNext=True),
        'caption':ParagraphStyle('Caption',parent=body,fontSize=9,leading=12,textColor=colors.HexColor('#435867'),spaceBefore=7,spaceAfter=14),
        'cell':ParagraphStyle('Cell',parent=body,fontSize=9.2,leading=12.5,spaceAfter=0),
        'headcell':ParagraphStyle('HeadCell',parent=body,fontName='ManualBold',fontSize=9.3,leading=12.5,spaceAfter=0,textColor=colors.white),
        'code':ParagraphStyle('Code',parent=body,fontName='ManualCode',fontSize=8.6,leading=12,backColor=colors.HexColor('#eef3f7'),borderPadding=8,spaceBefore=7,spaceAfter=14),
        'note':ParagraphStyle('Note',parent=body,backColor=colors.HexColor('#eef3f7'),borderPadding=9,spaceBefore=8,spaceAfter=16),
        'toc':ParagraphStyle('ContentsEntry',parent=body,fontSize=11,leading=17,spaceBefore=8)}
    width,height=A4;left=48;usable=width-left*2
    doc=ManualDocument(str(destination),pagesize=A4,leftMargin=left,rightMargin=left,topMargin=48,bottomMargin=49,title='EA-PDF User Manual',author='Thinkbox.DIGITAL',subject='Beginner guide to the creator, Java validator, Python/web validator and browser viewer',creator='EA-PDF manual builder',keywords='email, EA-PDF, PDF/A, creator, validator, viewer, user manual',pageCompression=1)
    def decorate(canvas,document):
        canvas.saveState();canvas.setStrokeColor(colors.HexColor('#d2dce4'));canvas.line(left,35,width-left,35)
        canvas.setFont('ManualSans',8);canvas.setFillColor(colors.HexColor('#435867'))
        canvas.drawString(left,23,'EA-PDF User Manual | 2 November 2026')
        canvas.drawRightString(width-left,23,str(document.page));canvas.restoreState()
    doc.addPageTemplates(PageTemplate(id='manual',frames=[Frame(left,49,usable,height-97,leftPadding=0,rightPadding=0,topPadding=0,bottomPadding=0)],onPage=decorate))
    root=ET.parse(source).getroot();story=[];figures=[]
    for chapter_index,chapter in enumerate(root.findall('body/section')):
        if chapter_index:
            story.append(PageBreak())
        if chapter_index==1:
            story.append(Paragraph('Contents',styles['chapter']))
            toc=TableOfContents();toc.levelStyles=[styles['toc']];story.append(toc);story.append(PageBreak())
        for item in chapter:
            if item.tag in ('h1','h2','h3'):
                story.append(Paragraph(inline(item),styles[{'h1':'chapter','h2':'h2','h3':'h3'}[item.tag]]))
            elif item.tag=='p':
                story.append(Paragraph(inline(item),styles['note'] if item.get('class')=='note' else body))
            elif item.tag in ('ol','ul'):
                entries=[ListItem(Paragraph(inline(li),body),leftIndent=0) for li in item.findall('li')]
                story.append(ListFlowable(entries,bulletType='1' if item.tag=='ol' else 'bullet',start='1' if item.tag=='ol' else None,leftIndent=19,bulletFontName='ManualSans',bulletFontSize=10,spaceAfter=9))
            elif item.tag=='pre':
                lines=[' '.join(line.split()) for line in ''.join(item.itertext()).splitlines()]
                story.append(Paragraph('<br/>'.join(escape(line) for line in lines),styles['code']))
            elif item.tag=='table':
                rows=[]
                for row_index,row in enumerate(item.findall('tr')):
                    rows.append([Paragraph(inline(cell),styles['headcell'] if row_index==0 else styles['cell']) for cell in row])
                columns=len(rows[0]);ratios=[.29,.71] if columns==2 else [.19,.42,.39]
                table=Table(rows,colWidths=[usable*r for r in ratios],repeatRows=1,hAlign='LEFT')
                table.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),blue),('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white,colors.HexColor('#f1f5f8')]),('GRID',(0,0),(-1,-1),.4,colors.HexColor('#cbd5df')),('VALIGN',(0,0),(-1,-1),'TOP'),('LEFTPADDING',(0,0),(-1,-1),7),('RIGHTPADDING',(0,0),(-1,-1),7),('TOPPADDING',(0,0),(-1,-1),7),('BOTTOMPADDING',(0,0),(-1,-1),7)]))
                story += [table,Spacer(1,12)]
            elif item.tag=='figure':
                image=item.find('img');path=source.parent/image.get('src');figures.append(path)
                flow=Image(str(path));ratio=min(usable/flow.imageWidth,390/flow.imageHeight);flow.drawWidth=flow.imageWidth*ratio;flow.drawHeight=flow.imageHeight*ratio
                caption=Paragraph(inline(item.find('figcaption')),styles['caption'])
                story.append(KeepTogether([Spacer(1,6),flow,caption]))
            else: raise ValueError('Unsupported manual element: '+item.tag)
    doc.multiBuild(story)
    return [source,regular,mono,bold_font,*figures]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--creator-jar',type=Path,default=ROOT / 'dist' / ('emailtopdfa-' + (ROOT / 'VERSION').read_text().strip() + '.jar'))
    parser.add_argument('--validator-jar',type=Path,default=ROOT / 'dist' / ('eapdf-validator-' + (ROOT / 'VERSION').read_text().strip() + '.jar'))
    parser.add_argument('--bold-font',type=Path,default=Path('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'))
    parser.add_argument('--output',type=Path,default=MANUAL)
    args=parser.parse_args();args.output.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='eapdf-manual-build-',dir='/tmp') as directory:
        work=Path(directory);intermediate=work/'manual-layout.pdf';sources=build(SOURCE,intermediate,args.bold_font)
        classpath=os.pathsep.join(str(p.resolve()) for p in [args.creator_jar,args.validator_jar])
        helper=ROOT/'scripts/manual/ManualPdf.java'
        subprocess.run(['javac','-cp',classpath,'-d',str(work),str(helper)],check=True,cwd=ROOT)
        report=args.output.with_suffix('.pdfa-validation.json');icc=ROOT/'src/main/resources/assets/sRGB.icc'
        subprocess.run(['java','-Xmx512m','-cp',str(work)+os.pathsep+classpath,'eapdf.ManualPdf',str(intermediate),str(args.output.resolve()),str(icc),str(report.resolve())],check=True,cwd=ROOT)
        receipt={'edition':'2026-11-08','pdf':args.output.name,'sha256':sha(args.output),'pdfa_validation':json.loads(report.read_text()),'sources':{str(p.relative_to(ROOT)) if p.is_relative_to(ROOT) else str(p):sha(p) for p in [*sources,icc,helper,Path(__file__).resolve()]},'runtime_jars':{p.name:sha(p) for p in [args.creator_jar,args.validator_jar]},'scope':'Ordinary PDF/A-2b user manual; no EA-PDF conformance asserted; screenshots use fictional mail.'}
        receipt_path=ROOT/'docs/manual/build-receipt.json' if args.output.resolve()==MANUAL.resolve() else args.output.with_suffix('.build-receipt.json')
        receipt_path.write_text(json.dumps(receipt,indent=2)+'\n')
        print(json.dumps({'pdf':str(args.output),'bytes':args.output.stat().st_size,'sha256':receipt['sha256'],'validation':receipt['pdfa_validation']['status']}))


if __name__=='__main__': main()
