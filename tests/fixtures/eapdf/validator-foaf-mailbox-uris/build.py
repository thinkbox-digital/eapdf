"""One mailbox representation changes; retained source and all PDF objects stay intact."""
import hashlib,json,re,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT));import validate_eapdf as v
SEED=ROOT/'tests/fixtures/eapdf/validator-contentset-parent/nested-single.pdf'
rows=[]
for name in ('literal','encoded-attribute','encoded-resource','lowercase-hex','quoted-local','domain-case','local-case','double-encoded','invalid-utf8'):
 p=HERE/(name+'.pdf')
 with pikepdf.open(SEED) as pdf:
  text=pdf.Root.Metadata.read_bytes().decode('utf-8')
  section=re.search(r'<pdfmailmeta:To>.*?</pdfmailmeta:To>',text,re.S);assert section
  old=re.search(r'foaf:mbox="([^"]+)"',section.group(0)).group(1)
  local,domain=old[7:].rsplit('@',1)
  if name=='literal':uri=old
  elif name.startswith('encoded-'):uri='mailto:%'+format(ord(local[0]),'02X')+local[1:]+'@'+domain
  elif name=='lowercase-hex':uri='mailto:%'+format(ord(local[0]),'02x')+local[1:]+'@'+domain
  elif name=='quoted-local':uri='mailto:%22'+local+'%22@'+domain
  elif name=='domain-case':uri='mailto:'+local+'@'+domain.upper()
  elif name=='local-case':uri='mailto:'+local[0].swapcase()+local[1:]+'@'+domain
  elif name=='double-encoded':uri='mailto:%25'+format(ord(local[0]),'02X')+local[1:]+'@'+domain
  else:uri='mailto:%FF'+local+'@'+domain
  part=section.group(0).replace('foaf:mbox="'+old+'"','foaf:mbox="'+uri+'"',1)
  if name=='encoded-resource':
   part,count=re.subn(r'<rdf:Description foaf:mbox="([^"]+)"([^>]*)/>',lambda m:'<rdf:Description'+m.group(2)+'><foaf:mbox rdf:resource="'+m.group(1)+'"/></rdf:Description>',part);assert count==1
  text=text[:section.start()]+part+text[section.end():];pdf.Root.Metadata.write(text.encode('utf-8'));pdf.save(p,static_id=True,fix_metadata_version=False)
 failures=['MESSAGE_TO_FOAF'] if name in ('local-case','double-encoded','invalid-utf8') else []
 rows.append(dict(file=p.name,sha256=hashlib.sha256(p.read_bytes()).hexdigest(),expected_status='FAIL' if failures else 'PASS',expected_failures=failures))
(HERE/'expectations.json').write_text(json.dumps(dict(authority=['EA-PDF1.0 Table18 FOAF Agent mailbox fields','RFC6068 section2: mailto percent encoding, once-only decoding','RFC5321 sections2.4 and4.1.2: local-part case and quoted equivalence'],source_urls=['https://xmlns.com/foaf/spec/#term_mbox','https://www.rfc-editor.org/rfc/rfc6068.html#section-2','https://www.rfc-editor.org/rfc/rfc5321.html#section-2.4'],cases=rows),indent=2)+'\n')
