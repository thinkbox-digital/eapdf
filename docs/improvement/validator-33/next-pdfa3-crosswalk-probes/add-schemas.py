"""Complete the PDF/A-3 control's declarations/associations; preserve the initial failed candidate."""
import hashlib,json,os,re,sys
from pathlib import Path
from xml.sax.saxutils import escape
import pikepdf
ROOT=Path(__file__).resolve().parents[4]
HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2')
sys.path.insert(0,str(ROOT/'.eapdf-verification-v33'))
import validate_eapdf as v

def text(tag,value):return f'<{tag}>{escape(value)}</{tag}>'
def record(body):return '<rdf:li rdf:parseType="Resource">'+body+'</rdf:li>'
def field(name,kind):return record(text('pdfaField:name',name)+text('pdfaField:valueType',kind)+text('pdfaField:description',name))
def custom_type(name,namespace,prefix,fields):
    return record(text('pdfaType:type',name)+text('pdfaType:namespaceURI',namespace)+text('pdfaType:prefix',prefix)+text('pdfaType:description',name)+'<pdfaType:field><rdf:Seq>'+''.join(field(*f) for f in fields)+'</rdf:Seq></pdfaType:field>')
def schema(name,namespace,prefix,properties,types=()):
    props=''.join(record(text('pdfaProperty:name',n)+text('pdfaProperty:valueType',t)+text('pdfaProperty:category','internal')+text('pdfaProperty:description',n)) for n,t in properties)
    return record(text('pdfaSchema:schema',name)+text('pdfaSchema:namespaceURI',namespace)+text('pdfaSchema:prefix',prefix)+'<pdfaSchema:property><rdf:Seq>'+props+'</rdf:Seq></pdfaSchema:property><pdfaSchema:valueType><rdf:Seq>'+''.join(types)+'</rdf:Seq></pdfaSchema:valueType>')
meta=v.NS_PDFMAILMETA;decl=v.NS_PDFD;foaf=v.NS_FOAF
email=[('Subject','Text'),('Raw-From','Text'),('Raw-To','Text'),('Sent','Date'),('SizeInBytes','Integer'),('NumberAttachments','Integer'),('Mail_GUID','Text'),('messageid','Text'),('From','Seq Agent'),('To','Seq Agent')]
asset=[('Filename','Text'),('SizeInBytes','Integer'),('ContentType','MIMEType'),('Checksum','Text')]
attachment=[('Filename','Text'),('SizeInBytes','Integer'),('Content-Type','Text'),('CheckSum','Text'),('Mail_GUID','Text'),('Description','Text')]
types=[custom_type('Email',meta,'pdfmailmeta',email),custom_type('Asset',meta,'pdfmailmeta',asset),custom_type('Attachment',meta,'pdfmailmeta',attachment),custom_type('Agent',foaf,'foaf',[('mbox','URI'),('name','ProperName')])]
schemas=[schema('EA-PDF identification',v.NS_PDFMAILID,'pdfmailid',[('version','Integer'),('rev','Integer'),('conformance','Text')]),schema('EA-PDF metadata',meta,'pdfmailmeta',[('email','Bag Email'),('assets','Bag Asset'),('attachments','Bag Attachment')],types),schema('PDF declarations',decl,'pdfd',[('declarations','Bag Declaration')],[custom_type('Declaration',decl,'pdfd',[('conformsTo','URI'),('claimData','Bag Claim')]),custom_type('Claim',decl,'pdfd',[('claimBy','Text'),('claimDate','Date')])])]
ns=' '.join('xmlns:'+p+'="http://www.aiim.org/pdfa/ns/'+tail+'"' for p,tail in [('pdfaExtension','extension/'),('pdfaSchema','schema#'),('pdfaProperty','property#'),('pdfaType','type#'),('pdfaField','field#')])
extension='<rdf:Description rdf:about="" '+ns+'><pdfaExtension:schemas><rdf:Bag>'+''.join(schemas)+'</rdf:Bag></pdfaExtension:schemas></rdf:Description>'
with pikepdf.Pdf.open(HERE/'candidate-3u.pdf') as pdf:
    data=pdf.Root.Metadata.read_bytes().decode('utf-8')
    data=data.replace('</rdf:RDF>',extension+'</rdf:RDF>')
    data=re.sub(r'<pdfd:conformsTo>(.*?)</pdfd:conformsTo>',r'<pdfd:conformsTo rdf:resource="\1"/>',data)
    data=re.sub(r'<rdf:Description foaf:mbox="([^"]*)" foaf:name="([^"]*)"/>',r'<rdf:Description foaf:name="\2"><foaf:mbox rdf:resource="\1"/></rdf:Description>',data)
    pdf.Root.Metadata.write(data.encode('utf-8'))
    pdf.Root.ViewerPreferences.NonFullScreenPageMode=pikepdf.Name.UseOutlines
    specs=[];seen=set()
    for page in pdf.pages:
        for annotation in page.obj.get('/Annots',[]):
            if annotation.get('/Subtype')==pikepdf.Name.FileAttachment:
                spec=annotation.FS
                if spec.objgen not in seen:seen.add(spec.objgen);specs.append(spec)
    pending=[pdf.Root.StructTreeRoot.K]
    while pending:
        item=pending.pop()
        if isinstance(item,pikepdf.Array):pending.extend(item)
        elif isinstance(item,pikepdf.Dictionary):
            if item.get('/S')==pikepdf.Name('/Mail_Message'):item.AF=pikepdf.Array(specs)
            child=item.get('/K')
            if child is not None:pending.append(child)
    names=pdf.Root.Names.EmbeddedFiles.Names
    pairs={str(names[i]):names[i+1] for i in range(0,len(names),2)}
    for spec in specs:
        if '/AFRelationship' not in spec:spec.AFRelationship=pikepdf.Name.Data
        pairs[str(spec.get('/UF',spec.F))]=spec
    pdf.Root.Names.EmbeddedFiles.Names=pikepdf.Array([x for name,spec in sorted(pairs.items(),key=lambda row:bytes(pikepdf.String(row[0]))) for x in (pikepdf.String(name),spec)])
    path=HERE/'declared-3u.pdf';pdf.save(path,static_id=True,force_version='1.7',fix_metadata_version=False)
result=v.validate_one(str(path),write_html=False)
(HERE/'declared-3u.json').write_text(json.dumps(result,indent=2,default=str)+'\n')
row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],pdfa=result['pdfa']['status'],profile=result['pdfa'].get('profile_name'),iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'),pdfa_failures=result['pdfa'].get('rule_summaries'),failures=[i for i in result['items'] if i['level']=='FAIL'])
(HERE/'declared-results.json').write_text(json.dumps(row,indent=2)+'\n');print(row,flush=True)
